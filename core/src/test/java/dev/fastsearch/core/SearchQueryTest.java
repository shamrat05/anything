package dev.fastsearch.core;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.CancellationException;
import com.google.re2j.Pattern;

/** Host regression suite. Run with the commands in core/README.md. */
public final class SearchQueryTest {
    private static int checks;

    public static void main(String[] args) {
        Locale oldLocale = Locale.getDefault();
        TimeZone oldZone = TimeZone.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
            entries();
            entryTable();
            metadataOrder();
            booleanSyntax();
            textAndWildcards();
            modifiersAndRegex();
            sizes();
            dates();
            errors();
            immutableCompile();
            boundedPatterns();
            System.out.println("SearchQueryTest: " + checks + " checks passed");
        } finally {
            Locale.setDefault(oldLocale);
            TimeZone.setDefault(oldZone);
        }
    }

    private static void metadataOrder() {
        java.util.Random random = new java.util.Random(42);
        java.util.List<FileEntry> rows = new java.util.ArrayList<>();
        long[] keys = {Long.MIN_VALUE, -1, 0, 1, 255, 256, Long.MAX_VALUE};
        for (int i = 0; i < 48_000; i++) {
            long size = i < 16_000 ? 0 : keys[random.nextInt(keys.length)];
            long modified = i < 16_000 ? -1 : random.nextLong();
            rows.add(new FileEntry("/" + (i % 16) + "/Nested-" + i, false, size, modified));
        }
        java.util.Comparator<FileEntry> nameOrder = java.util.Comparator
                .comparing((FileEntry entry) -> entry.name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(FileEntry::comparePath);
        rows.sort(nameOrder);
        for (boolean modified : new boolean[]{false, true}) {
            for (boolean descending : new boolean[]{false, true}) {
                java.util.List<FileEntry> expected = new java.util.ArrayList<>(rows);
                java.util.Comparator<FileEntry> order = java.util.Comparator
                        .comparingLong((FileEntry entry) -> modified ? entry.modified : entry.size);
                expected.sort((descending ? order.reversed() : order).thenComparing(nameOrder));
                check(expected.equals(MetadataOrder.sort(rows, modified, descending, () -> {})),
                        "metadata order matches exact comparison sort");
            }
        }
        try {
            MetadataOrder.sort(rows, false, false, () -> { throw new CancellationException(); });
            throw new AssertionError("metadata sort did not cancel");
        } catch (CancellationException expected) {
            checks++;
        }
    }

    private static void entries() {
        FileEntry root = new FileEntry("/", true, -1, -1);
        check(root.name.equals("/") && root.parent.isEmpty(), "root has no parent");
        FileEntry directory = new FileEntry("/storage/emulated/0/Download///", true, -1, -1);
        check(directory.path().equals("/storage/emulated/0/Download"), "trailing slash normalization");
        check(directory.parent.equals("/storage/emulated/0") && directory.name.equals("Download"), "folder fields");
        check(new FileEntry("/hello", false, 0, 0).parent.equals("/"), "root child");
        check(new FileEntry("/storage/.photos/holiday.jpg", false, 1, 1).hidden, "hidden ancestor hides descendants");
        check(new FileEntry("/storage/photos/.secret", true, 0, 1).hidden, "dot directory is hidden");
        check(new FileEntry(".settings", false, 1, 1).hidden, "relative dot file is hidden");
        check(!new FileEntry("/storage/photo.jpg", false, 1, 1).hidden && !root.hidden, "extensions and root are visible");
        check(!SearchQuery.compile("", null).matches(null), "null entry");
        try { new FileEntry("", false, 0, 0); throw new AssertionError("accepted empty path"); }
        catch (IllegalArgumentException expected) { checks++; }
    }

    private static void entryTable() {
        FileEntryTable table = new FileEntryTable();
        FileEntry left = file("/folder/collision-o7aipi");
        FileEntry right = file("/folder/collision-pvh1ko");
        check(left.pathHash() == right.pathHash(), "fixture has a real path hash collision");
        table.put(left);
        table.put(right);
        for (int i = 0; i < 3_000; i++) table.put(file("/folder/nested/item" + i));
        check(table.size() == 3_002 && table.get(right.path()) == right, "lookups survive a growing path table");
        check(table.remove(left.path()) == left && table.get(right.path()) == right, "deletion preserves colliding lookup");
        table.put(new FileEntry(right.path(), true, 0, 0));
        check(table.size() == 3_001 && table.get(right.path()).directory, "replacement does not increase the count");
        table.put(file("/folder/nested-sibling/item"));
        table.removeDescendants("/folder/nested");
        check(table.size() == 2 && table.get("/folder/nested-sibling/item") != null, "subtree removal respects path boundary");
        java.util.Iterator<FileEntry> rows = table.values().iterator();
        rows.next();
        rows.remove();
        check(table.size() == 1 && table.values().size() == 1, "iterator deletion updates table size");
        String[] paths = {"/", "/a", "/a/a", "/a-foo", "/a//b", "//a", "relative", "relative/file", "/Ä/a",
                "/prefix/a", "/prefix.test/a", "/prefix/aa/b", "/prefix/Z/b", "/prefix2/a",
                "/Prefix/z/B", "/PREFIX/A/𐐀", "/prefix/a/𐐨", "/İ/a", "/ı/A"};
        for (String a : paths) {
            FileEntry row = file(a);
            check(row.pathEquals(a) && row.path().equals(a), "compact entry preserves exact path");
            for (String b : paths) {
                check(Integer.signum(row.comparePath(file(b))) == Integer.signum(a.compareTo(b)), "compact path order matches String order");
                check(Integer.signum(row.comparePathIgnoreCase(file(b))) == Integer.signum(a.compareToIgnoreCase(b)),
                        "compact case-insensitive path order matches String order");
            }
        }
    }

    private static void booleanSyntax() {
        yes("", file("/notes.txt"));
        yes("  \t\n", file("/notes.txt"));
        yes("notes txt", file("/notes.txt"));
        no("notes pdf", file("/notes.txt"));
        yes("notes|photo", file("/photo.jpg"));
        yes("notes | photo", file("/photo.jpg"));
        no("apple banana|orange", file("/orange.txt"));
        yes("apple banana|orange", file("/apple orange.txt"));
        yes("<apple banana>|orange", file("/orange.txt"));
        yes("apple|banana orange", file("/banana orange.txt"));
        no("apple|banana orange", file("/apple.txt"));
        yes("!photo", file("/notes.txt"));
        no("!photo", file("/photo.jpg"));
        yes("!!photo", file("/photo.jpg"));
        yes("!<apple banana>", file("/apple.txt"));
        no("!<apple banana>", file("/apple banana.txt"));
        yes("<notes|photo> !<old backup>", file("/photo.jpg"));
        yes("\"a | ! <b>\"", file("/a | ! <b>.txt"));
        no("\"a b\"", file("/a-x-b.txt"));
        yes("pre\" two words\"post", file("/pre two wordspost.txt"));
        yes("\"content:foo\"", file("/content:foo.txt"));
    }

    private static void textAndWildcards() {
        yes("FILE", file("/file.txt"));
        yes("CAFÉ", file("/café.txt"));
        yes("ПРИВЕТ", file("/привет.txt"));
        yes("😀", file("/😀.png"));
        yes("foo", file("/abcfoobar.txt"));
        yes("*.txt", file("/a.txt"));
        no("*.txt", file("/a.txt.bak"));
        yes("a?.txt", file("/ab.txt"));
        no("a?.txt", file("/abc.txt"));
        yes("a?.txt", file("/a😀.txt"));
        yes("a??.txt", file("/a😀雪.txt"));
        no("a??.txt", file("/a😀.txt"));
        yes("**a***b**", file("/xa雪b.txt"));
        yes("a?b", file("/a\nb"));
        yes("a*b", file("/a\n\nb"));
        yes("*.CAFÉ", file("/a.café"));
        yes("*.ſ", file("/a.S"));
        no("case:*.ſ", file("/a.S"));
        no("foo*", file("/afoo.txt"));
        yes("*foo*", file("/afoo.txt"));
        yes("a[1].*", file("/a[1].txt"));
        no("a[1].*", file("/a1.txt"));
        yes("folder/abc", file("/folder/abc.txt"));
        yes("/folder/*.txt", file("/folder/sub/abc.txt"));
        no("folder", file("/folder/abc.txt"));
        yes("path:folder", file("/folder/abc.txt"));
        no("nopath:folder/abc", file("/folder/abc.txt"));
        yes("a&vert:b&excl:c&lt:d&gt:e&quot:f&sp:g", file("/a|b!c<d>e\"f g.txt"));
        yes("&#x1f600:", file("/😀.png"));
        yes("&#233:", file("/café.txt"));
        yes("quot:", file("/a\"b.txt"));
        yes("apos:", file("/a'b.txt"));
        yes("amp:", file("/a&b.txt"));
        yes("lt:", file("/a<b.txt"));
        yes("gt:", file("/a>b.txt"));
        yes("quot:fooquot:", file("/a\"foo\".txt"));
        no("quot:fooquot:", file("/foo.txt"));
        yes("lt:foogt:", file("/a<foo>.txt"));
        yes("case:quot:FOOquot:", file("/a\"FOO\".txt"));
        no("case:quot:FOOquot:", file("/a\"foo\".txt"));
        yes("quot:foo&quot:", file("/a\"foo\".txt"));
        yes("\"quot:\"", file("/quot:.txt"));
        no("\"quot:\"", file("/a\"b.txt"));
        no("!quot:", file("/a\"b.txt"));
        yes("a\\b", file("/a\\b.txt"));
        yes("\"a\\\"", file("/a\\.txt"));
        SearchQuery.Options words = new SearchQuery.Options();
        words.wholeWord = true;
        yes("cat", words, file("/cat-file.txt"));
        no("cat", words, file("/cats.txt"));
        no("cat", words, file("/cat_file.txt"));
        no("cat", words, file("/écat.txt"));
        yes("cat", words, file("/cats.cat"));
    }

    private static void modifiersAndRegex() {
        no("case:File", file("/file.txt"));
        yes("case:File", file("/File.txt"));
        no("case:<File TXT>", file("/File.txt"));
        yes("case:<File txt>", file("/File.txt"));
        yes("case:File TXT", file("/File.txt"));
        yes("case:nocase:FILE", file("/file.txt"));
        yes("path:<folder abc>", file("/folder/abc.txt"));
        no("path:<folder nopath:folder>", file("/folder/abc.txt"));
        yes("file:", file("/a.txt"));
        no("file:", folder("/a.txt"));
        yes("folder:", folder("/a.txt"));
        yes("folder:case:Music", folder("/Music"));
        no("folder:case:Music", folder("/music"));
        yes("ext:jpg;png", file("/photo.PNG"));
        no("ext:jpg;png", file("/photo.png.bak"));
        no("ext:jpg;png", folder("/photo.png"));
        no("case:ext:PNG", file("/photo.png"));
        yes("ext:.txt", file("/notes.txt"));
        yes("ext:jp?", file("/photo.jpg"));
        yes("parent:/folder/", file("/folder/a.txt"));
        no("parent:/folder", file("/folder/sub/a.txt"));
        no("parent:/folder", folder("/folder"));
        yes("parent:\"/folder with spaces\"", file("/folder with spaces/a.txt"));
        yes("parent:/", folder("/folder"));
        no("parent:/", folder("/"));
        yes("regex:^IMG_\\d+", file("/IMG_123.jpg"));
        no("regex:^IMG_\\d+", file("/aIMG_123.jpg"));
        yes("*.txt regex:\"^(foo|bar)\\.txt$\"", file("/bar.txt"));
        no("*.txt regex:\"^(foo|bar)\\.txt$\"", file("/baz.txt"));
        yes("path:regex:^/folder/", file("/folder/a.txt"));
        yes("regex:café", file("/CAFÉ.txt"));
        no("case:regex:café", file("/CAFÉ.txt"));
        SearchQuery.Options regex = new SearchQuery.Options();
        regex.regex = true;
        yes("^(foo|bar)\\.txt$", regex, file("/foo.txt"));
        yes("file:.*", regex, file("/file:hello"));
        no("file:", regex, file("/ordinary.txt"));
        SearchQuery.Options path = new SearchQuery.Options();
        path.matchPath = true;
        yes("folder", path, file("/folder/a.txt"));
        no("nopath:folder", path, file("/folder/a.txt"));
    }

    private static void sizes() {
        yes("size:1024", sized(1024));
        no("size:1024", sized(1025));
        yes("size:1kb", sized(1024));
        yes("size:1kb", sized(2047));
        no("size:1kb", sized(2048));
        yes("size:=1kb", sized(1024));
        no("size:=1kb", sized(1025));
        yes("size:==1KB", sized(1024));
        yes("size:>1kb", sized(1025));
        no("size:>1kb", sized(1024));
        yes("size:>=1kb", sized(1024));
        yes("size:<1kb", sized(1023));
        yes("size:<=1kb", sized(1024));
        no("size:!=1kb", sized(1024));
        yes("size:!=1kb", sized(1025));
        no("size:!1kb", sized(2047));
        yes("size:!1kb", sized(2048));
        yes("size:1kb..2kb", sized(2048));
        no("size:1kb..2kb", sized(2049));
        yes("size:1kb-2kb", sized(1024));
        yes("size:>=1.5mb", sized(1572864));
        no("size:>=1.5mb", sized(1572863));
        yes("size:1kib", sized(1024));
        yes("size:0", sized(0));
        no("size:!=0", sized(-1));
        no("size:>0", new FileEntry("/folder", true, 4096, 0));
        yes("<size:>=1kb size:<2kb>|zero", sized(1500));
    }

    private static void dates() {
        yes("dm:2024-02-29", dated("2024-02-29", 12));
        no("dm:2024-02-29", dated("2024-03-01", 0));
        yes("dm:2024-02", dated("2024-02-29", 23));
        no("dm:2024-02", dated("2024-03-01", 0));
        yes("dm:2024", dated("2024-12-31", 23));
        no("dm:2024", dated("2025-01-01", 0));
        yes("dm:2024-02-28..2024-02-29", dated("2024-02-29", 23));
        no("dm:2024-02-28..2024-02-29", dated("2024-03-01", 0));
        no("dm:>2024-02-29", dated("2024-02-29", 23));
        yes("dm:>2024-02-29", dated("2024-03-01", 0));
        yes("dm:>=2024-02-29", dated("2024-02-29", 0));
        yes("dm:<2024-03-01", dated("2024-02-29", 23));
        yes("dm:<=2024-02-29", dated("2024-02-29", 23));
        no("dm:!=2024-02-29", dated("2024-02-29", 12));
        yes("dm:!=2024-02-29", dated("2024-03-01", 0));
        no("dm:!=2024-02-29", new FileEntry("/unknown.txt", false, 0, -1));
        yes("dm:today", dated(LocalDate.now().toString(), 12));
        no("dm:today", dated(LocalDate.now().minusDays(1).toString(), 12));
        yes("dm:yesterday", dated(LocalDate.now().minusDays(1).toString(), 12));
        // The DST spring-forward day has 23 hours. Calendar bounds must handle it.
        long dayStart = LocalDate.parse("2024-03-10").atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        yes("dm:2024-03-10", new FileEntry("/late.txt", false, 0, dayStart + 23 * 3600000L - 1));
        no("dm:2024-03-10", new FileEntry("/next.txt", false, 0, dayStart + 23 * 3600000L));
        yes("date-modified:2024", dated("2024-12-01", 12));
        yes("datemodified:2024", dated("2024-12-01", 12));
    }

    private static void errors() {
        bad("content:hello", "Unsupported search function");
        bad("sha256:hello", "Unsupported search function");
        bad("child:*.txt", "relationship index");
        bad("audio:", "Unsupported search function");
        bad("\"unclosed", "closing double quote");
        bad("<hello", "closing '>'");
        bad("<>", "group needs");
        bad("hello>", "Unexpected");
        bad("|hello", "Expected a search term");
        bad("hello|", "OR needs");
        bad("hello||world", "OR needs");
        bad("!", "NOT must");
        bad("! hello", "NOT must");
        bad("case:", "needs a term");
        bad("case: hello", "needs a term");
        bad("size:", "needs a value");
        bad("size:-1", "Invalid size");
        bad("size:abc", "Invalid size");
        bad("size:1pb", "Unsupported size unit");
        bad("size:999999999999999999999999tb", "too large");
        bad("size:2kb..1kb", "starts after");
        bad("size:>1kb..2kb", "cannot also");
        bad("dm:2023-02-29", "Invalid or unsupported date");
        bad("dm:2024-13", "Invalid or unsupported date");
        bad("dm:tomorrow", "Invalid or unsupported date");
        bad("dm:2024-03..2024-02", "starts after");
        bad("dm:>2024..2025", "cannot also");
        bad("parent:relative", "absolute Android folder");
        bad("ext:jpg;", "needs extensions");
        bad("regex:[", "Invalid regular expression");
        bad("regex:\"(foo)\\1\"", "backreferences and lookaround are unsupported");
        bad("regex:\"(?=foo)foo\"", "backreferences and lookaround are unsupported");
        bad("regex:\"(?<=foo)bar\"", "backreferences and lookaround are unsupported");
        bad("regex:\"(a{1000}){1000}\"", "too complex");
        bad("&#x110000:", "Invalid Unicode");
        bad("&#xD800:", "Invalid Unicode");
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 66; i++) deep.append('<');
        deep.append('a');
        for (int i = 0; i < 66; i++) deep.append('>');
        bad(deep.toString(), "nested too deeply");
    }

    private static void immutableCompile() {
        SearchQuery.Options flags = new SearchQuery.Options();
        SearchQuery query = SearchQuery.compile("FILE", flags);
        flags.matchCase = true;
        flags.matchPath = true;
        check(query.matches(file("/file.txt")), "compiled options snapshot");
        SearchQuery empty = SearchQuery.compile(null, null);
        check(empty.matches(file("/anything.txt")), "null text");
    }

    private static void boundedPatterns() {
        bad(repeat('a', 8193), "limited to 8192");
        no("regex:\"^(a+)+$\"", file("/" + repeat('a', 512) + "!"));
        yes("regex:\"^\\Q(a){1000}\\E$\"", file("/(a){1000}"));
        yes("regex:\"^\\x{10400}+$\"", file("/𐐀𐐀"));
        yes("regex:\"^[[:alpha:]()]+$\"", file("/(abc)"));
        yes("regex:\"^a{2,4}b$\"", file("/aaab"));
        no("regex:\"^a{2,4}b$\"", file("/aaaaab"));
        String deep = repeat('(', 65) + "a" + repeat(')', 65);
        bad("regex:\"" + deep + "\"", "too complex");
        StringBuilder groups = new StringBuilder(), program = new StringBuilder();
        for (int i = 0; i < 129; i++) groups.append("(a)");
        bad("regex:\"" + groups + "\"", "too complex");
        for (int i = 0; i < 33; i++) program.append("regex:a{1000} ");
        bad(program.toString(), "too complex");

        FileEntry input = file("/" + repeat('a', 4000));
        SearchQuery one = SearchQuery.compile("regex:a{16}b", null);
        check(!one.matches(input), "single bounded pattern finishes");
        StringBuilder combined = new StringBuilder();
        for (int i = 0; i < 128; i++) {
            if (i > 0) combined.append('|');
            combined.append("regex:a{16}b");
        }
        SearchQuery shared = SearchQuery.compile(combined.toString(), null);
        limited(shared, input, "OR terms share a per-record work budget");
        check(shared.matches(file("/" + repeat('a', 16) + "b")), "work budget resets after a limited record");
        SearchQuery wildcard = SearchQuery.compile("*" + repeat('a', 600) + "b", null);
        limited(wildcard, file("/" + repeat('a', 20000)), "wildcard retries are bounded");
        check(wildcard.matches(file("/" + repeat('a', 600) + "b")), "wildcard budget resets");

        Thread.currentThread().interrupt();
        try {
            try { one.matches(input); throw new AssertionError("accepted interrupted search"); }
            catch (CancellationException expected) { check(Thread.currentThread().isInterrupted(), "interruption flag preserved"); }
        } finally { Thread.interrupted(); }
        check(!one.matches(file("/ordinary.txt")), "worker can be reused after cancellation");

        // A source read raises interruption after matching starts. This requires no
        // scheduler or wall-clock timing and exercises the actual pinned engine.
        PatternBudget budget = new PatternBudget();
        final int[] reads = {0};
        CharSequence interruptsDuringRead = new CharSequence() {
            @Override public int length() { return 4096; }
            @Override public char charAt(int at) {
                if (++reads[0] == 32) Thread.currentThread().interrupt();
                return 'a';
            }
            @Override public CharSequence subSequence(int from, int to) { throw new AssertionError("copied input"); }
            @Override public String toString() { throw new AssertionError("stringified input"); }
        };
        for (String expression : new String[]{"(a+)+b", "aaaaaaaaab"}) {
            Pattern pattern = Pattern.compile(expression);
            reads[0] = 0;
            budget.reset();
            try {
                pattern.matcher(new PatternBudget.Input(interruptsDuringRead, budget, pattern.programSize())).find();
                throw new AssertionError("ignored interruption during RE2 matching");
            } catch (CancellationException expected) {
                check(reads[0] >= 32 && reads[0] < 128, "NFA and prefix scans observe cancellation during guarded reads");
                check(Thread.currentThread().isInterrupted(), "in-match cancellation preserves interrupt flag");
            } finally { Thread.interrupted(); }
        }
    }

    private static String repeat(char value, int count) {
        char[] text = new char[count];
        java.util.Arrays.fill(text, value);
        return new String(text);
    }

    private static void limited(SearchQuery query, FileEntry input, String message) {
        try {
            query.matches(input);
            throw new AssertionError(message);
        } catch (QueryException expected) {
            check(expected.getMessage().contains("Pattern matching limit reached"), message + ": " + expected.getMessage());
        }
    }

    private static FileEntry file(String path) { return new FileEntry(path, false, 100, 0); }
    private static FileEntry folder(String path) { return new FileEntry(path, true, -1, 0); }
    private static FileEntry sized(long size) { return new FileEntry("/item.bin", false, size, 0); }
    private static FileEntry dated(String day, int hour) {
        long modified = LocalDate.parse(day).atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return new FileEntry("/item.txt", false, 0, modified);
    }
    private static void yes(String text, FileEntry entry) { yes(text, null, entry); }
    private static void yes(String text, SearchQuery.Options flags, FileEntry entry) {
        check(SearchQuery.compile(text, flags).matches(entry), "should match: " + text);
    }
    private static void no(String text, FileEntry entry) { no(text, null, entry); }
    private static void no(String text, SearchQuery.Options flags, FileEntry entry) {
        check(!SearchQuery.compile(text, flags).matches(entry), "should not match: " + text);
    }
    private static void bad(String text, String message) {
        try {
            SearchQuery.compile(text, null);
            throw new AssertionError("Accepted invalid query: " + text);
        } catch (QueryException error) {
            check(error.getMessage().contains(message), "error for " + text + ": " + error.getMessage());
        }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
