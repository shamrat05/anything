# DEX and Smali checklist

Scope: inspect apps and DEX files you own or have permission to examine. Nothing in the inspection view installs, executes or edits selected code.

## In the explorer

- [x] Open a standalone DEX or browse standard root DEX entries in an APK. Each class shows its DEX file.
- [x] Search by class, package or DEX name. Open a result to read selectable, colored Baksmali.
- [x] Find literal text inside displayed Smali. Next and previous wrap and show the current match.
- [x] Limit inspection by file size, resource budget, input structure and output length. Refuse unsupported DEX containers with an explanation.
- [x] Pick an explicit DEX entry or return to all entries. Class search honors the selected entry.
- [ ] Add a package tree and bookmarked classes.
- [ ] Add a method/field outline and a read-only member index. Keep unrelated classes out of memory when possible.

## Export and deeper inspection

- [x] **Save** a complete class as a new UTF-8 .smali document through Android's local Files picker. No cloud provider, overwrite, original-file mutation, or automatic installation.
- [ ] Evaluate safe support for newer DEX containers and richer DEX validation. Check synthetic multidex, malformed inputs, cancellation and existing views on Android 8 and newer.
- [ ] Verify the separate, memory-only Java reconstruction experiment on disposable Android 15+. Require isolated UID, no arbitrary file/ZIP/network access, hard timeout and cancel, memory measurements, and realistic class context. Until then, local Smali is the Android view; for approximate Java, save the authorized original locally and open it in desktop JADX.

## Deliberately not in the release

- [ ] Smali editing and reassembly, APK rebuild/repack, and signing are not shipped. Treat any future owner-controlled editing as a distinct workflow on a copy. It needs separate authorization, safe destination handling, verification and signing review. Never modify the inspected original or silently install anything.
