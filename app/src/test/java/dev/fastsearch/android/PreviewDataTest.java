package dev.fastsearch.android;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Bounded format fixtures; no device UI or user files are accessed. */
public final class PreviewDataTest {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        text(); archives();
        System.out.println("Preview data: " + assertions + " assertions passed.");
    }
    private static void text() throws Exception {
        PreviewText.Result plain = text("<script>alert('literal text')</script>\n<&>");
        check(plain.text.equals("<script>alert('literal text')</script>\n<&>"), "HTML and scripts remain literal text");
        check(!plain.truncated && plain.encoding.equals("UTF-8"), "small UTF-8 input is complete");
        check(text("").text.isEmpty(), "empty text");
        check(text("\uFEFFhello").text.equals("hello"), "UTF-8 BOM removed");
        byte[] utf16 = "snow 雪\n".getBytes(StandardCharsets.UTF_16LE);
        byte[] bom = new byte[utf16.length + 2]; bom[0] = (byte) 255; bom[1] = (byte) 254; System.arraycopy(utf16, 0, bom, 2, utf16.length);
        PreviewText.Result wide = PreviewText.read(new ByteArrayInputStream(bom));
        check(wide.text.equals("snow 雪\n") && wide.encoding.equals("UTF-16LE"), "UTF-16 BOM encoding");
        byte[] big = new byte[PreviewText.MAX_BYTES + 40000]; Arrays.fill(big, (byte) 'a');
        PreviewText.Result bounded = PreviewText.read(new ByteArrayInputStream(big));
        check(bounded.truncated && bounded.text.length() <= PreviewText.MAX_CHARACTERS, "byte and character limits");
        CountingInput count = new CountingInput(big);
        PreviewText.read(count);
        check(count.read == PreviewText.MAX_BYTES + 1, "read at most byte limit plus one");
        StringBuilder lines = new StringBuilder(); for (int i = 0; i < 2100; i++) lines.append("line\n");
        check(text(lines.toString()).truncated && text(lines.toString()).text.length() <= 10000, "line limit stops layout growth");
        int lineCount = 1; for (char ch : text(lines.toString()).text.toCharArray()) if (ch == '\n') lineCount++;
        check(lineCount == PreviewText.MAX_LINES, "exact rendered line limit");
        expectIo(() -> PreviewText.read(new ByteArrayInputStream(new byte[]{'a', 0, 'b'})), "binary null rejected");
        byte[] controls = new byte[512]; Arrays.fill(controls, (byte) 1);
        expectIo(() -> PreviewText.read(new ByteArrayInputStream(controls)), "binary controls rejected");
        Thread.currentThread().interrupt();
        try { expectIo(() -> text("cancel"), "text interruption"); } finally { Thread.interrupted(); }
    }
    private static PreviewText.Result text(String value) throws IOException {
        return PreviewText.read(new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)));
    }
    private static void archives() throws Exception {
        byte[] empty = zip(0);
        check(list(empty).entries.isEmpty() && !list(empty).truncated, "empty ZIP");
        byte[] ordinary;
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ZipOutputStream out = new ZipOutputStream(bytes)) {
            out.putNextEntry(new ZipEntry("folder/")); out.closeEntry();
            out.putNextEntry(new ZipEntry("folder/snow 雪.txt")); out.write(new byte[1000000]); out.closeEntry();
            out.putNextEntry(new ZipEntry("../literal\nname.txt")); out.write("x".getBytes(StandardCharsets.UTF_8)); out.closeEntry();
            out.finish(); ordinary = bytes.toByteArray();
        }
        ZipDirectory.Listing known = list(ordinary);
        check(known.totalEntries == 3 && known.entries.size() == 3 && !known.truncated, "ZIP entry count");
        check(known.entries.get(0).directory && known.entries.get(1).name.equals("folder/snow 雪.txt"), "directory and UTF-8 names");
        check(known.entries.get(1).size == 1000000 && known.entries.get(1).compressedSize < 10000, "compressed bomb metadata only");
        check(known.entries.get(2).name.equals("../literal\\nname.txt"), "unsafe entry paths are labels only");
        check(readEntry(ordinary, 2).text.equals("x"), "last deflated entry streams without reading preceding files");
        expectIo(() -> readEntry(ordinary, 0), "directories cannot open as data");
        byte[] badPayload = ordinary.clone();
        for (int i = 0; i < 150 && i < badPayload.length; i++) badPayload[i] = (byte) 0xFF;
        check(list(badPayload).entries.size() == 3, "listing never parses local headers or decompresses payloads");
        expectIo(() -> readEntry(badPayload, 1), "invalid local header fails when opened");
        ByteArrayOutputStream storedBytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(storedBytes)) {
            byte[] payload = { 'a', 'b', 'c' };
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(payload);
            ZipEntry stored = new ZipEntry("stored.txt");
            stored.setMethod(ZipEntry.STORED);
            stored.setSize(payload.length);
            stored.setCompressedSize(payload.length);
            stored.setCrc(crc.getValue());
            out.putNextEntry(stored); out.write(payload); out.closeEntry();
            out.putNextEntry(new ZipEntry("neighbor.txt")); out.write(new byte[]{'z'}); out.closeEntry();
        }
        check(readEntry(storedBytes.toByteArray(), 0).text.equals("abc"), "stored entry stops before adjacent local header");
        ZipDirectory.Listing clipped = list(zip(ZipDirectory.MAX_ENTRIES + 5));
        check(clipped.entries.size() == ZipDirectory.MAX_ENTRIES && clipped.totalEntries == ZipDirectory.MAX_ENTRIES + 5 && clipped.truncated, "entry-count cap");
        byte[] longNames = zipWithNames(150, 3000);
        ZipDirectory.Listing labels = list(longNames);
        int characters = 0; for (ZipDirectory.Entry entry : labels.entries) characters += entry.name.length();
        check(labels.truncated && characters <= ZipDirectory.MAX_LABEL_CHARACTERS, "aggregate label-memory cap");
        byte[] oversizedName = zipWithNames(1, 5000);
        check(list(oversizedName).entries.get(0).name.length() == ZipDirectory.MAX_NAME_BYTES + 1, "individual name cap");
        check(ZipDirectory.safeLabel("x\u202Ey\u2066z\t").equals("x\uFFFDy\uFFFDz\\t"), "control and direction labels are visible");
        byte[] largeDirectory = zipWithNames(35, 60000);
        check(list(largeDirectory).truncated, "central-directory byte cap");
        byte[] invalidOffset = ordinary.clone(); int end = invalidOffset.length - 22;
        put32(invalidOffset, end + 16, 0xFFFFFFFFL);
        expectIo(() -> list(invalidOffset), "unbacked ZIP64 offset rejected");
        byte[] split = ordinary.clone(); split[split.length - 22 + 4] = 1;
        expectIo(() -> list(split), "split archive rejected");
        byte[] badCount = ordinary.clone(); put16(badCount, end + 8, 4); put16(badCount, end + 10, 4);
        expectIo(() -> list(badCount), "truncated central directory rejected");
        check(list(zip64()).entries.get(0).size == 0x100000003L, "ZIP64 footer and entry sizes");
        byte[] unsigned = zip64(); unsigned[unsigned.length - 22 - 20 + 15] = (byte) 0x80;
        expectIo(() -> list(unsigned), "unsigned ZIP64 overflow rejected");
        expectIo(() -> list(new byte[22]), "missing ZIP footer rejected");
        Thread.currentThread().interrupt();
        try { expectIo(() -> list(ordinary), "archive interruption"); } finally { Thread.interrupted(); }
    }
    private static byte[] zip(int count) throws IOException { return zipWithNames(count, 12); }
    private static byte[] zipWithNames(int count, int length) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (int i = 0; i < count; i++) {
                char[] name = new char[length]; Arrays.fill(name, 'a'); String prefix = i + "-"; System.arraycopy(prefix.toCharArray(), 0, name, 0, prefix.length());
                out.putNextEntry(new ZipEntry(new String(name))); out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
    private static byte[] zip64() {
        // One central record, ZIP64 entry-size extra, ZIP64 footer/locator and classic sentinel footer.
        byte[] bytes = new byte[46 + 1 + 20 + 56 + 20 + 22];
        put32(bytes, 0, 0x02014B50); put16(bytes, 8, 0x800); put32(bytes, 20, 0xFFFFFFFFL); put32(bytes, 24, 0xFFFFFFFFL);
        put16(bytes, 28, 1); put16(bytes, 30, 20); bytes[46] = 'x'; put16(bytes, 47, 1); put16(bytes, 49, 16);
        put64(bytes, 51, 0x100000003L); put64(bytes, 59, 9);
        int large = 67; put32(bytes, large, 0x06064B50); put64(bytes, large + 4, 44);
        put64(bytes, large + 24, 1); put64(bytes, large + 32, 1); put64(bytes, large + 40, 67); put64(bytes, large + 48, 0);
        int locator = large + 56; put32(bytes, locator, 0x07064B50); put64(bytes, locator + 8, large); put32(bytes, locator + 16, 1);
        int end = locator + 20; put32(bytes, end, 0x06054B50); put16(bytes, end + 8, 65535); put16(bytes, end + 10, 65535);
        put32(bytes, end + 12, 0xFFFFFFFFL); put32(bytes, end + 16, 0xFFFFFFFFL);
        return bytes;
    }
    private static ZipDirectory.Listing list(byte[] bytes) throws IOException {
        File fixture = File.createTempFile("PreviewData-", ".zip");
        try {
            Files.write(fixture.toPath(), bytes);
            try (FileChannel file = FileChannel.open(fixture.toPath(), StandardOpenOption.READ)) { return ZipDirectory.read(file); }
        } finally { if (!fixture.delete()) fixture.deleteOnExit(); }
    }
    private static PreviewText.Result readEntry(byte[] bytes, int index) throws IOException {
        java.nio.file.Path fixture = Files.createTempFile("PreviewData-", ".zip");
        try {
            Files.write(fixture, bytes);
            try (FileChannel file = FileChannel.open(fixture, StandardOpenOption.READ);
                    java.io.InputStream entry = ZipDirectory.open(file, ZipDirectory.read(file).entries.get(index))) {
                return PreviewText.read(entry);
            }
        } finally { Files.delete(fixture); }
    }
    private interface Action { void run() throws Exception; }
    private static void expectIo(Action action, String label) throws Exception {
        try { action.run(); } catch (IOException expected) { check(true, label); return; }
        throw new AssertionError(label);
    }
    private static void check(boolean condition, String label) { assertions++; if (!condition) throw new AssertionError(label); }
    private static void put16(byte[] bytes, int at, int value) { bytes[at] = (byte) value; bytes[at + 1] = (byte) (value >>> 8); }
    private static void put32(byte[] bytes, int at, long value) { put16(bytes, at, (int) value); put16(bytes, at + 2, (int) (value >>> 16)); }
    private static void put64(byte[] bytes, int at, long value) { put32(bytes, at, value); put32(bytes, at + 4, value >>> 32); }
    private static final class CountingInput extends ByteArrayInputStream {
        int read;
        CountingInput(byte[] bytes) { super(bytes); }
        @Override public synchronized int read(byte[] bytes, int offset, int count) { int result = super.read(bytes, offset, count); if (result > 0) read += result; return result; }
    }
}
