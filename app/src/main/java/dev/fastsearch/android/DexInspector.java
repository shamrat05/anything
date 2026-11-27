package dev.fastsearch.android;

import com.android.tools.smali.baksmali.Adaptors.ClassDefinition;
import com.android.tools.smali.baksmali.BaksmaliOptions;
import com.android.tools.smali.baksmali.formatter.BaksmaliWriter;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.Writer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;

/** Offline, read-only Smali inspection, with no class loading or APK extraction. */
final class DexInspector {
    static final int MAX_DEX_BYTES = 16 * 1024 * 1024;
    static final int MAX_ALL_BYTES = 48 * 1024 * 1024;
    static final int MAX_CODE_CHARS = SmaliExportPlan.MAX_SOURCE_CHARS;
    static final int MAX_CLASSES = 40_000;
    private static final int MAX_DEX_ENTRIES = 32, MAX_STRING_CHARS = 8192, MAX_CLASS_NAME_CHARS = 1_000_000;
    private static final long MAX_APK_BYTES = 768L * 1024 * 1024;

    static final class Item {
        final String name, match, dex, descriptor;
        private final ClassDef definition;
        Item(String name, String dex, String descriptor, ClassDef definition) {
            this.name = name; this.match = name.toLowerCase(Locale.ROOT);
            this.dex = dex; this.descriptor=descriptor; this.definition = definition;
        }
        String key() { return dex + "/" + name; }
    }

    static final class Index {
        final List<Item> items;
        final List<String> entries;
        private final Map<String,List<Item>> byOwner;
        Index(List<Item> items,List<String> entries) {
            this.items=Collections.unmodifiableList(items);
            this.entries=Collections.unmodifiableList(entries);
            Map<String,List<Item>> owners=new HashMap<>();
            for(Item item:items)owners.computeIfAbsent(item.descriptor,ignored->new ArrayList<>(1)).add(item);
            byOwner=owners;
        }
        List<Item> targets(String owner) {
            List<Item> rows=byOwner.get(owner);
            return rows==null?Collections.emptyList():Collections.unmodifiableList(rows);
        }
        Item exact(String owner,String dex) {
            for(Item item:targets(owner))if(item.dex.equals(dex))return item;
            return null;
        }
    }

    static List<Item> read(FileChannel source, boolean apk, int budget) throws IOException {
        return index(source,apk,budget).items;
    }

    static Index index(FileChannel source, boolean apk, int budget) throws IOException {
        if (budget <= 0 || budget > MAX_ALL_BYTES) throw new IllegalArgumentException("Invalid DEX budget");
        List<Item> out = new ArrayList<>();
        List<String> entries=new ArrayList<>();
        long deadline = System.nanoTime() + 15_000_000_000L;
        int nameBudget = MAX_CLASS_NAME_CHARS;
        if (apk) {
            if (source.size() > MAX_APK_BYTES) throw new IOException("APKs over 768 MiB exceed the inspector limit.");
            ZipDirectory.Listing listing = ZipDirectory.readDexEntries(source);
            if (listing.truncated) throw new IOException("This APK has too many ZIP entries for the inspector.");
            if (listing.entries.isEmpty()) throw new IOException("No root classes.dex entries were found in this APK.");
            if (listing.entries.size() > MAX_DEX_ENTRIES) throw new IOException("This APK has more than 32 DEX files.");
            int remaining = budget;
            long compressedBudget = 2L * budget;
            HashSet<String> seen = new HashSet<>();
            for (ZipDirectory.Entry entry : listing.entries) {
                check(deadline);
                if (entry.rawName == null || entry.special || !seen.add(entry.rawName))
                    throw new IOException("This APK has ambiguous DEX entries.");
                int size = size(entry.size, remaining);
                if (entry.compressedSize > compressedBudget || entry.compressedSize > 2L * MAX_DEX_BYTES)
                    throw new IOException("Compressed DEX data exceeds the read limit.");
                compressedBudget -= entry.compressedSize;
                try (InputStream stream = ZipDirectory.open(source, entry)) {
                    nameBudget = addClasses(readBytes(stream, size, entry.crc, deadline), entry.name, out, nameBudget, deadline);
                }
                remaining -= size;
                entries.add(entry.name);
            }
        } else {
            source.position(0);
            entries.add("DEX");
            addClasses(readBytes(Channels.newInputStream(source), size(source.size(), budget), -1, deadline), "DEX", out, nameBudget, deadline);
        }
        out.sort(Comparator.comparing((Item item) -> item.name).thenComparing(item -> item.dex));
        check(deadline);
        return new Index(out,entries);
    }

    private static int size(long size, int remaining) throws IOException {
        if (size < 112) throw new IOException("This DEX header is incomplete.");
        if (size > MAX_DEX_BYTES || size > remaining)
            throw new IOException("DEX data exceeds the safe inspection limit for this device.");
        return (int) size;
    }

    private static byte[] readBytes(InputStream input, int size, long expectedCrc, long deadline) throws IOException {
        byte[] bytes = new byte[size];
        CRC32 crc = expectedCrc >= 0 ? new CRC32() : null;
        for (int offset = 0; offset < size;) {
            check(deadline);
            int read = input.read(bytes, offset, Math.min(64 * 1024, size - offset));
            if (read <= 0) throw new IOException("This DEX ended early.");
            if (crc != null) crc.update(bytes, offset, read);
            offset += read;
        }
        if (input.read() != -1 || (crc != null && crc.getValue() != expectedCrc))
            throw new IOException("DEX size or archive checksum does not match.");
        return bytes;
    }

