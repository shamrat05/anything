package dev.fastsearch.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;

import dev.fastsearch.core.FileEntry;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Synthetic icon fixture and same-thread paging predicates. Does not read contacts or SMS. */
public final class PeopleIconsProbe extends Instrumentation {
    private int checks;

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    @Override public void onStart() {
        Context context = getTargetContext();
        boolean passed = false;
        String detail;
        try {
            check(context.getPackageName().endsWith(".fresh"), "run on an isolated .fresh package");
            if (!BuildConfig.SMS_ENABLED) check(!DeviceSearch.hasSms(context), "standard flavor is gated");
            checkPaging();
            checkIcons(context);
            passed = true;
            detail = "PASS " + checks + " synthetic checks; no device messages or contacts queried.";
        } catch (Throwable failure) {
            detail = "FAIL: " + failure.getClass().getSimpleName() + ": " + failure.getMessage();
        }
        Bundle result = new Bundle();
        result.putBoolean("passed", passed);
        result.putInt("assertions", checks);
        result.putString("stream", detail + "\n");
        finish(passed ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private void checkPaging() {
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            db.execSQL("CREATE TABLE sms(_id INTEGER PRIMARY KEY, thread_id INTEGER, date INTEGER)");
            db.execSQL("INSERT INTO sms VALUES(1,7,100),(2,7,100),(3,7,100),(4,7,200),(5,8,120),(6,8,100),(7,7,20)");
            check(ids(db, DeviceDetails.OLDER_BY_KEY, "date DESC, _id DESC").equals(Arrays.asList(1L, 7L)),
                    "older page handles duplicate dates and excludes other threads");
            check(ids(db, DeviceDetails.NEWER_BY_KEY, "date ASC, _id ASC").equals(Arrays.asList(3L, 4L)),
                    "newer page handles duplicate dates and excludes other threads");
        }
    }

    private List<Long> ids(SQLiteDatabase db, String predicate, String order) {
        List<Long> ids = new ArrayList<>();
        try (Cursor cursor = db.query("sms", new String[]{"_id"}, "thread_id=? AND " + predicate,
                new String[]{"7", "100", "100", "2"}, null, null, order)) {
            while (cursor.moveToNext()) ids.add(cursor.getLong(0));
        }
        return ids;
    }

    private void checkIcons(Context context) throws Exception {
        File source = new File(context.getApplicationInfo().sourceDir);
        File fixture = File.createTempFile("test-self-icon-", ".apk", context.getCacheDir()).getCanonicalFile();
        try {
            Files.copy(source.toPath(), fixture.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            FileEntry entry = new FileEntry(fixture.getPath(), false, fixture.length(), fixture.lastModified());
            check(ArchiveAppIcon.supports(entry), "plain APK has an archive icon candidate");
            Bitmap bitmap = ArchiveAppIcon.load(context, entry, 64, false, new CancellationSignal());
            try {
                check(bitmap != null && bitmap.getWidth() == 64 && bitmap.getHeight() == 64,
                        "self APK resources yield a bounded icon");
            } finally { if (bitmap != null) bitmap.recycle(); }
            check(!ArchiveAppIcon.supports(new FileEntry(fixture.getPath() + "s", false, fixture.length(), 0)),
                    "split-container file falls back to a generic icon");
            check(!ArchiveAppIcon.supports(new FileEntry(fixture.getPath(), false, 97L << 20, 0)),
                    "oversized archive falls back to a generic icon");
            CancellationSignal canceled = new CancellationSignal(); canceled.cancel();
            boolean stopped = false;
            try { ArchiveAppIcon.load(context, entry, 64, false, canceled); }
            catch (OperationCanceledException expected) { stopped = true; }
            check(stopped, "canceled icon request does not start");
            check(fixture.isFile(), "the source archive is not consumed or moved");
        } finally { fixture.delete(); }
    }

    private void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
