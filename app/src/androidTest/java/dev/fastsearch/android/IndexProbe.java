package dev.fastsearch.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;

import dev.fastsearch.core.FileEntry;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Dependency-free, bounded backend instrumentation. It never calls the production singleton. */
public final class IndexProbe extends Instrumentation {
    private final List<FileIndex> opened = new ArrayList<>();
    private final AtomicBoolean wrongListenerThread = new AtomicBoolean();
    private final StringBuilder report = new StringBuilder();
    private Context target;
    private File fixture;
    private String id;
    private int assertions;
    private int stages;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        boolean passed = false;
        try {
            target = getTargetContext();
            runChecks();
            passed = true;
        } catch (Throwable failure) {
            StringWriter stack = new StringWriter();
            failure.printStackTrace(new PrintWriter(stack));
            report.append("FAIL ").append(stack);
        } finally {
            try { cleanup(); } catch (Throwable failure) {
                passed = false;
                report.append("FAIL cleanup: ").append(failure).append('\n');
            }
        }
        report.append(passed ? "PASS " : "FAIL ").append(assertions)
                .append(" Android backend assertions, ").append(stages).append(" stages.\n");
        result.putString("stream", report.toString());
        result.putInt("assertions", assertions);
        result.putBoolean("passed", passed);
        finish(passed ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private void runChecks() throws Exception {
        id = "IndexProbe" + SystemClock.elapsedRealtimeNanos();
        fixture = new File(target.getCacheDir(), "IndexProbe-" + SystemClock.elapsedRealtimeNanos());
        check(fixture.mkdir(), "create synthetic fixture");
        fixture = fixture.getCanonicalFile();
        String[] names = {"plain.txt", "with space.txt", "apostrophe'quote.txt", "tab\there.txt",
                "line\nbreak.txt", "日本語-é.txt", "literal%_[]*.txt", "$(touch BAD).txt"};
        for (String name : names) write(new File(fixture, name), 5);
        File nested = new File(fixture, "nested directory");
        check(nested.mkdir(), "create nested folder");
        write(new File(nested, "nested.txt"), 2);
        File locked = new File(fixture, "root-private");
        check(locked.mkdir(), "create root-private fixture");
        write(new File(locked, "root-only.txt"), 4);
        root("chown 0:0 " + q(locked) + " && chmod 0700 " + q(locked)
                + " && ln -s " + q(fixture) + " " + q(new File(fixture, "loop"))
                + " && /system/bin/mkfifo " + q(new File(fixture, "pipe")));
        stage("Synthetic fixture ready; only this app-cache tree will be traversed.");

        FileIndex index = open();
        index.setRootEnabled(true);
        check(index.isRootEnabled(), "probe defaults to root mode");
        check(index.roots().size() == 1 && index.roots().get(0).equals(fixture.getCanonicalPath()), "bounded roots only");
        index.load();
        await(index, state -> state.loaded && !state.scanning, "initial empty load");
        check(index.getState().entries.isEmpty(), "new isolated cache is empty");
        scan(index);
        FileIndex.State first = index.getState();
        check(first.entries.size() == 13, "root index includes exactly regular fixture files and folders");
        for (String name : names) {
            FileEntry entry = find(first, new File(fixture, name));
            check(entry != null && !entry.directory && entry.size == 5 && entry.modified > 0, "exact metadata for special filename");
        }
        check(find(first, new File(locked, "root-only.txt")) != null, "root indexes protected synthetic child");
        check(find(first, new File(fixture, "loop")) == null, "symlink loop excluded");
        check(find(first, new File(fixture, "pipe")) == null, "FIFO excluded");
        check(first.watchCount == 0 && first.status.contains("Root updates require refresh"), "root monitoring gap is explicit");
        check(first.deniedDirectories == 0 && first.scanDurationMillis > 0, "root scan metrics");
        boolean immutable = false;
        try { first.entries.clear(); } catch (UnsupportedOperationException expected) { immutable = true; }
        check(immutable, "published root snapshot is immutable");
        for (int i = 1; i < first.entries.size(); i++) {
            FileEntry a = first.entries.get(i - 1), b = first.entries.get(i);
            int order = a.name.compareToIgnoreCase(b.name);
            if (order == 0) order = a.path().compareToIgnoreCase(b.path());
            check(order <= 0, "published entries are sorted for the UI");
        }
        stage("Root scan: special filenames, protected metadata, excluded special files and immutable sorted snapshot passed.");

        File rootNew = new File(fixture, "root-new.txt");
        write(rootNew, 6);
        SystemClock.sleep(700);
        check(find(index.getState(), rootNew) == null, "root mode does not claim unprivileged live updates");
        scan(index);
        check(find(index.getState(), rootNew) != null, "manual root refresh reconciles new file");
        FileIndex.State completed = index.getState();
        File cancelledNew = new File(fixture, "cancelled-new.txt");
        write(cancelledNew, 7);
        CountDownLatch transaction = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        index.setTransactionProbe(() -> {
            transaction.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Rollback probe timed out.");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            }
        });
        index.startScan();
        try {
            check(transaction.await(20, TimeUnit.SECONDS), "scan reaches pending SQLite transaction");
            check(index.getState().scanning && index.getState().entries == completed.entries, "old snapshot stays searchable during transaction");
            index.cancelScan();
        } finally { release.countDown(); }
        await(index, state -> !state.scanning && state.status.contains("stopped"), "cancelled scan completion");
        check(index.getState().entries == completed.entries && find(index.getState(), cancelledNew) == null,
                "cancel retains the completed in-memory snapshot");
        check(index.getState().lastScanMillis == completed.lastScanMillis, "cancel preserves completion timestamp");
        index.setTransactionProbe(null);
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(target.getDatabasePath(id + "-root-filenames.db").getPath(), null, SQLiteDatabase.OPEN_READONLY);
             Cursor row = db.rawQuery("SELECT COUNT(*) FROM entries WHERE path=?", new String[]{cancelledNew.getAbsolutePath()})) {
            check(row.moveToFirst() && row.getInt(0) == 0, "cancel rolled back SQLite replacement");
        }
        close(index);
        index = open();
        index.load();
        await(index, state -> state.loaded && !state.scanning, "root cache reload");
        sameEntries(completed.entries, index.getState().entries);
        check(index.getState().lastScanMillis == completed.lastScanMillis, "root SQLite metadata survives new backend instance");
        stage("Real SQLite cancellation rollback and fresh-instance root cache reload passed.");

        index.setRootEnabled(false);
        await(index, state -> state.loaded && !state.scanning && state.lastScanMillis == 0 && state.entries.isEmpty(), "separate empty shared cache");
        scan(index);
        check(find(index.getState(), cancelledNew) != null, "shared scan sees accessible file omitted from cancelled root scan");
        check(find(index.getState(), new File(locked, "root-only.txt")) == null, "shared scan cannot inspect protected child");
        check(index.getState().deniedDirectories >= 1, "shared scan reports denied synthetic folder");
        check(index.getState().watchCount >= 2, "shared readable folders request observers");
        check(index.getState().status.contains("does not confirm watch registration"), "observer availability qualification is visible");
        stage("Shared traversal and denied-folder accounting passed; root and shared caches are separate.");

        List<FileEntry> beforeLive = index.getState().entries;
        File live = new File(nested, "live.tmp");
        write(live, 3);
        await(index, state -> size(state, live) == 3, "observer create");
        check(!contains(beforeLive, live), "published previous snapshot does not change after live create");
        write(live, 19);
        await(index, state -> size(state, live) == 19, "observer metadata update");
        File renamed = new File(nested, "renamed & %.tmp");
        check(live.renameTo(renamed), "rename synthetic file");
        await(index, state -> find(state, live) == null && size(state, renamed) == 19, "observer rename");
        check(renamed.delete(), "delete synthetic file");
        await(index, state -> find(state, renamed) == null, "observer delete");
        File tree = new File(fixture, "live-tree");
        check(tree.mkdir(), "create synthetic live subtree");
        File child = new File(tree, "child%_[].dat");
        write(child, 11);
        await(index, state -> size(state, child) == 11, "observer subtree create");
        File movedTree = new File(fixture, "renamed-tree");
        check(tree.renameTo(movedTree), "rename synthetic live subtree");
        File movedChild = new File(movedTree, child.getName());
        await(index, state -> find(state, child) == null && size(state, movedChild) == 11, "observer subtree rename");
        check(movedChild.delete() && movedTree.delete(), "delete synthetic live subtree");
        await(index, state -> find(state, movedChild) == null && find(state, movedTree) == null, "observer subtree delete");
        check(!index.getState().scanning, "observer updates do not launch full scans");
        stage("Shared observer create, size update, file rename/delete and subtree rename/delete passed.");

        File upper = new File(fixture, "Case%_[]");
        File lower = new File(fixture, "case%_[]");
        check(upper.mkdir() && lower.mkdir(), "create case-distinct literal-pattern folders");
        File upperChild = new File(upper, "item.dat"), lowerChild = new File(lower, "item.dat");
        write(upperChild, 2);
        write(lowerChild, 3);
        await(index, state -> size(state, upperChild) == 2 && size(state, lowerChild) == 3, "case-sensitive live siblings");
        check(upperChild.delete() && upper.delete(), "delete uppercase subtree only");
        await(index, state -> find(state, upperChild) == null && size(state, lowerChild) == 3, "binary subtree removal preserves lowercase sibling");
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(target.getDatabasePath(id + "-shared-filenames.db").getPath(), null, SQLiteDatabase.OPEN_READONLY);
             Cursor row = db.rawQuery("SELECT COUNT(*) FROM entries WHERE path=?", new String[]{lowerChild.getAbsolutePath()})) {
            check(row.moveToFirst() && row.getInt(0) == 1, "SQLite deletion preserves case-distinct literal-pattern sibling");
        }
        stage("SQLite live subtree deletion preserves case and literal %, _, [ and ] names.");

