# Synthetic 7z regression inputs

Generated using the official 7-Zip 26.06 Linux x64 CLI, from the release asset
with SHA-256 `2b539cf067b2ad48e1baa5093dbaa8818b73542d1222a0ffde9dc5f2922ea62e`.

The three accepted files hold the same content: an empty directory, an empty
file, a Unicode text file, and a binary file created by repeating bytes 0–255
437 times. Metadata timestamps were omitted to make fixtures reproducible.
Copy leaves the metadata uncompressed; the other two use independent standard
LZMA (non-solid) and LZMA2 (solid) archives. The rejected fixtures contain only
synthetic data: an encrypted header, a local symlink, a filtered stream, and
an archive needing a larger dictionary. No third-party material is archived here.
