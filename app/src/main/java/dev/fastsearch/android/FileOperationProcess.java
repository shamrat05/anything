package dev.fastsearch.android;

import android.content.Context;
import android.os.SystemClock;
import android.system.Os;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Owns one isolated helper, including delayed su grants and its whole child process group. */
final class FileOperationProcess implements Closeable {
    interface Progress { void onProgress(int completed, int total, JSONArray indices); default void onDelta(JSONObject delta)throws Exception{} }
    private static final int MAX_LINE = 2 * 1024 * 1024;
    private final File directory;
    private final File marker;
    private final File pidFile;
    private final File requestFile;
    private final String nonce = UUID.randomUUID().toString();
    private final boolean root;
    private final String apk;
    private final Progress progress;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean interrupting = new AtomicBoolean();
    private volatile Process child;
    private volatile boolean finished;

    FileOperationProcess(Context context, JSONObject request, boolean root, Progress progress) throws Exception {
        this.root = root; this.progress = progress;
        apk = context.getApplicationInfo().sourceDir;
        directory = new File(context.getCacheDir(), "file-operation-" + nonce);
        Os.mkdir(directory.getPath(), 0700);
        marker = new File(directory, "active");
        pidFile = new File(directory, "pid");
        requestFile = new File(directory, "request.json");
        try {
            write(marker, nonce);
            write(pidFile, "");
            request.put("active", marker.getPath()).put("pidFile", pidFile.getPath()).put("nonce", nonce)
                    .put("ownerPid", Os.getpid()).put("ownerStart", FileOperationWorker.processStart(Os.getpid()))
                    .put("protectedData", new File(context.getApplicationInfo().dataDir).getCanonicalPath()).put("protectedApk", apk)
                    .put("deadline", SystemClock.elapsedRealtime() + TimeUnit.MINUTES.toMillis(30));
            if (("paste".equals(request.optString("mode")) && request.optBoolean("move")) || "rename".equals(request.optString("mode"))) {
                String nativeDirectory = context.getApplicationInfo().nativeLibraryDir;
                if (nativeDirectory == null) throw new IOException("The atomic move library is unavailable. The source was retained.");
                // ApplicationInfo supplies this path; selection/destination text never does.
                request.put("nativeMoveLibrary", new File(nativeDirectory, "libfileops.so").getCanonicalPath());
            }
            if(root&&"reconcile".equals(request.optString("mode"))&&context.getApplicationInfo().nativeLibraryDir!=null)
                request.put("nativeDiscoveryLibrary",new File(context.getApplicationInfo().nativeLibraryDir,"libdiscovery.so").getCanonicalPath());
            write(requestFile, request.toString());
        } catch (Exception failure) { close(); throw failure; }
    }

