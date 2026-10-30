"""Selected-venv preflight/provisioning. No models, drivers or configuration writes.

Invoke with the chosen venv's Python. Reused custom environments stay read-only
unless --install-packages explicitly authorizes provisioning. JSON reports only
checks actually performed; optional framework and model readiness are separate.
"""
from __future__ import annotations
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
from python_dependencies import clean_environment, selected_mode, validate_venv


def venv_python(directory, system=None):
    return Path(directory).expanduser().absolute() / (
        'Scripts/python.exe' if (system or os.name) == 'nt' else 'bin/python')


def describe_environment(expected):
    root = validate_venv(expected)
    selected_mode('existing')
    return {'python_executable': str(Path(sys.executable).absolute()),
            'venv_root': str(root), 'version': list(sys.version_info[:3]), 'isolated': True}


def dependency_mode(requested, reused=False, install_packages=False):
    return 'existing' if reused and not install_packages else requested


def create_environment(uv, directory, selection, offline=False):
    """Create only into an absent/empty directory; never replace an existing env."""
    root = Path(directory).expanduser().absolute()
    if root.exists() and (not root.is_dir() or any(root.iterdir())):
        raise ValueError('Venv destination is not empty; select explicit reuse or a new directory.')
    command = [str(uv), '--no-config'] + (['--offline'] if offline else [])
    command += ['venv', '--python', str(selection), str(root), '--no-progress']
    env = clean_environment()
    env.pop('VIRTUAL_ENV', None)
    for key in ('UV_PYTHON_INSTALL_DIR', 'UV_PYTHON_BIN_DIR'):
        if key in os.environ:
            env[key] = os.environ[key]
    if subprocess.run(command, env=env, check=False).returncode:
        raise RuntimeError('Venv creation failed; no existing environment was replaced.')
    return venv_python(root)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--venv-root', required=True, type=Path)
    parser.add_argument('--inspect', action='store_true')
    parser.add_argument('--service', type=Path)
    parser.add_argument('--uv', type=Path)
    parser.add_argument('--mode', choices=('auto', 'locked', 'compatible', 'existing'), default='auto')
    parser.add_argument('--reused', action='store_true')
    parser.add_argument('--install-packages', action='store_true')
    parser.add_argument('--offline', action='store_true')
    parser.add_argument('--stt', default='disabled')
    parser.add_argument('--tts', default='google')
    parser.add_argument('--cpu-fallback', action='store_true')
    parser.add_argument('--native-fallback', action='store_true')
    parser.add_argument('--local-fallback', action='store_true')
    args = parser.parse_args(argv)
    try:
        report = describe_environment(args.venv_root)
        if not args.inspect:
            if args.service is None or args.uv is None:
                raise ValueError('--service and --uv are required for dependency checks.')
            mode = dependency_mode(args.mode, args.reused, args.install_packages)
            command = [sys.executable, '-B', str(args.service.resolve(strict=True) / 'python_dependencies.py'),
                       '--uv', str(args.uv), '--venv-root', str(args.venv_root),
                       '--mode', mode, '--stt', args.stt, '--tts', args.tts]
            for name in ('offline', 'cpu_fallback', 'native_fallback', 'local_fallback'):
                if getattr(args, name):
                    command.append('--' + name.replace('_', '-'))
            result = subprocess.run(command, stdout=sys.stderr, env=clean_environment(), check=False)
            report.update(dependency_mode=mode, dependencies_checked=True, ready=result.returncode == 0)
            print(json.dumps(report), flush=True)
            return 0 if report['ready'] else 1
        print(json.dumps(report), flush=True)
        return 0
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        print(json.dumps({'ready': False, 'error': str(error)}), flush=True)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
