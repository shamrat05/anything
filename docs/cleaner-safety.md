# Cleaner and Duplicate finder

Both tools are under **Sidebar → Storage**. Tap **Scan storage** to scan the shared storage that Android allows Anything to read, including recognized mounted cards when available. **Choose a folder** is an optional way to narrow the scan. If a portion could not be scanned or the bounded scan reaches its limit, the results report skipped items or a limited scan. Choosing a category, opening the sheet, and running a scan do not change files.

- **Exact duplicates** compares file contents, suggests a copy to keep, and does not remove shared files. Backup and primary-storage clues inform the suggestion. A suggestion is not permission to delete.
- **Temp & cache**, **Downloads**, **Backups**, **Archives**, and **Social media** are review categories. Names and locations alone cannot reliably tell whether another app still needs a file. Android app-private directories, app data, and system/root paths are excluded.
- **Safe cleanup → Thumbnail cache** measures only Anything's generated image copies. Confirming clears that reviewed set, rechecks each entry and skips changes, new files and links. The receipt reports what was removed and what was skipped. Original photos, downloads, other apps, and backups are untouched. Cleared thumbnails can be regenerated.

Kudu references: [cleaning rules](https://github.com/AdventDevInc/kudu/blob/main/rules/RULES.md), [preview/restore for custom folders](https://github.com/AdventDevInc/kudu/pull/536), and [keep-one and duplicate revalidation](https://github.com/AdventDevInc/kudu/pull/493). Nothing from Kudu runs on the device. Desktop Trash is not directly portable; this build does not claim to provide shared-file trash or bulk duplicate cleanup.

## Suggested reviews and duplicate groups

Cleaner keeps age-and-location suggestions ahead of plain matches. The **Review suggestions** filter shows their combined logical size. Current rules suggest reviewing older APKs and incomplete files directly in Downloads, or older .tmp/.temp files in clearly named shared temporary folders. The reason explains the specific threshold. Recent files, backups, archives, protected locations and messaging media are not included in that estimate. Browse those categories separately.

Duplicate finder shows exact groups, an explanation for the suggested keeper, and how much logical space eligible extras occupy. You can choose another keeper, select extras for one group or all complete groups, and clear the selection. A clipped group is not selectable; backups, protected copies and hard links are never included in the selection. These are review selections, not a shared-storage delete or Trash command.
