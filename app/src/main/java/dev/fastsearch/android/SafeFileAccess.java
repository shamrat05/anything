package dev.fastsearch.android;

import android.content.Context;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.system.StructStat;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.util.UUID;

/** Opens only regular files and transfers read-only descriptors from the root helper. */
final class SafeFileAccess {
    private SafeFileAccess() { }

    static ParcelFileDescriptor openLocal(String path) throws Exception {
        if (!path.startsWith("/") || path.indexOf('\0') >= 0
                || !path.equals(new File(path).getCanonicalPath())) {
            throw new IOException("File path is no longer a direct path. Refresh the index.");
        }
        int flags = OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW | OsConstants.O_NONBLOCK;
        if (android.os.Build.VERSION.SDK_INT >= 27) flags |= OsConstants.O_CLOEXEC;
        FileDescriptor fd = Os.open(path, flags, 0);
        try {
            StructStat stat = Os.fstat(fd);
            if (!OsConstants.S_ISREG(stat.st_mode)) throw new IOException("Only regular files can be opened.");
            ParcelFileDescriptor copy = ParcelFileDescriptor.dup(fd);
            try {
                if (!path.equals(Os.readlink("/proc/self/fd/" + copy.getFd()))) {
                    throw new IOException("File path changed. Refresh the index.");
                }
                return copy;
            } catch (Exception failure) {
                copy.close();
                throw failure;
            }
        } finally { Os.close(fd); }
    }

    /** Thumbnails may use the current user's FUSE alias, avoiding a root process per shared file. */
    static ParcelFileDescriptor openThumbnail(Context context,String path,boolean root)throws Exception{
        if(root){String alias=android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
            if(alias.startsWith("/storage/emulated/")){String backing="/data/media/"+new File(alias).getName();
                if(path.startsWith(backing+"/"))try{return openLocal(alias+path.substring(backing.length()));}catch(Exception unavailable){/* Private or restricted files retain the checked root fallback. */}
            }
        }
        return open(context,path,root);
    }

    static ParcelFileDescriptor open(Context context, String path, boolean allowRoot) throws Exception {
        try { return openLocal(path); }
        catch (Exception failure) { if (!allowRoot) throw failure; }
        String socketName = "dev.fastsearch.fd." + UUID.randomUUID();
        Process child = null;
        try (LocalServerSocket server = new LocalServerSocket(socketName)) {
            String command = "unset LD_PRELOAD LD_LIBRARY_PATH; export CLASSPATH="
                    + IndexRootScanner.quote(context.getApplicationInfo().sourceDir)
                    + "; exec /system/bin/toybox timeout -k 2 35 /system/bin/app_process /system/bin dev.fastsearch.android.RootFileReader "
                    + IndexRootScanner.quote(socketName) + " " + IndexRootScanner.quote(path);
            child = IndexRootScanner.rootCommand(command).start();
            child.getOutputStream().close();
            final Process reader = child;
            Thread drain = new Thread(() -> {
                try (java.io.InputStream error = reader.getErrorStream()) {
                    byte[] buffer = new byte[512];
                    while (error.read(buffer) != -1) { }
                } catch (IOException ignored) { }
            }, "root-open-errors");
            drain.setDaemon(true);
            drain.start();
            StructPollfd poll = new StructPollfd();
            poll.fd = server.getFileDescriptor();
            poll.events = (short) OsConstants.POLLIN;
            long deadline = android.os.SystemClock.elapsedRealtime() + 30_000;
            while (Os.poll(new StructPollfd[]{poll}, 200) == 0) {
                if (!child.isAlive()) throw new IOException("Root could not open this file.");
                if (Thread.currentThread().isInterrupted()
                        || android.os.SystemClock.elapsedRealtime() >= deadline) {
                    throw new IOException("Root file access timed out.");
                }
            }
            try (LocalSocket socket = server.accept()) {
                socket.setSoTimeout(5000);
                if (socket.getPeerCredentials().getUid() != 0) throw new IOException("Invalid root reader.");
                int response = socket.getInputStream().read();
                FileDescriptor[] received = socket.getAncillaryFileDescriptors();
                if (response != 1 || received == null || received.length != 1) {
                    if (received != null) for (FileDescriptor fd : received) Os.close(fd);
                    throw new IOException("File changed or root could not read it. Refresh and try again.");
                }
                try {
                    if (!OsConstants.S_ISREG(Os.fstat(received[0]).st_mode)) throw new IOException("Not a regular file.");
                    return ParcelFileDescriptor.dup(received[0]);
                } finally { Os.close(received[0]); }
            }
        } finally {
            if (child != null) {
                child.destroy();
                try { child.waitFor(250, java.util.concurrent.TimeUnit.MILLISECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                try { child.getInputStream().close(); } catch (IOException ignored) { }
                try { child.getErrorStream().close(); } catch (IOException ignored) { }
                try { child.getOutputStream().close(); } catch (IOException ignored) { }
            }
        }
    }
}
