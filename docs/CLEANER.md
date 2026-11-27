# Cleaner and Duplicate Finder

Cleaner is a local review tool for ordinary, Android-accessible shared storage. It skips app-private storage, protected Android directories, and symlinks instead of requesting extra access or using root. Open **Cleaner** or **Duplicate finder** directly in the sidebar. Pick a volume or a folder, choose what to look for, then run a scan. Results describe what was read or skipped; they do not certify that a file is disposable.

## Reviews

- **Exact duplicates** group different files with the same complete streamed digest after a size and short-read prefilter. Each group shows how much logical space its eligible extras represent while keeping a verified copy. Choose **Select extras** for the current review or tap a group heading to select only that group's extras. Tap a file to read its recommendation or choose a different keeper for this scan. The choices mark results for review only; they do not delete, move, pin or permanently store a preference. Protected folders, backup names and folders, and files with multiple hard links stay excluded. Incomplete groups cannot be selected. Actual disk savings may differ from the estimate.
- **Temporary, cache and download artifacts** flag conservative patterns with age limits. They are not another app’s private cache. Use that app's Android storage screen for its own cache.
- **Backups, archives and shared social-media files** are distinct review lists. Messages, album files and backup history are not junk just because an app wrote them. Filters are explicit and narrow scans by folder, minimum age, minimum size, extensions, and exclusions.
- Scans are cancellable and bounded. Shared storage can include removable volumes, but an inaccessible folder or a partial scan does not count as an empty one.

Cleaner does not modify files. To review an individual result in context, tap it and choose **Show in Files**. A later removal flow would need a fresh candidate and keeper verification, a serialized action, and a supported trash/restore route or an explicit permanent-delete confirmation. Report completed and skipped items separately rather than claiming all displayed bytes as reclaimed.

## Precedent

Kudu documents a deliberate scan, review and receipt workflow in [Cleaning Tools](https://usekudu.com/docs/agent/cleaning). Its [duplicates safety change](https://github.com/AdventDevInc/kudu/pull/493) separately checks current file identity and at least one independent surviving copy. Its [rule format](https://github.com/AdventDevInc/kudu/blob/main/rules/RULES.md) bounds discovery and excludes sensitive subtrees. Anything has its own native Android implementation and does not port Kudu's desktop paths or invoke its cleanup commands. [Android shared-storage restrictions](https://developer.android.com/training/data-storage/manage-all-files) still apply.
