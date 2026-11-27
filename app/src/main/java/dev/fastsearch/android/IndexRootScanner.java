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
    static final int LIVE_WATCH_LIMIT = 16;
    private static final int LIVE_EVENT_LIMIT = 32;
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

    static boolean mapAvailable(String directory) {
        return !belowAny(directory,Arrays.asList("/proc","/sys","/dev","/acct","/config"));
    }

    Result scanMap(Sink sink, String directory, boolean showHidden) throws IOException {
        if (!mapAvailable(directory)) {
            activeFile.delete();
            pidFile.delete();
            throw new IOException("Storage maps are unavailable for virtual system folders.");
        }
        return scan(sink, directory, showHidden ? "--map" : "--map-visible");
    }

    void listPinned(Sink sink,String scope,LiveChanges identity)throws IOException {
        scan(sink,scope,"--children-pinned",Collections.emptyList(),identity,null);
    }
    Result scanPinnedMap(Sink sink,String scope,boolean showHidden,LiveChanges identity)throws IOException {
        return scan(sink,scope,showHidden?"--map-pinned":"--map-visible-pinned",
                Collections.emptyList(),identity,null);
    }

    static boolean liveWatchScope(String scope) {
        if (scope == null || !scope.startsWith("/") || scope.length() < 2 || scope.endsWith("/")
                || scope.indexOf('\0') >= 0 || scope.getBytes(StandardCharsets.UTF_8).length > 4095
                || belowAny(scope, BASE_EXCLUDES)) return false;
        for(String part:scope.substring(1).split("/",-1))
            if(part.isEmpty()||part.equals(".")||part.equals(".."))return false;
        return true;
    }

    static final class LiveChanges {
        final List<String> paths;
        final boolean overflow;
        final long device,inode;
        LiveChanges(List<String> paths, boolean overflow,long device,long inode) {
            this.paths = paths;
            this.overflow = overflow;
            this.device=device;
            this.inode=inode;
        }
        boolean changed() { return overflow || !paths.isEmpty(); }
    }

    LiveChanges watch(String scope,List<String> directories)throws IOException{
        return watch(scope,directories,null);
    }
    LiveChanges watch(String scope, List<String> directories,Runnable ready) throws IOException {
        try {
            if (!liveWatchScope(scope)) throw new IOException("Not a direct watchable Root folder.");
            List<String> selected = new ArrayList<>();
            for (String directory : directories) {
                if (selected.size() >= LIVE_WATCH_LIMIT - 1) break;
                if (liveWatchScope(directory) && new File(directory).getParent().equals(scope)) selected.add(directory);
            }
            Set<String> watched = new LinkedHashSet<>(selected);
            watched.add(scope);
            Set<String> paths = new LinkedHashSet<>();
            boolean[] overflow = {false};
            long[] identity={0,0};
            scan((path, directory, event, modified) -> {
                if (path.equals(scope)) {
                    if(event == -1 && modified == 0)overflow[0] = true;
                    else {identity[0]=event;identity[1]=modified;}
                    return;
                }
                if (liveWatchScope(path) && watched.contains(new File(path).getParent())) paths.add(path);
            }, scope, "--watch", selected,null,ready);
            if((!paths.isEmpty()||overflow[0])&&identity[1]==0)
                throw new IOException("Root watch omitted folder identity.");
            return new LiveChanges(Collections.unmodifiableList(new ArrayList<>(paths)), overflow[0],identity[0],identity[1]);
        } finally {
            // Validation can fail before the scanner takes ownership of the marker.
            activeFile.delete();
            pidFile.delete();
        }
    }

    private Result scan(Sink sink, String traversalRoot, String modeFlag) throws IOException {
        return scan(sink, traversalRoot, modeFlag, Collections.emptyList(),null,null);
    }

    private Result scan(Sink sink, String traversalRoot, String modeFlag, List<String> watchPaths,LiveChanges identity,Runnable ready) throws IOException {
        if (!traversalRoot.startsWith("/") || traversalRoot.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Traversal root must be an absolute path.");
        }
        boolean recordRootScan = deviceNamespace && modeFlag.isEmpty();
        try {
            checkCancelled();
            // The app and su may have different mount namespaces, so read root's view.
            boolean shallow = modeFlag.startsWith("--children"), watching = modeFlag.equals("--watch");
            boolean map = modeFlag.startsWith("--map");
            List<String> excluded = shallow || watching ? Collections.emptyList() : exclusions(readMounts());
            checkCancelled();
            StringBuilder command = new StringBuilder(handshake()).append("exec ")
                    .append(quote(executable)).append(' ').append(quote(traversalRoot)).append(" --packed");
            if (!modeFlag.isEmpty()) command.append(' ').append(modeFlag);
            if(identity!=null)command.append(' ').append(Long.toUnsignedString(identity.device))
                    .append(' ').append(Long.toUnsignedString(identity.inode));
            for (String path : watchPaths) command.append(' ').append(quote(path));
            String prefix=traversalRoot.endsWith("/")?traversalRoot:traversalRoot+"/";
            int appliedExclusions=0;
            for (String path : excluded) if (!map||path.startsWith(prefix)) {
                command.append(' ').append(quote(path));
                appliedExclusions++;
            }
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
                if(ready!=null)ready.run();
                PackedReader fields = new PackedReader(input);
                for (;;) {
                    checkCancelled();
                    String path = fields.next();
                    if (path == null) break;
                    if (watching && records > LIVE_EVENT_LIMIT + 1) throw new IOException("Root watch exceeded its event limit.");
                    boolean directory = fields.directory;
                    sink.accept(path, directory, directory && !map && !watching ? 0 : fields.size, fields.modified);
                    records++;
                }
            } catch (IOException failure) {
                checkCancelled();
                throw failure;
            }
            int exit = waitFor(child);
            join(errorThread);
            checkCancelled();
            if (!watching && records == 0) {
                throw new IOException(rootFailure(errors.text()));
            }
            if (watching && exit != 0) throw new IOException("Root folder watch unavailable.");
            if(identity!=null&&exit>=2)throw new IOException("The watched Root folder changed.");
            if (shallow && exit != 0) throw new IOException("Could not read this folder. " + errors.text());
            if (exit > 1 || (exit != 0 && errors.accessErrors == 0 && errors.otherErrors == 0)) {
                throw new IOException("Root scanner failed (exit " + exit + "). " + errors.text());
            }
            String warning = errors.otherErrors > 0
                    ? errors.otherErrors + " traversal errors; refresh to reconcile." : "";
            if (recordRootScan) {
                SupportLog.record(SupportLog.Event.ROOT_SCAN_FINISHED, true, errors.accessErrors, errors.otherErrors);
            }
            return new Result(errors.accessErrors, errors.otherErrors, appliedExclusions, warning);
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

    /** Matches the native little-endian packed stream; only the path allocates per record. */
    static final class PackedReader {
        private static final int HEADER = 21;
        private static final int MAX_PATH = 4095; // Native scanner's Linux PATH_MAX minus its terminator.
        private final InputStream input;
        private final byte[] buffer = new byte[64 * 1024];
        private int position;
        private int limit;
        boolean directory;
        long size;
        long modified;

        PackedReader(InputStream input) {
            this.input = input;
        }

        private boolean ensure(int needed, boolean eofAllowed) throws IOException {
            if (limit - position >= needed) return true;
            limit -= position;
            if (limit != 0) System.arraycopy(buffer, position, buffer, 0, limit);
            position = 0;
            while (limit < needed) {
                int count = input.read(buffer, limit, buffer.length - limit);
                if (count < 0) {
                    if (eofAllowed && limit == 0) return false;
                    throw new IOException("Root scan ended before a metadata record was complete.");
                }
                limit += count;
            }
            return true;
        }

        private long littleEndian(int start, int width) {
            long value = 0;
            for (int i = width - 1; i >= 0; i--) value = (value << 8) | (buffer[start + i] & 255L);
            return value;
        }

        String next() throws IOException {
            if (!ensure(HEADER, true)) return null;
            byte type = buffer[position];
            long length = littleEndian(position + 17, 4);
            if ((type != 'd' && type != '-') || length < 1 || length > MAX_PATH) {
                throw new IOException("Root scanner returned an invalid metadata record.");
            }
            directory = type == 'd';
            size = littleEndian(position + 1, 8);
            modified = littleEndian(position + 9, 8);
            position += HEADER;
            ensure((int) length, false);
            int end = position + (int) length;
            if (buffer[position] != '/') throw new IOException("Root scanner returned an invalid path.");
            for (int i = position + 1; i < end; i++) {
                if (buffer[i] == 0) throw new IOException("Root scanner returned an invalid path.");
            }
            String path = new String(buffer, position, (int) length, StandardCharsets.UTF_8);
            position = end;
            return path;
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
            try (InputStream stream = new BufferedInputStream(input)) {
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
