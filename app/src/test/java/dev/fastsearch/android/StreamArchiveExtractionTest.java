package dev.fastsearch.android;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;

public final class StreamArchiveExtractionTest {
    private static int assertions;
    private static final byte[] CONTENT = {8, 14, 26};
    private static final byte[] EMPTY = new byte[0];

    public static void main(String[] args) throws IOException {
        Path root = Files.createTempDirectory("stream-archive-test-");
        try {
            check(StreamArchiveExtraction.format("Something.TAR.GZ") == StreamArchiveExtraction.Format.TAR_GZIP,
                    "compound and case-insensitive suffix");
            check(StreamArchiveExtraction.format("classic.tgz") == StreamArchiveExtraction.Format.TGZ,
                    "TGZ alias");
            check(StreamArchiveExtraction.format("something.tar.xz") == null
                    && StreamArchiveExtraction.format("locked.7z") == null
                    && StreamArchiveExtraction.format("locked.rar") == null, "do not offer unsupported codecs");
            byte[] tar = tar(record("./", '5', EMPTY), record("./Docs/", '5', EMPTY),
                    record("Docs/雪.txt", '0', CONTENT), record("Empty/", '5', EMPTY));
            Path target = root.resolve("out");
            for (String name : new String[]{"notes.tar", "notes.tar.gz", "notes.tgz"}) {
                Path source = root.resolve(name);
                StreamArchiveExtraction.Format f = StreamArchiveExtraction.format(name);
                Files.write(source, f.gzip ? gzip(tar) : tar);
                try (FileChannel channel = FileChannel.open(source)) { StreamArchiveExtraction.inspect(channel, f); }
                check(!Files.exists(target), "inspect has no output");
                Files.createDirectory(target);
                pull(source, target);
                check(Arrays.equals(Files.readAllBytes(target.resolve("Docs/雪.txt")), CONTENT),"exact bytes for " + name);
                check(Files.isDirectory(target.resolve("Empty")), "empty directories preserved");
                Files.write(target.resolve("Docs/雪.txt"), new byte[]{99});
                fail(source, target, true);
                check(Arrays.equals(Files.readAllBytes(target.resolve("Docs/雪.txt")), new byte[]{99}),
                        "existing file not overwritten");
                ArchiveExtraction.removeStaging(target);
            }

            Files.createDirectory(target);
            Path standalone = root.resolve("sample.txt.gz");
            Files.write(standalone, concat(gzip(CONTENT), gzip(new byte[]{42})));
            pull(standalone, target);
            check(Arrays.equals(Files.readAllBytes(target.resolve("sample.txt")), new byte[]{8, 14, 26, 42}),
                    "single named output includes concatenated members");
            check(StreamArchiveExtraction.decompressedName("...gz").equals("Uncompressed file"), "unsafe filename is replaced");
            ArchiveExtraction.removeStaging(target);

            Path source = root.resolve("bad.tar");
            for (String name : new String[]{"../outside.txt", "/absolute.txt", "p/../child", "p//child",
                    "p\\child", "c:child", "p/./child", "RLO\u202E.txt"}) {
                Files.write(source, tar(record("acceptable.txt", '0', CONTENT), record(name, '0', CONTENT)));
                Files.createDirectory(target);
                fail(source, target, false);
                check(!Files.exists(root.resolve("outside.txt")), "no traversal for " + name);
            }
            Files.write(source, tar(record("duplicate", '0', CONTENT), record("./duplicate", '0', CONTENT)));
            Files.createDirectory(target);
            fail(source, target, false);
            Files.write(source, tar(record("conflict/descendant", '0', CONTENT), record("conflict", '0', CONTENT)));
            Files.createDirectory(target);
            fail(source, target, false);
            for (char kind : new char[]{'1', '2', '3', '4', '6', 'S', 'g', 'K'}) {
                Files.write(source, tar(record("special", kind, EMPTY)));
                Files.createDirectory(target);
                fail(source, target, false);
            }
            Files.write(source, tar(record("pax", 'x', paxLine("path", "../../outside.txt")),
                    record("safe", '0', CONTENT)));
            Files.createDirectory(target);
            fail(source, target, false);
            Files.write(source, tar(record("pax", 'x', paxLine("GNU.sparse.name", "safe")),
                    record("safe", '0', CONTENT)));
            Files.createDirectory(target);
            fail(source, target, false);
            StringBuilder prefix = new StringBuilder();
            for (int i = 0; i < 115; i++) prefix.append('a');
            String longName = prefix + "/file.txt";
            Files.write(source, tar(record("pax", 'x', paxLine("path", longName)), record("short", '0', CONTENT)));
            Files.createDirectory(target);
            pull(source, target);
            check(Files.isRegularFile(target.resolve(longName)), "PAX UTF-8 path override supported");
            ArchiveExtraction.removeStaging(target);
            Files.write(source, tar(record("././@LongLink", 'L', (longName + "\0").getBytes(StandardCharsets.UTF_8)),
                    record("short", '0', CONTENT)));
            Files.createDirectory(target);
            pull(source, target);
            check(Files.isRegularFile(target.resolve(longName)), "GNU long name metadata supported");
            ArchiveExtraction.removeStaging(target);

            byte[] corrupted = tar.clone();
            corrupted[0] ^= 1;
            Files.write(source, corrupted);
            Files.createDirectory(target);
            fail(source, target, false);
            Files.write(source, Arrays.copyOf(tar, 516));
            Files.createDirectory(target);
            fail(source, target, false);
            Files.write(source, concat(tar, new byte[]{1}));
            Files.createDirectory(target);
            fail(source, target, false);

            Path badGzip = root.resolve("bad.tgz");
            corrupted = gzip(tar);
            corrupted[corrupted.length - 8] ^= 1;
            Files.write(badGzip, corrupted);
            Files.createDirectory(target);
            fail(badGzip, target, false);
            Files.write(standalone, Arrays.copyOf(corrupted, corrupted.length - 4));
            Files.createDirectory(target);
            fail(standalone, target, false);

            Files.write(source, tar(record("pax", 'x', paxLine("size", "1073741825")), record("huge", '0', CONTENT)));
            Files.createDirectory(target);
            fail(source, target, false);
            ByteArrayOutputStream many = new ByteArrayOutputStream();
            for (int i = 0; i <= StreamArchiveExtraction.MAX_RECORDS; i++) many.write(record("file" + i, '0', EMPTY));
            Files.write(source, tar(many.toByteArray()));
            Files.createDirectory(target);
            fail(source, target, false);
            check(!Files.exists(root.resolve("outside.txt")), "nothing appears outside private staging");
        } finally { ArchiveExtraction.removeStaging(root); }
        System.out.println("PASS " + assertions + " TAR/GZIP extraction checks.");
    }

