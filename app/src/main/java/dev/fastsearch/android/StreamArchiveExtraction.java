package dev.fastsearch.android;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/** Bounded TAR/GZIP handling. The caller publishes only after all data passes in private staging. */
final class StreamArchiveExtraction {
    static final int MAX_RECORDS = 2000;
    private static final int BLOCK = 512, META_LIMIT = 16 * 1024, MAX_NODES = 8192, MAX_PATH_BYTES = 4095;
    private static final long PADDING_LIMIT = 1024 * 1024;

    enum Format {
        TAR("TAR archive", ".tar", false, true),
        TAR_GZIP("GZIP-compressed TAR archive", ".tar.gz", true, true),
        TGZ("GZIP-compressed TAR archive", ".tgz", true, true),
        GZIP("GZIP-compressed file", ".gz", true, false);

        final String label, suffix;
        final boolean gzip, tar;
        Format(String label, String suffix, boolean gzip, boolean tar) {
            this.label = label; this.suffix = suffix; this.gzip = gzip; this.tar = tar;
        }
        String stem(String name) { return name.substring(0, name.length() - suffix.length()); }
    }

    static Format format(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (Format format : Format.values()) if (lower.endsWith(format.suffix)) return format;
        return null;
    }

    static String decompressedName(String name) {
        String proposed = Format.GZIP.stem(name);
        try { ArchiveExtraction.validateLeaf(proposed); }
        catch (IOException unsafe) { return "Uncompressed file"; }
        return proposed;
    }

    /** Check the envelope only; opening a large archive never scans or writes its contents. */
    static void inspect(FileChannel input, Format format) throws IOException {
        checkSourceSize(input);
        if (format.gzip) {
            // Do not scan an untrusted optional filename, comment, or compressed payload here.
            ByteBuffer marker = ByteBuffer.allocate(10);
            while (marker.hasRemaining()) {
                PreviewText.cancelled();
                if (input.read(marker) <= 0) throw damaged();
            }
            byte[] b = marker.array();
            if ((b[0] & 255) != 0x1F || (b[1] & 255) != 0x8B || b[2] != 8 || (b[3] & 0xE0) != 0)
                throw damaged();
            return;
        }
        try (InputStream source = Channels.newInputStream(input)) {
            byte[] header = new byte[BLOCK];
            fill(source, header, BLOCK);
            if (!isZero(header)) checksum(header);
            else {
                fill(source, header, BLOCK);
                if (!isZero(header)) throw damaged();
            }
        } catch (EOFException invalid) { throw damaged(); }
    }

    static void extract(FileChannel input, String name, Format format, Path fresh) throws IOException {
        long limit = Math.min(ArchiveExtraction.MAX_BYTES, fresh.toFile().getUsableSpace());
        try (InputStream source = decoded(input, format)) {
            if (format.tar) extractTar(source, fresh, limit);
            else try (OutputStream output = create(fresh.resolve(decompressedName(name)))) {
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                while (true) {
                    PreviewText.cancelled();
                    int read = source.read(buffer);
                    if (read < 0) break;
                    if (read > limit - total) throw expanded(limit);
                    output.write(buffer, 0, read);
                    total += read;
                }
            }
        } catch (EOFException | java.util.zip.ZipException invalid) { throw damaged(); }
    }

    private static long checkSourceSize(FileChannel input) throws IOException {
        long size = input.size();
        if (size > ArchiveExtraction.MAX_BYTES)
            throw new IOException("Archives larger than 1 GB need a dedicated archive app.");
        return size;
    }

    private static InputStream decoded(FileChannel input, Format format) throws IOException {
        long size = checkSourceSize(input);
        input.position(0);
        InputStream raw = Channels.newInputStream(input);
        // Keep available() accurate for the standard decoder's concatenated-member check.
        InputStream source = new BufferedInputStream(new java.io.FilterInputStream(raw) {
            private long remaining = size;
            @Override public int available() { return (int) Math.min(Integer.MAX_VALUE, remaining); }
            @Override public int read() throws IOException {
                PreviewText.cancelled();
                if (remaining == 0) return -1;
                int read = super.read();
                if (read >= 0) remaining--;
                return read;
            }
            @Override public int read(byte[] b, int off, int len) throws IOException {
                PreviewText.cancelled();
                if (len == 0) return 0;
                if (remaining == 0) return -1;
                int read = super.read(b, off, (int) Math.min(remaining, len));
                if (read > 0) remaining -= read;
                return read;
            }
        }, 64 * 1024);
        if (!format.gzip) return source;
        try { return new GZIPInputStream(source, 64 * 1024); }
        catch (IOException failure) { source.close(); throw failure; }
    }

