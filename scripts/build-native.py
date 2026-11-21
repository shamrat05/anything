#!/usr/bin/env python3
"""Build the file-operation, discovery and initial-index helpers for Android or pinned NDK."""
from pathlib import Path
import os
import platform
import shutil
import subprocess

root = Path(__file__).resolve().parent.parent
source = root / 'app/src/main/cpp/fileops.c'
output = root / 'app/build/generated/jniLibs'
sdk = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT', ''))
ndk = Path(os.environ.get('ANDROID_NDK_HOME') or sdk / 'ndk/28.2.13676358')
commands = []
if Path('/system/bin/app_process').exists():
    # Termux's compiler targets Bionic. Only build the host ABI for local phone checks.
    abi = {'aarch64': 'arm64-v8a', 'armv7l': 'armeabi-v7a', 'x86_64': 'x86_64', 'i686': 'x86'}.get(platform.machine())
    compiler = shutil.which('clang')
    java = Path(shutil.which('javac') or '').resolve().parent.parent
    if not abi or not compiler or not (java / 'include/jni.h').is_file():
        raise SystemExit('Termux clang and a JDK with JNI headers are required.')
    commands.append((abi, compiler, ['-I' + str(java / 'include'), '-I' + str(java / 'include/linux')]))
else:
    toolchain = ndk / 'toolchains/llvm/prebuilt/linux-x86_64'
    for abi, triple in [('arm64-v8a', 'aarch64-linux-android'), ('armeabi-v7a', 'armv7a-linux-androideabi'),
                        ('x86_64', 'x86_64-linux-android'), ('x86', 'i686-linux-android')]:
        compiler = toolchain / 'bin' / (triple + '26-clang')
        if not compiler.is_file():
            raise SystemExit('Install Android NDK 28.2.13676358 or set ANDROID_NDK_HOME.')
        commands.append((abi, str(compiler), []))

output.mkdir(parents=True, exist_ok=True)
expected = {abi for abi, _, _ in commands}
for old in output.iterdir():
    if old.is_dir() and old.name not in expected:
        shutil.rmtree(old)
for abi, compiler, includes in commands:
    destination = output / abi / 'libfileops.so'
    destination.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([compiler, '-std=c11', '-Oz', '-fPIC', '-shared', '-fvisibility=hidden',
                    '-fstack-protector-strong', '-D_FORTIFY_SOURCE=2', '-Wall', '-Wextra', '-Werror',
                    '-Wl,--no-undefined', '-Wl,-z,relro,-z,now', '-Wl,-z,max-page-size=16384',
                    '-Wl,--build-id=sha1', '-Wl,-s', *includes, str(source), '-o', str(destination)], check=True)
    print('Built ' + abi + '/libfileops.so (16 KiB ELF page alignment).')

    discovery = output / abi / 'libdiscovery.so'
    subprocess.run([compiler, '-x', 'c++', '-std=c++17', '-Oz', '-fPIC', '-shared',
                    '-fvisibility=hidden', '-fno-exceptions', '-fno-rtti', '-fstack-protector-strong',
                    '-D_FORTIFY_SOURCE=2', '-Wall', '-Wextra', '-Werror',
                    '-Wl,--no-undefined', '-Wl,-z,relro,-z,now', '-Wl,-z,max-page-size=16384',
                    '-Wl,--build-id=sha1', '-Wl,-s', *includes,
                    str(root / 'app/src/main/cpp/discovery.cpp'), '-o', str(discovery)], check=True)
    print('Built ' + abi + '/libdiscovery.so (16 KiB ELF page alignment).')

    scanner = output / abi / 'librootindex.so'
    subprocess.run([compiler, '-std=c11', '-Oz', '-fPIE', '-pie',
                    '-fstack-protector-strong', '-D_FORTIFY_SOURCE=2', '-Wall', '-Wextra', '-Werror',
                    '-Wl,--no-undefined', '-Wl,-z,relro,-z,now', '-Wl,-z,max-page-size=16384',
                    '-Wl,--build-id=sha1', '-Wl,-s',
                    str(root / 'app/src/main/cpp/rootindex.c'), '-o', str(scanner)], check=True)
    print('Built ' + abi + '/librootindex.so (standalone PIE, 16 KiB ELF page alignment).')
