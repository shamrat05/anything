package dev.fastsearch.android;

import android.media.ExifInterface;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.IOException;

/** Reads orientation without leaving EXIF's descriptor ownership to the platform. */
final class ImageOrientation {
    private ImageOrientation() {}

    static int read(ParcelFileDescriptor image) {
        // On Android 8.0, ExifInterface(FileDescriptor) duplicates a seekable FD
        // and wraps it in a non-owning FileInputStream. Use the stream constructor
        // and explicitly own the duplicate ourselves on every Android version.
        try {
            Os.lseek(image.getFileDescriptor(),0,OsConstants.SEEK_SET);
            try (ParcelFileDescriptor.AutoCloseInputStream input=
                         new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(image.getFileDescriptor()))) {
                return new ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION,1);
            }
        } catch(IOException | ErrnoException | RuntimeException invalid) {
            return 1;
        }
        // The duplicate and source share a file offset. Callers reset before decoding.
    }
}