        File bulk = new File(fixture, "watch-limit");
        check(bulk.mkdir(), "create bounded watch-limit fixture");
        for (int i = 0; i < 2060; i++) {
            if (!new File(bulk, "folder-" + i).mkdir()) throw new AssertionError("Create watch-limit directory " + i);
        }
        scan(index);
        check(index.getState().totalDirectories > 2048 && index.getState().watchCount <= 2048, "watch requests obey fixed limit");
        check(index.getState().status.contains("outside watch coverage"), "partial monitoring coverage is explicit");
        List<FileEntry> sharedCompleted = index.getState().entries;
        close(index);
        index = open();
        check(!index.isRootEnabled(), "isolated mode preference survives backend restart");
        index.load();
        await(index, state -> state.loaded && !state.scanning, "shared SQLite reload");
        sameEntries(sharedCompleted, index.getState().entries);
        long sharedScanMillis = index.getState().lastScanMillis;
        index.setRootEnabled(true);
        await(index, state -> !state.scanning && find(state, new File(locked, "root-only.txt")) != null
                && find(state, cancelledNew) == null, "original root cache restored");
        check(index.getState().watchCount == 0, "switching to root stops all shared observers");
        check(!wrongListenerThread.get(), "all listeners delivered on Android main thread");
        stage("Watch limit, shared SQLite reload, isolated mode preference and main-thread listener delivery passed.");

