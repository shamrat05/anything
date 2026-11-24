#!/usr/bin/env python3
"""Inspect signatures, actual permissions, bundle structure and 16 KiB ELF alignment."""
from pathlib import Path
import json
import os
import re
import shutil
import struct
import subprocess
import xml.etree.ElementTree as ET
import zipfile

root = Path(__file__).resolve().parent.parent
os.chdir(root)
out = root / 'dist'
sdk = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT', ''))
bundletool = Path(os.environ.get('BUNDLETOOL_JAR', Path.home() / '.cache/anything-android/bundletool-all-1.18.3.jar'))

def release_version_code():
    # Keep the expected version independent of the APKs without duplicating it.
    gradle = (root / 'app/build.gradle').read_text(encoding='utf-8')
    codes = re.findall(r'(?m)^[ \t]*versionCode[ \t]+([1-9][0-9]*)[ \t]*$', gradle)
    if len(codes) != 1:
        raise SystemExit('Expected one literal versionCode in app/build.gradle.')
    return codes[0]

def run(args):
    return subprocess.run([str(arg) for arg in args], check=True, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, text=True, env={**os.environ, 'LC_ALL': 'C'}).stdout

def sdk_tool(name):
    candidate = sdk / 'build-tools/36.0.0' / name
    found = shutil.which(name)
    if candidate.is_file() and not Path('/system/bin/app_process').exists():
        return str(candidate)
    if found:
        return found
    if candidate.is_file():
        return str(candidate)
    raise SystemExit('Missing Android build tool: ' + name)

def native_layout(archive, prefix):
    expected = {'arm64-v8a': 183, 'armeabi-v7a': 40, 'x86_64': 62, 'x86': 3}
    details = {}
    with zipfile.ZipFile(archive) as package:
        for library in ['libfileops.so', 'libdiscovery.so', 'librootindex.so']:
            for abi, machine in expected.items():
                name = prefix + '/' + abi + '/' + library
                data = package.read(name)
                if data[:4] != b'\x7fELF' or data[5] != 1 or struct.unpack_from('<H', data, 18)[0] != machine:
                    raise SystemExit('Invalid native ABI: ' + name)
                is64 = data[4] == 2
                executable = struct.unpack_from('<Q' if is64 else '<I', data, 24)[0] != 0
                phoff = struct.unpack_from('<Q' if is64 else '<I', data, 32 if is64 else 28)[0]
                phsize, count = struct.unpack_from('<HH', data, 54 if is64 else 42)
                alignments = []
                interpreter = False
                for index in range(count):
                    offset = phoff + index * phsize
                    kind = struct.unpack_from('<I', data, offset)[0]
                    if kind == 3:
                        interpreter = True
                    if kind == 1:
                        alignment = struct.unpack_from('<Q' if is64 else '<I', data, offset + (48 if is64 else 28))[0]
                        if alignment < 16384:
                            raise SystemExit('Native LOAD segment is not 16 KiB aligned: ' + name)
                        alignments.append(alignment)
                    if kind == 0x6474e551:
                        flags = struct.unpack_from('<I', data, offset + (4 if is64 else 24))[0]
                        if flags & 1:
                            raise SystemExit('Executable native stack: ' + name)
                if not alignments:
                    raise SystemExit('No native LOAD segments: ' + name)
                if library == 'librootindex.so' and (not executable or not interpreter):
                    raise SystemExit('Initial scanner must be a standalone executable: ' + name)
                details[abi + '/' + library] = {'load_alignment': min(alignments), 'bytes': len(data)}
    return details

signatures = {}
certificate = None
for name in ['anything-with-sms.apk', 'anything.apk',
             'anything-android-1.0.1-debug.apk', 'anything-android-tests.apk']:
    report = run([sdk_tool('apksigner'), 'verify', '--verbose', '--print-certs', out / name])
    (out / (name + '.signature.txt')).write_text(report)
    match = re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]+)', report)
    if not match:
        raise SystemExit('Missing signer certificate digest: ' + name)
    digest = match.group(1).lower()
    if certificate is not None and digest != certificate:
        raise SystemExit('APK signing certificates differ.')
    certificate = digest
    signatures[name] = digest

