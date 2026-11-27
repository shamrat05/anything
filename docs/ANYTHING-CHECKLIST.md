# Anything: full checklist

**Status:** Checked items are implemented; release checks separately say what has actually run. Ongoing changes are not part of the attached test APK unless noted. No files on the user's phone are changed by testing.

## Current test APK: implemented and verified on Android 8

- [x] Quick Preview: left/right navigation in the current results or pane, skip folders, no wrap at the ends, vertical scrolling preserved, and shorter content closer to the fixed bottom controls.
- [x] Cleaner and Duplicate Finder have separate items under Sidebar → Storage. **Scan storage** works without choosing a folder; choosing a folder narrows it.
- [x] Cleaner suggests reviewing older installers, unfinished downloads and temp files using age and location. Shows reasons and logical sizes; backup, archive and shared social media matches are not counted as automatic savings.
- [x] Duplicate Finder groups byte-for-byte matches, shows estimates, explains its suggested keeper, permits a different keeper and selects eligible extras without selecting the keeper, backups, protected files or hard links.
- [x] Safe cleanup can clear Anything-generated thumbnails after review and confirmation. Shared files and duplicates are not deleted in this build.

## Dark theme and selection

- [ ] Latest test candidate: pure black Dark file and folder backgrounds, internal storage surfaces, and Change view menu. Grid and Select use matching outlines and tighter icon spacing. Android visual regression is pending on this revision; Light keeps the existing wallpaper tint.

## Archives and viewers

- [x] Existing standalone viewers: supported images, audio/video via Android codecs, PDFs, text and supported Office summaries. Existing ZIP member previews: images, text, PDF.
- [x] Existing extraction: ZIP/JAR/CBZ, TAR/TGZ/GZIP and supported single-volume 7z. No silent overwrite.
- [ ] Open supported nested archives directly, then open entries within them and navigate back without extracting to a user folder. Nested ZIP navigation passed Android 8 probes with owned fixtures. Other nesting formats are checked separately.
- [ ] Play supported audio/video and render supported Office documents from ZIP-family members. Reusing ordinary viewers with bounded, CRC-checked private data; a nested DOCX, WAV, and MP4 passed Android 8 probes.
- [ ] Read TAR/TGZ listings without extracting. Extend nested/member routing to TAR and 7z; confirm limits and errors for oversized, corrupt, encrypted, split and unsupported archives.
- [ ] Finish archive integrity checks, pack/create support and cross-pane archive actions. RAR and encrypted/split coverage are separate work, not assumed.

## APK, DEX and power tools

- [x] APK metadata and normal Android installer handoff on GitHub builds. Read-only standard multi-DEX and selectable syntax-colored Baksmali already work without root for accessible files.
- [ ] Find within Smali and step through matches (implemented in source and compiled; Android UI verification pending). Add DEX picker/package tree, method/field navigation, reference/string search and Smali export.
- [ ] APK permissions, signer fingerprints, ABI/split facts, SHA-256; APK/DEX/text comparisons; read-only hex with byte search. Components in progress, not in the attached APK.
- [ ] Read compiled Android XML/manifest and resource table names/values; edit resource/XML copies only after read-only QA. Compiled XML work exists; integrated verification pending.
- [ ] Java decompilation on Android, editable Smali workspace with diagnostics, assemble DEX, rebuild and verify an APK copy, align, sign with a user-controlled key, verify signatures, handle split packages explicitly.
- [ ] Power-user file workflows: two-pane sync, bookmarks, range/type selection, improved path/filter controls and archive integration.
- [ ] Root stays opt-in for otherwise unreadable paths. Read/inspect or edit an authorized copy without root; don't change protected originals or bypass app/install protections.

## Safety and release gates

- [ ] Reversible shared-folder cleanup and duplicate Trash: recheck each reviewed identity after permission, protect the keeper and excluded paths, report every skipped item and confirm restore. Not shipped.
- [ ] Bulk review across large scans with searchable results, selected-only view, and clear counts when filters hide selected files.
- [ ] Recheck the exact combined build on the Realme and current Android, including Android 17/16 KiB where supported; production-signed upgrade for both editions and update routing before a public release.
- [ ] Existing backlog: root watcher and large-directory device benchmarks, retained launcher widget and assisted accessibility checks, and unsupported preview regression coverage.
