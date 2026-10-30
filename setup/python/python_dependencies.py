"""Provision/check only the caller's isolated service venv; never load models.

Run using service/.venv's Python, passing --venv-root service/.venv and --uv.
auto: hashed CPython 3.12 baseline, bounded wheel resolver on other Python 3.10+.
locked: hashed baseline, CPython 3.12 only; failures never fall back to a resolver.
compatible: explicit bounded resolver, official PyPI wheels, no source builds.
existing: check installed dependencies and selected imports without changing them.
--offline prevents network access in every uv invocation. Optional backend runtimes
(Torch, ROCm, native Vulkan/CUDA tools) are provisioned by their own setup paths.
Import checks establish library availability, not GPU/model readiness or quality.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import platform
import subprocess
import sys

ROOT = Path(__file__).resolve().parent
STT_ENGINES = ('disabled', 'google', 'faster-whisper', 'cpu', 'cuda', 'rocm', 'vulkan', 'native-cuda', 'native-cpu')
TTS_PROVIDERS = ('google', 'cloud', 'native', 'silero', 'drama3', 'disabled')


def selected_mode(mode, version=None, implementation=None):
    version = sys.version_info[:2] if version is None else tuple(version[:2])
    implementation = platform.python_implementation() if implementation is None else implementation
    if version < (3, 10):
        raise ValueError('Python 3.10 or newer is required.')
    baseline = implementation == 'CPython' and version == (3, 12)
    if mode == 'auto':
        return 'locked' if baseline else 'compatible'
    if mode == 'locked' and not baseline:
        raise ValueError('Locked dependencies target CPython 3.12; use compatible or existing mode explicitly.')
    if mode not in ('locked', 'compatible', 'existing'):
        raise ValueError('Unknown dependency mode.')
    return mode


def validate_venv(expected=None):
    prefix = Path(sys.prefix).resolve()
    if prefix == Path(sys.base_prefix).resolve():
        raise ValueError('Run this helper using the isolated service venv Python, not a global interpreter.')
    if expected is not None and prefix != Path(expected).resolve():
        raise ValueError('The running Python does not belong to --venv-root.')
    config = prefix / 'pyvenv.cfg'
    if not config.is_file():
        raise ValueError('An isolated Python venv with pyvenv.cfg is required.')
    options = {}
    for line in config.read_text(encoding='utf-8-sig').splitlines():
        if '=' in line:
            key, value = line.split('=', 1)
            options[key.strip().lower()] = value.strip().lower()
    if options.get('include-system-site-packages') != 'false':
        raise ValueError('Venv must disable system site packages; external packages will not be modified.')
    return prefix


def profile_requirements(stt='faster-whisper', tts='google', cpu_fallback=False, local_fallback=False, check_optional=False, native_fallback=False):
    """Return resolver roots and actual import probes without importing a backend."""
    if stt not in STT_ENGINES or tts not in TTS_PROVIDERS:
        raise ValueError('Unsupported speech profile.')
    roots = ['numpy', 'scipy', 'huggingface-hub']
    imports = ['numpy', 'scipy.signal', 'huggingface_hub']
    whisper = stt in ('faster-whisper', 'cpu', 'cuda') or (
        stt == 'rocm' and cpu_fallback and not native_fallback)
    if whisper:
        roots.extend(['faster-whisper', 'av'])
        imports.extend(['av', 'ctranslate2', 'onnxruntime', 'faster_whisper'])
    elif tts == 'drama3':
        roots.append('av')
        imports.append('av')
    if check_optional and (tts == 'silero' or tts in ('google', 'cloud', 'native') and local_fallback):
        # Silero's isolated optional setup installs Torch, never the base resolver.
        imports.append('torch')
    return roots, imports


def clean_environment():
    # Ignore user-configured indexes, package injection and uv target overrides.
    # Preserve only uv's cache location so offline setup can reuse its own cache.
    env = {key: value for key, value in os.environ.items()
           if not key.upper().startswith(('UV_', 'PIP_'))
           and key.upper() not in ('PYTHONPATH', 'PYTHONHOME', 'VIRTUAL_ENV')}
    if 'UV_CACHE_DIR' in os.environ:
        env['UV_CACHE_DIR'] = os.environ['UV_CACHE_DIR']
    env['VIRTUAL_ENV'] = sys.prefix
    return env


def uv_command(uv, offline=False):
    command = [str(uv), '--no-config']
    if offline:
        command.append('--offline')
    return command


def install_command(uv, mode, roots, offline=False):
    command = uv_command(uv, offline) + [
        'pip', 'install', '--python', sys.executable, '--only-binary', ':all:',
        '--index-url', 'https://pypi.org/simple', '--no-progress',
    ]
    if mode == 'locked':
        return command + ['--require-hashes', '-r', str(ROOT / 'requirements-lock.txt')]
    if mode == 'compatible':
        return command + ['-c', str(ROOT / 'requirements-compatible.txt')] + list(roots)
    raise ValueError('Existing mode does not install dependencies.')


def probe_imports(modules, env=None):
    """Import libraries in an isolated child; no service module, GPU call or model."""
    code = (
        'import importlib,json,sys\n'
        'failures=[]\n'
        'for name in json.loads(sys.argv[1]):\n'
        ' try: importlib.import_module(name)\n'
        ' except Exception as error: failures.append({"module":name,"error":type(error).__name__})\n'
        'print(json.dumps({"imports":json.loads(sys.argv[1]),"failures":failures}))\n'
        'sys.exit(bool(failures))\n'
    )
    result = subprocess.run(
        [sys.executable, '-I', '-B', '-c', code, json.dumps(list(modules))],
        env=env, capture_output=True, text=True, timeout=90, check=False,
    )
    # Native crashes/timeouts are also failures; avoid echoing arbitrary library output.
    try:
        report = json.loads(result.stdout.strip().splitlines()[-1])
    except (ValueError, IndexError):
        report = {'imports': list(modules), 'failures': [{'error': 'import probe failed', 'exit': result.returncode}]}
    if result.returncode and not report.get('failures'):
        report['failures'] = [{'error': 'import probe failed', 'exit': result.returncode}]
    return result.returncode == 0 and not report.get('failures'), report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--uv', required=True, type=Path)
    parser.add_argument('--venv-root', type=Path, required=True)
    parser.add_argument('--mode', choices=('auto', 'locked', 'compatible', 'existing'), default='auto')
    parser.add_argument('--offline', action='store_true')
    parser.add_argument('--stt', choices=STT_ENGINES, default='faster-whisper')
    parser.add_argument('--tts', choices=TTS_PROVIDERS, default='google')
    parser.add_argument('--cpu-fallback', '--stt-cpu-fallback', action='store_true')
    parser.add_argument('--local-fallback', action='store_true')
    parser.add_argument('--native-fallback', action='store_true')
    parser.add_argument('--check-optional', action='store_true',
                        help='Also import optional Torch for Silero/local TTS fallback after backend setup.')
    args = parser.parse_args(argv)
    try:
        validate_venv(args.venv_root)
        mode = selected_mode(args.mode)
        uv = args.uv.resolve(strict=True)
        if not uv.is_file():
            raise ValueError('--uv must name the verified uv executable.')
        roots, modules = profile_requirements(args.stt, args.tts, args.cpu_fallback, args.local_fallback, args.check_optional, args.native_fallback)
        env = clean_environment()
        print('Python dependency mode: ' + mode, flush=True)
        installed = True
        if mode != 'existing':
            installed = subprocess.run(install_command(uv, mode, roots, args.offline), env=env, check=False).returncode == 0
        # Always check the resulting environment, including after a failed install.
        checked = subprocess.run(uv_command(uv, args.offline) + [
            'pip', 'check', '--python', sys.executable], env=env, check=False).returncode == 0
        imports_ok, report = probe_imports(modules, env)
        print(json.dumps({'mode': mode, 'python': platform.python_version(),
                          'installed': installed, 'pip_check': checked, **report}), flush=True)
        return 0 if installed and checked and imports_ok else 1
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        print('Python dependency check failed: ' + str(error), file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
