#!/usr/bin/env python3
"""Reject unofficial or incompatible upgrade candidates. This is offline; it does not install either APK."""
import argparse
import os
from pathlib import Path
import re
import subprocess

from signing_identity import PUBLISHED_CERT

PUBLIC_PACKAGE = 'com.callcraftstudio.anything'
BASE = {
    'standard': '38f0ab3a90f64e73a6fdca7b7f600c3e2dcd32d674cc46f7b68ededf1639ac26',
    'sms': '5e962b5cad335a05e31e56838d207754d2ed0702826afbd27ae39d47120eb44c',
}

def run(tool, *args):
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT', ''))
    path = sdk / 'build-tools/36.0.0' / tool
    try:
        return subprocess.check_output(
            [str(path), *map(str, args)], text=True, stderr=subprocess.STDOUT,
            env={**os.environ, 'LC_ALL': 'C'}
        )
    except (OSError, subprocess.CalledProcessError) as exc:
        raise SystemExit(f'{tool} could not verify the APK: {exc}')

def match(text, pattern, description):
    fields = re.findall(pattern, text, re.MULTILINE)
    if len(fields) != 1:
        raise SystemExit('Expected one ' + description)
    return fields[0]

def identity(file):
    return match(run('aapt2', 'dump', 'badging', file),
                 r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'",
                 'APK identity for ' + file.name)

def certificate(file):
    return match(run('apksigner', 'verify', '--print-certs', file),
                 r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$',
                 'verified APK signer for ' + file.name).lower()

def permissions(file):
    return set(re.findall(r"^uses-permission: name='([^']+)'",
                          run('aapt2', 'dump', 'permissions', file), re.MULTILINE))

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('edition', choices=BASE)
    p.add_argument('old', type=Path)
    p.add_argument('candidate', type=Path)
    args = p.parse_args()
    import hashlib
    if hashlib.sha256(args.old.read_bytes()).hexdigest() != BASE[args.edition]:
        raise SystemExit('Old APK is not the exact published build 3 for this edition.')
    old_id, old_code, old_name = identity(args.old)
    new_id, new_code, new_name = identity(args.candidate)
    if old_id != PUBLIC_PACKAGE or new_id != PUBLIC_PACKAGE:
        raise SystemExit('Candidate package differs from the public app. A side-by-side test build is not an upgrade.')
    if old_code != '3' or int(new_code) <= int(old_code) or new_name == old_name:
        raise SystemExit('Version is not a forward upgrade from public build 3.')
    old_cert, new_cert = certificate(args.old), certificate(args.candidate)
    if old_cert != PUBLISHED_CERT or new_cert != PUBLISHED_CERT:
        raise SystemExit('Published signer differs. Do not send this APK as an in-place upgrade.')
    old_permissions, new_permissions = permissions(args.old), permissions(args.candidate)
    sms = 'android.permission.READ_SMS'
    if (sms in new_permissions) != (args.edition == 'sms') or (sms in old_permissions) != (sms in new_permissions):
        raise SystemExit('SMS edition mismatch. Keep variants separate.')
    if {'android.permission.INTERNET', 'android.permission.INSTALL_PACKAGES', 'android.permission.QUERY_ALL_PACKAGES'} & new_permissions:
        raise SystemExit('Unexpected network, broad package query, or silent-install permission.')
    added_permissions = new_permissions - old_permissions
    if added_permissions:
        raise SystemExit('Upgrade requests permissions absent in the previous edition: ' + ', '.join(sorted(added_permissions)))
    print(f'Signature, package, version and edition match: {args.edition} {old_name} ({old_code}) → {new_name} ({new_code}).')
    print('Still required: install over build 3 on a test device and verify that state survives.')

if __name__ == '__main__':
    main()
