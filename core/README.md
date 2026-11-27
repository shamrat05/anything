# Search core

Independent Java 8 implementation of the bounded Anything query language.
It matches immutable index records and never reads the filesystem. The Android
app owns indexing, sorting, filters, cancellation, history and file actions.

API: `dev.fastsearch.core.FileEntry(String path, boolean directory, long size,
long modified)` exposes final `path`, `name`, `parent`, `directory`, `size` and
`modified` fields. Times are Unix milliseconds; negative metadata values mean
unknown. `SearchQuery.compile(text, options)` returns a reusable query with
`boolean matches(FileEntry)`. `SearchQuery.Options` has `matchCase`, `wholeWord`,
`matchPath` and `regex` booleans, all false by default. The compiler snapshots
options. Invalid or unsupported syntax throws `QueryException` with a displayable
message and zero-based `position`. Pattern work limits also report a
`QueryException`; thread interruption throws `CancellationException` without
clearing the interrupt flag. The same compiled query can be used by multiple
workers.

Implemented syntax:

- Space is AND, `|` is OR, `!` negates the following term, `<...>` groups terms.
  OR binds before AND, so `apple banana|orange` means
  `apple AND (banana OR orange)`. Quotes protect spaces and Boolean operators.
- Plain text matches a filename substring, with case ignored by default.
  `*` and `?` match a complete filename. Wildcards stay active inside quotes.
- `file:`, `folder:`, `case:`, `nocase:`, `path:` and `nopath:` apply to an
  immediately following term or group. Bare `file:` and `folder:` are filters.
- `ext:jpg;png` matches the final extension of files. An optional leading dot
  and wildcard characters are accepted in each extension.
- `parent:"/absolute/folder"` matches direct children, with trailing `/` ignored.
- `regex:"^(foo|bar)\.txt$"` uses an RE2 regular expression for one atom. Quote
  patterns containing query operators. The Regex option treats the entire search
  field as one expression and disables the query language.
- `size:>1mb`, `size:=1024`, `size:1kb..2kb`, `size:1kb-2kb` and the comparison
  operators `<`, `<=`, `>`, `>=`, `=`, `==`, `!=` and `!` are supported for files.
  Units B, K/KB, M/MB, G/GB and T/TB use powers of 1024. KiB/MiB/GiB/TiB are
  accepted aliases. A bare unit value retains its specified granularity:
  `size:1kb` matches 1024 through 2047, while `size:=1kb` matches exactly 1024.
  Decimal precision narrows that granularity; conversion rounds down to bytes.
  Ranges include their numeric endpoints. Unknown sizes fail every size test.
- `dm:` (`datemodified:` / `date-modified:`) accepts YYYY, YYYY-MM, YYYY-MM-DD,
  `today`, `yesterday`, comparisons and inclusive `..` ranges. Dates use calendar
  boundaries in the device timezone, including daylight saving changes.
  `dm:>2024-02-29` starts on March 1; `dm:<=2024-02-29` includes all February 29.
  Relative days and timezone are captured when compiling. Unknown dates fail
  every date test.
- Character entities `&sp:`, `&vert:`, `&excl:`, `&lt:`, `&gt:`, `&quot:`,
  `&#233:` and `&#x1f600:` insert literal characters after tokenization.
  Backslash is literal, including before quotes; `&quot:` inserts a quote.
- The shipped stable help also documents `quot:`, `apos:`, `amp:`, `lt:` and
  `gt:` literal-character macros. These are supported outside quotes, including
  `quot:fooquot:` to find `"foo"`. Quote a macro name to search for its literal
  spelling. Quoted legacy macro expansion was not verified against the target.

Android adaptations and limits:

- Paths use `/`. A slash in ordinary search text enables path matching unless
  `nopath:` overrides it. Backslash is an ordinary filename character. Wildcards
  can cross `/` when matching full paths. Root `/` has no parent.