permissions = {}
versions = {}
funding_assets = {'support-on-patreon.svg', 'anything-usdt-tron-qr.svg'}
expected_identity = ('com.callcraftstudio.anything', release_version_code(), '1.0.1')
for name, sms in [('anything-with-sms.apk', True), ('anything.apk', False)]:
    badging = run([sdk_tool('aapt2'), 'dump', 'badging', out / name])
    identity = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
    if not identity or identity.groups() != expected_identity:
        observed = identity.groups() if identity else 'unreadable'
        raise SystemExit(f'Wrong release package/version: {name}: expected {expected_identity}, got {observed}')
    versions[name] = {'package': identity[1], 'version_code': int(identity[2]), 'version_name': identity[3]}
    text = run([sdk_tool('aapt2'), 'dump', 'permissions', out / name])
    (out / (name + '.permissions.txt')).write_text(text)
    values = set(re.findall(r"uses-permission: name='([^']+)'", text))
    if ('android.permission.READ_SMS' in values) != sms:
        raise SystemExit('Wrong SMS permission distribution: ' + name)
    if not {'android.permission.READ_CONTACTS', 'android.permission.MANAGE_EXTERNAL_STORAGE'} <= values:
        raise SystemExit('Required core permission missing: ' + name)
    if {'android.permission.QUERY_ALL_PACKAGES', 'android.permission.INTERNET'} & values:
        raise SystemExit('Unexpected broad package or network permission: ' + name)
    permissions[name] = sorted(values)
    with zipfile.ZipFile(out / name) as apk:
        if not {'assets/' + item for item in funding_assets} <= set(apk.namelist()):
            raise SystemExit('Missing GitHub funding assets: ' + name)

aab = out / 'anything-play-1.0.1.aab'
with zipfile.ZipFile(aab) as bundle:
    if {'base/assets/' + item for item in funding_assets} & set(bundle.namelist()):
        raise SystemExit('Funding assets must be absent from the Play bundle.')
validation = run(['java', '-jar', bundletool, 'validate', '--bundle=' + str(aab)])
(out / 'AAB-VALIDATION.txt').write_text(validation)
manifest = run(['java', '-jar', bundletool, 'dump', 'manifest', '--bundle=' + str(aab), '--module=base'])
(out / 'PLAY-MANIFEST.xml').write_text(manifest)
parsed = ET.fromstring(manifest)
android = '{http://schemas.android.com/apk/res/android}'
bundle_identity = (parsed.get('package'), parsed.get(android + 'versionCode'), parsed.get(android + 'versionName'))
if bundle_identity != expected_identity:
    raise SystemExit(f'Wrong Play bundle package/version: expected {expected_identity}, got {bundle_identity}')
aab_permissions = {item.get(android + 'name') for item in parsed.findall('uses-permission')}
if aab_permissions != set(permissions['anything.apk']):
    raise SystemExit('Play bundle and APK permission sets differ.')
if parsed.find('uses-sdk').get(android + 'targetSdkVersion') != '36':
    raise SystemExit('Play bundle must target API 36.')
report = run(['jarsigner', '-verify', str(aab)])
if 'jar verified.' not in report:
    raise SystemExit('Bundle JAR signature did not verify.')
(out / 'AAB-SIGNATURE.txt').write_text(report)
cert = run(['keytool', '-printcert', '-jarfile', str(aab)])
match = re.search(r'SHA256:\s*([0-9A-Fa-f:]+)', cert)
if not match or match.group(1).replace(':', '').lower() != certificate:
    raise SystemExit('Play bundle signing certificate differs from the APKs.')

native = {}
for name in ['anything-with-sms.apk', 'anything.apk']:
    native[name] = native_layout(out / name, 'lib')
native[aab.name] = native_layout(aab, 'base/lib')
(out / 'DISTRIBUTION-CHECKS.json').write_text(json.dumps({
    'target_sdk': 36, 'versions': versions, 'signatures': signatures, 'aab_certificate_sha256': certificate,
    'permissions': permissions, 'play_aab_permissions': sorted(aab_permissions), 'native': native,
}, indent=2) + '\n')
print('Verified APK/AAB signatures, full/Play permissions, API 36 and four 16 KiB-aligned native ABIs.')
