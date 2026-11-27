#!/usr/bin/env python3
"""Run the actual native scanner against owned trees; independent lstat is the oracle."""
from pathlib import Path
import os
import shutil
import subprocess
import struct
import tempfile

root = Path(__file__).resolve().parent.parent
binary = root / 'app/build/rootindex-host'
binary.parent.mkdir(parents=True, exist_ok=True)
compiler = shutil.which('cc') or shutil.which('clang')
if not compiler:
    raise SystemExit('A C compiler is required for native scanner checks.')
subprocess.run([compiler, '-std=c11', '-pthread', '-O2', '-Wall', '-Wextra', '-Werror',
                str(root / 'app/src/main/cpp/rootindex.c'), '-o', str(binary)], check=True)
assertions = 0


def check(value, message):
    global assertions
    assertions += 1
    if not value:
        raise AssertionError(message)


def scan(path, *exclusions):
    result = subprocess.run([str(binary), str(path), *map(str, exclusions)],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10)
    check(result.returncode == 0 and not result.stderr, 'native fixture scan completes without errors')
    fields = result.stdout.split(b'\0')
    check(fields.pop() == b'' and len(fields) % 4 == 0, 'complete NUL-framed records')
    records = {}
    for offset in range(0, len(fields), 4):
        mode, size, modified, name = fields[offset:offset + 4]
        check(len(mode) == 10 and mode[:1] in {b'd', b'-'}, 'only regular files/directories emitted')
        key = Path(os.fsdecode(name))
        check(key not in records, 'one row per literal path')
        follows_link = exclusions[:1] == ('--children',) or (exclusions[:1] == ('--tree',) and key == path)
        metadata = key.stat() if follows_link else key.lstat()
        check(int(modified) == metadata.st_mtime_ns // 1_000_000, 'exact independent millisecond timestamp')
        check(int(size) == (0 if mode[:1] == b'd' else metadata.st_size), 'exact size/type metadata')
        records[key] = mode[:1]
    return records


def protocol_records(source, packed):
    records = {}
    if packed:
        header = struct.Struct('<cqqI')
        offset = 0
        while offset < len(source):
            kind, size, modified, length = header.unpack_from(source, offset)
            offset += header.size
            check(0 < length <= 4095 and offset + length <= len(source), 'valid packed record length')
            path = source[offset:offset + length]
            offset += length
            check(path not in records, 'each packed record emitted exactly once')
            records[path] = kind, size, modified
    else:
        fields = source.split(b'\0')
        for mode, size, modified, path in zip(fields[0::4], fields[1::4], fields[2::4], fields[3::4]):
            records[path] = mode[:1], int(size), int(modified)
    return records


def compare_protocols(fixture, *options):
    args = [str(binary), str(fixture)]
    baseline = subprocess.run([*args, *options], capture_output=True, timeout=10, check=True)
    packed = subprocess.run([*args, '--packed', *options], capture_output=True, timeout=10, check=True)
    check(not baseline.stderr and not packed.stderr, 'both stream encodings traverse the same fixture without errors')
    records = protocol_records(packed.stdout, True)
    check(protocol_records(baseline.stdout, False) == records, 'packed mode preserves exact path/type/size/mtime')
    return records


with tempfile.TemporaryDirectory(prefix="rootindex-'[]* ") as temporary:
    fixture = Path(temporary)
    names = ['plain.txt', 'with space', "apostrophe'", 'tab\there', 'line\nbreak',
             '日本語-é', 'literal%_[]*?', '$(touch BAD)', '.hidden']
    for name in names:
        file = fixture / name
        file.write_bytes(b'12345')
        os.utime(file, ns=(1_700_000_000_123_000_000, 1_700_000_000_123_000_000))
    nested = fixture / "literal'[*]?"
    nested.mkdir()
    (nested / 'child').write_bytes(b'child')
    (fixture / 'loop').symlink_to(fixture, target_is_directory=True)
    os.mkfifo(fixture / 'fifo')
    found = scan(fixture)
    check(set(found) == {fixture, nested, nested / 'child', *(fixture / name for name in names)},
          'exact tree, including hidden and special names, excludes symlinks and FIFOs')
    check(not (fixture / 'BAD').exists(), 'literal filename never runs a command')
    excluded = scan(fixture, nested)
    check(nested not in excluded and nested / 'child' not in excluded, 'literal metacharacter exclusion prunes descendants')
    sibling = fixture / (nested.name + '-sibling')
    sibling.mkdir()
    (nested / '.dot').write_bytes(b'hidden')
    found = scan(fixture, nested)
    check(sibling in found, 'exclusion has a path boundary and retains similarly named siblings')
    check(scan(fixture, fixture) == {}, 'excluded traversal root emits no rows')
    shallow = scan(fixture, '--children')
    check(nested in shallow and nested / 'child' not in shallow and shallow[fixture / 'loop'] == b'd',
          'folder browsing shows immediate children and directory links without traversing them')
    scoped = scan(fixture / 'loop', '--tree')
    check(fixture / 'loop' / nested.name / 'child' in scoped,
          'scoped tree supports an explicitly opened directory alias')
    check(fixture / 'loop' / 'loop' not in scoped,
          'scoped tree does not descend into nested aliases')
    for mode, total in [('--map', 11), ('--map-visible', 5)]:
        result = subprocess.run([str(binary), str(fixture), mode], capture_output=True, timeout=10)
        check(result.returncode == 0 and not result.stderr, 'native map completes')
        fields = result.stdout.split(b'\0')
        mapped = {Path(os.fsdecode(fields[i+3])): int(fields[i+1]) for i in range(0, len(fields)-1, 4)}
        check(mapped[nested] == total and mapped[sibling] == 0 and mapped[fixture / 'plain.txt'] == 5,
              'map emits exact totals for immediate children including empty folders')
        check((fixture / '.hidden' in mapped) == (mode == '--map'), 'map honors hidden-file preference')
        check(not any(path.parent == nested for path in mapped) and fixture / 'loop' not in mapped,
              'map does not stream descendants or traverse aliases')
    parallel = fixture / 'parallel'
    parallel.mkdir()
    expected = {parallel}
    for i in range(48):
        parent = parallel / str(i)
        parent.mkdir()
        leaf = parent / ('long-name-' + str(i))
        leaf.write_bytes(b'example')
        expected.update((parent, leaf))
    for attempt in range(3):
        check(set(scan(parallel)) == expected, 'concurrent traversal emits every expected row once')
    # Many workers contribute to the same tile; exact totals must not lose simultaneous updates.
    for i in range(48):
        parent = parallel / str(i)
        (parent / '.hidden').write_bytes(b'hidden')
        (parent / f'日本語-{i}\nfile').write_bytes(bytes(i))
    for attempt in range(5):
        for options, expected in [(('--map',), 48 * 13 + sum(range(48))),
                                  (('--map-visible',), 48 * 7 + sum(range(48)))]:
            records = compare_protocols(fixture, *options)
            check(records[os.fsencode(parallel)][1] == expected, 'parallel folder totals stay exact across workers')
    compare_protocols(fixture, '--children')
    compare_protocols(fixture / 'loop', '--tree')
    compare_protocols(fixture, str(nested))
    missing = subprocess.run([str(binary), str(fixture / 'missing')], capture_output=True, timeout=10)
    check(missing.returncode == 1 and not missing.stdout and bool(missing.stderr), 'missing root reports traversal error')
    invalid = subprocess.run([str(binary), 'relative'], capture_output=True, timeout=10)
    check(invalid.returncode == 2 and not invalid.stdout, 'invalid root reports fatal input error')

print(f'PASS {assertions} native scanner assertions: metadata, names, types, exclusions and errors.')
