# Anything user and maintainer guide

Local filename search for Android 8.0 and later. Anything saves separate shared-storage and root indexes in SQLite and searches an immutable memory snapshot as you type. Reopening uses the saved index; background reconciliation applies changes without starting another full build.

## Use

Choose Anything for Android for file search and management. Choose Anything with SMS search only if you want optional text message search. Install one APK at a time, and use the same variant and signing certificate for updates.

A fresh install explains File access, then opens Android's storage permission screen when requested. Contacts and SMS ask separately when their category is used. Notifications are optional. App search requires no private-data permission. Root folders require a rooted phone and a separate root grant. Shared storage permission does not expose other apps' private files.

Use the category control to filter files or hold it to combine file types. Combined types share the active Name, Path, Size or Modified sort. Choose Automatic, List, Compact list or Grid. Touch the right edge to reveal the draggable scrollbar and jump through every indexed file result; a slim bar identifies the current letter, day, size range or folder. Contacts and SMS load automatically near the bottom. Light, Dark, System, hidden-file visibility, haptics and the optional compact status badge are saved in the menu.

Descending is initially checked; later sort choices are retained. Within a file category, the extension button before Change view shows available formats and counts, most frequent first. The compact three-column picker expands with More. Its filter also applies to selection, export and preview browsing.

Tap an icon or hold a result to select. Selection offers Select all, Invert, Copy, Move, Paste, Rename and confirmed Delete. One Back clears selection. Pinch results to move between list densities and grid sizes. Copy preserves existing destination names. Moves require the same filesystem. Deletion is permanent and asks for confirmation. Folder taps scope the search; file taps open native previews for images, vector SVG/SVGZ, video, audio, PDF, Markdown, HTML, text and ZIP. Video/audio have seek, play/pause, playback speed and volume controls; video also has per-window brightness. Rich documents offer source view and text size controls. Swipe or use the previous/next controls to browse; pinch and pan take priority when an image is zoomed. Hold a preview to add it to the selection strip. Tap its path to view and copy the complete path.

The first index reads metadata. Queries do not traverse the filesystem. Shared storage requests at most 2,048 live directory watches while running. After process restart, one background folder check reconciles changed/new branches while cached results stay usable. Successful catch-up does not repeat when returning from previews. Copy, Move, Rename and Delete update only affected paths and parent metadata before the operation completes. Explicit Refresh also verifies cached file metadata and root updates. This is not a durable filesystem journal. Statistics report scan scope and monitoring gaps.

Initial root traversal uses a bundled native scanner, so Android 10 does not need system find printf support. Filenames and millisecond metadata stream through the existing cancellable privileged process; virtual paths, duplicate mounts, symlinks and special files remain excluded.

Folder names appear in ordinary results; `folder: music` searches folders specifically. Tapping a folder scopes searches to its descendants. Back and the scoped search X return one browsed directory while keeping the current category. Hold X for query, folder or both clearing. Directory history survives Activity recreation. Hidden files are initially shown. Turning them off also hides descendants of dot-prefixed folders without rebuilding the index.

The leading folder icon in Search switches between Internal storage, mounted SD cards and Root. In a root index, Internal storage uses its already indexed `/data/media/<user>` backing path. Selecting Root keeps the current root directory across relaunches until you explicitly switch storage. Root requires a root index. Storage > Start location offers Internal storage, Root and Last location. Last location also saves the query, result position and directory history. Storage > Index scope controls root and shared index access separately.

Choose **File Manager** in the category picker to browse a directory's immediate contents. Tap the path and type an absolute path to see matching directories. Tap a suggestion or **Parent folder** to browse in the same dialog, then tap **Go** to open the displayed path. The clock button on the path bar opens recent folders and bookmarks.

Choose **Storage map** under Change view. With no folder selected, it uses the first mounted SD card or internal storage. Swipe up or down to reach every block and spread two fingers to enlarge smaller blocks. The color legend groups blocks by file size or total folder size. Large items get more space within each row, with a size floor for small and zero-byte entries. Tap a folder to drill down or a file to open it. Hold a block to select it and see its name, full path, type, size and share of the current folder. Keep holding and drag over nearby blocks to inspect their details without changing the selection. Copy, Move and Delete use the selection toolbar, and Paste defaults to the current directory. The file-list icon switches between the map and the list.

The overflow menu groups Search, Filters, File actions, Storage, Appearance and Help. Its back arrow and Android Back each return one parent level in the same window. Back at the top level closes the menu.

Internal previews reuse the ordered results and open files on a worker. On Android 8.1+, up to 24 MiB of decoded preview data and 128 per-file view states remain available in the process. Each revisit reopens the file and validates its device/inode, size and nanosecond modification/change times before reuse. Memory pressure or process death can evict this data; players and descriptors are always released. Android 8.0 re-reads content because its public stat API lacks the required time precision. Shared-storage reconciliation uses the existing app worker and snapshot; root reconciliation keeps its isolated helper.

Default name searches reuse the index's order. Updates merge changed records into that order, preserving immutable snapshots.

Example queries:

```text
invoice ext:pdf;txt
photo !backup
apple banana|orange
size:>10mb dm:today
parent:"/storage/emulated/0/Download"
```

OR binds before AND. Paths use `/`. Regex uses pinned RE2/J 1.8 with compilation and matching work limits; PCRE features such as lookaround and backreferences are unsupported. [Query syntax and limits](core/README.md) documents the bounded implementation.

## Build

For a desktop build, use x86-64 Linux or WSL with JDK 17, Python 3, Bash, Android platform 36, build-tools 36.0.0 and NDK 28.2.13676358. Set `ANDROID_HOME` to the SDK in that environment. The wrapper uses Gradle 8.13. Minimum API is 26; compile and target API are 36.

