# Anything

Anything is an on-device filename search and file manager for Android 8.0 and later, by CallCraft Studio. It builds a local index so you can search files and folders as you type, without scanning storage for every query.

## Features

- Search by name, extension, folder, size or modified date. Sort results by name, path, size or date.
- Browse in list, compact or grid view, or use the storage map to see what takes up space.
- Preview images, audio, video, PDFs, text and ZIP archives without leaving the app.
- Copy, move, rename, share or delete files from search results. Bulk rename previews changes before applying them.
- Search installed launcher apps and, with permission, contacts. The Full APK can also search SMS; the Play variant omits SMS access.

## Get started

Build a debug APK from source using the instructions below. For packaged builds, choose the Full APK for optional SMS search or the Play APK without SMS access.

Grant file access when prompted and let the first index finish. Later launches reuse the saved index and check for changes in the background. Shared-storage search does not require root. To search protected directories, select the root index scope on a rooted device and grant root access.

Try these queries:

```text
invoice ext:pdf
photo !backup
size:>10mb dm:today
```

See the [query guide](core/README.md) for more examples and supported syntax.

## Build from source

Use x86-64 Linux (or x86-64 WSL on Windows) for the desktop build. Install JDK 17, Python 3, Bash, Android SDK Platform 36, Build Tools 36.0.0 and NDK 28.2.13676358. Set `ANDROID_HOME` to your Android SDK directory in that environment. From the repository root, run:

```sh
bash scripts/build.sh :app:assembleFullDebug
```

The APK is written to `app/build/outputs/apk/full/debug/app-full-debug.apk`. Use `:app:assemblePlayDebug` to build without SMS access. The [user and maintainer guide](GUIDE.md) covers signing, tests and release packaging.

## Support and license

Open **More actions → Help** for search examples, troubleshooting, and bug reports. See [Support](SUPPORT.md) for reporting options and [Privacy](store/PRIVACY.md) for how the app handles data.

Copyright © 2026 CallCraft Studio. The project’s original software and documentation are licensed under the [MIT License](LICENSE). See [third-party notices](THIRD_PARTY_NOTICES.md) for separately licensed material.
