package dev.fastsearch.android;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Lists entries and opens bounded streams directly from the archive. */
final class ZipDirectory {
    static final int MAX_ENTRIES = 2000;
    static final int MAX_DIRECTORY_BYTES = 2 * 1024 * 1024;
    static final int MAX_NAME_BYTES = 4096;
    static final int MAX_LABEL_CHARACTERS = 256 * 1024;

    static final class Entry {
        final String name;
        final long size, compressedSize, offset, boundary;
        final int method, flags;
        final boolean directory, encrypted;
        Entry(String name, long size, long compressedSize, boolean directory, int flags, int method,
                long offset, long boundary) {
            this.name = name; this.size = size; this.compressedSize = compressedSize;
            this.directory = directory; this.encrypted = (flags & 1) != 0;
            this.flags = flags; this.method = method; this.offset = offset; this.boundary = boundary;
        }
    }
    static final class Listing {
        final List<Entry> entries;
        final long totalEntries;
        final boolean truncated;
        Listing(List<Entry> entries, long totalEntries, boolean truncated) {
            this.entries = Collections.unmodifiableList(entries);
            this.totalEntries = totalEntries; this.truncated = truncated;
        }
    }

    static Listing read(FileChannel file) throws IOException {
        long fileSize = file.size();
        if (fileSize < 22) throw invalid();
        int tailLength = (int) Math.min(fileSize, 65535L + 22);
        byte[] tail = bytes(file, fileSize - tailLength, tailLength);
        int end = -1;
        for (int i = tail.length - 22; i >= 0; i--) {
            if (u32(tail, i) == 0x06054B50L && i + 22 + u16(tail, i + 20) == tail.length) { end = i; break; }
        }
        if (end < 0 || u16(tail, end + 4) != 0 || u16(tail, end + 6) != 0) throw invalid();
        long endOffset = fileSize - tailLength + end;
        long count = u16(tail, end + 10), directorySize = u32(tail, end + 12), offset = u32(tail, end + 16);
        if (u16(tail, end + 8) != count) throw invalid();
        if (count == 65535 || directorySize == 0xFFFFFFFFL || offset == 0xFFFFFFFFL) {
            if (endOffset < 20) throw invalid();
            byte[] locator = bytes(file, endOffset - 20, 20);
            if (u32(locator, 0) != 0x07064B50L || u32(locator, 4) != 0 || u32(locator, 16) != 1) throw invalid();
            long zip64Offset = u64(locator, 8);
            if (zip64Offset > endOffset - 20 - 56) throw invalid();
            byte[] large = bytes(file, zip64Offset, 56);
            long recordSize = u64(large, 4);
            if (u32(large, 0) != 0x06064B50L || recordSize < 44 || recordSize > endOffset - 20 - zip64Offset - 12
                    || u32(large, 16) != 0 || u32(large, 20) != 0) throw invalid();
            count = u64(large, 32);
            if (u64(large, 24) != count) throw invalid();
            directorySize = u64(large, 40); offset = u64(large, 48);
            endOffset = zip64Offset;
        }
        if (offset > endOffset || directorySize > endOffset - offset) throw invalid();
        if (count == 0) {
            if (directorySize != 0) throw invalid();
            return new Listing(new ArrayList<>(), 0, false);
        }
        long directoryEnd = offset + directorySize, start = offset;
        int labels = 0;
        boolean clipped = false;
        ArrayList<Entry> entries = new ArrayList<>((int) Math.min(count, MAX_ENTRIES));
        while (entries.size() < count && entries.size() < MAX_ENTRIES) {
            PreviewText.cancelled();
            if (directoryEnd - offset < 46) throw invalid();
            if (offset - start + 46 > MAX_DIRECTORY_BYTES) { clipped = true; break; }
            byte[] header = bytes(file, offset, 46);
            if (u32(header, 0) != 0x02014B50L) throw invalid();
            int flags = u16(header, 8), nameLength = u16(header, 28), extraLength = u16(header, 30), commentLength = u16(header, 32);
            int length = 46 + nameLength + extraLength + commentLength;
            if (nameLength == 0 || length > directoryEnd - offset || u16(header, 34) != 0) throw invalid();
            if (offset - start + length > MAX_DIRECTORY_BYTES) { clipped = true; break; }
            int shown = Math.min(nameLength, MAX_NAME_BYTES);
            byte[] name = bytes(file, offset + 46, shown);
            String label = safeLabel(new String(name, (flags & 0x800) != 0 ? StandardCharsets.UTF_8 : legacyCharset()));
            if (shown < nameLength) label += "…";
            if (labels + label.length() > MAX_LABEL_CHARACTERS) { clipped = true; break; }
            labels += label.length();
            long size = u32(header, 24), compressed = u32(header, 20);
            long local = u32(header, 42);
            if (size == 0xFFFFFFFFL || compressed == 0xFFFFFFFFL || local == 0xFFFFFFFFL) {
                byte[] extra = bytes(file, offset + 46 + nameLength, extraLength);
                boolean found = false;
                for (int i = 0; i + 4 <= extra.length;) {
                    int fieldSize = u16(extra, i + 2), next = i + 4 + fieldSize;
                    if (next > extra.length) throw invalid();
                    if (u16(extra, i) == 1) {
                        int field = i + 4;
                        if (size == 0xFFFFFFFFL) { if (field + 8 > next) throw invalid(); size = u64(extra, field); field += 8; }
                        if (compressed == 0xFFFFFFFFL) { if (field + 8 > next) throw invalid(); compressed = u64(extra, field); field += 8; }
                        if (local == 0xFFFFFFFFL) { if (field + 8 > next) throw invalid(); local = u64(extra, field); }
                        found = true; break;
                    }
                    i = next;
                }
                if (!found) throw invalid();
            }
            boolean directory = name[name.length - 1] == '/' && shown == nameLength;
            entries.add(new Entry(label, size, compressed, directory, flags, u16(header, 10), local, start));
            offset += length;
        }
        return new Listing(entries, count, clipped || entries.size() < count);
    }

