"""ROCm 10.1 wheel selection. Detection does not load an AI model."""
import json
import platform
import subprocess

INDEX = 'https://stable.repo.amd.com/rocm/whl-next/'
TORCH = '2.14.0+rocm10.1.0'
ROCM = '10.1.0'
WHISPER_REQUIREMENTS = (
    'transformers==5.20.2', 'huggingface-hub==2.3.0',
    'safetensors==0.8.0', 'numpy==2.5.3', 'scipy==1.18.1',
)
WINDOWS_TARGETS = frozenset((
    'gfx1010', 'gfx1011', 'gfx1012', 'gfx1030', 'gfx1031', 'gfx1032',
    'gfx1033', 'gfx1034', 'gfx1035', 'gfx1036', 'gfx1100', 'gfx1101',
    'gfx1102', 'gfx1103', 'gfx1150', 'gfx1151', 'gfx1152', 'gfx1200', 'gfx1201',
))


def requirements(targets):
    targets = sorted(set(targets))
    if not targets or any(target not in WINDOWS_TARGETS for target in targets):
        raise RuntimeError('No GFX target covered by the ROCm 10.1 manifest was detected. Check the official AMD driver or reuse a known-working ROCm venv; no driver is changed.')
    extras = ','.join('device-' + target for target in targets)
    # Device-scoped extras pull only the GPU kernels needed by this machine.
    # Whisper does not need torchvision/torchaudio image/audio extension wheels.
    return [f'torch[{extras}]=={TORCH}', f'rocm[{extras}]=={ROCM}',
            'rocm-sdk-core==10.1.0', 'rocm-sdk-libraries==10.1.0',
            *WHISPER_REQUIREMENTS,
            # uv gives --extra-index-url priority over --index-url. Use explicit
            # priority so PyPI's torch cannot hide AMD's matching HIP build.
            '--index', INDEX, '--default-index', 'https://pypi.org/simple',
            # The ROCm metapackage is source-only; native packages must be wheels.
            '--only-binary', 'torch,rocm-sdk-core,rocm-sdk-libraries,numpy,scipy,safetensors,tokenizers']


def install(uv, python, runner=subprocess.run):
    if platform.system() not in ('Windows', 'Linux') or platform.machine().lower() not in ('amd64', 'x86_64'):
        raise RuntimeError('The managed ROCm manifest supports Windows/Linux x64 only; reuse a compatible environment on other platforms.')
    from python_dependencies import clean_environment
    env = clean_environment()
    base = [str(uv), '--no-config', 'pip', 'install', '--python', str(python)]
    result = runner(base + ['rocm-bootstrap==0.3.0', '--only-binary', ':all:', '--index-url', 'https://pypi.org/simple'], env=env, check=False)
    if result.returncode:
        raise RuntimeError('ROCm GPU detection dependency installation failed')
    detected = runner([str(python), '-I', '-c',
                       'import json; from rocm_bootstrap import detect_gfx_targets; print(json.dumps(sorted({target.name for target in detect_gfx_targets()})))'],
                      env=env, capture_output=True, text=True, timeout=45, check=False)
    if detected.returncode:
        raise RuntimeError('AMD GFX detection failed; no Torch wheels were installed')
    try:
        targets = json.loads(detected.stdout)
        if not isinstance(targets, list) or any(not isinstance(target, str) for target in targets):
            raise ValueError('Invalid target response')
    except (ValueError, TypeError):
        raise RuntimeError('Invalid AMD GFX detection response') from None
    arguments = requirements(targets)
    print('ROCm 10.1 / PyTorch 2.14.0; selected GPU kernels: ' + ', '.join(sorted(set(targets))))
    # Resolve platform/device packages before changing the isolated environment.
    if runner(base + arguments + ['--dry-run'], env=env, check=False).returncode:
        raise RuntimeError('ROCm 10.1 manifest has no resolvable package set for this OS/Python/GPU; use native Vulkan or an existing compatible ROCm venv. No Torch wheels were installed.')
    if runner(base + arguments, env=env, check=False).returncode:
        raise RuntimeError('ROCm 10.1 package installation failed')
