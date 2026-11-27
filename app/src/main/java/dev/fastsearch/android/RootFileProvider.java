package dev.fastsearch.android;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.system.Os;
import android.system.StructStat;
import dev.fastsearch.core.FileEntry;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.UUID;
import org.json.JSONObject;

/** Read-only grants survive process death and stay bound to the selected file's identity. */
public final class RootFileProvider extends ContentProvider {
    private static final String AUTHORITY = BuildConfig.APPLICATION_ID + ".files";
    private static final long GRANT_LIFETIME = 7L * 24 * 60 * 60 * 1000;

    private static SharedPreferences grants(Context context) {
        return context.getSharedPreferences("file-grants", Context.MODE_PRIVATE);
    }

    /** Called on a worker only after an explicit Open or Share action. */
    static Uri issue(Context context, FileEntry entry, boolean allowRoot) throws Exception {
        return issue(context,entry,allowRoot,null);
    }
    static String identity(StructStat stat) {
        if(android.os.Build.VERSION.SDK_INT>=27)return stat.st_dev+":"+stat.st_ino+":"+stat.st_size+":"+stat.st_mtim.tv_sec+":"+stat.st_mtim.tv_nsec
                +":"+stat.st_ctim.tv_sec+":"+stat.st_ctim.tv_nsec;
        return stat.st_dev+":"+stat.st_ino+":"+stat.st_size+":"+stat.st_mtime+":"+stat.st_ctime;
    }
    static Uri issue(Context context, FileEntry entry, boolean allowRoot, String expectedIdentity) throws Exception {
        if (entry.directory) throw new FileNotFoundException("Select a file.");
        try (ParcelFileDescriptor fd = SafeFileAccess.open(context, entry.path(), allowRoot)) {
            StructStat stat = Os.fstat(fd.getFileDescriptor());
            if(expectedIdentity!=null&&!expectedIdentity.equals(identity(stat)))throw new java.io.IOException("File changed. Open it again from results.");
            long now = System.currentTimeMillis();
            JSONObject record = new JSONObject().put("path", entry.path()).put("name", entry.name)
                    .put("size", stat.st_size).put("device", stat.st_dev).put("inode", stat.st_ino)
                    .put("root", allowRoot).put("expires", now + GRANT_LIFETIME);
            return save(context, record, entry.name, now);
        }
    }

    static Uri issueArchiveEntry(Context context, Uri archiveUri, String path, boolean root, String expectedIdentity,
                                 ZipDirectory.Entry entry) throws Exception {
        if (entry.directory || entry.encrypted || (entry.method != 0 && entry.method != 8))
            throw new IOException("This ZIP entry cannot be shared.");
        if (entry.size > ArchiveEntryFile.MAX_BYTES) throw new IOException("ZIP entries over 32 MiB exceed the sharing limit.");
        if (archiveUri != null) {
            JSONObject source = record(context, archiveUri);
            path = source.getString("path");
            root = source.getBoolean("root");
        }
        try (ParcelFileDescriptor fd = SafeFileAccess.open(context, path, root)) {
            StructStat stat = Os.fstat(fd.getFileDescriptor());
            if (!expectedIdentity.equals(identity(stat))) throw new IOException("The archive changed. Reopen it to share its entries.");
            String name = entry.name.substring(entry.name.lastIndexOf('/') + 1);
            long now = System.currentTimeMillis();
            JSONObject record = new JSONObject().put("path", path).put("name", name).put("size", entry.size)
                    .put("root", root).put("identity", expectedIdentity).put("expires", now + GRANT_LIFETIME)
                    .put("offset", entry.offset).put("boundary", entry.boundary).put("compressed", entry.compressedSize)
                    .put("method", entry.method).put("flags", entry.flags);
            return save(context, record, name, now);
        }
    }