        // Keep the service's scanning contract continuous across an active scope change.
        CountDownLatch switchingTransaction = new CountDownLatch(1);
        CountDownLatch releaseSwitch = new CountDownLatch(1);
        AtomicBoolean switching = new AtomicBoolean();
        AtomicBoolean completionGap = new AtomicBoolean();
        FileIndex switchingIndex = index;
        index.addListener(state -> {
            if (switching.get() && !state.scanning && state.lastScanMillis <= sharedScanMillis) completionGap.set(true);
        });
        index.setTransactionProbe(() -> {
            switchingTransaction.countDown();
            try {
                if (!releaseSwitch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Scope-change probe timed out.");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            }
        });
        index.startScan();
        try {
            check(switchingTransaction.await(20, TimeUnit.SECONDS), "active root scan pauses before scope change");
            runOnMainSync(() -> switching.set(true));
            index.setRootEnabled(false);
        } finally { releaseSwitch.countDown(); }
        await(index, state -> !state.scanning && state.lastScanMillis > sharedScanMillis
                && find(state, new File(bulk, "folder-0")) != null, "scope change cancels then scans shared fixture");
        runOnMainSync(() -> switching.set(false));
        check(!completionGap.get(), "scope change has no false completion before replacement scan");
        check(!switchingIndex.isRootEnabled(), "active scope change finishes in shared mode");
        switchingIndex.setTransactionProbe(null);
        stage("Active mode switch keeps scanning true through cancellation, cache load and replacement scan.");
    }

    private FileIndex open() throws IOException {
        FileIndex index = FileIndex.forProbe(target, fixture, id);
        opened.add(index);
        index.addListener(state -> {
            if (Looper.myLooper() != Looper.getMainLooper()) wrongListenerThread.set(true);
        });
        return index;
    }

    private void close(FileIndex index) throws InterruptedException {
        index.shutdown();
        check(index.awaitShutdown(10_000), "backend worker and SQLite shut down");
    }

    private interface Condition { boolean matches(FileIndex.State state); }

    private void await(FileIndex index, Condition condition, String description) {
        long deadline = SystemClock.elapsedRealtime() + 25_000;
        while (SystemClock.elapsedRealtime() < deadline) {
            FileIndex.State state = index.getState();
            if (condition.matches(state)) return;
            if (!state.scanning && !state.error.isEmpty()) throw new AssertionError(description + ": " + state.error);
            SystemClock.sleep(25);
        }
        throw new AssertionError("Timeout: " + description + "; " + index.getState().status);
    }

    private void scan(FileIndex index) {
        long previous = index.getState().lastScanMillis;
        index.startScan();
        await(index, state -> !state.scanning && state.lastScanMillis > previous, "complete bounded scan");
        check(index.getState().error.isEmpty(), "bounded scan completes without error");
    }

    private static FileEntry find(FileIndex.State state, File file) { return entry(state.entries, file); }
    private static boolean contains(List<FileEntry> entries, File file) { return entry(entries, file) != null; }
    private static FileEntry entry(List<FileEntry> entries, File file) {
        String path = file.getAbsolutePath();
        for (FileEntry entry : entries) if (entry.path().equals(path)) return entry;
        return null;
    }
    private static long size(FileIndex.State state, File file) {
        FileEntry entry = find(state, file);
        return entry == null ? -1 : entry.size;
    }

    private void sameEntries(List<FileEntry> expected, List<FileEntry> actual) {
        check(expected.size() == actual.size(), "reloaded snapshot count matches");
        for (int i = 0; i < expected.size(); i++) {
            FileEntry a = expected.get(i), b = actual.get(i);
            if (!a.path().equals(b.path()) || a.directory != b.directory || a.size != b.size || a.modified != b.modified) {
                throw new AssertionError("Reloaded metadata differs at record " + i);
            }
        }
        check(true, "all reloaded path/type/size/time records match");
    }

    private void check(boolean condition, String description) {
        assertions++;
        if (!condition) throw new AssertionError(description);
    }

    private void stage(String text) {
        stages++;
        report.append("Stage ").append(stages).append(": ").append(text).append('\n');
        Bundle status = new Bundle();
        status.putString("stream", text + "\n");
        sendStatus(0, status);
    }

    private static void write(File file, int size) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(new byte[size]); }
    }
    private static String q(File file) { return IndexRootScanner.quote(file.getAbsolutePath()); }

    private static void root(String command) throws Exception {
        Process process = new ProcessBuilder(IndexRootScanner.suBinary(), "-c", command).redirectErrorStream(true).start();
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroy();
            throw new IOException("Synthetic root fixture operation timed out. Check root-manager access for this app.");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream input = process.getInputStream()) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1 && bytes.size() < 8192) bytes.write(buffer, 0, count);
        }
        if (process.exitValue() != 0) {
            throw new IOException("Synthetic root fixture operation failed (exit " + process.exitValue() + "): "
                    + new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    private void cleanup() throws Exception {
        for (FileIndex index : opened) {
            index.shutdown();
            if (!index.awaitShutdown(12_000)) throw new IOException("Probe worker did not stop.");
        }
        if (fixture != null && fixture.getName().startsWith("IndexProbe-")
                && fixture.getCanonicalPath().startsWith(target.getCacheDir().getCanonicalPath() + "/")) {
            root("rm -rf " + q(fixture));
        }
        if (target != null && id != null) {
            target.deleteDatabase(id + "-root-filenames.db");
            target.deleteDatabase(id + "-shared-filenames.db");
            target.deleteSharedPreferences(id + "-filename-index");
        }
    }
}
