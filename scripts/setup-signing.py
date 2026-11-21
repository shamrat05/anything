#!/usr/bin/env python3
"""Create a private local key, or restore the same key from an Actions secret."""
import base64
import json
import os
from pathlib import Path
import secrets
import subprocess

project = Path(__file__).resolve().parent.parent
bundle_env = os.environ.get('FASTSEARCH_SIGNING_BUNDLE', '')
key_dir = Path(os.environ['RUNNER_TEMP']) / 'anything-signing' if bundle_env else Path.home() / '.local/share/anything-android'
key_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
key_file = key_dir / 'app-signing.p12'
bundle_file = key_dir / 'signing.json'
if bundle_env:
    bundle = json.loads(bundle_env)
    key_file.write_bytes(base64.b64decode(bundle['keystore_b64'], validate=True))
elif bundle_file.exists():
    bundle = json.loads(bundle_file.read_text())
    if not key_file.exists():
        key_file.write_bytes(base64.b64decode(bundle['keystore_b64'], validate=True))
else:
    if key_file.exists():
        raise SystemExit('Existing key has no saved signing configuration; refusing to replace it.')
    password = secrets.token_hex(24)
    env = dict(os.environ, FASTSEARCH_KEY_PASSWORD=password)
    subprocess.run(['keytool', '-genkeypair', '-keystore', str(key_file), '-storetype', 'PKCS12',
                    '-storepass:env', 'FASTSEARCH_KEY_PASSWORD', '-keypass:env', 'FASTSEARCH_KEY_PASSWORD',
                    '-alias', 'anything-android', '-keyalg', 'RSA', '-keysize', '3072',
                    '-validity', '10000', '-dname', 'CN=Anything Android'],
                   env=env, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    bundle = {'keystore_b64': base64.b64encode(key_file.read_bytes()).decode(),
              'store_password': password, 'key_password': password, 'key_alias': 'anything-android'}
    bundle_file.write_text(json.dumps(bundle))
    bundle_file.chmod(0o600)
key_file.chmod(0o600)
values = {'storeFile': str(key_file), 'storePassword': bundle['store_password'],
          'keyPassword': bundle['key_password'], 'keyAlias': bundle['key_alias']}
if any('\n' in value or '\r' in value for value in values.values()):
    raise SystemExit('Invalid signing configuration.')
config_file = project / 'release-signing.properties'
config_file.write_text(''.join(key + '=' + value + '\n' for key, value in values.items()))
config_file.chmod(0o600)
print('Private signing configuration prepared.')
