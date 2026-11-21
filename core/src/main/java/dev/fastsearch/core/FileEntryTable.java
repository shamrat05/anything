package dev.fastsearch.core;

import java.util.AbstractCollection;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;

/** Path lookup for large snapshots without a second copy of each path or a node per file. */
public final class FileEntryTable {
    private static final FileEntry DELETED = new FileEntry("/", true, 0, 0);
    private FileEntry[] buckets = new FileEntry[16];
    private int size;
    private int used;

    public int size() { return size; }
    public boolean isEmpty() { return size == 0; }

    public FileEntry get(String path) {
        int hash = FileEntry.hashPath(path);
        int mask = buckets.length - 1;
        int step = probeStep(hash);
        for (int i = hash & mask; buckets[i] != null; i = (i + step) & mask) {
            FileEntry entry = buckets[i];
            if (entry != DELETED && entry.pathHash() == hash && entry.pathEquals(path)) return entry;
        }
        return null;
    }

    public boolean contains(FileEntry key) {
        FileEntry entry = buckets[slot(key)];
        return entry != null && entry != DELETED;
    }

    public void put(FileEntry entry) {
        int i = slot(entry);
        if (buckets[i] == null) {
            if (used >= buckets.length * 3 / 4) {
                resize(size >= buckets.length / 2 ? buckets.length * 2 : buckets.length);
                i = slot(entry);
            }
        }
        if (buckets[i] == null) used++;
        if (buckets[i] == null || buckets[i] == DELETED) size++;
        buckets[i] = entry;
    }

    public void putAll(FileEntryTable source) { for (FileEntry entry : source.values()) put(entry); }

    public FileEntry remove(String path) {
        int hash = FileEntry.hashPath(path);
        int mask = buckets.length - 1;
        int step = probeStep(hash);
        for (int i = hash & mask; buckets[i] != null; i = (i + step) & mask) {
            FileEntry entry = buckets[i];
            if (entry != DELETED && entry.pathHash() == hash && entry.pathEquals(path)) {
                erase(i);
                return entry;
            }
        }
        return null;
    }

    public void removeDescendants(String parent) {
        String prefix = parent.endsWith("/") ? parent : parent + "/";
        for (int i = 0; i < buckets.length; i++) {
            FileEntry entry = buckets[i];
            if (entry != null && entry != DELETED
                    && (entry.parent.equals(parent) || entry.parent.startsWith(prefix))) erase(i);
        }
    }

    public void clear() {
        buckets = new FileEntry[16];
        size = used = 0;
    }

    public Collection<FileEntry> values() {
        return new AbstractCollection<FileEntry>() {
            @Override public int size() { return FileEntryTable.this.size; }
            @Override public Iterator<FileEntry> iterator() {
                return new Iterator<FileEntry>() {
                    private int cursor;
                    private int last = -1;
                    @Override public boolean hasNext() {
                        while (cursor < buckets.length && (buckets[cursor] == null || buckets[cursor] == DELETED)) cursor++;
                        return cursor < buckets.length;
                    }
                    @Override public FileEntry next() {
                        if (!hasNext()) throw new NoSuchElementException();
                        last = cursor++;
                        return buckets[last];
                    }
                    @Override public void remove() {
                        if (last < 0) throw new IllegalStateException();
                        erase(last);
                        last = -1;
                    }
                };
            }
        };
    }

    // An odd step visits every bucket and keeps entries that share a starting bucket from clustering.
    private static int probeStep(int hash) { return Integer.rotateRight(hash, 16) | 1; }
    private void erase(int index) { buckets[index] = DELETED; size--; }

    private int slot(FileEntry entry) {
        int deleted = -1;
        int hash = entry.pathHash();
        int mask = buckets.length - 1;
        int step = probeStep(hash);
        int i = hash & mask;
        while (buckets[i] != null) {
            FileEntry previous = buckets[i];
            if (previous == DELETED) {
                if (deleted < 0) deleted = i;
            } else if (previous.pathHash() == hash && previous.samePath(entry)) return i;
            i = (i + step) & mask;
        }
        return deleted < 0 ? i : deleted;
    }

    private void resize(int capacity) {
        FileEntry[] previous = buckets;
        buckets = new FileEntry[capacity];
        used = size = 0;
        for (FileEntry entry : previous) if (entry != null && entry != DELETED) put(entry);
    }
}
