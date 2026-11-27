package dev.fastsearch.android;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

public final class IndexProtocolTest {
    static int assertions;
    static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        String mounts = "1 0 1:1 / / rw - ext4 x rw\n"
                + "2 1 2:2 / /apex/item@2 rw - erofs x ro\n"
                + "3 1 2:2 / /apex/item rw - erofs x ro\n"
                + "4 1 3:3 / /storage/alias rw - fuse x rw\n"
                + "5 1 4:4 / /mnt/media_rw/disk rw - vfat x rw\n";
        check(IndexRootScanner.liveWatchScope("/data/local/tmp/owned folder"),"ordinary scoped root directory accepted");
        for(String scope:new String[]{null,"/","/sys","/proc/1","/storage/emulated/0","/data/../tmp","/data//tmp","/data/tmp/","relative","/data/bad\0name"})
            check(!IndexRootScanner.liveWatchScope(scope),"reject malformed or reserved live scope");
        List<String> exclusions = IndexRootScanner.exclusions(mounts);
        check(exclusions.contains("/apex/item@2") && !exclusions.contains("/apex/item"), "APEX alias selection");
        check(!exclusions.contains("/mnt/media_rw/disk"), "physical external volume retained");
        check(exclusions.contains("/proc") && exclusions.contains("/storage"), "virtual/storage aliases skipped");
        check(!exclusions.contains("/data/user/0"), "owner data path retained without identity evidence");
        List<String> ownerAlias = IndexRootScanner.exclusions("owner-data-bind-alias\n" + mounts);
        check(ownerAlias.contains("/data/user/0") && !ownerAlias.contains("/data/data")
                && !ownerAlias.contains("/data/user/10"), "verified owner bind alias pruned, canonical path and other users retained");
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
        byte[] literal = "/folder/日本語-é\nname".getBytes(StandardCharsets.UTF_8);
        byte[] encoded = ByteBuffer.allocate(21 + literal.length).order(ByteOrder.LITTLE_ENDIAN)
                .put((byte) '-').putLong(5_932_797_660L).putLong(-2_184_345L)
                .putInt(literal.length).put(literal).array();
        ByteArrayInputStream fragmented = new ByteArrayInputStream(encoded) {
            @Override public synchronized int read(byte[] target, int offset, int count) {
                return super.read(target, offset, Math.min(count, 3));
            }
        };
        IndexRootScanner.PackedReader packed = new IndexRootScanner.PackedReader(fragmented);
        check(new String(literal, StandardCharsets.UTF_8).equals(packed.next()), "packed framing across pipe reads");
        check(!packed.directory && packed.size == 5_932_797_660L && packed.modified == -2_184_345L,
                "exact signed 64-bit metadata");
        check(packed.next() == null, "packed clean EOF");
        for (int length : new int[]{1, 20, 21, encoded.length - 1}) {
            rejectPacked(java.util.Arrays.copyOf(encoded, length), "partial packed record is rejected");
        }
        byte[] malformed = encoded.clone();
        ByteBuffer.wrap(malformed).order(ByteOrder.LITTLE_ENDIAN).putInt(17, 4096);
        rejectPacked(malformed, "oversized packed path rejected");
        malformed = encoded.clone();
        malformed[0] = 'l';
        rejectPacked(malformed, "unknown packed entry type rejected");
        malformed = encoded.clone();
        malformed[22] = 0;
        rejectPacked(malformed, "embedded NUL rejected");
        File cache = new File(System.getProperty("java.io.tmpdir"), "IndexProtocolCache");
        cache.mkdirs();
        IndexRootScanner cancelled = new IndexRootScanner(cache, "/not-installed/librootindex.so");
        cancelled.cancel();
        boolean stopped = false;
        try { cancelled.scan((p,d,s,m) -> { throw new AssertionError("entry after cancellation"); }, "/data/local/tmp/IndexBackend-does-not-exist"); }
        catch (CancellationException expected) { stopped = true; }
        check(stopped, "cancel before process launch");
        check(cache.list().length == 0, "cancel removes marker/PID files");
        IndexRootScanner denied = new IndexRootScanner(cache, "/not-installed/librootindex.so", "/not-installed/su");
        boolean actionable = false;
        try { denied.scan((p,d,s,m) -> { throw new AssertionError("unexpected entry"); }, "/data/local/tmp/IndexBackend-does-not-exist"); }
        catch (IOException expected) {
            actionable = expected.getMessage().contains("root manager") && expected.getMessage().contains("shared storage only");
        }
        check(actionable, "root failure offers root-manager/shared-only choices");
        check(cache.list().length == 0, "failed su removes marker/PID files");
        String shell = new File("/system/bin/sh").exists() ? "/system/bin/sh" : "/bin/sh";
        IndexRootScanner granted = new IndexRootScanner(cache, "/not-installed/librootindex.so", shell);
        boolean traversalFailure = false;
        try { granted.scan((p,d,s,m) -> {}, "/not-installed/fixture"); }
        catch (IOException expected) {
            traversalFailure = expected.getMessage().startsWith("Root indexing failed.")
                    && !expected.getMessage().contains("root manager");
        }
        check(traversalFailure, "successful session followed by command failure does not claim denied root");
        check(cache.list().length == 0, "granted but failed scanner removes markers");
        System.out.println("PASS " + assertions + " protocol assertions: framing, aliases, pre-launch cancellation and failure cleanup.");
    }
    private static void rejectPacked(byte[] bytes, String reason) throws Exception {
        boolean rejected = false;
        try { new IndexRootScanner.PackedReader(new ByteArrayInputStream(bytes)).next(); }
        catch (IOException expected) { rejected = true; }
        check(rejected, reason);
    }

}
