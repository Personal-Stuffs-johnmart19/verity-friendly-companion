"""Install/check selected audio assets without model inference or cloud generation."""
import argparse
import hashlib
import importlib.util
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.request
import wave
import zipfile

ROOT = Path(__file__).resolve().parent
PINS = {
    'Systran/faster-whisper-small': '536b0662742c02347bc0e980a01041f333bce120',
    'openai/whisper-small': '973afd24965f72e36ca33b3055d56a652f456b4d',
}


def read_json(path):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'))


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + '.tmp')
    temporary.write_text(json.dumps(value, ensure_ascii=True, indent=2), encoding='utf-8')
    temporary.replace(path)


def local_path(value, base=ROOT):
    path = Path(value).expanduser()
    return path if path.is_absolute() else base / path


def hash_file(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def fetch_model(repo, directory, patterns, allow_large=False, offline=False):
    from huggingface_hub import HfApi, snapshot_download
    directory = Path(directory)
    manifest = directory / 'verity-assets.json'
    if offline:
        if not manifest.is_file():
            raise RuntimeError('Offline model manifest missing; complete one online setup first')
        verify_assets(directory)
        return
    info = HfApi(endpoint='https://huggingface.co').model_info(repo, revision=PINS.get(repo), files_metadata=True, token=False)
    import fnmatch
    selected = [file for file in info.siblings if any(fnmatch.fnmatch(file.rfilename, pattern) for pattern in patterns)]
    if any(file.size is None for file in selected):
        raise RuntimeError('Cannot verify selected model download sizes; refusing an unbounded download')
    size = sum(file.size for file in selected)
    if not selected:
        raise RuntimeError('The selected model repository contains no matching assets')
    existing = directory
    while not existing.exists():
        existing = existing.parent
    if shutil.disk_usage(existing).free < size * 2:
        raise RuntimeError('Insufficient free disk space for the model and temporary download files')
    print(f'Model {repo}: {size / 1024**3:.2f} GiB, revision {info.sha}; download only, no inference')
    if size > 2 * 1024**3 and not allow_large:
        raise RuntimeError('Large model download needs explicit -DownloadLargeModels or confirmation in the optional chooser')
    snapshot_download(repo, revision=info.sha, local_dir=directory, allow_patterns=patterns,
                      token=False, endpoint='https://huggingface.co', max_workers=2)
    files = []
    for file in selected:
        path = directory / file.rfilename
        if not path.resolve().is_relative_to(directory.resolve()):
            raise RuntimeError('Invalid remote model path')
        digest = hash_file(path)
        if file.lfs and file.lfs.sha256 and digest != file.lfs.sha256:
            raise RuntimeError('Model checksum mismatch')
        if not file.lfs and file.blob_id:
            git_hash = hashlib.sha1(b'blob ' + str(path.stat().st_size).encode() + b'\0')
            with path.open('rb') as content:
                for chunk in iter(lambda: content.read(1024 * 1024), b''):
                    git_hash.update(chunk)
            if git_hash.hexdigest() != file.blob_id:
                raise RuntimeError('Model metadata/tokenizer checksum mismatch')
        files.append({'name': file.rfilename, 'bytes': path.stat().st_size, 'sha256': digest})
    write_json(manifest, {'repo': repo, 'revision': info.sha, 'files': files})


def verify_assets(directory, full=False):
    directory = Path(directory)
    manifest = read_json(directory / 'verity-assets.json')
    if not isinstance(manifest, dict) or not isinstance(manifest.get('files'), list) or not manifest['files']:
        raise RuntimeError('Empty model asset manifest')
    for file in manifest['files']:
        if not isinstance(file,dict) or not isinstance(file.get('name'),str) or type(file.get('bytes')) is not int or file['bytes'] < 1 or not isinstance(file.get('sha256'),str) or not re.fullmatch('[a-f0-9]{64}',file['sha256']):
            raise RuntimeError('Invalid model asset manifest; rerun setup')
        path = directory / file['name']
        if not path.resolve().is_relative_to(directory.resolve()) or not path.is_file() or path.stat().st_size != file['bytes']:
            raise RuntimeError('Missing/incomplete model files; rerun setup')
        if full and hash_file(path) != file['sha256']:
            raise RuntimeError('Model checksum changed; preserve it and repair the model cache explicitly')


from speech_input import model_directory


def prepared_fallback(config):
    cpu = config.get('stt_cpu_fallback', True) and (model_directory(config) / 'model.bin').is_file()
    native = (config.get('stt_vulkan_fallback', False)
              and local_path(config.get('stt_native_executable', 'missing')).is_file()
              and local_path(config.get('stt_native_model', 'missing')).is_file())
    return cpu or native


def _check(config, full=False):
    problems = []
    engine = config.get('stt_engine', 'faster-whisper')
    if engine in ('faster-whisper', 'cuda') or engine == 'rocm' and config.get('stt_cpu_fallback', True) and not config.get('stt_vulkan_fallback', False):
        try:
            directory = model_directory(config)
            for name in ('model.bin', 'config.json', 'tokenizer.json'):
                if not (directory / name).is_file():
                    raise RuntimeError('Whisper files missing')
            if (directory / 'verity-assets.json').is_file():
                verify_assets(directory, full)
            elif full:
                raise RuntimeError('No checksum manifest for this user-supplied model')
        except (OSError, ValueError, RuntimeError):
            problems.append('Whisper assets missing/incomplete: run setup-server.cmd (or backend_setup.py --prepare). -SkipModelDownloads explicitly defers voice readiness.')
    if engine == 'rocm':
        python = local_path(config.get('stt_rocm_python', 'missing'))
        directory = local_path(config.get('stt_rocm_model', 'missing'))
        if not python.is_file() or not (directory / 'model.safetensors').is_file():
            if prepared_fallback(config):
                print('OPTIONAL: ROCm assets unavailable; prepared local fallback will be used.')
            else:
                problems.append('ROCm Python/model missing: choose the ROCm backend in setup-server.cmd -OptionalBackends or supply an existing compatible environment.')
    elif engine not in ('faster-whisper', 'cpu', 'cuda', 'vulkan', 'native-cuda', 'native-cpu', 'google', 'disabled'):
        problems.append('Unsupported stt_engine; choose google, faster-whisper, cuda, vulkan, rocm or disabled.')
    provider = config.get('provider', 'google')
    if provider == 'silero' or provider == 'google' and config.get('local_fallback', False) and config.get('local_fallback_provider', 'silero') == 'silero':
        if importlib.util.find_spec('torch') is None:
            problems.append('Silero requires CPU PyTorch; rerun setup with the Silero optional backend.')
        for key, default in (('russian_model', 'v5_ru'), ('english_model', 'v3_en')):
            weight = ROOT / 'models' / (config.get(key, default) + '.pt')
            if not weight.is_file() or weight.stat().st_size < 100000 or not zipfile.is_zipfile(weight):
                problems.append('Configured Silero RU/EN weights missing; rerun setup to download them from the official source.')
    if provider not in ('google', 'silero', 'drama3', 'disabled'):
        problems.append('Unsupported TTS provider; choose google, silero, drama3 or disabled.')
    return problems


def check(config, full=False):
    try:
        return _check(config, full)
    except (OSError, ValueError, KeyError, IndexError, TypeError, AttributeError, RuntimeError):
        return ['Selected backend configuration/assets are invalid; run setup-server.cmd -CheckOnly and repair the selected profile.']


def probe_environment(config):
    problems = []
    if config.get('stt_engine') == 'cuda':
        try:
            from cuda_dependencies import probe
            probe()
        except (ImportError, OSError, RuntimeError, ValueError):
            if config.get('stt_vulkan_fallback', False) and local_path(config.get('stt_native_executable', 'missing')).is_file() and local_path(config.get('stt_native_model', 'missing')).is_file():
                print('OPTIONAL: CUDA unavailable; prepared local Vulkan recognition will be used. No cloud fallback.')
            elif config.get('stt_cpu_fallback', True) and (model_directory(config) / 'model.bin').is_file():
                print('OPTIONAL: CUDA unavailable; prepared CPU recognition will be used. Select Vulkan for cross-vendor GPU recognition.')
            else:
                problems.append('CUDA libraries/device unavailable; choose Vulkan, prepare a local fallback, or repair NVIDIA CUDA 12/cuDNN 9 libraries. No model was loaded.')
    if config.get('stt_engine') == 'rocm':
        python = local_path(config.get('stt_rocm_python', 'missing'))
        try:
            result = subprocess.run([str(python), '-B', '-c', 'import torch,numpy,scipy; from transformers import WhisperForConditionalGeneration, WhisperProcessor; assert torch.version.hip and torch.cuda.is_available()'], capture_output=True, timeout=60)
            if result.returncode:
                if prepared_fallback(config):
                    print('OPTIONAL: ROCm unavailable; prepared local fallback will be used.')
                else:
                    problems.append('ROCm native imports/driver/GPU check failed; rerun optional setup or select CPU recognition. No AI model was loaded.')
        except (OSError, subprocess.TimeoutExpired):
            if prepared_fallback(config):
                print('OPTIONAL: ROCm probe unavailable; prepared local fallback will be used.')
            else:
                problems.append('ROCm dependency probe could not run; verify its configured Python environment.')
    if config.get('provider') == 'silero' or config.get('provider') == 'google' and config.get('local_fallback', False) and config.get('local_fallback_provider', 'silero') == 'silero':
        try:
            if subprocess.run([sys.executable, '-B', '-c', 'import torch'], capture_output=True, timeout=30).returncode:
                problems.append('Silero PyTorch native imports failed; repair the Windows runtime/optional packages.')
        except (OSError, subprocess.TimeoutExpired):
            problems.append('Silero PyTorch dependency probe failed; no model was loaded.')
    return problems


def run_uv(uv, *arguments):
    from python_dependencies import clean_environment
    result = subprocess.run([str(uv), '--no-config', *map(str, arguments)], env=clean_environment(), check=False)
    if result.returncode:
        raise RuntimeError('Optional Python dependency installation failed; existing configuration was not activated')


def download_silero(config, offline):
    for key, default, lang in (('russian_model', 'v5_ru', 'ru'), ('english_model', 'v3_en', 'en')):
        model = config.get(key, default)
        if not model.replace('_', '').isalnum():
            raise ValueError('Invalid Silero model name')
        path = ROOT / 'models' / (model + '.pt')
        if path.exists():
            if path.stat().st_size < 100000 or not zipfile.is_zipfile(path):
                raise RuntimeError('An existing Silero model file is incomplete; preserve/move it aside before reinstalling')
            continue
        if offline:
            raise RuntimeError('Silero weights missing in offline mode')
        temporary = path.with_suffix('.download')
        path.parent.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen(f'https://models.silero.ai/models/tts/{lang}/{model}.pt', timeout=45) as source, temporary.open('wb') as target:
            shutil.copyfileobj(source, target, 1024 * 1024)
        if temporary.stat().st_size < 100000 or not zipfile.is_zipfile(temporary):
            raise RuntimeError('Silero download is incomplete')
        temporary.replace(path)


def prepare_references(directory, reference_config):
    if not reference_config:
        return
    import av
    references = read_json(reference_config)
    voices = directory / 'voices'
    voices.mkdir(parents=True, exist_ok=True)
    texts_file = voices / 'reference-text.json'
    texts = read_json(texts_file) if texts_file.exists() else {}
    for language in ('ru', 'en'):
        target = voices / f'verity-{language}.wav'
        if target.exists() and texts.get(language):
            continue
        entry = references.get(language, {})
        source = Path(entry.get('audio', 'missing'))
        text = entry.get('text', '')
        if not source.is_file() or not isinstance(text, str) or not text.strip():
            raise RuntimeError('Provide both reference audio paths and their exact RU/EN transcripts; existing references are preserved')
        if target.exists() and source.resolve() != target.resolve():
            raise RuntimeError('An existing reference recording has no transcript. Supply its actual path/transcript or choose a new voice directory; it will not be overwritten.')
        temporary = target.with_suffix('.tmp')
        try:
            count = 0
            with av.open(str(source)) as audio, wave.open(str(temporary), 'wb') as output:
                output.setnchannels(1); output.setsampwidth(2); output.setframerate(44100)
                resampler = av.AudioResampler(format='s16', layout='mono', rate=44100)
                for frame in audio.decode(audio=0):
                    for converted in resampler.resample(frame):
                        count += converted.samples
                        if count > 30 * 44100:
                            raise RuntimeError('Use a reference recording no longer than 30 seconds')
                        output.writeframes(converted.to_ndarray().tobytes())
                for converted in resampler.resample(None):
                    count += converted.samples
                    output.writeframes(converted.to_ndarray().tobytes())
            if count < 44100:
                raise RuntimeError('A reference recording must contain at least one second of audio')
            if not target.exists():
                temporary.replace(target)
            texts[language] = text.strip()
            write_json(texts_file, texts)
        finally:
            temporary.unlink(missing_ok=True)
    write_json(texts_file, texts)


def prepare(config, uv, stt='keep', tts='keep', offline=False, allow_large=False,
            rocm_python=None, native_archive=None, reference_config=None,
            vulkan_fallback=False, dependency_mode='auto', cpu_fallback='keep', packages_only=False):
    updated = dict(config)
    if stt != 'keep':
        updated['stt_engine'] = {'rocm': 'rocm', 'cpu': 'faster-whisper', 'cuda': 'cuda', 'vulkan': 'vulkan', 'native-cuda': 'native-cuda', 'native-cpu': 'native-cpu', 'google': 'google', 'disabled': 'disabled'}[stt]
    if vulkan_fallback:
        updated['stt_vulkan_fallback'] = True
    if cpu_fallback not in ('keep', 'on', 'off'):
        raise ValueError('CPU fallback must be keep, on or off')
    if cpu_fallback != 'keep':
        updated['stt_cpu_fallback'] = cpu_fallback == 'on'
    if tts != 'keep':
        updated['provider'] = tts
    # A selected optional profile is activated only after its assets install.
    if updated.get('stt_engine') == 'rocm':
        try:
            python = local_path(rocm_python or updated.get('stt_rocm_python', 'missing'))
            if not python.is_file():
                if dependency_mode == 'existing':
                    raise RuntimeError('Existing dependency mode requires an already prepared ROCm environment; no packages were installed')
                if offline:
                    raise RuntimeError('ROCm environment missing in offline mode')
                environment = ROOT / '.venv-rocm'
                from python_environment import venv_python
                python = venv_python(environment)
                if not python.is_file():
                    # The known AMD wheel set has its own Python baseline; the
                    # bridge may use another Python through the JSON worker protocol.
                    run_uv(uv, 'venv', '--python', '3.12', environment)
                from rocm_dependencies import install as install_rocm
                install_rocm(uv, python)
            # Existing environments are checked, never modified.
            probe = subprocess.run([str(python), '-B', '-c', 'import torch,numpy,scipy; from transformers import WhisperForConditionalGeneration, WhisperProcessor; assert torch.version.hip and torch.cuda.is_available(), "ROCm driver/GPU unavailable"'], capture_output=True, timeout=90)
            if probe.returncode:
                raise RuntimeError('ROCm packages/driver are not usable. The managed profile uses AMD ROCm 10.1.0 / PyTorch 2.14.0 with device-specific kernels; check GPU/driver compatibility. Existing environments were not changed; CPU fallback remains available.')
            directory = local_path(updated.get('stt_rocm_model', 'models/whisper-small-rocm'))
            if not packages_only and not (directory / 'model.safetensors').is_file():
                directory = ROOT / 'models/whisper-small-rocm'
                fetch_model('openai/whisper-small', directory, ['*.json', '*.txt', 'model.safetensors'], allow_large, offline)
            updated.update(stt_rocm_python=str(python), stt_rocm_model=str(directory))
        except (OSError, RuntimeError, subprocess.SubprocessError):
            if not updated.get('stt_vulkan_fallback', False) and not updated.get('stt_cpu_fallback', True):
                raise
            print('OPTIONAL: ROCm unavailable; preparing the selected local fallback instead.')
    if updated.get('stt_engine') == 'cuda':
        from cuda_dependencies import probe, device_available
        from cuda_install import install
        try:
            probe()
        except (ImportError, OSError, RuntimeError, ValueError):
            if dependency_mode != 'existing' and device_available():
                try:
                    install(uv, sys.executable, offline)
                except RuntimeError:
                    if not updated.get('stt_vulkan_fallback', False) and not updated.get('stt_cpu_fallback', True):
                        raise
                    print('OPTIONAL: CUDA packages unavailable; preparing the selected local fallback instead.')
    if not packages_only and (updated.get('stt_engine', 'faster-whisper') in ('faster-whisper', 'cuda') or updated.get('stt_engine') == 'rocm' and updated.get('stt_cpu_fallback', True) and not updated.get('stt_vulkan_fallback', False)):
        name = updated.get('whisper_model', 'small')
        candidate = model_directory(updated)
        if candidate.is_dir() and (candidate / 'model.bin').is_file():
            if (candidate / 'verity-assets.json').is_file():
                verify_assets(candidate)
        else:
            if '/' in name or '\\' in name or name not in ('tiny', 'base', 'small', 'medium', 'large-v3'):
                raise RuntimeError('Select a supported Whisper name or an existing complete local model directory')
            directory = ROOT / 'models/whisper' / name
            fetch_model('Systran/faster-whisper-' + name, directory,
                        ['config.json', 'model.bin', 'tokenizer.json', 'vocabulary.*', 'preprocessor_config.json'], allow_large, offline)
            updated['whisper_model'] = directory.relative_to(ROOT).as_posix()
    if updated.get('provider') == 'silero' or updated.get('provider') == 'google' and updated.get('local_fallback', False) and updated.get('local_fallback_provider', 'silero') == 'silero':
        if importlib.util.find_spec('torch') is None:
            if dependency_mode == 'existing':
                raise RuntimeError('Selected Silero backend requires Torch; existing dependency mode never installs packages')
            if offline:
                raise RuntimeError('CPU PyTorch missing in offline mode')
            run_uv(uv, 'pip', 'install', '--python', sys.executable, '--only-binary', ':all:', '--index-url', 'https://download.pytorch.org/whl/cpu', 'torch>=2.9,<3')
        probe = subprocess.run([sys.executable, '-I', '-B', '-c', 'import torch'], capture_output=True, timeout=45)
        if probe.returncode:
            raise RuntimeError('PyTorch native dependencies failed to import; keep the previous voice provider and repair the Windows runtime')
        if not packages_only:
            download_silero(updated, offline)
    if not packages_only and updated.get('provider') == 'drama3' and reference_config:
        prepare_references(local_path(updated.get('drama3_directory', '../verity-tts')), reference_config)
    return updated


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--prepare', action='store_true')
    parser.add_argument('--packages-only', action='store_true', help='Provision packages without downloading models or activating settings.')
    parser.add_argument('--uv')
    parser.add_argument('--stt', choices=('keep', 'cpu', 'cuda', 'vulkan', 'native-cuda', 'native-cpu', 'rocm', 'google', 'disabled'), default='keep')
    parser.add_argument('--tts', choices=('keep', 'google', 'silero', 'drama3', 'disabled'), default='keep')
    parser.add_argument('--rocm-python')
    parser.add_argument('--native-archive')
    parser.add_argument('--reference-config')
    parser.add_argument('--vulkan-fallback', action='store_true')
    parser.add_argument('--cpu-fallback', choices=('keep', 'on', 'off'), default='keep')
    parser.add_argument('--dependency-mode', choices=('auto', 'locked', 'compatible', 'existing'), default='auto')
    parser.add_argument('--offline', action='store_true')
    parser.add_argument('--allow-large', action='store_true')
    parser.add_argument('--verify-hashes', action='store_true')
    args = parser.parse_args()
    try:
        config = read_json(ROOT / 'voice-config.json')
        if args.prepare:
            if not args.uv:
                raise ValueError('--uv is required for dependency installation')
            from python_dependencies import validate_venv
            validate_venv()
            config = prepare(config, args.uv, args.stt, args.tts, args.offline, args.allow_large, args.rocm_python, args.native_archive, args.reference_config, args.vulkan_fallback, args.dependency_mode, args.cpu_fallback, args.packages_only)
        if args.packages_only:
            from python_dependencies import profile_requirements, probe_imports
            _, modules = profile_requirements(config.get('stt_engine', 'disabled'), config.get('provider', 'google'), config.get('stt_cpu_fallback', True), config.get('local_fallback', False) and config.get('local_fallback_provider', 'silero') == 'silero', True, config.get('stt_vulkan_fallback', False))
            imports_ok, report = probe_imports(modules)
            problems = [] if imports_ok else ['Selected packages are not ready: ' + str(report['failures'])]
        else:
            problems = check(config, args.verify_hashes)
        if args.prepare and not args.packages_only and not problems:
            problems.extend(probe_environment(config))
        if args.prepare and not problems:
            run_uv(args.uv, 'pip', 'check', '--python', sys.executable)
            if config.get('stt_engine') == 'rocm':
                python = local_path(config.get('stt_rocm_python', 'missing'))
                try:
                    if not python.is_file():
                        raise RuntimeError('Optional ROCm environment missing')
                    run_uv(args.uv, 'pip', 'check', '--python', python)
                except RuntimeError:
                    if not prepared_fallback(config):
                        raise
                    print('OPTIONAL: ROCm dependency graph unavailable; prepared local fallback remains active.')
        if args.prepare and not args.packages_only and not problems and config != read_json(ROOT / 'voice-config.json'):
            write_json(ROOT / 'voice-config.json', config)
        for problem in problems:
            print('DEPENDENCY REQUIRED: ' + problem)
        if not problems:
            print('PASS selected package imports; models and runtime assets were not checked' if args.packages_only else 'PASS selected audio packages and model assets; no inference or cloud generation performed')
        return 1 if problems else 0
    except Exception as error:
        print('DEPENDENCY SETUP FAILED: ' + str(error))
        return 1


if __name__ == '__main__':
    sys.exit(main())
