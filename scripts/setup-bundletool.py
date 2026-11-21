#!/usr/bin/env python3
"""Fetch the pinned official bundletool used to inspect the actual Play bundle."""
from pathlib import Path
import hashlib
import os
import urllib.request

version = '1.18.3'
digest = 'a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29'
destination = Path(os.environ.get('BUNDLETOOL_JAR', Path.home() / '.cache/anything-android' / ('bundletool-all-' + version + '.jar')))
destination.parent.mkdir(parents=True, exist_ok=True)
if not destination.is_file() or hashlib.sha256(destination.read_bytes()).hexdigest() != digest:
    url = 'https://github.com/google/bundletool/releases/download/' + version + '/bundletool-all-' + version + '.jar'
    request = urllib.request.Request(url, headers={'User-Agent': 'anything-android-build'})
    with urllib.request.urlopen(request, timeout=90) as response:
        data = response.read(64 * 1024 * 1024 + 1)
    if hashlib.sha256(data).hexdigest() != digest:
        raise SystemExit('Official bundletool SHA-256 mismatch.')
    pending = destination.with_suffix('.download')
    pending.write_bytes(data)
    pending.replace(destination)
print(str(destination))
