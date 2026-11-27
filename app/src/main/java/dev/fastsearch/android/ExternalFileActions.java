package dev.fastsearch.android;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.webkit.MimeTypeMap;
import java.util.ArrayList;
import java.util.Locale;

/** Single route for handing one explicitly selected file to another app. */
final class ExternalFileActions {
    private ExternalFileActions() {}

    static String mimeFor(String name) {
        OfficePreview.Format office = OfficePreview.format(name);
        if (office != null) return OfficePreview.mime(office);
        CsvPreview.Format table=CsvPreview.format(name);
        if(table!=null)return table.mime;
        int dot = name == null ? -1 : name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        switch (ext) {
            case "apk": return ApkInstallAction.MIME;
            case "7z": return "application/x-7z-compressed";
            case "rar": return "application/vnd.rar";
            case "tar": return "application/x-tar";
            case "gz": case "tgz": return "application/gzip";
            case "xz": case "txz": return "application/x-xz";
            case "bz2": case "tbz": case "tbz2": return "application/x-bzip2";
            case "zst": case "zstd": return "application/zstd";
            case "md": case "log": case "ini": return "text/plain";
            case "mkv": return "video/x-matroska";
            default:
                String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                return type == null ? (CodeSyntax.known(name) ? "text/plain" : "application/octet-stream") : type;
        }
    }

    static Intent target(Uri file, String label, String mime, boolean share) {
        if (file == null || !"content".equals(file.getScheme())) {
            throw new IllegalArgumentException("A content URI is required");
        }
        Intent target = share
                ? new Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, file)
                : new Intent(Intent.ACTION_VIEW).setDataAndType(file, mime);
        target.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        target.setClipData(ClipData.newRawUri(label, file));
        return target;
    }

    /**
     * The chooser itself can resolve on an empty app list. Check the actual VIEW
     * target first using the manifest's narrowly scoped content VIEW query.
     */
    static Intent chooser(Activity activity, Uri file, String label, boolean share) {
        String mime = mimeFor(label);
        Intent target = target(file, label, mime, share);
        ArrayList<ComponentName> excluded = new ArrayList<>();
        if (!share) {
            PackageManager pm = activity.getPackageManager();
            boolean found = false;
            for (ResolveInfo candidate : pm.queryIntentActivities(target, PackageManager.MATCH_DEFAULT_ONLY)) {
                ActivityInfo info = candidate.activityInfo;
                if (info == null || info.packageName == null || info.name == null) continue;
                boolean installer = ApkInstallAction.MIME.equals(mime)
                        && pm.checkPermission(android.Manifest.permission.INSTALL_PACKAGES, info.packageName)
                           == PackageManager.PERMISSION_GRANTED;
                if (installer || activity.getPackageName().equals(info.packageName) || !info.exported
                        || (info.permission != null && activity.checkSelfPermission(info.permission)
                            != PackageManager.PERMISSION_GRANTED)) {
                    excluded.add(new ComponentName(info.packageName, info.name));
                } else {
                    found = true;
                }
            }
            if (!found) return null;
        }
        Intent result = Intent.createChooser(target, share ? "Share file" : "Open with");
        if (!excluded.isEmpty()) {
            result.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, excluded.toArray(new ComponentName[0]));
        }
        return result;
    }

    /** Null means the chooser opened; a readable string is returned on failure. */
    static String launch(Activity activity, Uri file, String label, boolean share) {
        try {
            Intent chooser = chooser(activity, file, label, share);
            if (chooser != null) {
                activity.startActivity(chooser);
                return null;
            }
        } catch (IllegalArgumentException | SecurityException denied) {
            return "Could not give another app access to this file. Reopen the file and try again.";
        } catch (ActivityNotFoundException unavailable) {
            // The last matching app may have been removed after the query.
        }
        if (share) return "No installed app can share this file.";
        if (ApkInstallAction.MIME.equals(mimeFor(label))) {
            return ApkInstallAction.supported()
                    ? "No installed app can open APKs for inspection. To install this APK, open its preview and choose Install APK."
                    : "No installed app can open APKs for inspection.";
        }
        return "No installed app can open this file type.";
    }
}
