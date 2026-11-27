package dev.fastsearch.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Exercises inspection and the native intent without sending it or installing an APK. */
public final class ApkInstallProbe extends Instrumentation {
    private int assertions;

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Bundle output = new Bundle();
        boolean passed = false;
        try {
            exercise(getTargetContext());
            passed = true;
            output.putString("stream", "PASS " + assertions + " APK inspection and handoff assertions.\n");
        } catch (Throwable failure) {
            java.io.StringWriter trace = new java.io.StringWriter();
            failure.printStackTrace(new java.io.PrintWriter(trace));
            output.putString("stream", trace.toString());
        }
        output.putInt("assertions", assertions);
        output.putBoolean("passed", passed);
        finish(passed ? Activity.RESULT_OK : Activity.RESULT_CANCELED, output);
    }

    private void exercise(Context context) throws Exception {
        File own = new File(context.getApplicationInfo().sourceDir);
        File cache = new File(context.getCacheDir(), ApkInstallAction.CACHE_FOLDER);
        Set<String> previous = tokens(cache);
        Uri uri;

        try (ParcelFileDescriptor source = ParcelFileDescriptor.open(own, ParcelFileDescriptor.MODE_READ_ONLY)) {
            Os.lseek(source.getFileDescriptor(), 8, OsConstants.SEEK_SET);
            try (ApkInstallAction.Details details = ApkInstallAction.inspect(context, source)) {
                check(Os.lseek(source.getFileDescriptor(), 0, OsConstants.SEEK_CUR) == 8, "descriptor offset preserved");
                check(context.getPackageName().equals(details.packageName), "actual APK package identity");
                check(details.size == own.length() && !details.label.isEmpty()
                        && details.minSdk == context.getApplicationInfo().minSdkVersion, "APK metadata");
                check(!details.splitRequired, "test build is a standalone APK");
                ApkInspection.Report report=details.inspectMore(context);
                try(InputStream original=new FileInputStream(own)){
                    check(report.checksum.equals(ApkInspectionFacts.hex(digest(original),false))&&report.checksum.length()==64,
                            "one-pass inspection checksum covers every original APK byte");
                }
                check(report.permissions.contains("android.permission.MODIFY_AUDIO_SETTINGS"),
                        "inspection shows Android-declared permissions without requesting them");
                check(report.splits.startsWith("No split requirement was detected"),"inspection describes standalone split state");
                check(report.nativeABIs.contains("arm64-v8a"),"inspection reports the signed fixture’s native-library ABI");
                @SuppressWarnings("deprecation")
                int signFlags=android.os.Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;
                android.content.pm.PackageInfo installed=context.getPackageManager().getPackageInfo(context.getPackageName(),signFlags);
                @SuppressWarnings("deprecation")
                android.content.pm.Signature[] signers=android.os.Build.VERSION.SDK_INT>=28
                        ?installed.signingInfo.getApkContentsSigners():installed.signatures;
                check(signers.length>0&&report.certificates.contains(ApkInspectionFacts.hex(
                        MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray()),true)),
                        "reported fingerprint matches independently reported installed signer");

                check(details.canInstall() == ApkInstallAction.supported(), "install allowed only in supported flavors");
                Set<String> staged = tokens(cache);
                staged.removeAll(previous);
                check(staged.size() == 1, "one private snapshot");
                uri = new Uri.Builder().scheme("content").authority(context.getPackageName() + ".apk")
                        .appendPath(staged.iterator().next()).build();
                File stagedApk = new File(cache, uri.getLastPathSegment());
                try (InputStream original = new FileInputStream(own); InputStream copy = new FileInputStream(stagedApk)) {
                    check(Arrays.equals(digest(original), digest(copy)), "handoff snapshot matches the inspected APK");
                }
                if (ApkInstallAction.supported()) {
                    check(ApkInstallAction.MIME.equals(context.getContentResolver().getType(uri)), "exact APK MIME");
                    try (InputStream original = new FileInputStream(own);
                         InputStream handed = context.getContentResolver().openInputStream(uri)) {
                        check(handed != null && Arrays.equals(digest(original), digest(handed)), "provider serves only staged bytes");
                    }
                    checkRejected(context, uri, "w", "provider is read-only");
                    Uri unknown = uri.buildUpon().path("/00000000-0000-0000-0000-000000000000.apk").build();
                    checkRejected(context, unknown, "r", "unknown URI token rejected");
                    Intent intent = ApkInstallAction.installerIntent(context, uri);
                    check(intent != null && intent.getComponent() != null, "Android system installer resolved");
                    check(intent.getFlags() == Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    && ApkInstallAction.MIME.equals(intent.getType()) && uri.equals(intent.getData()),
                            "handoff carries one exact read-only URI");
                    check(context.getPackageManager().checkPermission(android.Manifest.permission.INSTALL_PACKAGES,
                            intent.getComponent().getPackageName()) == PackageManager.PERMISSION_GRANTED,
                            "handoff target holds privileged installer capability, not this app");
                }
            }
        }
        check(tokens(cache).equals(previous), "inspection snapshot removed on close without install");
        if (ApkInstallAction.supported()) checkRejected(context, uri, "r", "closed snapshot is no longer served");
        File invalid = File.createTempFile("not-an-package-", ".apk", context.getCacheDir());
        try {
            try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(invalid))) {
                out.putNextEntry(new ZipEntry("AndroidManifest.xml"));
                out.write("not an Android package".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            boolean rejected = false;
            try (ParcelFileDescriptor source = ParcelFileDescriptor.open(invalid, ParcelFileDescriptor.MODE_READ_ONLY);
                 ApkInstallAction.Details details = ApkInstallAction.inspect(context, source)) {
                throw new AssertionError("Non-package ZIP accepted as " + details.packageName);
            } catch (IOException expected) { rejected = true; }
            check(rejected, "malformed package is rejected with a read error");
            check(tokens(cache).equals(previous), "malformed package leaves no private copy");
        } finally {
            invalid.delete();
        }
    }

    private static Set<String> tokens(File directory) {
        String[] files = directory.list((dir, name) -> ApkInstallAction.validToken(name));
        return files == null ? new HashSet<>() : new HashSet<>(Arrays.asList(files));
    }

    private static byte[] digest(InputStream input) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        byte[] bytes = new byte[8192];
        for (int size; (size = input.read(bytes)) > 0;) hash.update(bytes, 0, size);
        return hash.digest();
    }

    private void checkRejected(Context context, Uri uri, String mode, String description) throws Exception {
        boolean rejected = false;
        try (ParcelFileDescriptor ignored = context.getContentResolver().openFileDescriptor(uri, mode)) {
            rejected = ignored == null;
        } catch (IOException | SecurityException expected) { rejected = true; }
        check(rejected, description);
    }

    private void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        assertions++;
    }
}
