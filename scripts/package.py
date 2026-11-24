#!/usr/bin/env python3
"""Package checked build outputs without signing keys or downloaded target binaries."""
from pathlib import Path
import hashlib
import os
import shutil
import subprocess

root = Path(__file__).resolve().parent.parent
os.chdir(root)
out = root / 'dist'
out.mkdir(exist_ok=True)
artifacts = {
    'app/build/outputs/bundle/playRelease/app-play-release.aab': 'anything-play-1.0.1.aab',
    'app/build/outputs/apk/github/release/app-github-release.apk': 'anything.apk',
    'app/build/outputs/apk/full/release/app-full-release.apk': 'anything-with-sms.apk',
    'app/build/outputs/apk/full/debug/app-full-debug.apk': 'anything-android-1.0.1-debug.apk',
    'app/build/outputs/apk/androidTest/full/debug/app-full-debug-androidTest.apk': 'anything-android-tests.apk',
}
for source, name in artifacts.items():
    if not Path(source).is_file():
        raise SystemExit('Missing build output: ' + source)
    shutil.copyfile(source, out / name)
for file in ['README.md', 'GUIDE.md', 'LICENSE', 'THIRD_PARTY_NOTICES.md']:
    if Path(file).is_file():
        shutil.copyfile(file, out / Path(file).name)
subprocess.run(['git', 'archive', '--format=zip', '--prefix=anything/',
                '--output=' + str(out / 'anything-source.zip'), 'HEAD'], check=True)
subprocess.run(['python3', 'scripts/setup-bundletool.py'], check=True)
subprocess.run(['python3', 'scripts/verify-distributions.py'], check=True)
shutil.make_archive(str(out / 'anything-play-store'), 'zip', root / 'store')
# Keep the original public URL working without changing the verified, signed APK.
shutil.copyfile(out / 'anything.apk', out / 'anything-play.apk')

def checksum_lines(files):
    return ''.join(hashlib.sha256(p.read_bytes()).hexdigest() + '  ' + p.name + '\n' for p in files)

release_apks = ('anything.apk', 'anything-with-sms.apk', 'anything-play.apk')
(out / 'SHA256SUMS-release.txt').write_text(checksum_lines(out / name for name in release_apks))
files = sorted(p for p in out.iterdir() if p.is_file() and p.name != 'SHA256SUMS')
(out / 'SHA256SUMS').write_text(checksum_lines(files))
print('Packaged signed APKs/AAB, source, store assets, reports and SHA256SUMS in dist/.')
