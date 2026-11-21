package dev.fastsearch.android;

import android.content.Context;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.Os;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;

/** A seekable, unnamed entry for readers and read-only sharing. */
final class ArchiveEntryFile {
    static final long MAX_BYTES = 32L * 1024 * 1024;

    static ParcelFileDescriptor copy(Context context, InputStream input, long size) throws Exception {
        if (size < 0 || size > MAX_BYTES) throw new IOException("ZIP entries over 32 MiB exceed the reader and sharing limit.");
        try (ParcelFileDescriptor backing = openWritable(context)) {
            try (ParcelFileDescriptor.AutoCloseOutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(
                    ParcelFileDescriptor.dup(backing.getFileDescriptor()))) {
                byte[] buffer = new byte[64 * 1024];
                for (long remaining = size; remaining > 0;) {
                    PreviewText.cancelled();
                    int count = input.read(buffer, 0, (int) Math.min(remaining, buffer.length));
                    if (count < 0) throw new IOException("The ZIP entry is incomplete.");
                    out.write(buffer, 0, count);
                    remaining -= count;
                }
                if (input.read() != -1) throw new IOException("The ZIP entry size is invalid.");
            }
            return ParcelFileDescriptor.open(new File("/proc/self/fd/" + backing.getFd()), ParcelFileDescriptor.MODE_READ_ONLY);
        }
    }

    private static ParcelFileDescriptor openWritable(Context context) throws Exception {
        if (Build.VERSION.SDK_INT >= 30) {
            FileDescriptor memory = Os.memfd_create("archive-entry", 0);
            try { return ParcelFileDescriptor.dup(memory); }
            finally { Os.close(memory); }
        }
        File empty = File.createTempFile("archive-entry-", ".tmp", context.getCacheDir());
        try (ParcelFileDescriptor file = ParcelFileDescriptor.open(empty, ParcelFileDescriptor.MODE_READ_WRITE)) {
            if (!empty.delete()) throw new IOException("Could not prepare the archive entry reader.");
            return ParcelFileDescriptor.dup(file.getFileDescriptor());
        } finally {
            if (empty.exists()) empty.delete();
        }
    }
}