```sh
python3 scripts/setup-signing.py
scripts/check.sh
scripts/build.sh :app:assembleFullDebug :app:assembleFullRelease :app:assembleGithubRelease :app:bundlePlayRelease :app:assembleFullDebugAndroidTest :app:lintFullDebug :app:lintGithubRelease :app:lintPlayRelease --console=plain
```

Signing material lives outside Git under `~/.local/share/anything-android/`; `release-signing.properties` is ignored. Back up the key privately. GitHub Actions builds four native ABIs with 16 KiB ELF alignment. Without a configured `FASTSEARCH_SIGNING_BUNDLE`, the runner generates a temporary signing key. Such artifacts are for testing, not updates to existing installations. Use the same release signing key for updates. Local Termux builds use installed `aapt2` and a local compiler.

[The workflow](.github/workflows/android.yml) runs host checks, builds the APKs and Play AAB, runs Full/Play lint, validates signatures, permissions, target SDK, bundle structure and native ABIs, then uploads the distribution. It does not run Android instrumentation.

## Device checks

Use a separate `.fresh` debug application ID for synthetic device tests. Never clear the owner's installation or index. Install the test APK with the same application ID and run one instrumentation process at a time:

```sh
scripts/build.sh -PfastsearchApplicationId=com.callcraftstudio.anything.fresh :app:assembleFullDebug :app:assembleFullDebugAndroidTest
adb install -r -t app/build/outputs/apk/full/debug/app-full-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/full/debug/app-full-debug-androidTest.apk
adb shell am instrument -w -r com.callcraftstudio.anything.fresh.test/dev.fastsearch.android.IndexProbe
adb shell am instrument -w -r -e phase warm com.callcraftstudio.anything.fresh.test/dev.fastsearch.android.ProviderProbe
adb shell am force-stop com.callcraftstudio.anything.fresh
adb shell am instrument -w -r -e phase cold com.callcraftstudio.anything.fresh.test/dev.fastsearch.android.ProviderProbe
```

Other custom runners: UpdateProbe, OperationUpdateProbe, DeviceSearchProbe, FileOperationProbe, PreviewProbe, UiProbe, LocationProbe, PreviewUiProbe, PerformanceProbe and PermissionProbe. LocationProbe checks nested-menu Android Back, location shortcuts, saved starts/history and unchanged index counters. OperationUpdateProbe runs the actual foreground file-operation service and checks targeted commits without whole-cache verification in shared/root scopes. PerformanceProbe measures 100,000 synthetic saved records and live changes among 5,000 owned files. PermissionProbe requires a fresh `.fresh` debug installation with file/contacts/SMS permissions initially denied. Clearing an existing test installation's data may retain its all-files UID AppOp; verify the test-only grant is denied first. Probes use bounded synthetic files, not user contacts, messages or media. Index/provider/root-operation checks require a test root grant.

RootScannerProbe additionally exercises the packaged initial native scanner, metadata precision, special filenames, cancellation and failure messages. It defaults to an unprivileged owned fixture and is included in API 29 CI without a test-app root grant. Its optional `root=true` mode requires an existing grant.

`HelpProbe` checks the three help tabs, report validation, opt-in diagnostic preview, and correct composition of an email draft. It does not send email, open the web form, or request root.

Every invocation must report both `INSTRUMENTATION_RESULT: passed=true` and `INSTRUMENTATION_CODE: -1`. Shell exit status alone is insufficient.

After committing the checked source, `python3 scripts/package.py` archives `HEAD`, validates distributions and writes checksums under `dist/`. Outputs include the Anything GitHub APKs, the Play AAB, test APK, source ZIP, store assets and reports. The GitHub APKs offer in-app funding links. Use the Play AAB for store distribution. Updates must retain the same signing certificate and data.

For GitHub releases, publish `anything.apk` as **Anything for Android (recommended)** and `anything-with-sms.apk` as **Anything with SMS search**. Keep the byte-identical `anything-play.apk` under a **Legacy download link** label so previously shared URLs still work. Upload `SHA256SUMS-release.txt` with those three APKs.

## Support and submission

Support: [shamrat@callcraftstudio.com](mailto:shamrat@callcraftstudio.com). Website: https://callcraftstudio.com/. [Privacy policy](https://callcraftstudio.com/anything/privacy/).

A signed bundle is not Play approval. Console enrollment, declarations, testing eligibility, upload and review remain account tasks.

See the [MIT License](LICENSE) and [third-party notices](THIRD_PARTY_NOTICES.md).

Rename supports one name or a batch of up to 512 items with replace, prefix/suffix and numbering. A live preview preserves extensions by default. Existing destinations, name cycles and parent/child batches are rejected; nothing is automatically overwritten. Case-only changes may be rejected on case-insensitive storage. Batch failures retain completed changes and reconcile affected paths. SVG previews support bounded vector content, without embedded raster images or external resources. Markdown tables use a simplified native text layout.

Thumbnail work follows visible cells. Requests are deduplicated and obsolete queued work is cancelled. A process-wide bitmap cache is capped at 24 MiB (lower on small heaps), with up to 96 MiB / 1,500 private disk thumbnails, pruned toward 80 MiB / 1,200 entries. Indexed path/size/modification metadata, access scope and normalized decode size identify reusable thumbnails. UI hiding does not evict this cache; real memory pressure does. Current-user shared-storage thumbnails can use the accessible storage alias in root mode, while protected files retain the checked root reader. Rename/Delete panels render before asynchronous identity preparation; confirmation stays disabled until preparation succeeds.
