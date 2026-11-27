# Anything user and maintainer guide

Local filename search for Android 8.0 and later. Anything saves separate shared-storage and root indexes in SQLite and searches an immutable memory snapshot as you type. Reopening uses the saved index; background reconciliation applies changes without starting another full build.

## Use

Choose the Full APK for optional SMS search, or the Play APK without SMS. If both APKs use the same package ID, only one can be installed at a time. Updating an existing installation requires the same signing certificate.

Open **More → Guided tour → Topics** to revisit a feature without restarting the tour. The viewer has its own guide under its three-dot menu, with controls for the file you opened. You can skip or go back without changing files or granting permissions.

A fresh install explains File access, then opens Android's storage permission screen when requested. Contacts and SMS ask separately when their category is used. Notifications are optional. App search requires no private-data permission. Root folders require a rooted phone and a separate root grant. Shared storage permission does not expose other apps' private files.

Use the category control to filter files or hold it to combine file types. Combined types share the active Name, Path, Size or Modified sort. Choose Automatic, List, Compact list or Grid. Touch the right edge to reveal the draggable scrollbar and jump through every indexed file result; a slim bar identifies the current letter, day, size range or folder. Contacts and SMS load automatically near the bottom. Light, Dark, System, hidden-file visibility, haptics and the optional compact status badge are saved in the menu.

Search options opens next to the sliders icon. Match case, Whole words, Include folder paths and Regular expressions apply immediately; the menu stays open so you can change more than one. Reset matching options restores the defaults without clearing your query or folder. The same switches are under More → Search.

Descending is initially checked; later sort choices are retained. Within a file category, the extension button before Change view shows available formats and counts, most frequent first. The compact three-column picker expands with More. Its filter also applies to selection, export and preview browsing.

Press and hold a file to open its actions, including Share, Copy, Move and Properties, without opening a preview. Keep your finger down and slide to another file to update the menu. Lifting leaves the menu open on the last file; tap a button to act. Tap the filename or path at the top to copy that text, not the file. Tap a list icon or choose Select items to start multi-select. Selection offers Select all, Invert, Copy, Move, Paste, Rename and confirmed Delete. One Back clears selection. Pinch results to move between list densities and grid sizes. Copy preserves existing destination names. Moves require the same filesystem. Deletion is permanent and asks for confirmation. Folder taps scope the search; file taps open native previews for images, vector SVG/SVGZ, video, audio, PDF, Markdown, HTML, text, OOXML Office summaries and ZIP. Video/audio have seek, play/pause, playback speed and volume controls; video also has per-window brightness. Rich documents offer source view and text size controls. Swipe or use the previous/next controls to browse; pinch and pan take priority when an image is zoomed. Hold a preview to add it to the selection strip. Tap its path to view and copy the complete path.

CSV and TSV open in a scrollable, read-only table. **Source text** shows the original text preview. Full preview shows up to 80 rows and 16 columns; Quick Preview shows up to 12 rows and 6 columns. Quoted fields are read as data; formulas and links are never opened. Use **Open in another app** for the full file.

The first index reads metadata. Queries do not traverse the filesystem. Shared storage requests at most 2,048 live directory watches while running. After process restart, one background folder check reconciles changed/new branches while cached results stay usable. Successful catch-up does not repeat when returning from previews. While a window is focused, a bounded metadata check helps catch missed file events in shared storage and the open folder; it does not repeatedly scan the full drive. Copy, Move, Rename and Delete update only affected paths and parent metadata before the operation completes. Explicit Refresh also verifies cached file metadata and root updates. This is not a durable filesystem journal. Statistics report scan scope and monitoring gaps.

Initial root traversal uses a bundled native scanner, so Android 10 does not need system find printf support. Filenames and millisecond metadata stream through the existing cancellable privileged process; virtual paths, duplicate mounts, symlinks and special files remain excluded.

Folder names appear in ordinary results; `folder: music` searches folders specifically. Tapping a folder scopes searches to its descendants. Back and the scoped search X return one browsed directory while keeping the current category. Hold X for query, folder or both clearing. Directory history survives Activity recreation. Hidden files are initially shown. Turning them off also hides descendants of dot-prefixed folders without rebuilding the index.

The leading folder icon in Search switches between Internal storage, mounted SD cards and Root. In a root index, Internal storage uses its already indexed `/data/media/<user>` backing path. Selecting Root keeps the current root directory across relaunches until you explicitly switch storage. Root requires a root index. Storage > Start location offers Internal storage, Root and Last location. Last location also saves the query, result position and directory history. Storage > Index scope controls root and shared index access separately.

To start from the Android home screen, press and hold an empty area, choose **Widgets**, and add **Anything Search** or **Anything Files**. Search opens with the keyboard ready; Files opens Internal storage. Neither widget shows your history or file names on the home screen.

Open **Folder tree** in the sidebar or tap the current path in Files. The tree shows where you are and keeps neighboring folders visible as you expand a branch. Tap a folder name to jump there. Use **Type path** if you already know the address. The clock button opens recent folders and bookmarks. Two-pane Files and Paste use the same tree; choosing a Paste destination does not start the transfer.

Open **Storage overview** in the sidebar or folder tree to see Internal storage and any mounted volumes, with used and available space. Choose a volume to browse it. Back at that volume’s top level returns to the overview; this does not require Android root access.