    private static byte[] paxLine(String key, String value) {
        String record = " " + key + "=" + value + "\n";
        int bytes = record.getBytes(StandardCharsets.UTF_8).length;
        int size = bytes + 1;
        while (size != bytes + Integer.toString(size).length()) size = bytes + Integer.toString(size).length();
        return (size + record).getBytes(StandardCharsets.UTF_8);
    }

    private static void pull(Path source, Path target) throws IOException {
        String name = source.getFileName().toString();
        try (FileChannel input = FileChannel.open(source, StandardOpenOption.READ)) {
            StreamArchiveExtraction.extract(input, name, StreamArchiveExtraction.format(name), target);
        }
    }

    private static void fail(Path source, Path target, boolean retain) throws IOException {
        boolean refused = false;
        try { pull(source, target); } catch (IOException expected) { refused = true; }
        check(refused, "unsafe, corrupt or colliding archive was refused: " + source.getFileName());
        if (!retain) {
            ArchiveExtraction.removeStaging(target);
            check(!Files.exists(target), "failed staging is removed");
        }
    }

    private static byte[] record(String name, char kind, byte[] contents) throws IOException {
        byte[] header = new byte[512];
        write(header, 0, name);
        write(header, 100, "0000644");
        write(header, 124, String.format(Locale.ROOT, "%011o", contents.length));
        Arrays.fill(header, 148, 156, (byte) ' ');
        header[156] = (byte) kind;
        write(header, 257, "ustar\00000");
        int checksum = 0;
        for (byte b : header) checksum += b & 255;
        write(header, 148, String.format(Locale.ROOT, "%06o\0 ", checksum));
        return concat(header, contents, new byte[(512 - contents.length % 512) % 512]);
    }

    private static void write(byte[] target, int offset, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(bytes, 0, target, offset, bytes.length);
    }

    private static byte[] tar(byte[]... records) throws IOException {
        return concat(concat(records), new byte[1024]);
    }

    private static byte[] gzip(byte[] bytes) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        try (OutputStream destination = new GZIPOutputStream(result)) { destination.write(bytes); }
        return result.toByteArray();
    }

    private static byte[] concat(byte[]... arrays) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        for (byte[] array : arrays) result.write(array);
        return result.toByteArray();
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
