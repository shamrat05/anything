package dev.fastsearch.android;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import java.io.FileDescriptor;

/** app_process entry point. It sends one checked read-only FD and then exits. */
public final class RootFileReader {
    private RootFileReader() { }

    public static void main(String[] args) {
        if (args.length != 2 || Os.getuid() != 0) System.exit(1);
        try (LocalSocket socket = new LocalSocket()) {
            socket.connect(new LocalSocketAddress(args[0], LocalSocketAddress.Namespace.ABSTRACT));
            try (ParcelFileDescriptor file = SafeFileAccess.openLocal(args[1])) {
                socket.setFileDescriptorsForSend(new FileDescriptor[]{file.getFileDescriptor()});
                socket.getOutputStream().write(1);
                socket.getOutputStream().flush();
            } catch (Exception failure) {
                socket.setFileDescriptorsForSend(null);
                socket.getOutputStream().write(0);
            }
        } catch (Exception failure) { System.exit(1); }
    }
}