    private static Uri save(Context context, JSONObject record, String name, long now) throws Exception {
        String token = UUID.randomUUID().toString();
        SharedPreferences prefs = grants(context);
        synchronized (RootFileProvider.class) {
            SharedPreferences.Editor edit = prefs.edit();
            for (Map.Entry<String, ?> item : prefs.getAll().entrySet()) {
                try {
                    if (new JSONObject(String.valueOf(item.getValue())).optLong("expires") < now) edit.remove(item.getKey());
                } catch (Exception invalid) { edit.remove(item.getKey()); }
            }
            if (!edit.putString(token, record.toString()).commit()) throw new IOException("Could not save file access.");
        }
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(token).appendPath(name).build();
    }

    @Override public boolean onCreate() { return true; }

    private static JSONObject record(Context context, Uri uri) throws FileNotFoundException {
        try {
            if (!"content".equals(uri.getScheme()) || !AUTHORITY.equals(uri.getAuthority())
                    || uri.getPathSegments().size() != 2) throw new Exception();
            String token = uri.getPathSegments().get(0);
            UUID.fromString(token);
            String saved = grants(context).getString(token, null);
            if (saved == null) throw new Exception();
            JSONObject value = new JSONObject(saved);
            if (value.getLong("expires") < System.currentTimeMillis()
                    || !value.getString("name").equals(uri.getLastPathSegment())) throw new Exception();
            return value;
        } catch (Exception invalid) { throw new FileNotFoundException("File access expired. Open or share the file again."); }
    }

    @Override public String getType(Uri uri) {
        return ExternalFileActions.mimeFor(uri.getLastPathSegment());
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        try {
            JSONObject value = record(getContext(), uri);
            String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
            MatrixCursor cursor = new MatrixCursor(columns);
            Object[] values = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) values[i] = value.getString("name");
                if (OpenableColumns.SIZE.equals(columns[i])) values[i] = value.getLong("size");
            }
            cursor.addRow(values);
            return cursor;
        } catch (Exception invalid) { return null; }
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only file access.");
        if (ArchivePreviewSources.matches(uri)) return ArchivePreviewSources.open(uri);
        JSONObject value = record(getContext(), uri);
        ParcelFileDescriptor fd = null;
        try {
            if (value.has("offset")) return openArchiveEntry(value);
            fd = SafeFileAccess.open(getContext(), value.getString("path"), value.getBoolean("root"));
            StructStat stat = Os.fstat(fd.getFileDescriptor());
            if (stat.st_dev != value.getLong("device") || stat.st_ino != value.getLong("inode")) {
                throw new java.io.IOException("File was replaced. Open or share it again.");
            }
            return fd;
        } catch (Exception failure) {
            if (fd != null) try { fd.close(); } catch (Exception ignored) { }
            throw new FileNotFoundException(failure.getMessage() == null ? "File cannot be opened." : failure.getMessage());
        }
    }

    private ParcelFileDescriptor openArchiveEntry(JSONObject value) throws Exception {
        ZipDirectory.Entry entry = new ZipDirectory.Entry(value.getString("name"), value.getLong("size"),
                value.getLong("compressed"), false, value.getInt("flags"), value.getInt("method"),
                value.getLong("offset"), value.getLong("boundary"));
        try (ParcelFileDescriptor fd = SafeFileAccess.open(getContext(), value.getString("path"), value.getBoolean("root"));
             ParcelFileDescriptor.AutoCloseInputStream archive =
                     new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(fd.getFileDescriptor()))) {
            String expected = value.getString("identity");
            if (!expected.equals(identity(Os.fstat(fd.getFileDescriptor())))) throw new IOException("The shared archive changed.");
            try (InputStream input = ZipDirectory.open(archive.getChannel(), entry)) {
                ParcelFileDescriptor result = ArchiveEntryFile.copy(getContext(), input, entry.size);
                boolean valid = false;
                try {
                    if (!expected.equals(identity(Os.fstat(fd.getFileDescriptor())))) throw new IOException("The shared archive changed.");
                    valid = true;
                    return result;
                } finally {
                    if (!valid) result.close();
                }
            }
        }
    }

    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException("Read-only"); }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read-only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException("Read-only"); }
}
