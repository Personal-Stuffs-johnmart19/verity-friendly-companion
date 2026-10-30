"""Read-only setup checks. Does not contact providers, import models or start a bridge."""
import json
from pathlib import Path
import sys
sys.dont_write_bytecode = True
import secrets
import subprocess
from urllib.parse import urlsplit
from backend_setup import check as check_backends, probe_environment
from python_dependencies import profile_requirements, probe_imports


def main():
    root = Path(__file__).resolve().parent
    problems = []
    try:
        config = json.loads((root / 'voice-config.json').read_text(encoding='utf-8-sig'))
        bridge_config = json.loads((root.parent / 'config/verity-companion-server.json').read_text(encoding='utf-8-sig'))
        config.setdefault('stt_engine','disabled' if bridge_config.get('runtime','bridge')=='native' else 'faster-whisper')
        _, modules = profile_requirements(config.get('stt_engine', 'faster-whisper'),
                                         config.get('provider', 'google'),
                                         config.get('stt_cpu_fallback', True),
                                         config.get('local_fallback', False) and config.get('local_fallback_provider','silero')=='silero', True, config.get('stt_vulkan_fallback',False))
        try:
            imports_ok, _ = probe_imports(modules)
            if not imports_ok:
                problems.append('Selected speech library imports failed; rerun setup or choose compatible/existing dependency mode. No model was loaded.')
        except (OSError, subprocess.TimeoutExpired):
            problems.append('Selected speech import check failed/timed out; no model was loaded.')
        endpoint = urlsplit(bridge_config.get('endpoint', 'http://127.0.0.1:8766'))
        if endpoint.scheme != 'http' or endpoint.hostname not in ('127.0.0.1', 'localhost', '::1') or endpoint.username or endpoint.password or endpoint.path not in ('', '/') or endpoint.query or endpoint.fragment or (endpoint.port or 80) != config.get('port', 8766):
            problems.append('Bridge endpoint must be loopback HTTP and match port in voice-config.json. Fix config/verity-companion-server.json.')
        token_path = root.parent / bridge_config.get('tokenFile', 'verity-service/.secrets/bridge-token')
        actual_token = (root / '.secrets/bridge-token').read_text(encoding='utf-8-sig').strip()
        if not actual_token or not secrets.compare_digest(token_path.read_text(encoding='utf-8-sig').strip().encode(), actual_token.encode()):
            problems.append('Bridge tokens do not match. Fix tokenFile in config/verity-companion-server.json. Never copy tokens to clients.')
        problems.extend(check_backends(config))
        problems.extend(probe_environment(config))
    except (OSError, ValueError, TypeError, AttributeError):
        problems.append('Missing or invalid server JSON configuration; run setup-server.cmd. Existing settings were not changed.')
    for problem in problems:
        print('SETUP REQUIRED: ' + problem)
    if not problems:
        print('PASS configuration/dependencies. Provider credentials, model availability and live gameplay were not exercised.')
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
