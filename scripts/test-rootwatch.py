#!/usr/bin/env python3
"""Synthetic Linux checks for the exact native foreground Root watch protocol."""
from pathlib import Path
import os
import shutil
import struct
import subprocess
import tempfile
import time

SOURCE = Path(__file__).resolve().parent.parent / "app/src/main/cpp/rootindex.c"
HEADER = struct.Struct("<cqqI")
assertions = 0


def check(condition, message):
    global assertions
    assertions += 1
    if not condition:
        raise AssertionError(message)


def decode(data):
    records = []
    cursor = 0
    while cursor < len(data):
        kind, event, modified, size = HEADER.unpack_from(data, cursor)
        cursor += HEADER.size
        check(0 < size <= 4095 and cursor + size <= len(data) and
              kind in (b"d", b"-"), "valid bounded watch record")
        records.append((Path(os.fsdecode(data[cursor:cursor + size])), event, modified))
        cursor += size
    return records


def begin(scope, *children, watches=1):
    child = subprocess.Popen([str(binary), str(scope), "--packed", "--watch",
                              *map(str, children)], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    deadline = time.monotonic() + 1
    while time.monotonic() < deadline:
        if child.poll() is not None:
            break
        try:
            count = sum(f.read_text().count("inotify wd:") for f in Path(f"/proc/{child.pid}/fdinfo").iterdir())
            if count >= watches:
                return child
        except OSError:
            pass
        time.sleep(.005)
    output, error = child.communicate(timeout=4)
    raise AssertionError(f"Watch never registered: {child.returncode}, {output!r}, {error!r}")


def finish(child):
    out, err = child.communicate(timeout=4)
    check(child.returncode == 0 and not err, "native live watcher completed")
    return decode(out)


with tempfile.TemporaryDirectory(prefix="AnythingRootWatch-") as directory:
    base = Path(directory)
    binary = base / "rootindex"
    compiler = shutil.which("cc") or shutil.which("clang")
    subprocess.run([compiler, "-std=c11", "-pthread", "-O2", "-Wall", "-Wextra", "-Werror",
                    "-DLIVE_WAIT_MS=650", str(SOURCE), "-o", str(binary)], check=True)
    scope = base / "open folder"
    outside = base / "separate folder"
    inside = scope / "watched child"
    deep = inside / "not watched deeper"
    scope.mkdir()
    outside.mkdir()
    inside.mkdir()
    deep.mkdir()
    (scope / "link out").symlink_to(outside, target_is_directory=True)
    prior = scope / "rename source"
    prior.write_bytes(b"fixture")
    old = inside / "delete me"
    old.write_bytes(b"fixture")
    child = begin(scope, inside, outside, scope / "link out", watches=2)
    fresh = scope / "new Unicode 日本語 file"
    fresh.write_bytes(b"new")
    destination = inside / "renamed destination"
    prior.rename(destination)
    old.unlink()
    (deep / "should stay outside current watch cap").write_bytes(b"deep")
    (outside / "must not be watched").write_bytes(b"private synthetic sibling")
    records = finish(child)
    emitted = {name for name, event, modified in records if event >= 0 and modified == 0}
    identity = scope.stat()
    check(records[-1] == (scope, identity.st_dev, identity.st_ino), "watched inode device identity is propagated")
    check({fresh, prior, destination, old}.issubset(emitted), "create, move source and destination, delete")
    check(emitted == {fresh, prior, destination, old}, "only exact direct event paths; no outside, deep or alias leak")
    start = time.monotonic()
    check(not finish(begin(scope)), "idle watch is bounded and returns no synthetic change")
    check(.45 <= time.monotonic() - start < 3, "idle listener self-expires")
    bad = subprocess.run([str(binary), str(scope / "link out"), "--packed", "--watch"],
                         capture_output=True, timeout=4)
    check(bad.returncode == 2 and not bad.stdout, "top-level link cannot redirect a watch")
    bad = subprocess.run([str(binary), "/", "--packed", "--watch"], capture_output=True, timeout=4)
    check(bad.returncode == 2 and not bad.stdout, "whole root cannot be watched")
    child = begin(scope)
    for n in range(65):
        (scope / f"burst-{n}").write_bytes(b"x")
    flooded = finish(child)
    check(len(flooded) == 34 and flooded[-2] == (scope, identity.st_dev, identity.st_ino)
          and flooded[-1] == (scope, -1, 0),
          "bounded flood retains at-most-32 hints and produces explicit gap sentinel")
    child = begin(scope)
    moved = base / "renamed scope"
    scope.rename(moved)
    check(finish(child) == [(scope, identity.st_dev, identity.st_ino), (scope, -1, 0)],
          "watched directory identity change signals invalidation")
    scope.mkdir()
    def pinned(mode, expected):
        return subprocess.run([str(binary), str(scope), "--packed", mode, str(expected.st_dev),
                               str(expected.st_ino)], capture_output=True, timeout=4)
    for mode in ("--children-pinned", "--map-pinned"):
        wrong = pinned(mode, identity)
        check(wrong.returncode == 2 and not wrong.stdout, "replacement inode refuses automatic redraw")
    (scope / "child.txt").write_bytes(b"fixture")
    (scope / "link out").symlink_to(outside, target_is_directory=True)
    for mode in ("--children-pinned", "--map-pinned"):
        current = pinned(mode, scope.stat())
        check(current.returncode == 0 and not current.stderr, "pinned foreground redraw succeeds")
        check({row[0] for row in decode(current.stdout)} == {scope, scope / "child.txt"},
              "pinned foreground redraw cannot follow a replacement symlink")
print(f"PASS {assertions} foreground Root native watcher assertions.")
