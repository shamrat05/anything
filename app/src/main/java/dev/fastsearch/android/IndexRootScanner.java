package dev.fastsearch.android;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Metadata-only root traversal. Query text is never part of a shell command. */
final class IndexRootScanner {
    interface Sink {
        void accept(String path, boolean directory, long size, long modified);
    }

    static final class Result {
        final int accessErrors;
        final int otherErrors;
        final int excludedPaths;
        final String warning;

        Result(int accessErrors, int otherErrors, int excludedPaths, String warning) {
            this.accessErrors = accessErrors;
            this.otherErrors = otherErrors;
            this.excludedPaths = excludedPaths;
            this.warning = warning;
        }
    }

    private static final int MAX_FIELD_BYTES = 1024 * 1024;
    static final String OWNER_DATA = "/data/data";
    static final String OWNER_DATA_ALIAS = "/data/user/0";
    private static final String OWNER_DATA_ALIAS_MARKER = "owner-data-bind-alias";
    private static final List<String> BASE_EXCLUDES = Collections.unmodifiableList(Arrays.asList(
            "/proc", "/sys", "/dev", "/acct", "/config", "/data_mirror", "/storage",
            "/sdcard", "/mnt/runtime", "/mnt/user", "/mnt/installer",
            "/mnt/androidwritable", "/mnt/pass_through", "/mnt/secure", "/mnt/asec"));

    private final File activeFile;
    private final File pidFile;
    private final String executable;
    private final String launcher;
    private final boolean deviceNamespace;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean cancelThreadStarted = new AtomicBoolean();
    private volatile Process process;
    private volatile long rootPid;
    private volatile boolean finished;
    private boolean rootGranted;

    IndexRootScanner(File cacheDirectory, String executable) throws IOException {
        this(cacheDirectory, executable, suBinary(), true);
    }

    /** Package-private launcher override for bounded, unprivileged API compatibility fixtures. */
    IndexRootScanner(File cacheDirectory, String executable, String launcher) throws IOException {
        this(cacheDirectory, executable, launcher, false);
    }

    private IndexRootScanner(File cacheDirectory, String executable, String launcher, boolean deviceNamespace) throws IOException {
        this.executable = executable;
        this.launcher = launcher;
        this.deviceNamespace = deviceNamespace;
        activeFile = File.createTempFile("root-index-", ".active", cacheDirectory);
        pidFile = new File(activeFile.getAbsolutePath() + ".pid");
    }

    Result scan(Sink sink) throws IOException {
        return scan(sink, "/");
    }

    /** Bounded-fixture hook; normal indexing always calls scan(Sink) with /. */
    Result scan(Sink sink, String traversalRoot) throws IOException {
        return scan(sink, traversalRoot, "");
    }

    void listChildren(Sink sink, String directory) throws IOException {
        scan((path, folder, size, modified) -> {
            if (!path.equals(directory)) sink.accept(path, folder, size, modified);
        }, directory, "--children");
    }

    Result scanMap(Sink sink, String directory, boolean showHidden) throws IOException {
        if (belowAny(directory, Arrays.asList("/proc", "/sys", "/dev", "/acct", "/config"))) {
            throw new IOException("Storage maps are unavailable for virtual system folders.");
        }
        return scan(sink, directory, showHidden ? "--map" : "--map-visible");
    }

