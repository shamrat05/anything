package dev.fastsearch.android;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;

import dev.fastsearch.core.FileEntry;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Bounded, read-only icon extraction. An APK is never executed or sent to an installer. */
final class ArchiveAppIcon {
    private static final long MAX_APK_BYTES = 96L * 1024 * 1024;
    private static final long MAX_RESOURCE_BYTES = 24L * 1024 * 1024;
    private static final int MAX_ENTRIES = 32_768;
    private ArchiveAppIcon() {}

    static boolean supports(FileEntry entry) {
        return !entry.directory && entry.size > 0 && entry.size <= MAX_APK_BYTES
                && "apk".equals(FileSubtypes.extension(entry));
    }

    static Bitmap load(Context context, FileEntry entry, int edge, boolean allowRoot,
                       CancellationSignal signal) throws Exception {
        if (!supports(entry)) return null;
        signal.throwIfCanceled();
        // PackageManager takes a path. Keep the checked original descriptor pinned while copying,
        // and parse only a private, immutable snapshot, not a replaceable shared-storage path.
        File cache = File.createTempFile("archive-icon-", ".apk", context.getCacheDir());
        try {
            try (ParcelFileDescriptor source = SafeFileAccess.openThumbnail(context, entry.path(), allowRoot);
                 ParcelFileDescriptor.AutoCloseInputStream input =
                         new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(source.getFileDescriptor()));
                 FileOutputStream output = new FileOutputStream(cache)) {
                if (source.getStatSize() != entry.size) return null;
                byte[] buffer = new byte[64 * 1024];
                long copied = 0;
                for (int read; (read = input.read(buffer)) >= 0;) {
                    signal.throwIfCanceled();
                    if (Thread.currentThread().isInterrupted()) return null;
                    if (read == 0) continue;
                    copied += read;
                    if (copied > entry.size || copied > MAX_APK_BYTES) return null;
                    output.write(buffer, 0, read);
                }
                if (copied != entry.size) return null;
            }
            if (!boundedArchive(cache)) return null;
            signal.throwIfCanceled();
            PackageManager packages = context.getPackageManager();
            PackageInfo archive = packages.getPackageArchiveInfo(cache.getAbsolutePath(), 0);
            if (archive == null || archive.applicationInfo == null) return null;
            ApplicationInfo info = archive.applicationInfo;
            if (info.icon == 0) return null;
            info.sourceDir = cache.getAbsolutePath();
            info.publicSourceDir = cache.getAbsolutePath();
            Drawable icon = packages.getResourcesForApplication(info).getDrawable(info.icon, null);
            signal.throwIfCanceled();
            return bitmap(icon, edge);
        } finally {
            // Private app-cache expiry is a second line of defence if the process is killed.
            cache.delete();
        }
    }

    private static boolean boundedArchive(File file) throws IOException {
        try (ZipFile zip = new ZipFile(file)) {
            if (zip.size() > MAX_ENTRIES) return false;
            ZipEntry manifest = zip.getEntry("AndroidManifest.xml");
            if (manifest == null || manifest.getSize() <= 0 || manifest.getSize() > 1024 * 1024) return false;
            java.util.Enumeration<? extends ZipEntry> all = zip.entries();
            while (all.hasMoreElements()) {
                ZipEntry entry = all.nextElement();
                String name = entry.getName();
                if (("resources.arsc".equals(name) || name.startsWith("res/"))
                        && (entry.getSize() < 0 || entry.getSize() > MAX_RESOURCE_BYTES)) return false;
            }
            return true;
        }
    }

    static Bitmap bitmap(Drawable icon, int edge) {
        if (icon == null || edge <= 0) return null;
        Bitmap result = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888);
        boolean success = false;
        try {
            icon = icon.mutate();
            int iw = icon.getIntrinsicWidth(), ih = icon.getIntrinsicHeight();
            float scale = iw > 0 && ih > 0 ? edge / (float) Math.max(iw, ih) : 1f;
            int w = iw > 0 ? Math.max(1, Math.round(iw * scale)) : edge;
            int h = ih > 0 ? Math.max(1, Math.round(ih * scale)) : edge;
            int x = (edge - w) / 2, y = (edge - h) / 2;
            icon.setBounds(x, y, x + w, y + h);
            icon.draw(new Canvas(result));
            success = true;
            return result;
        } finally {
            if (!success) result.recycle();
        }
    }
}