    private static int addClasses(byte[] bytes, String dex, List<Item> out, int nameBudget, long deadline) throws IOException {
        validate(bytes, MAX_CLASSES - out.size(), deadline);
        try {
            DexBackedDexFile input = new DexBackedDexFile(null, bytes);
            for (ClassDef item : input.getClasses()) {
                check(deadline);
                String type = item.getType();
                if (type.length() < 3 || type.length() > 1024 || type.charAt(0) != 'L'
                        || type.charAt(type.length() - 1) != ';') throw invalid();
                for (int i = 1; i < type.length() - 1; i++) {
                    char c = type.charAt(i);
                    if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT) throw invalid();
                }
                nameBudget -= type.length();
                if (nameBudget < 0) throw new IOException("Class names exceed the safe inspection limit.");
                out.add(new Item(type.substring(1, type.length() - 1).replace('/', '.'), dex, type, item));
            }
        } catch (RuntimeException malformed) { throw invalid(); }
        return nameBudget;
    }

    private static void validate(byte[] bytes, int remainingClasses, long deadline) throws IOException {
        if (bytes[0] != 'd' || bytes[1] != 'e' || bytes[2] != 'x' || bytes[3] != '\n' || bytes[7] != 0)
            throw new IOException("This is not a standard DEX file.");
        String version = new String(bytes, 4, 3, java.nio.charset.StandardCharsets.US_ASCII);
        if (!"035 037 038 039 040".contains(version) || version.indexOf(' ') >= 0)
            throw new IOException("DEX version " + version.replaceAll("[^0-9]", "?") + " is not supported.");
        if (u32(bytes, 32) != bytes.length || u32(bytes, 36) != 112 || u32(bytes, 40) != 0x12345678L) throw invalid();
        int count = table(bytes, 56, 4, 400_000);
        int start = (int) u32(bytes, 60);
        table(bytes, 64, 4, 65_536);
        table(bytes, 72, 12, 65_536);
        table(bytes, 80, 8, 65_536);
        table(bytes, 88, 8, 65_536);
        table(bytes, 96, 32, remainingClasses);
        for (int i = 0; i < count; i++) {
            if ((i & 511) == 0) check(deadline);
            long offset = u32(bytes, start + 4 * i);
            if (offset < 112 || offset >= bytes.length - 1) throw invalid();
            int len = 0, shift = 0, next;
            do {
                if (offset >= bytes.length || shift >= 21) throw invalid();
                next = bytes[(int) offset++] & 255;
                len |= (next & 127) << shift;
                shift += 7;
            } while ((next & 128) != 0);
            if (len > MAX_STRING_CHARS || len >= bytes.length - offset) throw invalid();
        }
    }

    private static int table(byte[] bytes, int position, int width, int max) throws IOException {
        long count = u32(bytes, position), off = u32(bytes, position + 4);
        if (count > max) throw new IOException("This DEX has too many items for safe inspection.");
        if (count != 0 && (off < 112 || (off & 3) != 0 || off + count * width > bytes.length)) throw invalid();
        return (int) count;
    }

    private static long u32(byte[] bytes, int p) {
        return (bytes[p] & 255L) | ((bytes[p + 1] & 255L) << 8) | ((bytes[p + 2] & 255L) << 16) | ((bytes[p + 3] & 255L) << 24);
    }

    static String smali(Item item) throws IOException { return disassemble(item).text; }

    static final class Disassembly {
        final String text;
        final boolean complete;
        Disassembly(String text,boolean complete) { this.text=text; this.complete=complete; }
    }

    static Disassembly disassemble(Item item) throws IOException {
        CodeOutput limit = new CodeOutput();
        BaksmaliOptions options = new BaksmaliOptions();
        options.debugInfo = false;
        boolean complete=false;
        try (BaksmaliWriter writer = new BaksmaliWriter(limit)) {
            ClassDefinition definition=new ClassDefinition(options,item.definition);
            definition.writeTo(writer);
            complete=!definition.hadValidationErrors();
        } catch (CodeLimit clipped) {
            complete=false;
            limit.out.append("\n# Preview limit reached.\n");
        } catch (RuntimeException | StackOverflowError malformed) { throw invalid(); }
        return new Disassembly(limit.out.toString(),complete);
    }

    private static IOException invalid() { return new IOException("DEX data is damaged or unsupported."); }

    private static void check(long deadline) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
        if (System.nanoTime() - deadline > 0) throw new IOException("DEX inspection exceeded its time limit.");
    }

    private static final class CodeLimit extends IOException {}
    private static final class CodeOutput extends Writer {
        final StringBuilder out = new StringBuilder();
        private final long deadline = System.nanoTime() + 6_000_000_000L;
        @Override public void write(char[] chars, int off, int length) throws IOException {
            check(deadline);
            if (length > MAX_CODE_CHARS - out.length()) throw new CodeLimit();
            out.append(chars, off, length);
        }
        @Override public void flush() {}
        @Override public void close() {}
    }
}