Choose **Storage map** in the sidebar. Larger blocks take up more space, while the color key compares file sizes within this folder, from blue for smaller files to red for the largest. Gray marks grouped small files or items without a measured size. Tap any size color to outline matching files without hiding folders or changing the layout. Tap it again to clear. The summary names the biggest file the map found. Large folder blocks show a sample of files inside; tap a readable file block for its preview or tap the folder header to drill down. Use **List** for every immediate item, including tiny and zero-byte files. Press and hold a block for its actions. Folder sizes are computed from indexed or scanned file metadata, not by opening each file.

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

Signing material lives outside Git under `~/.local/share/anything-android/`; `release-signing.properties` is ignored. Back up the key privately. GitHub Actions builds four native ABIs with 16 KiB ELF alignment. A production workflow requires the original release key. Nonproduction workflows use a temporary release key; those APKs cannot update existing installations. Debug builds and their tests use a separate debug key and package identity. Use the same release signing key for updates. Local Termux builds use installed `aapt2` and a local compiler.

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

After committing the checked source, `python3 scripts/package.py` archives `HEAD`, validates distributions and writes checksums under `dist/`. Outputs include the Full and SMS-free GitHub APKs, the Play AAB, test APK, source ZIP, store assets and reports. The GitHub APKs offer in-app funding links. Use the Play AAB for store distribution. Updates must retain the same signing certificate and data.

## Support and submission

Support: [shamrat@callcraftstudio.com](mailto:shamrat@callcraftstudio.com). Website: https://callcraftstudio.com/. [Privacy policy](https://callcraftstudio.com/anything/privacy/).

A signed bundle is not Play approval. Console enrollment, declarations, testing eligibility, upload and review remain account tasks.

See the [MIT License](LICENSE) and [third-party notices](THIRD_PARTY_NOTICES.md).

Rename supports one name or a batch of up to 512 items with replace, prefix/suffix and numbering. A live preview preserves extensions by default. Existing destinations, name cycles and parent/child batches are rejected; nothing is automatically overwritten. Case-only changes may be rejected on case-insensitive storage. Batch failures retain completed changes and reconcile affected paths. SVG previews support bounded vector content, without embedded raster images or external resources. Markdown tables use a simplified native text layout.

Thumbnail work follows visible cells. Requests are deduplicated and obsolete queued work is cancelled. A process-wide bitmap cache is capped at 24 MiB (lower on small heaps), with up to 96 MiB / 1,500 private disk thumbnails, pruned toward 80 MiB / 1,200 entries. Indexed path/size/modification metadata, access scope and normalized decode size identify reusable thumbnails. UI hiding does not evict this cache; real memory pressure does. Current-user shared-storage thumbnails can use the accessible storage alias in root mode, while protected files retain the checked root reader. Rename/Delete panels render before asynchronous identity preparation; confirmation stays disabled until preparation succeeds.

### Read DEX bytecode

Open a standalone `.dex` or an APK, then choose **Browse DEX classes**. Search by
class name, package or DEX entry and tap a result to read its Smali. Inside a class,
**Find** highlights literal matches. Tap an underlined reference or open **Refs**
to follow references within the opened APK or DEX. Exact methods and fields
jump to their declarations. When more than one other DEX contains the class,
choose which one to open. Back restores the previous reading position. External
classes are not opened. Nothing is
run, modified or installed. This is bytecode disassembly, not Java decompilation.
The browser supports standard DEX versions 035–040 and root multi-DEX entries
(`classes.dex`, `classes2.dex`, etc.) within configured device and file limits.
A new DEX container format, uncommon DEX data, malformed APKs, or exceptionally
large classes may need a desktop inspector.

### Find a misspelled filename

If an ordinary single-word search finds no matching file or folder, Anything can try one missing, extra, or wrong letter, or two neighboring letters swapped. For example, try `budegt` or `budegt.pdf`; when you include a file extension, it still has to match exactly. Search labels these results **Similar file names**. Quotes, multiple words, query operators, wildcards, and case, whole-word, path or regex modes keep their usual matching rules.

Turn **Typo-tolerant search** off in **Search options** if you only want regular matches. This applies to indexed file search and its results map, not to contacts or messages. Similar-name search checks at most 250,000 eligible entries and shows up to 128 results; regular search still covers the entire index.

### Check a folder’s size

Long-press a folder and open **Properties**. Anything adds up the logical file sizes in that folder and its subfolders, including hidden files, without following symbolic links. A verified empty folder shows zero. If a folder cannot be fully checked, Properties labels its total as incomplete or shows the amount already indexed without claiming it is current. Root mode keeps its existing exclusions for virtual system folders and storage aliases.

## Cleaner and app badges

Open **Cleaner** or **Duplicate finder** directly in the sidebar, or under **More actions → Storage**. Tap **Scan storage** to scan accessible shared storage, or choose a folder to narrow it. Review exact duplicates, possible temporary files, downloads, backups, archives, or shared social files separately. See [Cleaner and Duplicate Finder](docs/CLEANER.md) for limits.

A small app badge identifies a verified package folder or an Android-reported media contributor. It does not establish who created or edited the file. Files without reliable attribution retain their ordinary type icon only.

See the [APK, DEX, and power tools checklist](docs/APK-TOOLS-CHECKLIST.md) for the remaining developer workflows.