    private Result scan(Sink sink, String traversalRoot, String modeFlag) throws IOException {
        if (!traversalRoot.startsWith("/") || traversalRoot.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Traversal root must be an absolute path.");
        }
        boolean recordRootScan = deviceNamespace && modeFlag.isEmpty();
        try {
            checkCancelled();
            // The app and su may have different mount namespaces, so read root's view.
            boolean shallow = modeFlag.equals("--children");
            boolean map = modeFlag.startsWith("--map");
            List<String> excluded = shallow ? Collections.emptyList() : exclusions(readMounts());
            checkCancelled();
            StringBuilder command = new StringBuilder(handshake()).append("exec ")
                    .append(quote(executable)).append(' ').append(quote(traversalRoot));
            if (!modeFlag.isEmpty()) command.append(' ').append(modeFlag);
            String prefix=traversalRoot.endsWith("/")?traversalRoot:traversalRoot+"/";
            for (String path : excluded)
                if (!map||path.startsWith(prefix)) command.append(' ').append(quote(path));
            Process child = launch(command.toString());
            process = child;
            child.getOutputStream().close();
            ErrorReader errors = new ErrorReader(child.getErrorStream());
            Thread errorThread = new Thread(errors, "index-root-stderr");
            errorThread.setDaemon(true);
            errorThread.start();
            int records = 0;
            try (InputStream input = new BufferedInputStream(child.getInputStream(), 64 * 1024)) {
                readHandshake(input);
                FieldReader fields = new FieldReader(input);
                for (;;) {
                    checkCancelled();
                    String mode = fields.next(true);
                    if (mode == null) break;
                    String sizeText = fields.next(false);
                    String modifiedText = fields.next(false);
                    String path = fields.next(false);
                    if (mode.length() != 10 || path.isEmpty() || path.charAt(0) != '/') {
                        throw new IOException("Root scanner returned an invalid metadata record.");
                    }
                    long size;
                    long modified;
                    try {
                        size = Long.parseLong(sizeText);
                        modified = Long.parseLong(modifiedText);
                    } catch (NumberFormatException e) {
                        throw new IOException("Root scanner returned invalid size or time metadata.", e);
                    }
                    boolean directory = mode.charAt(0) == 'd';
                    // Never expose symlinks, FIFOs, sockets or device nodes as openable files.
                    if (!directory && mode.charAt(0) != '-') continue;
                    sink.accept(path, directory, directory && !map ? 0 : size, modified);
                    records++;
                }
            } catch (IOException failure) {
                checkCancelled();
                throw failure;
            }
            int exit = waitFor(child);
            join(errorThread);
            checkCancelled();
            if (records == 0) {
                throw new IOException(rootFailure(errors.text()));
            }
            if (shallow && exit != 0) throw new IOException("Could not read this folder. " + errors.text());
            if (exit > 1 || (exit != 0 && errors.accessErrors == 0 && errors.otherErrors == 0)) {
                throw new IOException("Root scanner failed (exit " + exit + "). " + errors.text());
            }
            String warning = errors.otherErrors > 0
                    ? errors.otherErrors + " traversal errors; refresh to reconcile." : "";
            if (recordRootScan) {
                SupportLog.record(SupportLog.Event.ROOT_SCAN_FINISHED, true, errors.accessErrors, errors.otherErrors);
            }
            return new Result(errors.accessErrors, errors.otherErrors, excluded.size(), warning);
        } catch (IOException failure) {
            checkCancelled();
            if (recordRootScan) {
                SupportLog.record(rootGranted ? SupportLog.Event.ROOT_SCAN_FAILED : SupportLog.Event.ROOT_NO_HANDSHAKE,
                        true, 0, 0);
            }
            String detail = failure.getMessage() == null ? "" : failure.getMessage();
            if (!detail.startsWith("Root access failed.") && !detail.startsWith("Root indexing failed.")) {
                throw new IOException(rootFailure(detail), failure);
            }
            throw failure;
        } finally {
            // A parse/IO failure must stop the helper, rather than leave a privileged orphan.
            Process child = process;
            if (child != null && child.isAlive()) terminate(child, rootPid > 1 ? rootPid : readPidFile());
            finished = true;
            process = null;
            activeFile.delete();
            pidFile.delete();
        }
    }

