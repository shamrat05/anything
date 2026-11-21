package dev.fastsearch.core;

import java.util.Locale;
import java.util.Map;

/** One immutable index record. Negative size/modified values mean unknown metadata. */
public final class FileEntry {
    public final String name;
    public final String parent;
    public final boolean directory;
    /** A dot-prefixed path component hides both that entry and its descendants. */
    public final boolean hidden;
    public final long size;
    public final long modified;
    final String foldedName;
    private final int pathHash;
    private final boolean extraRootSlash;

    public FileEntry(String path, boolean directory, long size, long modified) {
        this(path, directory, size, modified, null);
    }

    /** A bulk index reader can share parent strings among entries without sharing the records. */
    public FileEntry(String path, boolean directory, long size, long modified, Map<String, String> parents) {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("A file entry needs a path.");
        }
        int end = path.length();
        while (end > 1 && path.charAt(end - 1) == '/') end--;
        String fullPath = path.substring(0, end);
        int slash = fullPath.lastIndexOf('/');
        extraRootSlash = slash == 1 && fullPath.charAt(0) == '/';
        name = fullPath.equals("/") ? "/" : fullPath.substring(slash + 1);
        String parentPath = fullPath.equals("/") || slash < 0 ? "" : slash == 0 ? "/" : fullPath.substring(0, slash);
        String sharedParent = parents == null ? null : parents.putIfAbsent(parentPath, parentPath);
        parent = sharedParent == null ? parentPath : sharedParent;
        this.directory = directory;
        boolean hiddenPath = false;
        for (int i = 0; i < fullPath.length(); i++) {
            if ((i == 0 || fullPath.charAt(i - 1) == '/') && fullPath.charAt(i) == '.') {
                hiddenPath = true;
                break;
            }
        }
        hidden = hiddenPath;
        this.size = size;
        this.modified = modified;
        pathHash = hashPath(fullPath);
        foldedName = name.toLowerCase(Locale.ROOT);
    }

    public String path() {
        if (parent.isEmpty()) return name;
        return parent.equals("/") && !extraRootSlash ? "/" + name : parent + "/" + name;
    }

    String foldedPath() { return path().toLowerCase(Locale.ROOT); }

    static int hashPath(String path) {
        int hash = 0x811c9dc5;
        for (int i = 0; i < path.length(); i++) hash = (hash ^ path.charAt(i)) * 0x01000193;
        // Mix the high and low bits for power-of-two tables.
        hash = (hash ^ (hash >>> 16)) * 0x85ebca6b;
        hash = (hash ^ (hash >>> 13)) * 0xc2b2ae35;
        return hash ^ (hash >>> 16);
    }

    int pathHash() { return pathHash; }
    boolean samePath(FileEntry other) {
        return extraRootSlash == other.extraRootSlash && name.equals(other.name) && parent.equals(other.parent);
    }

    public boolean pathEquals(String path) {
        if (path == null) return false;
        int prefix = prefixLength();
        return path.length() == prefix + name.length() && path.startsWith(parent)
                && (prefix == 0 || path.charAt(prefix - 1) == '/')
                && path.regionMatches(prefix, name, 0, name.length());
    }

    public int comparePath(FileEntry other) {
        int parentOrder = parent == other.parent ? 0 : parent.compareTo(other.parent);
        if (parentOrder == 0 && extraRootSlash == other.extraRootSlash) return name.compareTo(other.name);
        int prefix = prefixLength(), otherPrefix = other.prefixLength();
        int sharedParentLength = Math.min(parent.length(), other.parent.length());
        if (parentOrder != 0) {
            boolean parentIsPrefix = parent.length() < other.parent.length() && parentOrder < 0
                    && other.parent.startsWith(parent);
            boolean otherParentIsPrefix = other.parent.length() < parent.length() && parentOrder > 0
                    && parent.startsWith(other.parent);
            if (!parentIsPrefix && !otherParentIsPrefix) return parentOrder;
        }
        int length = prefix + name.length(), otherLength = otherPrefix + other.name.length();
        int common = Math.min(length, otherLength);
        for (int i = sharedParentLength; i < common; i++) {
            int difference = pathCharAt(i, prefix) - other.pathCharAt(i, otherPrefix);
            if (difference != 0) return difference;
        }
        return length - otherLength;
    }

    public int comparePathIgnoreCase(FileEntry other) {
        int order = parent == other.parent ? 0 : parent.compareToIgnoreCase(other.parent);
        if (order == 0 && extraRootSlash == other.extraRootSlash) return name.compareToIgnoreCase(other.name);
        int shared = Math.min(parent.length(), other.parent.length());
        if (order != 0 && !parent.regionMatches(true, 0, other.parent, 0, shared)) return order;
        return path().compareToIgnoreCase(other.path());
    }

    private int prefixLength() {
        return parent.isEmpty() || parent.equals("/") && !extraRootSlash ? parent.length() : parent.length() + 1;
    }

    private char pathCharAt(int index, int prefix) {
        if (index >= prefix) return name.charAt(index - prefix);
        return index == parent.length() ? '/' : parent.charAt(index);
    }
}