- Regex uses the pinned, unmodified RE2/J 1.8 engine.
  RE2 deliberately excludes backreferences, lookahead and lookbehind, atomic
  groups, possessive quantifiers and other PCRE extensions. Unsupported patterns
  produce a displayable syntax error. Ordinary groups, alternation, character
  classes, anchors, Unicode properties and supported counted repetitions remain
  available. RE2 `\b` and Perl shorthand classes use their upstream ASCII rules.
  Whole-word matching uses Unicode letters/digits and `_` as word characters;
  it affects ordinary text, not already anchored wildcard patterns or regex.
- Case folding uses `Locale.ROOT` for literal text, RE2 Unicode simple folding
  for regex and Java codepoint upper/lower folding for wildcards. `?` consumes one
  Unicode codepoint and `*` can consume newlines. Unicode normalization and
  Windows collation are not reproduced.
- `child:` cannot be answered from a single record and reports an unsupported
  relationship-index error. Other unimplemented functions, macros and modifiers
  report an unsupported-query error. Quote colon-containing text when needed.
  Empty extension queries, content search, PCRE-specific extensions, Windows
  paths, localized dates, durations and additional search operators are absent.
- Limits: 8192 query characters, 1024 terms/modifiers, 64 nesting levels.
  Regex additionally limits group depth to 64 and total groups to 128 per pattern,
  and uses a conservative 8192-unit compilation estimate before expanding
  repetitions, followed by an actual 8192-instruction program limit. The whole
  query may compile at most 32,768 regex instructions. These guards can reject
  expressions below RE2's own syntax limits. Nested large counted repetitions
  fail before expansion.
- Every `matches` call shares one budget across all pattern terms: 8,000,000
  weighted reads/retries. A regex UTF-16 read is charged its compiled program
  size; each wildcard retry is charged one unit.
  These are work accounting units, not measured CPU instructions. Wildcards use
  direct codepoint matching with bounded retries, without a backtracking regex.
  Interruption is checked every 64 budget operations and at entry/exit. There is
  no elapsed-time cutoff: class loading, garbage collection and device scheduling
  do not consume the work budget. This bounds matching work rather than wall-clock
  latency. The worker should still check cancellation between records. No
  per-record path, query or Android context is retained by the reused primitive
  budget.

The vendored JAR, original license, verified digest and upstream source references
are in [`third_party/re2j`](../third_party/re2j/README.md). Source review and a test
that interrupts during matching confirm that RE2/J 1.8 reads the guarded input
directly. Android ICU's `java.util.regex` copies it with `toString()`, so the same
guard would not enforce cancellation there.

Run from the project root (JDK 8 or newer):

```sh
mkdir -p core/build/test-classes
javac --release 8 -cp third_party/re2j/re2j-1.8.jar -d core/build/test-classes core/src/main/java/dev/fastsearch/core/*.java core/src/test/java/dev/fastsearch/core/*.java
java -cp core/build/test-classes:third_party/re2j/re2j-1.8.jar dev.fastsearch.core.SearchQueryTest
```

On JDK 8, replace `--release 8` with `-source 8 -target 8`.

## Cleaner review rules

`CleanerRules` classifies immutable `FileEntry` metadata without filesystem access.
Construct a `Filter` from verified, ordinary app-accessible Android storage
mounts, a minimum age in milliseconds, a minimum byte size and optional folder
and extension conditions. The optional final constructor parameter excludes
whole folders. Folder comparisons are full path segments; extension groups are
comma- or semicolon-separated, and `tar.gz` matches as one compound suffix.
`Filter.accepts` applies the same scope and review conditions even to files
without a rule label, for example when preparing a separate duplicate check.

Classifications describe filename or location clues, including temporary files,
shared thumbnail caches, rejected patches, diagnostics, backups, archives and
shared WhatsApp or Telegram media. No result marks anything safe to remove, and
personal media outside recognized folders is not independently suggested.
Private/root paths, non-media Android subtrees, system providers, trash and
protected metadata paths are out of scope. Do not use this lexical filter in
place of an ordinary verified volume traversal, a symlink policy or user consent
for any action. It never opens, changes or deletes files.