    private static void write(File file, String text) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(text.getBytes(StandardCharsets.UTF_8)); }
        Os.chmod(file.getPath(), 0600);
    }

    JSONObject run() throws Exception {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new IOException("File operation stopped.");
        ProcessBuilder builder;
        if (root) {
            // The marker is checked before exec so a late root grant cannot revive a cancelled job.
            String command = "[ -f " + IndexRootScanner.quote(marker.getPath()) + " ] || exit 125; "
                    + "unset LD_PRELOAD LD_LIBRARY_PATH; export CLASSPATH=" + IndexRootScanner.quote(apk)
                    + "; exec /system/bin/app_process /system/bin dev.fastsearch.android.FileOperationWorker "
                    + IndexRootScanner.quote(requestFile.getPath());
            builder = IndexRootScanner.rootCommand(command);
        } else {
            builder = new ProcessBuilder("/system/bin/app_process", "/system/bin", "dev.fastsearch.android.FileOperationWorker", requestFile.getPath());
            Map<String, String> env = builder.environment();
            env.remove("LD_PRELOAD"); env.remove("LD_LIBRARY_PATH"); env.put("CLASSPATH", apk);
        }
        Process process = builder.start();
        child = process;
        if (cancelled.get() || Thread.currentThread().isInterrupted()) cancel();
        process.getOutputStream().close();
        Thread drain = new Thread(() -> {
            try (InputStream errors = process.getErrorStream()) {
                byte[] buffer = new byte[1024];
                while (errors.read(buffer) != -1) { }
            } catch (IOException ignored) { }
        }, "operation-errors");
        drain.setDaemon(true); drain.start();
        Thread ownerThread = Thread.currentThread();
        Thread watchdog = new Thread(() -> {
            long deadline = SystemClock.elapsedRealtime() + TimeUnit.MINUTES.toMillis(30);
            while (!finished && process.isAlive()) {
                if (cancelled.get() || ownerThread.isInterrupted() || SystemClock.elapsedRealtime() >= deadline) { cancel(); return; }
                try { Thread.sleep(100); } catch (InterruptedException ignored) { return; }
            }
        }, "operation-watchdog");
        watchdog.setDaemon(true); watchdog.start();
        JSONObject result = null;
        try (InputStream input = process.getInputStream()) {
            for (;;) {
                String line = readLine(input);
                if (line == null) break;
                JSONObject message = new JSONObject(line);
                if ("delta".equals(message.optString("event"))&&progress!=null)progress.onDelta(message);
                else if ("progress".equals(message.optString("event")) && progress != null) {
                    progress.onProgress(message.optInt("completed"), message.optInt("total"), message.optJSONArray("completedIndices"));
                } else if ("done".equals(message.optString("event"))) result = message;
            }
            if (!process.waitFor(5, TimeUnit.SECONDS)) throw new IOException("The file operation worker did not exit.");
            if (result == null) throw new IOException(cancelled.get() ? "File operation stopped." : "Could not start file operations. Check file access or the root grant.");
            return result;
        } finally {
            finished = true;
            watchdog.interrupt();
            if (process.isAlive()) terminate(process);
            closeStreams(process);
            try { drain.join(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
    }

    void cancel() {
        cancelled.set(true);
        marker.delete();
        Process process = child;
        // A later run() assignment retries cancellation; do not consume its guard
        // while a root-grant wrapper has not yet been created.
        if (process == null) return;
        if (!interrupting.compareAndSet(false, true)) return;
        Thread stop = new Thread(() -> {
            try { if (process.waitFor(1500, TimeUnit.MILLISECONDS)) return; }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            if (process.isAlive()) terminate(process);
        }, "operation-cancel");
        stop.setDaemon(true); stop.start();
    }

    private void terminate(Process process) {
        try {
            JSONObject pid = new JSONObject(readSmall(pidFile, 2048));
            int value = pid.getInt("pid");
            if (value > 1 && nonce.equals(pid.getString("nonce"))
                    && pid.getString("start").equals(FileOperationWorker.processStart(value))) {
                // The worker creates its own process group before recording this identity.
                if (root) {
                    Process killer = new ProcessBuilder(IndexRootScanner.suBinary(), "-c", "/system/bin/toybox kill -TERM -- -" + value).start();
                    killer.getOutputStream().close();
                    if (!killer.waitFor(1500, TimeUnit.MILLISECONDS)) killer.destroyForcibly();
                    closeStreams(killer);
                } else Os.kill(-value, android.system.OsConstants.SIGTERM);
            }
        } catch (Exception ignored) { }
        process.destroy();
        try { if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly(); }
        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        closeStreams(process);
    }

    private static void closeStreams(Process process) {
        try { process.getInputStream().close(); } catch (IOException ignored) { }
        try { process.getErrorStream().close(); } catch (IOException ignored) { }
        try { process.getOutputStream().close(); } catch (IOException ignored) { }
    }

    static String readSmall(File file, int limit) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] bytes = new byte[4096]; int count;
            while ((count = in.read(bytes)) != -1) {
                if (out.size() + count > limit) throw new IOException("Operation data exceeded its limit.");
                out.write(bytes, 0, count);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int next;
        while ((next = in.read()) != -1 && next != '\n') {
            if (out.size() >= MAX_LINE) throw new IOException("Invalid operation response.");
            out.write(next);
        }
        return next == -1 && out.size() == 0 ? null : new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    @Override public void close() {
        marker.delete();
        Process process = child;
        if (process != null && process.isAlive()) terminate(process);
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) file.delete();
        directory.delete();
    }
}
