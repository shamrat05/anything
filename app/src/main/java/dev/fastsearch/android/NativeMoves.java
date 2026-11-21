package dev.fastsearch.android;

import java.io.File;
import java.io.IOException;

/** Atomic no-replace rename only. Copy and clipboard capture never load this library. */
final class NativeMoves {
    private static String loadedPath;

    private NativeMoves() { }

    static synchronized void load(String path) throws IOException {
        if (path == null) throw new IOException("Atomic moves are unavailable. The source was retained.");
        File library = new File(path);
        if (!library.isAbsolute() || !library.getName().equals("libfileops.so")
                || !library.isFile() || !library.canRead() || !path.equals(library.getCanonicalPath())) {
            throw new IOException("The atomic move library is unavailable. The source was retained.");
        }
        if (path.equals(loadedPath)) return;
        if (loadedPath != null) throw new IOException("The atomic move library changed. The source was retained.");
        try { System.load(path); }
        catch (UnsatisfiedLinkError | SecurityException unavailable) {
            throw new IOException("Atomic moves are unavailable. The source was retained.", unavailable);
        }
        loadedPath = path;
    }

    /** UTF-8 bytes, absolute and NUL-free, at most 4095 bytes each. Returns 0 or errno. */
    static native int renameNoReplace(byte[] from, byte[] to);
}
