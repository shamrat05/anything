package dev.fastsearch.android;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32;

/** Validates the entire ZIP before writing, and writes only into an app-private fresh tree. */
final class ArchiveExtraction {
    static final long MAX_BYTES = 1024L * 1024 * 1024;
    private static final int MAX_DEPTH = 64;

    static boolean supports(String extension) {
        return "zip".equals(extension) || "jar".equals(extension) || "cbz".equals(extension);
    }

    static void validateLeaf(String value) throws IOException {
        if (value.isEmpty() || value.equals(".") || value.equals("..") || value.indexOf('/') >= 0
                || value.indexOf('\\') >= 0 || value.indexOf(':') >= 0 || !ZipDirectory.safeLabel(value).equals(value)
                || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 255) {
            throw new IOException("The archive contains an unsafe or unsupported file name.");
        }
    }

    static void extract(FileChannel input, Path fresh) throws IOException {
        ZipDirectory.Listing listing = ZipDirectory.read(input);
        if (listing.truncated) throw new IOException("This archive is above the in-app limit. Use an archive app to extract it.");
        Map<String, Boolean> paths = new HashMap<>();
        java.util.Set<String> explicit = new java.util.HashSet<>();
        long total = 0;
        for (ZipDirectory.Entry item : listing.entries) {
            if (item.rawName == null || item.special) throw new IOException("This archive contains an unsupported path or file type.");
            if (item.encrypted || item.method != 0 && item.method != 8)
                throw new IOException("This archive needs a different archive app. An entry is encrypted or uses unsupported compression.");
            String path = relative(item);
            String[] names = path.split("/", -1);
            if (names.length > MAX_DEPTH) throw new IOException("The archive folders are too deeply nested.");
            StringBuilder key = new StringBuilder();
            for (int i = 0; i < names.length; i++) {
                validateLeaf(names[i]);
                if (i != 0) key.append('/');
                key.append(names[i]);
                boolean folder = i < names.length - 1 || item.directory;
                Boolean existing = paths.putIfAbsent(key.toString(), folder);
                if (existing != null && existing != folder) throw new IOException("The archive has conflicting paths.");
            }
            if (!explicit.add(path)) throw new IOException("The archive has duplicate paths.");
            if (!item.directory) {
                if (item.size > MAX_BYTES - total) throw new IOException("This archive expands beyond the 1 GB in-app limit.");
                total += item.size;
            } else if (item.size != 0) throw new IOException("An archive folder has unexpected file data.");
        }
        if (fresh.toFile().getUsableSpace() < total)
            throw new IOException("Not enough free space to prepare extraction.");
        byte[] buffer = new byte[64 * 1024];
        for (ZipDirectory.Entry item : listing.entries) {
            PreviewText.cancelled();
            Path out = fresh.resolve(relative(item));
            if (item.directory) { Files.createDirectories(out); continue; }
            Files.createDirectories(out.getParent());
            long remaining = item.size;
            CRC32 crc = new CRC32();
            try (InputStream source = ZipDirectory.open(input, item);
                    OutputStream target = Files.newOutputStream(out, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                while (remaining > 0) {
                    PreviewText.cancelled();
                    int read = source.read(buffer, 0, (int)Math.min(remaining, buffer.length));
                    if (read <= 0) throw new IOException("An archive entry ended early. Nothing was extracted.");
                    target.write(buffer, 0, read);
                    crc.update(buffer, 0, read);
                    remaining -= read;
                }
                if (source.read() != -1 || crc.getValue() != item.crc)
                    throw new IOException("An archive entry failed its integrity check. Nothing was extracted.");
            }
        }
    }

    static void removeStaging(Path staging) {
        try {
            Files.walkFileTree(staging,new java.nio.file.SimpleFileVisitor<Path>() {
                @Override public java.nio.file.FileVisitResult visitFile(Path file,java.nio.file.attribute.BasicFileAttributes attrs)
                        throws IOException {
                    Files.deleteIfExists(file);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
                @Override public java.nio.file.FileVisitResult postVisitDirectory(Path directory,IOException failure)
                        throws IOException {
                    if (failure != null) throw failure;
                    Files.deleteIfExists(directory);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) { /* App-private cache only. */ }
    }

    private static String relative(ZipDirectory.Entry item) {
        return item.directory ? item.rawName.substring(0, item.rawName.length() - 1) : item.rawName;
    }
    private ArchiveExtraction() {}
}
