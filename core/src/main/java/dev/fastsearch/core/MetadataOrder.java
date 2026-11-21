package dev.fastsearch.core;

import java.util.Arrays;
import java.util.List;

/** Stable sort by signed metadata, retaining the index's name order for ties. */
public final class MetadataOrder {
    private MetadataOrder() {}

    public static List<FileEntry> sort(List<FileEntry> rows, boolean modified, boolean descending,
                                       Runnable checkCancelled) {
        FileEntry[] source = rows.toArray(new FileEntry[0]);
        FileEntry[] target = new FileEntry[source.length];
        long[] keys = new long[source.length], reordered = new long[source.length];
        long first = source.length == 0 ? 0 : key(source[0], modified, descending);
        long variation = 0;
        for (int i = 0; i < source.length; i++) {
            if ((i & 4095) == 0) checkCancelled.run();
            keys[i] = key(source[i], modified, descending);
            variation |= first ^ keys[i];
        }
        int[] offsets = new int[256];
        for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
            if (((variation >>> shift) & 255) == 0) continue;
            Arrays.fill(offsets, 0);
            for (int i = 0; i < keys.length; i++) {
                if ((i & 4095) == 0) checkCancelled.run();
                offsets[(int) (keys[i] >>> shift) & 255]++;
            }
            int offset = 0;
            for (int i = 0; i < offsets.length; i++) {
                int count = offsets[i];
                offsets[i] = offset;
                offset += count;
            }
            for (int i = 0; i < keys.length; i++) {
                if ((i & 4095) == 0) checkCancelled.run();
                int position = offsets[(int) (keys[i] >>> shift) & 255]++;
                target[position] = source[i];
                reordered[position] = keys[i];
            }
            FileEntry[] entries = source; source = target; target = entries;
            long[] values = keys; keys = reordered; reordered = values;
        }
        return Arrays.asList(source);
    }

    private static long key(FileEntry row, boolean modified, boolean descending) {
        long ascending = (modified ? row.modified : row.size) ^ Long.MIN_VALUE;
        return descending ? ~ascending : ascending;
    }
}
