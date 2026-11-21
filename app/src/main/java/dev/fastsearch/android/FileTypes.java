package dev.fastsearch.android;

import dev.fastsearch.core.FileEntry;
import java.util.Locale;

/** Shared presentation categories, independent of query-parser extension rules. */
final class FileTypes {
    private static final String[] ALL_LABELS = {"Anything", "Files", "Folders", "Audio", "Documents", "Executables", "Photos", "Videos", "Archives", "Apps", "Contacts", "SMS"};
    static final String[] LABELS = BuildConfig.SMS_ENABLED ? ALL_LABELS : java.util.Arrays.copyOf(ALL_LABELS, 11);
    static final int[] ICONS = {R.drawable.ic_all, R.drawable.ic_file, R.drawable.ic_folder,
            R.drawable.ic_audio, R.drawable.ic_document, R.drawable.ic_app,
            R.drawable.ic_photo, R.drawable.ic_video, R.drawable.ic_archive,
            R.drawable.ic_app, R.drawable.ic_person, R.drawable.ic_message};
    static final int[] COLORS = {0xFF1A2FFB, 0xFF64717D, 0xFFA96700, 0xFFAC3675,
            0xFF286699, 0xFF378248, 0xFF7453B6, 0xFF386AC5, 0xFF9C6830,
            0xFF378248, 0xFF416F92, 0xFF4368AD};
    private FileTypes() { }

    static int category(FileEntry entry) {
        if (entry.directory) return 2;
        int dot = entry.name.lastIndexOf('.');
        return dot < 0 ? 1 : categoryOfExtension(entry.name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static int categoryOfExtension(String extension) {
        switch (extension) {
            case "mp3": case "flac": case "m4a": case "aac": case "wav": case "ogg":
            case "opus": case "wma": case "mid": case "amr":
                return 3;
            case "pdf": case "txt": case "md": case "doc": case "docx": case "xls":
            case "xlsx": case "ppt": case "pptx": case "odt": case "ods": case "rtf":
            case "epub": case "csv": case "json": case "xml": case "html": case "css":
            case "js": case "java": case "kt": case "py": case "log": case "yaml":
            case "yml": case "ini":
                return 4;
            case "apk": case "apks": case "xapk": case "dex": case "so": case "sh":
            case "exe": case "msi": case "bat": case "cmd":
                return 5;
            case "jpg": case "jpeg": case "png": case "gif": case "webp": case "heic":
            case "heif": case "avif": case "bmp": case "svg": case "dng": case "raw":
                return 6;
            case "mp4": case "mkv": case "webm": case "avi": case "mov": case "m4v":
            case "3gp": case "ts":
                return 7;
            case "zip": case "rar": case "7z": case "tar": case "gz": case "bz2":
            case "xz": case "zst": case "tgz":
                return 8;
            default:
                return 1;
        }
    }

    static boolean matches(FileEntry entry, int category) {
        return category == 0 || (category == 1 ? !entry.directory : category(entry) == category);
    }

}
