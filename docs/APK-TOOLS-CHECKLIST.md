# Anything: APK, DEX, and power tools checklist

This is the scope for an Anything workflow inspired by [MT Manager's official guide](https://mt.cc/guide/) and [developer release notes](https://t.me/s/mtmanager). It describes Anything's status, not feature parity with MT Manager. Only inspect or modify packages you own or are authorized to work on.

## Already in the test line

- [x] Open an ordinary APK/Dex from accessible storage without root. Show package name, version, Android requirements and file contents.
- [x] Browse standalone DEX and standard `classes.dex`, `classes2.dex`, etc. in APKs. Search by class, package or DEX entry. Identify which entry contains a class.
- [x] Read selectable, syntax-colored Baksmali output without executing or modifying the source.
- [x] Browse common archive types, extract supported formats without overwriting, and copy/move from one file pane into the other.
- [x] Hand eligible APK installation to Android's normal confirmation screen on the GitHub editions. Play edition inspects only.

## Next: make the read-only tools useful for real work

- [ ] Find text within Smali with previous/next, match count, current-line visibility and clear feedback.
- [ ] Add a dedicated DEX entry picker and package tree with class/method/field navigation.
- [ ] Add bounded cross-file search for strings, methods and references, with cancellation and results labeled by source DEX.
- [ ] Export a selected class as a `.smali` file to a destination the user picks. Never overwrite a file silently.
- [ ] Show an APK summary with permissions, signing certificates, supported ABIs, splits and per-file checksums.
- [ ] Open compiled Android XML/manifest and resource tables (`resources.arsc`) read-only before adding editing. Resolve resource IDs to names where possible.
- [ ] Add APK and DEX comparison, text diff, archive integrity checks, and read-only hex with offsets and byte search.
- [ ] Evaluate Java decompilation separately. A prototype exists; it is not integrated or verified on Android’s runtime.

## Then: edit and build a copy the user controls

- [ ] Edit Smali in a separate project with find/replace, undo, line numbers and assembler diagnostics. Preserve the original package and DEX.
- [ ] Assemble edited classes back to DEX, preserve untouched multidex entries and verify the output loads.
- [ ] Repack to a new APK, align it, sign with a user-controlled key on device, and verify the new signature before export. Never pretend the original signature remains valid.
- [ ] Add controlled binary XML/resource edits with preview, validation and diffs; handle split/APKS packages explicitly rather than silently constructing a broken single APK.
- [ ] Support explicit pack/create/test workflows for archives, bookmarks and folder sync for two panes, range/type selections and quicker path/filter controls. Treat unsupported formats as such.
- [ ] Test malformed inputs, large and multiple DEX files, cancellation, exact input preservation, permission-denied states and restoration after interruptions. Validate Android 8 and current Android on isolated fixtures before a public release.

**Root boundary:** Read-only analysis and editing a user-owned copy do not need root. Root only helps read otherwise inaccessible paths if the user enables it. Do not write to protected locations, bypass app protections or perform silent installs.