    static InputStream open(FileChannel file, Entry entry) throws IOException {
        if (entry.directory || entry.encrypted) throw new IOException("This entry cannot be previewed.");
        if (entry.method != 0 && entry.method != 8)
            throw new IOException("This entry uses an unsupported ZIP compression method.");
        if (entry.offset > entry.boundary - 30) throw invalid();
        byte[] header = bytes(file, entry.offset, 30);
        if (u32(header, 0) != 0x04034B50L || u16(header, 6) != entry.flags || u16(header, 8) != entry.method)
            throw invalid();
        long position = entry.offset + 30 + u16(header, 26) + u16(header, 28);
        if (position > entry.boundary || entry.compressedSize > entry.boundary - position) throw invalid();
        InputStream compressed = new Slice(file, position, entry.compressedSize);
        if (entry.method == 0) {
            if (entry.compressedSize != entry.size) throw invalid();
            return compressed;
        }
        Inflater inflater = new Inflater(true);
        return new InflaterInputStream(compressed, inflater, 8192) {
            @Override public void close() throws IOException {
                try { super.close(); } finally { inflater.end(); }
            }
        };
    }

    private static final class Slice extends InputStream {
        private final FileChannel file;
        private long position, remaining;
        private final byte[] single = new byte[1];

        Slice(FileChannel file, long position, long length) {
            this.file = file;
            this.position = position;
            remaining = length;
        }

        @Override public int read() throws IOException {
            return read(single, 0, 1) < 0 ? -1 : single[0] & 255;
        }

        @Override public int read(byte[] data, int offset, int length) throws IOException {
            if (offset < 0 || length < 0 || offset > data.length - length)
                throw new IndexOutOfBoundsException();
            if (length == 0) return 0;
            if (remaining == 0) return -1;
            PreviewText.cancelled();
            ByteBuffer buffer = ByteBuffer.wrap(data, offset, (int) Math.min(remaining, length));
            int count;
            do {
                PreviewText.cancelled();
                count = file.read(buffer, position);
            } while (count == 0);
            if (count < 0) throw invalid();
            remaining -= count;
            position += count;
            return count;
        }
    }

    private static byte[] bytes(FileChannel file, long position, int count) throws IOException {
        PreviewText.cancelled();
        if (position < 0 || count < 0 || position > file.size() || count > file.size() - position) throw invalid();
        byte[] result = new byte[count];
        ByteBuffer buffer = ByteBuffer.wrap(result);
        while (buffer.hasRemaining()) {
            PreviewText.cancelled();
            int read = file.read(buffer, position + buffer.position());
            if (read < 0) throw invalid();
        }
        return result;
    }
    private static Charset legacyCharset() {
        try { return Charset.forName("Cp437"); } catch (Exception unavailable) { return StandardCharsets.ISO_8859_1; }
    }
    static String safeLabel(String source) {
        StringBuilder label = new StringBuilder(source.length());
        for (int i = 0; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (ch == '\n') label.append("\\n");
            else if (ch == '\r') label.append("\\r");
            else if (ch == '\t') label.append("\\t");
            else if (Character.isISOControl(ch) || (ch >= '\u202A' && ch <= '\u202E') || (ch >= '\u2066' && ch <= '\u2069')) label.append('\uFFFD');
            else label.append(ch);
        }
        return label.toString();
    }
    private static int u16(byte[] data, int at) { return (data[at] & 255) | ((data[at + 1] & 255) << 8); }
    private static long u32(byte[] data, int at) { return (long) u16(data, at) | ((long) u16(data, at + 2) << 16); }
    private static long u64(byte[] data, int at) throws IOException {
        long low = u32(data, at), high = u32(data, at + 4);
        if ((high & 0x80000000L) != 0) throw invalid();
        return low | (high << 32);
    }
    private static IOException invalid() { return new IOException("This ZIP cannot be listed. It may be damaged or split across files."); }
    private ZipDirectory() { }
}
