"""Optional CUDA package provisioning; never imported by inference adapters."""
import subprocess

def install(uv, python, offline=False):
    from python_dependencies import clean_environment
    arguments = [str(uv), '--no-config'] + (['--offline'] if offline else [])
    arguments += ['pip', 'install', '--python', str(python), '--only-binary', ':all:',
                  '--index-url', 'https://pypi.org/simple',
                  'nvidia-cublas-cu12>=12,<13', 'nvidia-cudnn-cu12>=9,<10']
    if subprocess.run(arguments, env=clean_environment(), check=False).returncode:
        raise RuntimeError('Optional CUDA libraries could not be installed; use Vulkan or existing compatible NVIDIA libraries')
