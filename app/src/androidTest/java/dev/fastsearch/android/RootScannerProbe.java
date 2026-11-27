package dev.fastsearch.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import dev.fastsearch.core.FileEntry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

/** Initial scanner coverage, including Android 10's find without -printf. Never opens the app index. */
public final class RootScannerProbe extends Instrumentation {
    private Context target;
    private Activity foreground;
    private File fixture, markers;
    private String executable, launcher;
    private int assertions;
    private boolean privileged,liveOnly;
    private final StringBuilder report = new StringBuilder();

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        privileged = arguments != null && "true".equals(arguments.getString("root"));
        liveOnly = arguments != null && "live".equals(arguments.getString("phase"));
        start();
    }

    @Override public void onStart() {
        boolean passed = false;
        try {
            target = getTargetContext();
            foreground = startActivitySync(new Intent(target, ProbeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            if(liveOnly) liveWatch(); else exercise();
            passed = true;
        } catch (Throwable failure) {
            java.io.StringWriter trace = new java.io.StringWriter();
            failure.printStackTrace(new java.io.PrintWriter(trace));
            report.append(trace);
        } finally {
            try {
                if (fixture != null) delete(fixture);
                if (markers != null) delete(markers);
                if (foreground != null) runOnMainSync(() -> foreground.finish());
            } catch (Exception failure) { passed = false; report.append("Cleanup: ").append(failure); }
        }
        Bundle result = new Bundle();
        result.putBoolean("passed", passed);
        result.putInt("assertions", assertions);
        result.putString("stream", report.append(passed ? "PASS " : "FAIL ").append(assertions)
                .append(" initial native scanner assertions.\n").toString());
        finish(passed ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private void exercise() throws Exception {
        String token = "RootScanner-" + SystemClock.elapsedRealtimeNanos();
        fixture = new File(target.getCacheDir(), token).getCanonicalFile();
        markers = new File(target.getCacheDir(), token + "-markers");
        check(fixture.mkdir() && markers.mkdir(), "create isolated fixtures");
        executable = new File(target.getApplicationInfo().nativeLibraryDir, "librootindex.so").getPath();
        launcher = privileged ? IndexRootScanner.suBinary() : "/system/bin/sh";
        check(new File(executable).canExecute(), "packaged native scanner is executable");
        String[] names = {"plain.txt", "with space.txt", "apostrophe'quote.txt", "tab\there.txt",
                "line\nbreak.txt", "日本語-é.txt", "literal%_[]*.txt", "$(touch BAD).txt", ".hidden"};
        for (String name : names) {
            File file = new File(fixture, name);
            try (FileOutputStream output = new FileOutputStream(file)) { output.write(new byte[]{1,2,3,4,5}); }
            check(file.setLastModified(1700000000123L), "set subsecond fixture timestamp");
        }
        File nested = new File(fixture, "nested'[*] directory");
        check(nested.mkdir(), "create literal nested directory");
        File privateChild = new File(nested, "child.txt");
        try (FileOutputStream output = new FileOutputStream(privateChild)) { output.write(9); }
        Os.symlink(fixture.getPath(), new File(fixture, "loop").getPath());
        Os.mkfifo(new File(fixture, "pipe").getPath(), 0600);
        if (privileged) command("chown 0:0 " + IndexRootScanner.quote(nested.getPath())
                + " && chmod 0700 " + IndexRootScanner.quote(nested.getPath()));
        try {
            Map<String, FileEntry> found = new HashMap<>();
            IndexRootScanner.Result result = scanner(executable).scan((path, directory, size, modified) ->
                    found.put(path, new FileEntry(path, directory, size, modified)), fixture.getPath());
            check(found.size() == names.length + 3, "exact regular-file and directory count");
            for (String name : names) {
                FileEntry entry = found.get(new File(fixture, name).getPath());
                check(entry != null && !entry.directory && entry.size == 5, "literal filename and size");
                check(entry.modified == 1700000000123L, "millisecond timestamp survives native stream");
            }
            check(found.containsKey(privateChild.getPath()), "nested/protected child discovered");
            check(found.get(fixture.getPath()).directory && found.get(nested.getPath()).directory, "folders included");
            check(NativeDiscovery.availableForProbe(), "packaged incremental discovery library is loaded");
            check(found.get(fixture.getPath()).modified == NativeDiscovery.modified(
                            java.util.Collections.singletonList(fixture.getPath()))[0],
                    "initial folder precision matches incremental native stamps");
            check(!found.containsKey(new File(fixture, "loop").getPath()), "symlink loop excluded");
            check(!found.containsKey(new File(fixture, "pipe").getPath()), "FIFO excluded without opening");
            check(!new File(fixture, "BAD").exists(), "filename does not execute shell substitution");
            check(result.accessErrors == 0 && result.otherErrors == 0, "bounded scan has no traversal errors");
            check(markers.list().length == 0, "successful scan cleans markers");
        } finally {
            if (privileged) command("chown " + Os.getuid() + ":" + Os.getgid() + " "
                    + IndexRootScanner.quote(nested.getPath()) + " && chmod 0700 " + IndexRootScanner.quote(nested.getPath()));
        }

        File mapped = new File(fixture, "parallel-map");
        check(mapped.mkdir(), "create isolated map fixture");
        long expectedBytes = 0;
        for (int n = 1; n <= 64; n++) {
            File leaf = new File(mapped, "child-" + n);
            if (!leaf.mkdir()) throw new IOException("Could not create map folder.");
            try (FileOutputStream output = new FileOutputStream(new File(leaf, "visible.txt"))) {
                output.write(new byte[n]);
            }
            try (FileOutputStream output = new FileOutputStream(new File(leaf, ".hidden"))) {
                output.write(1);
            }
            expectedBytes += n;
        }
        for (boolean showHidden : new boolean[]{true, false}) {
            Map<String, Long> sizes = new HashMap<>();
            IndexRootScanner.Result result = scanner(executable).scanMap((path, directory, size, modified) ->
                    sizes.put(path, size), fixture.getPath(), showHidden);
            check(sizes.get(mapped.getPath()) == expectedBytes + (showHidden ? 64 : 0),
                    "native parallel map preserves exact nested totals and hidden setting");
            check(!sizes.containsKey(new File(mapped, "child-1").getPath()), "packed map streams immediate tiles only");
            check(result.accessErrors == 0 && result.otherErrors == 0, "bounded map completed without traversal errors");
        }
        check(markers.list().length == 0, "map sessions clean markers");

        // A command failure after a valid session must never ask for another root grant.
        assertTraversalFailure(scanner(executable + ".missing"), fixture.getPath());
        assertTraversalFailure(scanner(executable), new File(fixture, "does-not-exist").getPath());
        IndexRootScanner denied = new IndexRootScanner(markers, executable, "/system/bin/false");
        try { denied.scan((p,d,s,m) -> {}, fixture.getPath()); throw new AssertionError("denied session succeeded"); }
        catch (IOException expected) {
            check(expected.getMessage().startsWith("Root access failed."), "denied session keeps root guidance");
            check(expected.getMessage().contains("root manager"), "denied session has actionable message");
        }
        IndexRootScanner cancelled = scanner(executable);
        cancelled.cancel();
        try { cancelled.scan((p,d,s,m) -> { throw new AssertionError("entry after cancel"); }, fixture.getPath());
            throw new AssertionError("cancelled scan succeeded"); }
        catch (CancellationException expected) { check(true, "cancel before process launch"); }
        check(markers.list().length == 0, "failed/cancelled scans clean markers");

        File many = new File(fixture, "cancellation");
        check(many.mkdir(), "create cancellation tree");
        for (int i = 0; i < 6000; i++) {
            if (!new File(many, "long-filename-for-full-pipe-" + i).createNewFile())
                throw new AssertionError("cancellation file");
        }
        IndexRootScanner live = scanner(executable);
        Field processField = IndexRootScanner.class.getDeclaredField("process");
        Field pidField = IndexRootScanner.class.getDeclaredField("rootPid");
        processField.setAccessible(true); pidField.setAccessible(true);
        Process[] child = new Process[1]; long[] pid = new long[1]; boolean[] alive = new boolean[1];
        long started = SystemClock.elapsedRealtime();
        try {
            live.scan((p,d,s,m) -> {
                try {
                    child[0] = (Process) processField.get(live); pid[0] = pidField.getLong(live);
                    alive[0] = child[0].isAlive();
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
                live.cancel();
            }, many.getPath());
            throw new AssertionError("live cancel succeeded");
        } catch (CancellationException expected) { check(true, "live scan cancellation delivered"); }
        check(alive[0] && pid[0] > 1, "cancel interrupted a live helper");
        check(child[0] != null && !child[0].isAlive(), "scanner client reaped");
        Process checkPid = new ProcessBuilder(launcher, "-c", "kill -0 " + pid[0] + " 2>/dev/null").start();
        check(checkPid.waitFor(5, TimeUnit.SECONDS) && checkPid.exitValue() != 0, "no orphan helper");
        check(SystemClock.elapsedRealtime() - started < 8000, "cancellation is bounded");
        check(markers.list().length == 0, "live cancellation cleans markers");

        Process find = new ProcessBuilder("/system/bin/find", fixture.getPath(), "-maxdepth", "0", "-printf", "").start();
        check(find.waitFor(5, TimeUnit.SECONDS), "bounded platform-find compatibility observation");
        report.append("API ").append(android.os.Build.VERSION.SDK_INT).append("; privileged=").append(privileged)
                .append("; system find -printf exit=").append(find.exitValue())
                .append("; native initial scan passed independently.\n");
    }

    private void liveWatch() throws Exception {
        String token="RootScannerLive-"+SystemClock.elapsedRealtimeNanos();
        fixture=new File(target.getCacheDir(),token).getCanonicalFile();
        markers=new File(target.getCacheDir(),token+"-markers");
        check(fixture.mkdir()&&markers.mkdir(),"create owned foreground watcher fixture");
        executable=new File(target.getApplicationInfo().nativeLibraryDir,"librootindex.so").getPath();
        launcher=privileged?IndexRootScanner.suBinary():"/system/bin/sh";
        File nested=new File(fixture,"selected child");
        check(nested.mkdir(),"owned map candidate");
        File changed=new File(nested,"synthetic 🧪.txt");
        IndexRootScanner monitor=scanner(executable);
        java.util.concurrent.FutureTask<IndexRootScanner.LiveChanges> events=
                new java.util.concurrent.FutureTask<>(()->monitor.watch(fixture.getPath(),java.util.Collections.singletonList(nested.getPath())));
        Thread watch=new Thread(events,"Root-live-probe");
        watch.start();
        long begin=SystemClock.elapsedRealtime();
        try {
            while(!events.isDone()&&SystemClock.elapsedRealtime()-begin<5500){
                try(FileOutputStream out=new FileOutputStream(changed)){out.write(123);}
                SystemClock.sleep(75);
            }
            IndexRootScanner.LiveChanges result=events.get(3,TimeUnit.SECONDS);
            check(result.changed()&&result.paths.contains(changed.getPath())&&!result.overflow,
                    "watch bridge delivers exact direct event path through packed protocol");
            android.system.StructStat expected=Os.lstat(fixture.getPath());
            check(result.device==expected.st_dev&&result.inode==expected.st_ino,
                    "watch scope sends its actual device and inode");
            try(android.os.ParcelFileDescriptor directory=NativeDiscovery.pin(-1,fixture.getPath())){
                long[] metadata=NativeDiscovery.pinnedMetadata(directory.getFd(),"selected child/"+changed.getName());
                check(metadata[0]==0&&metadata[2]==1,"native anchored metadata can read only owned fixture");
                File link=new File(fixture,"scope-link");
                Os.symlink(nested.getPath(),link.getPath());
                check(NativeDiscovery.pinnedMetadata(directory.getFd(),"scope-link/"+changed.getName())[0]<0,
                        "component-wise pinned lookup refuses ancestor symlink");
                Map<String,FileEntry> shown=new HashMap<>();
                scanner(executable).listPinned((path,folder,size,modified)->shown.put(path,
                        new FileEntry(path,folder,size,modified)),fixture.getPath(),result);
                check(shown.containsKey(nested.getPath())&&!shown.containsKey(link.getPath()),
                        "automatic Files redraw cannot follow aliases");
            }
            check(markers.list().length==0,"watch exit removes active markers");
        }finally{if(!events.isDone()){monitor.cancel();watch.join(4000);}}

        IndexRootScanner cancelled=scanner(executable);
        java.util.concurrent.FutureTask<IndexRootScanner.LiveChanges> waiting=
                new java.util.concurrent.FutureTask<>(()->cancelled.watch(fixture.getPath(),java.util.Collections.emptyList()));
        Thread blocked=new Thread(waiting,"Root-live-cancel-probe");
        blocked.start();
        try{
            SystemClock.sleep(500);long stopping=SystemClock.elapsedRealtime();
            cancelled.cancel();
            try{waiting.get(5,TimeUnit.SECONDS);throw new AssertionError("Cancellation did not reach the foreground watcher");}
            catch(java.util.concurrent.ExecutionException expected){check(expected.getCause() instanceof CancellationException,
                    "foreground loss cancellation tears down a blocked privileged watch");}
            check(SystemClock.elapsedRealtime()-stopping<5000,"foreground watch cancellation bounded");
            check(markers.list().length==0,"cancelled watch removes markers");
        }finally{if(!waiting.isDone()){cancelled.cancel();blocked.join(4000);}}
        check(!IndexRootScanner.liveWatchScope("/")&&!IndexRootScanner.liveWatchScope("/proc/self"),
                "automation cannot opt into whole-root or virtual filesystem watches");
        report.append("Native event bridge and cancellation checked on owned folder fixture; root launcher=")
                .append(privileged).append('\n');
    }

    private IndexRootScanner scanner(String path) throws IOException { return new IndexRootScanner(markers, path, launcher); }
    private void assertTraversalFailure(IndexRootScanner scanner, String path) throws Exception {
        try { scanner.scan((p,d,s,m) -> {}, path); throw new AssertionError("invalid traversal succeeded"); }
        catch (IOException expected) {
            check(expected.getMessage().startsWith("Root indexing failed."), "granted session reports scanner failure");
            check(!expected.getMessage().contains("root manager"), "no misleading root grant instruction");
        }
        check(markers.list().length == 0, "failed traversal cleans markers");
    }
    private void command(String text) throws Exception {
        Process child = new ProcessBuilder(launcher, "-c", text).start();
        check(child.waitFor(10, TimeUnit.SECONDS) && child.exitValue() == 0, "bounded protected fixture setup/cleanup");
    }
    private void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private static void delete(File path) throws Exception {
        if (OsConstants.S_ISDIR(Os.lstat(path.getPath()).st_mode)) {
            File[] children = path.listFiles();
            if (children != null) for (File child : children) delete(child);
        }
        if (!path.delete()) throw new IOException("Could not remove owned scanner fixture.");
    }
}