    private static void extractTar(InputStream source, Path fresh, long limit) throws IOException {
        byte[] header = new byte[BLOCK];
        byte[] buffer = new byte[64 * 1024];
        Map<String, Boolean> paths = new HashMap<>();
        Set<String> explicit = new HashSet<>();
        Map<String, String> metadata = null;
        long total = 0;
        int records = 0;
        while (true) {
            fill(source, header, BLOCK);
            if (isZero(header)) {
                if (metadata != null) throw damaged();
                fill(source, header, BLOCK);
                if (!isZero(header)) throw damaged();
                // Read through GZIP end-of-stream, which verifies the trailer CRC and size.
                long padded = 0;
                while (true) {
                    PreviewText.cancelled();
                    int read = source.read(buffer);
                    if (read == -1) return;
                    padded += read;
                    if (padded > PADDING_LIMIT) throw damaged();
                    for (int i = 0; i < read; i++) if (buffer[i] != 0) throw damaged();
                }
            }
            if (++records > MAX_RECORDS) throw new IOException("This archive has more than 2,000 records. Use a dedicated archive app.");
            boolean posix = checksum(header);
            int type = header[156];
            long size = octal(header, 124, 12);
            if (type == 'x' || type == 'L') {
                if (metadata != null || size > META_LIMIT || size == 0) throw unsupported();
                byte[] data = new byte[(int) size];
                fill(source, data, data.length);
                fill(source, buffer, padding(size));
                if (type == 'x') metadata = pax(data);
                else {
                    metadata = new HashMap<>();
                    metadata.put("path", string(data, 0, data.length));
                }
                continue;
            }
            if (type != 0 && type != '0' && type != '5') throw unsupported();
            boolean directory = type == '5';
            String name;
            if (metadata != null && metadata.containsKey("path")) name = metadata.get("path");
            else {
                String prefix = posix ? string(header, 345, 155) : "";
                name = prefix.isEmpty() ? string(header, 0, 100) : prefix + "/" + string(header, 0, 100);
            }
            if (metadata != null && metadata.containsKey("size")) size = decimal(metadata.get("size"));
            metadata = null;
            if (directory && size != 0) throw damaged();
            // Standard tar tools emit "./" as the archive's directory marker.
            if (directory && (".".equals(name) || "./".equals(name))) {
                if (!explicit.add(".")) throw duplicate();
                continue;
            }
            while (name.startsWith("./")) name = name.substring(2);
            if (directory && name.endsWith("/")) name = name.substring(0, name.length() - 1);
            register(name, directory, paths, explicit);
            if (size > limit - total) throw expanded(limit);
            total += size;
            Path target = fresh.resolve(name);
            if (directory) Files.createDirectories(target);
            else {
                Files.createDirectories(target.getParent());
                try (OutputStream output = create(target)) {
                    long remaining = size;
                    while (remaining > 0) {
                        int read = (int) Math.min(remaining, buffer.length);
                        fill(source, buffer, read);
                        output.write(buffer, 0, read);
                        remaining -= read;
                    }
                }
                fill(source, buffer, padding(size));
            }
        }
    }

