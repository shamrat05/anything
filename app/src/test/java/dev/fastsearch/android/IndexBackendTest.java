package dev.fastsearch.android;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

public final class IndexBackendTest {
    private static int assertions;
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    private static String q(String value) { return IndexRootScanner.quote(value); }
    private static int root(String command) throws Exception {
        Process child = new ProcessBuilder(IndexRootScanner.suBinary(), "-c", command).inheritIO().start();
        return child.waitFor();
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Pass the built librootindex.so path.");
        File cache = new File("/data/data/com.termux/files/usr/tmp/IndexBackendCache");
        cache.mkdirs();
        File staging = Files.createTempDirectory("IndexBackend-stage-").toFile();
        String fixture = "/data/local/tmp/IndexBackend-fixture-" + System.nanoTime();
        try {
            String[] names = {"plain.txt", "with space.txt", "apostrophe'quote.txt", "tab\there.txt",
                    "line\nbreak.txt", "日本語-é.txt", "literal%_[]*.txt", "$(touch BAD).txt"};
            for (String name : names) Files.write(new File(staging, name).toPath(), new byte[]{1,2,3,4,5});
            File nested = new File(staging, "nested directory");
            check(nested.mkdir(), "create nested directory");
            Files.write(new File(nested, "nested.txt").toPath(), new byte[]{8,9});
            File hidden = new File(staging, "root-private");
            check(hidden.mkdir(), "create private directory");
            Files.write(new File(hidden, "root-only.txt").toPath(), new byte[]{4,3,2,1});
            String privateDirectory = fixture + "/root-private";
            check(root("/system/bin/cp -a " + q(staging.getAbsolutePath()) + " " + q(fixture)
                    + " && chown 0:0 " + q(privateDirectory) + " && chmod 0700 " + q(privateDirectory)
                    + " && ln -s " + q(fixture) + " " + q(fixture + "/loop")
                    + " && /system/bin/mkfifo " + q(fixture + "/pipe")) == 0, "special fixtures");
            IndexRootScanner scanner = new IndexRootScanner(cache, args[0]);
            Map<String,long[]> actual = new HashMap<>();
            IndexRootScanner.Result result = scanner.scan((path, directory, size, modified) ->
                    actual.put(path, new long[]{directory ? 1 : 0, size, modified}), fixture);
            check(actual.size() == 13, "expected 13 regular files/directories, got " + actual.size());
            for (String name : names) {
                long[] entry = actual.get(fixture + "/" + name);
                check(entry != null && entry[0] == 0 && entry[1] == 5 && entry[2] > 0, "metadata: " + name);
            }
            check(actual.containsKey(privateDirectory + "/root-only.txt"), "root-only metadata indexed");
            check(!actual.containsKey(fixture + "/loop"), "symlink excluded");
            check(!actual.containsKey(fixture + "/pipe"), "FIFO excluded without blocking");
            check(root("test ! -e " + q(fixture + "/BAD")) == 0, "filename never executes shell substitution");
            check(result.accessErrors == 0, "fixture has no access errors");
            check(cache.list().length == 0, "normal completion removed marker and PID files");

            // Enough metadata to exceed pipe buffers; cancel while the scanner is actually alive.
            File many = new File(staging, "cancellation");
            check(many.mkdir(), "create cancellation fixture");
            String suffix = "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz";
            for (int i = 0; i < 5000; i++) new File(many, i + suffix).createNewFile();
            check(root("/system/bin/cp -a " + q(many.getAbsolutePath()) + " " + q(fixture + "/cancellation")) == 0, "copy cancellation fixture");
            IndexRootScanner cancel = new IndexRootScanner(cache, args[0]);
            Field processField = IndexRootScanner.class.getDeclaredField("process");
            Field pidField = IndexRootScanner.class.getDeclaredField("rootPid");
            processField.setAccessible(true);
            pidField.setAccessible(true);
            Process[] captured = new Process[1];
            long[] pid = new long[1];
            boolean[] live = new boolean[1];
            boolean cancelled = false;
            long started = System.nanoTime();
            try {
                cancel.scan((path, directory, size, modified) -> {
                    try {
                        captured[0] = (Process) processField.get(cancel);
                        pid[0] = pidField.getLong(cancel);
                        live[0] = captured[0].isAlive();
                    } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
                    cancel.cancel();
                }, fixture + "/cancellation");
            } catch (CancellationException expected) { cancelled = true; }
            check(cancelled, "cancel raises cancellation");
            check(live[0], "cancel test interrupted a live scanner process");
            check(captured[0] != null && !captured[0].isAlive(), "su client reaped");
            check(pid[0] > 1 && root("kill -0 " + pid[0] + " 2>/dev/null") != 0, "privileged scanner terminated");
            check((System.nanoTime() - started) / 1_000_000 < 8000, "cancellation completed within 8 seconds");
            check(cache.list().length == 0, "cancel removed coordination files");

            String mounts = "1 0 1:1 / / rw - ext4 x rw\n"
                    + "2 1 2:2 / /apex/item@2 rw - erofs x ro\n"
                    + "3 1 2:2 / /apex/item rw - erofs x ro\n"
                    + "4 1 3:3 / /storage/alias rw - fuse x rw\n"
                    + "5 1 4:4 / /mnt/media_rw/disk rw - vfat x rw\n";
            List<String> exclusions = IndexRootScanner.exclusions(mounts);
            check(exclusions.contains("/apex/item@2") && !exclusions.contains("/apex/item"), "APEX alias selection");
            check(!exclusions.contains("/mnt/media_rw/disk"), "physical external volume retained");
            check(exclusions.contains("/proc") && exclusions.contains("/storage"), "virtual/storage aliases skipped");
            check(IndexRootScanner.unescapeMount("/a\\040b\\134c").equals("/a b\\c"), "mount escapes decoded");
            List<String> glob = new ArrayList<>(); glob.add("/a[b]*?\\c'");
            String prune = IndexRootScanner.pruneExpression(glob);
            check(prune.contains("\\[") && prune.contains("\\*") && prune.contains("'\\''"), "find glob and shell quoting");
            ByteArrayInputStream stream = new ByteArrayInputStream("one\0two\nthree\0".getBytes(StandardCharsets.UTF_8));
            check(IndexRootScanner.readField(stream, false).equals("one"), "NUL framing");
            check(IndexRootScanner.readField(stream, false).equals("two\nthree"), "embedded newline preserved");
            check(IndexRootScanner.readField(stream, true) == null, "clean EOF");
            boolean truncated = false;
            try { IndexRootScanner.readField(new ByteArrayInputStream(new byte[]{1}), false); }
            catch (IOException expected) { truncated = true; }
            check(truncated, "truncated records rejected");
            System.out.println("PASS " + assertions + " assertions; bounded root metadata, special names, symlink/FIFO exclusion, live cancellation, PID cleanup, aliases and framing.");
        } finally {
            // Only the uniquely created fixture is removed.
            root("rm -rf " + q(fixture));
            try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(staging.toPath())) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.delete(path); } catch (IOException failure) { throw new RuntimeException(failure); }
                });
            }
        }
    }
}
