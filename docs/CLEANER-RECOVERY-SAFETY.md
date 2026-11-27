# Duplicate removal safety gate

Cleaner is non-mutating. A read-only preflight can recheck up to 32 selected
target–keeper pairs and reserve at most 128 MiB of paired reads. It validates that
no keeper is a target anywhere in the selection, rejects repeated and protected
targets, and reports individual mismatches and budget skips. The preflight
rehashes both ordinary shared files by descriptor against the original scan
and compares the current names with those descriptors. These are point-in-time
observations, not permissions or an action token. Neither the app nor the
preflight requests write access, trashes, renames, copies or deletes these files.

## Evaluated Android mechanisms

- [Android MediaStore](https://developer.android.com/reference/android/provider/MediaStore#createTrashRequest(android.content.ContentResolver,java.util.Collection,boolean))
  performs a stock trash request before delivering the activity result. Approval
  cannot be used to run the keeper/target recheck before that operation.
- Item-scoped `createWriteRequest` separates approval from the operation, but
  granting write access does not bind the next mutation to the reviewed
  descriptor. A MediaStore generation is useful for stale row detection, not a
  filesystem compare-and-swap. [AOSP MediaProvider](https://android.googlesource.com/platform/packages/providers/MediaProvider/+/69a25763cdb46c8f23fe9eb976132acbe2af82d6/src/com/android/providers/media/MediaProvider.java#8997)
  evaluates the conditional row lookup, then renames a pathname, then runs the
  final metadata update predicate. An intervening same-path replacement can
  therefore reach the rename, even with an exact predicate and post-write
  checking. A return value is not a filesystem receipt. Android may later
  permanently remove media from system Trash when it expires.
- Linux [`renameat2(RENAME_NOREPLACE)`](https://man7.org/linux/man-pages/man2/renameat2.2.html)
  protects the destination name from overwrite. It does not assert the source
  path's object identity. Android/FUSE availability also cannot be assumed.
  Comparing a path immediately before renaming reduces but does not eliminate
  this race. Automatically rolling back to a re-used original name risks
  overwriting new data unless it is another no-replace operation.

## Requirements to enable a removal flow

An eligible platform path must atomically bind the intended source, not just
its name. If a future design can only move a pathname, it needs a durable
same-volume recovery journal persisted before mutation, a private recovery
namespace with no-overwrite transfer, proof of which object actually moved,
and a recovery path that can never delete or overwrite on conflict. That
design still cannot claim it acts on the reviewed source under concurrent
replacement unless the source binding itself is atomic.

Test concurrent same-name replacements, changed keepers, permission revocation,
unexpected rename outcomes and unmounts. Test crash points before and after both
the durable intent and the move, including restarts, full disks and failed
rollback. Require a review and a separate explicit confirmation; keep truthful
receipts and restore availability, never use permanent deletion as a fallback.
Until a supported mechanism meets those conditions, individual files may be
opened in Files for regular user-directed actions; Cleaner does not remove them.