    static void register(String path, boolean directory, Map<String, Boolean> paths,
            Set<String> explicit) throws IOException {
        if (path.getBytes(StandardCharsets.UTF_8).length > MAX_PATH_BYTES) throw unsupported();
        String[] parts = path.split("/", -1);
        if (parts.length > 64) throw new IOException("The archive folders are too deeply nested.");
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            ArchiveExtraction.validateLeaf(parts[i]);
            if (i != 0) key.append('/');
            key.append(parts[i]);
            boolean folder = i < parts.length - 1 || directory;
            Boolean previous = paths.putIfAbsent(key.toString(), folder);
            if (previous != null && previous != folder) throw duplicate();
            if (paths.size() > MAX_NODES) throw unsupported();
        }
        if (!explicit.add(path)) throw duplicate();
    }

    static Map<String, String> pax(byte[] data) throws IOException {
        Map<String, String> values = new HashMap<>();
        Set<String> seen = new HashSet<>();
        int start = 0;
        while (start < data.length) {
            int separator = start;
            int length = 0;
            while (separator < data.length && data[separator] != ' ') {
                int digit = data[separator++] - '0';
                if (digit < 0 || digit > 9 || length > META_LIMIT) throw damaged();
                length = length * 10 + digit;
            }
            if (separator == start || length > data.length - start || length <= separator - start + 3)
                throw damaged();
            int end = start + length;
            if (data[end - 1] != '\n') throw damaged();
            int equals = separator + 1;
            while (equals < end - 1 && data[equals] != '=') equals++;
            if (equals == separator + 1 || equals == end - 1) throw damaged();
            String key = utf8(data, separator + 1, equals - separator - 1);
            if (!seen.add(key)) throw damaged();
            switch (key) {
                case "path": case "size":
                    values.put(key, utf8(data, equals + 1, end - equals - 2)); break;
                case "atime": case "mtime": case "ctime": case "uid": case "gid":
                case "uname": case "gname": case "comment": break; // Never restore metadata.
                default: throw unsupported();
            }
            start = end;
        }
        return values;
    }

    static long decimal(String value) throws IOException {
        if (value.isEmpty()) throw damaged();
        long result = 0;
        for (int i = 0; i < value.length(); i++) {
            int digit = value.charAt(i) - '0';
            if (digit < 0 || digit > 9 || result > (Long.MAX_VALUE - digit) / 10) throw damaged();
            result = result * 10 + digit;
        }
        return result;
    }

    /** Validate header checksum and distinguish the POSIX prefix from GNU extension bytes. */
    static boolean checksum(byte[] header) throws IOException {
        if (header[257] != 'u' || header[258] != 's' || header[259] != 't'
                || header[260] != 'a' || header[261] != 'r') throw damaged();
        boolean posix = header[262] == 0 && header[263] == '0' && header[264] == '0';
        boolean gnu = header[262] == ' ' && header[263] == ' ' && header[264] == 0;
        if (!posix && !gnu) throw unsupported();
        long sum = 0;
        for (int i = 0; i < BLOCK; i++) sum += i >= 148 && i < 156 ? 32 : header[i] & 255;
        if (sum != octal(header, 148, 8)) throw damaged();
        return posix;
    }

    static long octal(byte[] source, int offset, int length) throws IOException {
        int end = offset + length;
        while (offset < end && source[offset] == ' ') offset++;
        long value = 0;
        while (offset < end && source[offset] >= '0' && source[offset] <= '7')
            value = value * 8 + source[offset++] - '0';
        while (offset < end) if (source[offset] != 0 && source[offset] != ' ') throw unsupported(); else offset++;
        return value;
    }

    static String string(byte[] source, int offset, int length) throws IOException {
        int end = offset;
        while (end < offset + length && source[end] != 0) end++;
        return utf8(source, offset, end - offset);
    }

    private static String utf8(byte[] bytes, int offset, int length) throws IOException {
        try { return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes, offset, length)).toString(); }
        catch (java.nio.charset.CharacterCodingException invalid) {
            throw new IOException("The archive contains a file name or metadata that is not valid UTF-8.");
        }
    }

    private static void fill(InputStream source, byte[] bytes, int need) throws IOException {
        int position = 0;
        while (position < need) {
            PreviewText.cancelled();
            int read = source.read(bytes, position, need - position);
            if (read <= 0) throw new EOFException();
            position += read;
        }
    }

    static boolean isZero(byte[] bytes) {
        for (byte value : bytes) if (value != 0) return false;
        return true;
    }

    static int padding(long size) { return (int) ((BLOCK - size % BLOCK) % BLOCK); }

    private static OutputStream create(Path target) throws IOException {
        return Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static IOException damaged() {
        return new IOException("This TAR or GZIP is incomplete or failed an integrity check. Nothing was extracted.");
    }

    private static IOException unsupported() {
        return new IOException("This archive uses an unsupported TAR feature. Links, special files, and some metadata need a dedicated archive app.");
    }

    private static IOException duplicate() {
        return new IOException("This archive has duplicate or conflicting paths. Nothing was extracted.");
    }

    private static IOException expanded(long limit) {
        return new IOException(limit < ArchiveExtraction.MAX_BYTES
                ? "Not enough free space to prepare extraction." : "This archive expands beyond the 1 GB in-app limit.");
    }

    private StreamArchiveExtraction() {}
}