    /** Returns promptly; a short-lived helper interrupts any blocked pipe read. */
    void cancel() {
        cancelled.set(true);
        activeFile.delete();
        if (!cancelThreadStarted.compareAndSet(false, true)) return;
        Thread interrupter = new Thread(() -> {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            long pid = rootPid;
            while (!finished && pid <= 1 && System.nanoTime() < deadline) {
                pid = readPidFile();
                if (pid > 1) break;
                Process child = process;
                if (child != null) child.destroy();
                try { Thread.sleep(25); } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (!finished) terminate(process, pid);
        }, "index-root-cancel");
        interrupter.setDaemon(true);
        interrupter.start();
    }

    private String readMounts() throws IOException {
        Process child = launch(handshake()
                + "if [ " + OWNER_DATA + " -ef " + OWNER_DATA_ALIAS + " ]; then echo "
                + OWNER_DATA_ALIAS_MARKER + "; fi; exec /system/bin/cat /proc/self/mountinfo");
        process = child;
        child.getOutputStream().close();
        ErrorReader errors = new ErrorReader(child.getErrorStream());
        Thread errorThread = new Thread(errors, "index-mount-stderr");
        errorThread.setDaemon(true);
        errorThread.start();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream input = new BufferedInputStream(child.getInputStream())) {
            readHandshake(input);
            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) != -1) {
                checkCancelled();
                if (bytes.size() + length > 8 * 1024 * 1024) {
                    throw new IOException("Root mount table is unexpectedly large.");
                }
                bytes.write(buffer, 0, length);
            }
        } catch (IOException failure) {
            checkCancelled();
            throw new IOException(rootFailure(errors.text()), failure);
        }
        int exit = waitFor(child);
        join(errorThread);
        checkCancelled();
        if (exit != 0) throw new IOException(rootFailure(errors.text()));
        rootPid = 0;
        process = null;
        pidFile.delete();
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    private String handshake() {
        // The active marker prevents a delayed su grant from starting a cancelled scan.
        return "printf '%s\\n' \"$$\" > " + quote(pidFile.getAbsolutePath())
                + "; chmod 0644 " + quote(pidFile.getAbsolutePath())
                + "; [ -f " + quote(activeFile.getAbsolutePath()) + " ] || exit 125; "
                + "printf 'PID\\000%s\\000' \"$$\"; ";
    }

    private void readHandshake(InputStream input) throws IOException {
        String marker = readField(input, false);
        String pid = readField(input, false);
        try {
            long parsed = Long.parseLong(pid);
            if (!"PID".equals(marker) || parsed <= 1) throw new NumberFormatException();
            rootPid = parsed;
            rootGranted = true;
        } catch (NumberFormatException e) {
            throw new IOException(rootFailure("Invalid scanner process handshake."));
        }
        checkCancelled();
    }

    private long readPidFile() {
        try (InputStream input = new FileInputStream(pidFile)) {
            byte[] bytes = new byte[32];
            int length = input.read(bytes);
            if (length <= 0) return 0;
            return Long.parseLong(new String(bytes, 0, length, StandardCharsets.US_ASCII).trim());
        } catch (IOException | NumberFormatException ignored) {
            return 0;
        }
    }

    private void checkCancelled() {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Indexing cancelled.");
        }
    }

    private static int waitFor(Process child) throws IOException {
        try {
            return child.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Root scan interrupted.", e);
        }
    }

    private static void join(Thread thread) {
        try { thread.join(1500); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void terminate(Process child, long pid) {
        if (pid > 1) {
            try {
                // Only a parsed positive decimal PID is interpolated, never a path or query.
                Process killer = new ProcessBuilder(launcher, "-c", "kill -TERM " + pid).start();
                killer.waitFor(1500, TimeUnit.MILLISECONDS);
                if (killer.isAlive()) killer.destroyForcibly();
                if (child != null && !child.waitFor(500, TimeUnit.MILLISECONDS)) {
                    Process force = new ProcessBuilder(launcher, "-c", "kill -KILL " + pid).start();
                    force.waitFor(1500, TimeUnit.MILLISECONDS);
                    if (force.isAlive()) force.destroyForcibly();
                }
            } catch (IOException ignored) {
                // Closing the su client is still necessary if root is revoked mid-scan.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (child != null) {
            child.destroy();
            if (child.isAlive()) child.destroyForcibly();
        }
    }

    /** Decode contiguous NUL fields directly from each pipe read. */
    static final class FieldReader {
        private final InputStream input;
        private final byte[] buffer = new byte[64 * 1024];
        private int position;
        private int limit;

        FieldReader(InputStream input) {
            this.input = input;
        }

        String next(boolean eofAllowed) throws IOException {
            ByteArrayOutputStream split = null;
            for (;;) {
                if (position == limit) {
                    int count = input.read(buffer);
                    if (count < 0) {
                        if (eofAllowed && split == null) return null;
                        throw new IOException("Root scan ended before a metadata record was complete.");
                    }
                    if (count == 0) continue;
                    position = 0;
                    limit = count;
                }
                int start = position;
                while (position < limit && buffer[position] != 0) position++;
                int length = position - start;
                if (length + (split == null ? 0 : split.size()) > MAX_FIELD_BYTES)
                    throw new IOException("Root metadata field is too large.");
                boolean complete = position < limit;
                if (complete) position++;
                if (complete && split == null)
                    return new String(buffer, start, length, StandardCharsets.UTF_8);
                if (split == null) split = new ByteArrayOutputStream(length);
                split.write(buffer, start, length);
                if (complete) return new String(split.toByteArray(), StandardCharsets.UTF_8);
            }
        }
    }

    static String readField(InputStream input, boolean eofAllowed) throws IOException {
        ByteArrayOutputStream field = new ByteArrayOutputStream(128);
        for (;;) {
            int value = input.read();
            if (value == -1) {
                if (eofAllowed && field.size() == 0) return null;
                throw new IOException("Root scan ended before a metadata record was complete.");
            }
            if (value == 0) return new String(field.toByteArray(), StandardCharsets.UTF_8);
            if (field.size() >= MAX_FIELD_BYTES) throw new IOException("Root metadata field is too large.");
            field.write(value);
        }
    }

    static List<String> exclusions(String mountInfo) {
        Set<String> excluded = new LinkedHashSet<>(BASE_EXCLUDES);
        Map<String, List<String>> mounts = new HashMap<>();
        for (String line : mountInfo.split("\n")) {
            if (line.equals(OWNER_DATA_ALIAS_MARKER)) {
                excluded.add(OWNER_DATA_ALIAS);
                continue;
            }
            String[] parts = line.split(" ");
            if (parts.length < 6) continue;
            String path = unescapeMount(parts[4]);
            if (!path.startsWith("/") || belowAny(path, BASE_EXCLUDES)) continue;
            String key = parts[2] + "|" + parts[3];
            List<String> aliases = mounts.get(key);
            if (aliases == null) {
                aliases = new ArrayList<>();
                mounts.put(key, aliases);
            }
            if (!aliases.contains(path)) aliases.add(path);
        }
        Comparator<String> preferred = (a, b) -> {
            int order = Integer.compare(priority(a), priority(b));
            if (order == 0) order = Integer.compare(a.length(), b.length());
            return order != 0 ? order : a.compareTo(b);
        };
        for (List<String> aliases : mounts.values()) {
            aliases.sort(preferred);
            for (int i = 1; i < aliases.size(); i++) excluded.add(aliases.get(i));
        }
        List<String> result = new ArrayList<>(excluded);
        Collections.sort(result);
        return Collections.unmodifiableList(result);
    }

    private static int priority(String path) {
        if (path.equals("/") || path.equals("/data")) return 0;
        if (path.startsWith("/system") || path.startsWith("/vendor")
                || path.startsWith("/product") || path.startsWith("/odm")) return 1;
        if (path.startsWith("/apex/") && path.indexOf('@') < 0) return 2;
        if (path.startsWith("/mnt/media_rw") || path.startsWith("/mnt/expand")) return 3;
        return path.indexOf('@') >= 0 ? 5 : 4;
    }

    private static boolean belowAny(String path, List<String> roots) {
        for (String root : roots) if (path.equals(root) || path.startsWith(root + "/")) return true;
        return false;
    }

    static String unescapeMount(String value) {
        return value.replace("\\040", " ").replace("\\011", "\t")
                .replace("\\012", "\n").replace("\\134", "\\");
    }

    static String pruneExpression(List<String> paths) {
        StringBuilder command = new StringBuilder("\\( ");
        for (int i = 0; i < paths.size(); i++) {
            if (i > 0) command.append(" -o ");
            command.append("-path ").append(quote(findPattern(paths.get(i))));
        }
        return command.append(" \\) -prune -o").toString();
    }

    static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private Process launch(String command) throws IOException {
        return (deviceNamespace ? rootCommand(launcher, command)
                : new ProcessBuilder(launcher, "-c", command)).start();
    }

    static ProcessBuilder rootCommand(String command) {
        return rootCommand(suBinary(), command);
    }

    private static ProcessBuilder rootCommand(String launcher, String command) {
        // A root shell can inherit the app's isolated /data mounts. Enter the device view
        // after su grants access. Probe with true so a file operation is only ever launched once.
        String enter = "/system/bin/nsenter -t 1 -m -- ";
        String wrapper = "set -- /system/bin/sh -c " + quote(command)
                + "; if " + enter + "/system/bin/true </dev/null >/dev/null 2>&1; then exec "
                + enter + "\"$@\"; fi; exec \"$@\"";
        return new ProcessBuilder(launcher, "-c", wrapper);
    }

    static String suBinary() {
        // These are the locations searched by Termux's installed su wrapper too.
        String[] candidates = {"/debug_ramdisk/su", "/sbin/su", "/system/sbin/su", "/system/bin/su",
                "/system/xbin/su", "/su/bin/su", "/magisk/.core/bin/su"};
        for (String candidate : candidates) if (new File(candidate).canExecute()) return candidate;
        return "su";
    }

    private static String findPattern(String value) {
        // find -path interprets glob metacharacters even when the shell argument is quoted.
        return value.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]")
                .replace("*", "\\*").replace("?", "\\?");
    }

    private String rootFailure(String detail) {
        if (detail.startsWith("Root access failed.") || detail.startsWith("Root indexing failed.")) return detail;
        String message = rootGranted ? "Root indexing failed."
                : "Root access failed. Allow this app in your root manager, or choose shared storage only.";
        return detail.isEmpty() ? message : message + " " + detail;
    }

    private static final class ErrorReader implements Runnable {
        private final InputStream input;
        private final StringBuilder sample = new StringBuilder();
        volatile int accessErrors;
        volatile int otherErrors;

        ErrorReader(InputStream input) { this.input = input; }

        @Override public void run() {
            try (InputStream stream = input) {
                ByteArrayOutputStream line = new ByteArrayOutputStream();
                int value;
                while ((value = stream.read()) != -1) {
                    if (value == '\n') {
                        record(line);
                        line.reset();
                    } else if (line.size() < 8192) line.write(value);
                }
                if (line.size() > 0) record(line);
            } catch (IOException ignored) { }
        }

        private void record(ByteArrayOutputStream bytes) {
            String line = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            if (line.contains("Permission denied") || line.contains("Operation not permitted")) accessErrors++;
            else if (!line.trim().isEmpty()) otherErrors++;
            synchronized (sample) {
                if (sample.length() < 2048) {
                    if (sample.length() > 0) sample.append(' ');
                    sample.append(line, 0, Math.min(line.length(), 2048 - sample.length()));
                }
            }
        }

        String text() { synchronized (sample) { return sample.toString().trim(); } }
    }
}
