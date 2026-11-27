package dev.fastsearch.android;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.widget.Toast;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.xmlpull.v1.XmlPullParser;

/** Inspects a private copy of the chosen APK; only Android's installer can install it. */
final class ApkInstallAction {
    static final String MIME = "application/vnd.android.package-archive";
    static final String CACHE_FOLDER = "apk-inspection";
    static final long CACHE_LIFETIME_MILLIS = 2L * 60 * 60 * 1000;
    private static final long MAX_APK_BYTES = 2L * 1024 * 1024 * 1024;
    private static final long MAX_DIRECTORY_BYTES = 8L * 1024 * 1024;
    private static final long MAX_MANIFEST_BYTES = 1024L * 1024;
    private static final long MAX_RESOURCES_BYTES = 32L * 1024 * 1024;
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";

    private ApkInstallAction() {}

    static boolean supported() {
        return BuildConfig.APK_INSTALL_ENABLED;
    }

    /** Worker-only. Copies without changing the caller's descriptor position. */
    @SuppressWarnings("deprecation")
    static Details inspect(Context context, ParcelFileDescriptor source) throws Exception {
        if (source == null) throw unreadable();
        StructStat initial = Os.fstat(source.getFileDescriptor());
        if (!OsConstants.S_ISREG(initial.st_mode) || initial.st_size <= 0) throw unreadable();
        if (initial.st_size > MAX_APK_BYTES) throw new IOException("APK inspection is limited to 2 GiB.");
        File directory = new File(context.getCacheDir(), CACHE_FOLDER);
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Could not prepare private APK storage.");
        }
        prune(directory);
        if (directory.getUsableSpace() < initial.st_size + 16L * 1024 * 1024) {
            throw new IOException("Not enough free space to inspect this APK.");
        }
        File snapshot = new File(directory, UUID.randomUUID().toString() + ".apk");
        if (!snapshot.createNewFile()) throw new IOException("Could not prepare a private APK copy.");
        boolean keep = false;
        try {
            String identity = RootFileProvider.identity(initial);
            java.security.MessageDigest checksum = ApkInspectionFacts.sha256();
            try (FileOutputStream out = new FileOutputStream(snapshot)) {
                byte[] buffer = new byte[128 * 1024];
                for (long position = 0; position < initial.st_size;) {
                    checkCancelled();
                    int count = Os.pread(source.getFileDescriptor(), buffer, 0,
                            (int) Math.min(buffer.length, initial.st_size - position), position);
                    if (count <= 0) throw changed();
                    out.write(buffer, 0, count);
                    checksum.update(buffer, 0, count);
                    position += count;
                }
            }
            if (!identity.equals(RootFileProvider.identity(Os.fstat(source.getFileDescriptor())))) throw changed();
            if (!snapshot.setReadOnly()) throw new IOException("Could not protect the private APK copy.");
            checkArchive(snapshot);
            checkCancelled();

            PackageManager pm = context.getPackageManager();
            PackageInfo info;
            try {
                info = pm.getPackageArchiveInfo(snapshot.getAbsolutePath(), PackageManager.GET_META_DATA);
            } catch (RuntimeException invalid) {
                throw unreadable();
            }
            if (info == null || info.applicationInfo == null || info.packageName == null) throw unreadable();
            ApplicationInfo application = info.applicationInfo;
            application.sourceDir = snapshot.getAbsolutePath();
            application.publicSourceDir = snapshot.getAbsolutePath();
            Resources resources = null;
            try {
                resources = pm.getResourcesForApplication(application);
            } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
                // Label and split attributes are best effort. Android still validates installation.
            }
            String label = cleanLabel(application.nonLocalizedLabel, info.packageName);
            if (resources != null && application.nonLocalizedLabel == null && application.labelRes != 0) {
                try {
                    label = cleanLabel(resources.getText(application.labelRes), info.packageName);
                } catch (RuntimeException ignored) {
                    // A package can still be inspected if its localized label cannot be loaded.
                }
            }
            checkCancelled();
            boolean splitRequired = (info.splitNames != null && info.splitNames.length != 0)
                    || (application.metaData != null && application.metaData.getBoolean("com.android.vending.splits.required"))
                    || manifestNeedsSplits(resources);
            long versionCode = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
            Details result = new Details(snapshot, label, info.packageName, cleanLabel(info.versionName, Long.toString(versionCode)),
                    application.minSdkVersion, application.targetSdkVersion, initial.st_size, splitRequired,
                    ApkInspectionFacts.hex(checksum.digest(),false));
            keep = true;
            return result;
        } finally {
            if (!keep) snapshot.delete();
        }
    }

    private static boolean manifestNeedsSplits(Resources resources) {
        if (resources == null) return false;
        try (XmlResourceParser xml = resources.getAssets().openXmlResourceParser("AndroidManifest.xml")) {
            while (xml.next() != XmlPullParser.END_DOCUMENT) {
                if (xml.getEventType() != XmlPullParser.START_TAG) continue;
                String name = xml.getName();
                if (xml.getDepth() == 1 && "manifest".equals(name)) {
                    if (hasText(xml.getAttributeValue(null, "split"))
                            || hasText(xml.getAttributeValue(ANDROID, "requiredSplitTypes"))
                            || xml.getAttributeBooleanValue(ANDROID, "isSplitRequired", false)) return true;
                }
                if (xml.getDepth() == 2 && ("uses-split".equals(name)
                        || ("application".equals(name)
                            && xml.getAttributeBooleanValue(ANDROID, "isSplitRequired", false)))) return true;
            }
        } catch (Exception ignored) {
            // Some Android/OEM versions do not expose this XML through public Resources.
        }
        return false;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String cleanLabel(CharSequence value, String fallback) {
        if (value == null) return fallback;
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < value.length() && text.length() < 120;) {
            int cp = Character.codePointAt(value, i);
            i += Character.charCount(cp);
            if (!Character.isISOControl(cp) && Character.getType(cp) != Character.FORMAT) text.appendCodePoint(cp);
        }
        String result = text.toString().trim();
        return result.isEmpty() ? fallback : result;
    }

    // APK signature schemes and Android's package parser use regular (not multi-disk/ZIP64)
    // ZIP packaging. Check the directory size before the platform or ZipFile maps it.
    private static void checkArchive(File snapshot) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(snapshot, "r")) {
            int count = (int) Math.min(file.length(), 65_557L);
            byte[] tail = new byte[count];
            file.seek(file.length() - count);
            file.readFully(tail);
            boolean found = false;
            for (int i = count - 22; i >= 0; i--) {
                if (little(tail, i, 4) != 0x06054b50L || i + 22 + little(tail, i + 20, 2) != count) continue;
                long entries = little(tail, i + 10, 2), directory = little(tail, i + 12, 4);
                long offset = little(tail, i + 16, 4);
                if (entries == 0 || entries >= 0xffff || little(tail, i + 4, 4) != 0
                        || little(tail, i + 8, 2) != entries || directory > MAX_DIRECTORY_BYTES
                        || directory + offset != file.length() - count + i) throw unreadable();
                found = true;
                break;
            }
            if (!found) throw unreadable();
        }
        try (ZipFile zip = new ZipFile(snapshot)) {
            ZipEntry manifest = zip.getEntry("AndroidManifest.xml");
            ZipEntry resources = zip.getEntry("resources.arsc");
            if (manifest == null || manifest.getSize() <= 0 || manifest.getSize() > MAX_MANIFEST_BYTES
                    || (resources != null && (resources.getSize() < 0 || resources.getSize() > MAX_RESOURCES_BYTES)))
                throw unreadable();
        } catch (java.util.zip.ZipException invalid) {
            throw unreadable();
        }
    }

    private static long little(byte[] bytes, int start, int length) {
        long result = 0;
        for (int i = length - 1; i >= 0; i--) result = (result << 8) | (bytes[start + i] & 255);
        return result;
    }

    private static IOException unreadable() {
        return new IOException("This file isn’t a complete APK or can’t be read by Android. For a split app, select a standalone APK.");
    }

    private static IOException changed() {
        return new IOException("The APK changed while it was being read. Reopen it and try again.");
    }

    private static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
    }

    static boolean expired(File snapshot) {
        long now = System.currentTimeMillis();
        long modified = snapshot.lastModified();
        return modified <= 0 || modified > now + CACHE_LIFETIME_MILLIS || now - modified >= CACHE_LIFETIME_MILLIS;
    }

    private static void prune(File directory) {
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) {
            if (validToken(file.getName()) && expired(file)) file.delete();
        }
    }

    static boolean validToken(String name) {
        if (name == null || name.length() != 40 || !name.endsWith(".apk")) return false;
        String token = name.substring(0, 36);
        try { return UUID.fromString(token).toString().equals(token); }
        catch (IllegalArgumentException bad) { return false; }
    }

    static final class Details implements Closeable {
        final String label, packageName, version, fileSha256;
        final int minSdk, targetSdk;
        final long size;
        final boolean splitRequired;
        private final File snapshot;
        private boolean handedOff;
        private boolean closed;

        Details(File snapshot, String label, String packageName, String version, int minSdk,
                int targetSdk, long size, boolean splitRequired, String fileSha256) {
            this.snapshot = snapshot;
            this.label = label;
            this.packageName = packageName;
            this.version = version;
            this.minSdk = minSdk;
            this.targetSdk = targetSdk;
            this.size = size;
            this.splitRequired = splitRequired;
            this.fileSha256 = fileSha256;
        }

        /** A separately requested, worker-only report against the same snapshot. */
        ApkInspection.Report inspectMore(Context context) throws IOException {
            try (ParcelFileDescriptor.AutoCloseInputStream input=
                         new ParcelFileDescriptor.AutoCloseInputStream(openContents())) {
                return ApkInspection.read(context,this,snapshot,input.getChannel());
            }
        }

        boolean canInstall() {
            return installUnavailableReason() == null;
        }

        String installUnavailableReason() {
            if (splitRequired) return "This APK needs additional split files. Use a standalone APK.";
            if (minSdk > Build.VERSION.SDK_INT) return "This APK needs Android API " + minSdk + " or later.";
            if (!supported()) return "Install from Anything is not available in the Play edition.";
            return null;
        }

        /** Package browsing uses the same private read-only snapshot that supplied the metadata. */
        synchronized ParcelFileDescriptor openContents() throws IOException {
            if (closed || !snapshot.isFile() || expired(snapshot))
                throw new IOException("The APK preview expired. Reopen it to browse the package.");
            return ParcelFileDescriptor.open(snapshot, ParcelFileDescriptor.MODE_READ_ONLY);
        }

        /** Called by an explicit click; never opts out of Android verification or confirmation. */
        synchronized void install(Activity activity) {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            String unavailable = installUnavailableReason();
            if (unavailable != null) {
                toast(activity, unavailable);
                return;
            }
            if (closed || !snapshot.isFile() || expired(snapshot)) {
                toast(activity, "The APK preview expired. Reopen it to install.");
                return;
            }
            Uri uri = new Uri.Builder().scheme("content").authority(BuildConfig.APPLICATION_ID + ".apk")
                    .appendPath(snapshot.getName()).build();
            try {
                Intent intent = installerIntent(activity, uri);
                if (intent == null) {
                    toast(activity, "Android’s package installer is unavailable.");
                    return;
                }
                // No persisted URI permission. The system installer retains its short-lived read
                // grant while the confirmation UI (and any user-enabled settings page) is open.
                activity.startActivity(intent);
                if (!handedOff) {
                    handedOff = true;
                    Context application = activity.getApplicationContext();
                    File staged = snapshot;
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        if (expired(staged)) {
                            application.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            staged.delete();
                        }
                    }, Math.max(0, CACHE_LIFETIME_MILLIS - (System.currentTimeMillis() - staged.lastModified())));
                }
            } catch (ActivityNotFoundException | SecurityException unavailableInstaller) {
                toast(activity, "Android could not open the installer. Check your device’s installation settings.");
            }
        }

        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            if (!handedOff) snapshot.delete();
        }
    }

    // Keep the URI off generic Open with handlers. Only a system APK handler already
    // holding Android's privileged installation permission may receive this read grant.
    static Intent installerIntent(Context context, Uri uri) {
        Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri("APK", uri));
        PackageManager packages = context.getPackageManager();
        for (ResolveInfo candidate : packages.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)) {
            if (candidate.activityInfo != null && candidate.activityInfo.exported
                    && candidate.activityInfo.applicationInfo != null
                    && (candidate.activityInfo.applicationInfo.flags
                        & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
                    && packages.checkPermission(android.Manifest.permission.INSTALL_PACKAGES,
                            candidate.activityInfo.packageName) == PackageManager.PERMISSION_GRANTED) {
                return intent.setClassName(candidate.activityInfo.packageName, candidate.activityInfo.name);
            }
        }
        return null;
    }

    private static void toast(Context context, String message) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show();
    }
}
