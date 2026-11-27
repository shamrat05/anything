# XZ for Java 1.12

- Project and release information: https://tukaani.org/xz/java.html
- Vendored artifact: https://repo.maven.apache.org/maven2/org/tukaani/xz/1.12/xz-1.12.jar
- License: 0BSD, included here from the signed upstream release.
- Verified both the artifact and release source OpenPGP signatures against
  the publisher's key (fingerprint `3690C240CE51B4670D30AD1C38EE757D69184620`).
  The app pins the SHA-256 in `third_party/preview-sha256.txt`.

Only the raw LZMA/LZMA2 decoder entry points are used. Anything bounds
metadata, decompressed size, dictionary allocation and output paths before
opening a decoder. This dependency does not enable generic archive handling.
