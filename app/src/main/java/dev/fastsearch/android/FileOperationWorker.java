package dev.fastsearch.android;

import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.content.Context;
import java.util.function.BooleanSupplier;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Isolated app_process entry point. No Activity, app singleton, query text or network access. */
public final class FileOperationWorker {
    private static final int MAX_DEPTH = 128;
    private final JSONObject request;
    private final Control control;
    private final JSONArray completed = new JSONArray();
    private final FileOutputStream output;
    private final FileOperationProcess.Progress localProgress;
    private boolean changed;

    private FileOperationWorker(JSONObject request, BooleanSupplier cancelled,
            FileOperationProcess.Progress localProgress) throws Exception {
        this.request = request;
        this.localProgress = localProgress;
        output = cancelled == null ? new FileOutputStream(FileDescriptor.out) : null;
        control = new Control(request, cancelled);
    }

    static JSONObject runLocal(Context context, JSONObject request, BooleanSupplier cancelled,
            FileOperationProcess.Progress progress) throws Exception {
        String mode = request.getString("mode");
        if (!mode.equals("capture") && !mode.equals("delete"))
            throw new IOException("Invalid local file operation.");
        request.put("protectedData", new File(context.getApplicationInfo().dataDir).getCanonicalPath());
        request.put("protectedApk", context.getApplicationInfo().sourceDir);
        return new FileOperationWorker(request, cancelled, progress).run();
    }

    public static void main(String[] args) {
        if (args.length != 1) System.exit(1);
        try {
            File requestFile = new File(requirePath(args[0]));
            JSONObject request = new JSONObject(FileOperationProcess.readSmall(requestFile, 2 * FileClipboard.MAX_BYTES));
            FileOperationWorker worker = new FileOperationWorker(request, null, null);
            worker.run();
        } catch (Throwable failure) { System.exit(1); }
    }

    private JSONObject run() throws Exception {
        JSONObject result = new JSONObject().put("event", "done");
        try {
            if (output != null) {
                // A dedicated group lets the controller stop helper + Toybox children together.
                try { Os.setsid(); } catch (ErrnoException alreadyLeader) {
                    String[] fields = processFields(Os.getpid());
                    if (Integer.parseInt(fields[2]) != Os.getpid()) throw new IOException("Could not isolate file operations.");
                }
                JSONObject identity = new JSONObject().put("pid", Os.getpid()).put("start", processStart(Os.getpid()))
                        .put("nonce", request.getString("nonce"));
                try (FileOutputStream out = new FileOutputStream(request.getString("pidFile"))) {
                    out.write(identity.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            control.check(true);
            if("reconcile".equals(request.getString("mode"))){NativeDiscovery.load(request.optString("nativeDiscoveryLibrary",null));JSONObject update=IndexReconciler.run(request,()->control.check(false),this::emit);
                java.util.Iterator<String> keys=update.keys();while(keys.hasNext()){String key=keys.next();result.put(key,update.get(key));}
                result.put("ok",true);emit(result);return result;}
            JSONArray entries = request.getJSONArray("entries");
            if (entries.length() == 0 || entries.length() > FileClipboard.MAX_ITEMS) throw new IOException("The clipboard is empty or invalid.");
            if ("capture".equals(request.getString("mode"))) {
                JSONArray captured = new JSONArray();
                Set<String> identities = new HashSet<>();
                for (int i = 0; i < entries.length(); i++) {
                    control.check(true);
                    JSONObject item = entries.getJSONObject(i);
                    String path = requirePath(item.getString("path"));
                    try (Pinned source = Pinned.absolute(path, item.getBoolean("directory"))) {
                        protectSource(path, source.stat, request.optBoolean("move"));
                        if (!identities.add(source.stat.st_dev + ":" + source.stat.st_ino)) throw new IOException("The selection contains the same item through multiple paths.");
                        captured.put(record(path, source.stat));
                    }
                }
                result.put("entries", captured);
            } else if ("paste".equals(request.getString("mode"))) paste(entries);
            else if("delete".equals(request.getString("mode"))) delete(entries);
            else if("rename".equals(request.getString("mode"))) rename(entries);
            else throw new IOException("Invalid file operation.");
            result.put("ok", true);
        } catch (Stopped stopped) {
            result.put("ok", false).put("cancelled", true).put("error", stopped.getMessage());
        } catch (Exception failure) {
            if(BuildConfig.DEBUG){java.io.StringWriter trace=new java.io.StringWriter();failure.printStackTrace(new java.io.PrintWriter(trace));result.put("debug",trace.toString());}
            String detail = failure instanceof IOException ? failure.getMessage() : "File access failed. Refresh and try again.";
            result.put("ok", false).put("error", detail == null ? "File operation failed." : detail);
        }
        result.put("completedIndices", completed).put("changed", changed);
        emit(result);
        return result;
    }

    private void rename(JSONArray entries)throws Exception{
        NativeMoves.load(request.optString("nativeMoveLibrary",null));Set<String> destinations=new HashSet<>(),sources=new HashSet<>();
        for(int i=0;i<entries.length();i++)sources.add(requirePath(entries.getJSONObject(i).getString("path")));
        // Complete preflight before the first mutation. Cycles/occupied names are rejected, never overwritten.
        for(int i=0;i<entries.length();i++){control.check(true);JSONObject item=entries.getJSONObject(i);String path=requirePath(item.getString("path")),name=item.getString("newName");
            dev.fastsearch.core.RenameRules.validate(name);String destination=parent(path)+(parent(path).equals("/")?"":"/")+name;
            if(!destinations.add(destination))throw new IOException("Two items would have the same name.");
            for(String ancestor=parent(path);!ancestor.equals("/");ancestor=parent(ancestor))if(sources.contains(ancestor))throw new IOException("Choose a folder or its contents, not both.");
            try(Pinned source=Pinned.absolute(path,item.getBoolean("directory"));Pinned folder=Pinned.absolute(parent(path),true)){
                verify(item,source.stat);protectSource(path,source.stat,true);
                if(!name.equals(basename(path))&&exists(folder.proc()+"/"+name))throw new IOException("An item with that name already exists. Nothing was replaced.");
            }
        }
        for(int i=0;i<entries.length();i++){control.check(true);JSONObject item=entries.getJSONObject(i);String path=requirePath(item.getString("path")),name=item.getString("newName");
            try(Pinned source=Pinned.absolute(path,item.getBoolean("directory"));Pinned folder=Pinned.absolute(parent(path),true)){
                verify(item,source.stat);protectSource(path,source.stat,true);
                if(!name.equals(basename(path))){String from=folder.proc()+"/"+basename(path),to=folder.proc()+"/"+name;
                    verify(item,Os.lstat(from));verifyDestination(parent(path),folder);control.check(true);
                    int error=NativeMoves.renameNoReplace(from.getBytes(StandardCharsets.UTF_8),to.getBytes(StandardCharsets.UTF_8));
                    if(error!=0)throw new IOException(error==OsConstants.EEXIST||error==OsConstants.ENOTEMPTY?"An item with that name already exists. Nothing was replaced.":"Could not rename the item. Its source was retained.");
                    changed=true;if(!sameIdentity(source.stat,Os.lstat(to)))throw new IOException("An item changed during renaming.");
                }
            }
            completed.put(i);emit(new JSONObject().put("event","progress").put("completed",completed.length()).put("total",entries.length()).put("completedIndices",completed));
        }
    }

    private void delete(JSONArray entries)throws Exception{
        Set<String> identities=new HashSet<>();
        // Validate all confirmed top-level identities before removing the first item.
        for(int i=0;i<entries.length();i++){control.check(true);JSONObject item=entries.getJSONObject(i);String path=requirePath(item.getString("path"));
            try(Pinned source=Pinned.absolute(path,item.getBoolean("directory"))){verify(item,source.stat);protectSource(path,source.stat,true);
                if(!identities.add(source.stat.st_dev+":"+source.stat.st_ino))throw new IOException("The selection contains duplicate filesystem items.");}}
        for(int i=0;i<entries.length();i++){control.check(true);JSONObject item=entries.getJSONObject(i);String path=requirePath(item.getString("path"));
            try(Pinned source=Pinned.absolute(path,item.getBoolean("directory"));Pinned parent=Pinned.absolute(parent(path),true)){
                verify(item,source.stat);protectSource(path,source.stat,true);deleteNode(parent,basename(path),source.stat,source.stat.st_dev,0);}
            completed.put(i);emit(new JSONObject().put("event","progress").put("completed",completed.length()).put("total",entries.length()).put("completedIndices",completed));}
    }

    private void deleteNode(Pinned parent,String name,StructStat expected,long device,int depth)throws Exception{
        control.check(false);if(depth>MAX_DEPTH)throw new IOException("The folder is deeper than the supported 128 levels. Some contents may already have been deleted.");
        requireName(name);String path=parent.proc()+"/"+name;StructStat current=Os.lstat(path);
        if(!sameIdentity(expected,current))throw new IOException("An item changed during deletion. Remaining items were retained.");
        if(current.st_dev!=device)throw new IOException("A mounted filesystem cannot be deleted as part of this folder.");
        if(OsConstants.S_ISDIR(current.st_mode)){
            try(Pinned directory=Pinned.raw(path,true)){
                if(!sameIdentity(current,directory.stat))throw new IOException("A folder changed during deletion.");
                try(DirectoryStream<Path> children=Files.newDirectoryStream(Paths.get(directory.proc()))){
                    for(Path child:children){control.check(false);String childName=child.getFileName().toString();requireName(childName);
                        deleteNode(directory,childName,Os.lstat(directory.proc()+"/"+childName),device,depth+1);}}
            }
            control.check(false);if(!sameIdentity(current,Os.lstat(path)))throw new IOException("A folder changed during deletion.");Os.remove(path);
        }else{control.check(false);if(!sameIdentity(current,Os.lstat(path)))throw new IOException("An item changed during deletion.");Os.remove(path);}
        changed=true;
    }

    private void paste(JSONArray entries) throws Exception {
        String destinationPath = requirePath(request.getString("destination"));
        boolean move = request.getBoolean("move");
        if (move) NativeMoves.load(request.optString("nativeMoveLibrary", null));
        try (Pinned destination = Pinned.absolute(destinationPath, true)) {
            Set<String> names = new HashSet<>();
            Set<String> identities = new HashSet<>();
            // Check the entire batch before creating or moving anything.
            for (int i = 0; i < entries.length(); i++) {
                control.check(true);
                JSONObject item = entries.getJSONObject(i);
                String path = requirePath(item.getString("path"));
                String name = basename(path);
                if (!names.add(name)) throw new IOException("Selected items have the same name. Paste them separately.");
                try (Pinned source = Pinned.absolute(path, item.getBoolean("directory"))) {
                    verify(item, source.stat);
                    protectSource(path, source.stat, move);
                    if (!identities.add(source.stat.st_dev + ":" + source.stat.st_ino)) throw new IOException("The selection contains duplicate filesystem items.");
                    if (exists(destination.proc() + "/" + name)) throw new IOException("An item with the same name already exists. Nothing was replaced.");
                    if (OsConstants.S_ISDIR(source.stat.st_mode) && ancestor(destination, source.stat)) throw new IOException("A folder cannot be pasted into itself or a descendant.");
                    if (move && source.stat.st_dev != destination.stat.st_dev) throw new IOException("Move between filesystems is unavailable. Use Copy instead.");
                }
            }
            for (int i = 0; i < entries.length(); i++) {
                control.check(true);
                JSONObject item = entries.getJSONObject(i);
                String path = requirePath(item.getString("path"));
                try (Pinned source = Pinned.absolute(path, item.getBoolean("directory"))) {
                    verify(item, source.stat);
                    verifyDestination(destinationPath, destination);
                    if (move) move(source, destination, path);
                    else copy(source, destination, basename(path));
                }
                completed.put(i);
                emit(new JSONObject().put("event", "progress").put("completed", completed.length())
                        .put("total", entries.length()).put("completedIndices", completed));
            }
        }
    }

    private void move(Pinned source, Pinned destination, String path) throws Exception {
        try (Pinned parent = Pinned.absolute(parent(path), true)) {
            String from = parent.proc() + "/" + basename(path);
            String to = destination.proc() + "/" + basename(path);
            verify(record(path, source.stat), Os.lstat(from));
            control.check(true);
            int error = NativeMoves.renameNoReplace(from.getBytes(StandardCharsets.UTF_8), to.getBytes(StandardCharsets.UTF_8));
            if (error != 0) {
                if (error == OsConstants.EEXIST || error == OsConstants.ENOTEMPTY) throw new IOException("An item with the same name already exists. Nothing was replaced.");
                if (error == OsConstants.EXDEV) throw new IOException("Move between these mounts is unavailable. Use Copy instead.");
                if (error == OsConstants.ENOSYS || error == OsConstants.EINVAL || error == OsConstants.EOPNOTSUPP) {
                    throw new IOException("Atomic moves are unavailable on this filesystem. The source was retained. Use Copy instead.");
                }
                throw new IOException("Could not move the item. Its source was retained.");
            }
            changed = true;
            StructStat moved = Os.lstat(to);
            if (!sameIdentity(source.stat, moved)) throw new IOException("The filesystem changed during the move. Inspect the destination before retrying.");
        }
    }

    private void copy(Pinned source, Pinned destination, String name) throws Exception {
        File journalFile = new File(new File(request.getString("active")).getParentFile(), "rollback.bin");
        try (Journal journal = new Journal(journalFile, destination)) {
            try {
                copyNode(source, destination, name, name, source.stat.st_dev, 0, journal);
                control.check(true);
            } catch (Exception failure) {
                boolean cleaned = journal.rollback();
                if (!cleaned) throw new IOException("The copy stopped. An incomplete destination was retained; inspect the destination folder.", failure);
                throw failure;
            }
        }
    }

    private void copyNode(Pinned source, Pinned targetParent, String name, String relative,
                          long sourceDevice, int depth, Journal journal) throws Exception {
        control.check(true);
        if (depth > MAX_DEPTH) throw new IOException("The folder is deeper than the supported 128 levels.");
        StructStat before = Os.fstat(source.file.getFileDescriptor());
        String target = targetParent.proc() + "/" + name;
        if (OsConstants.S_ISDIR(before.st_mode)) {
            if (before.st_dev != sourceDevice) throw new IOException("A nested mount cannot be copied as part of this folder.");
            Os.mkdir(target, 0700); changed = true;
            journal.created();
            StructStat created = Os.lstat(target);
            journal.add(relative, created);
            try (Pinned directory = Pinned.raw(target, true)) {
                if (!sameIdentity(created, directory.stat)) throw new IOException("The new destination folder changed during copying.");
                inheritOwner(directory, targetParent);
                try (DirectoryStream<Path> children = Files.newDirectoryStream(Paths.get(source.proc()))) {
                    for (Path child : children) {
                        control.check(true);
                        String childName = child.getFileName().toString();
                        requireName(childName);
                        String sourceChild = source.proc() + "/" + childName;
                        StructStat stat = Os.lstat(sourceChild);
                        if (OsConstants.S_ISLNK(stat.st_mode)) {
                            String link = Os.readlink(sourceChild);
                            if (!sameIdentity(stat, Os.lstat(sourceChild))) throw new IOException("A source link changed during copying.");
                            String targetChild = directory.proc() + "/" + childName;
                            Os.symlink(link, targetChild); changed = true;
                            journal.created();
                            journal.add(relative + "/" + childName, Os.lstat(targetChild));
                        } else if (OsConstants.S_ISDIR(stat.st_mode) || OsConstants.S_ISREG(stat.st_mode)) {
                            try (Pinned nested = Pinned.raw(sourceChild, OsConstants.S_ISDIR(stat.st_mode))) {
                                if (!sameIdentity(stat, nested.stat)) throw new IOException("A source item changed during copying.");
                                copyNode(nested, directory, childName, relative + "/" + childName, sourceDevice, depth + 1, journal);
                            }
                        } else throw new IOException("The folder contains a special file. Only folders, regular files and symbolic links can be copied.");
                    }
                }
                if (!sameSnapshot(before, Os.fstat(source.file.getFileDescriptor()))) throw new IOException("The source folder changed during copying. Try again when it is idle.");
                applyMetadata(directory, before, true);
            }
        } else {
            if (!OsConstants.S_ISREG(before.st_mode)) throw new IOException("Only regular files can be copied.");
            try (Pinned copied = Pinned.create(target)) {
                changed = true; journal.created(); journal.add(relative, copied.stat);
                inheritOwner(copied, targetParent);
                byte[] sourceDigest = copyFile(source, copied);
                StructStat after = Os.fstat(source.file.getFileDescriptor());
                if (!sameSnapshot(before, after) || Os.fstat(copied.file.getFileDescriptor()).st_size != before.st_size
                        || !Arrays.equals(sourceDigest, digest(copied))
                        || !sameSnapshot(before, Os.fstat(source.file.getFileDescriptor()))) {
                    throw new IOException("A source file changed or the copied bytes did not match. The source was retained.");
                }
                Os.fsync(copied.file.getFileDescriptor());
                applyMetadata(copied, before, false);
            }
        }
    }

    private byte[] copyFile(Pinned source, Pinned target) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(
                     ParcelFileDescriptor.dup(source.file.getFileDescriptor()));
             FileOutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(
                     ParcelFileDescriptor.dup(target.file.getFileDescriptor()))) {
            byte[] buffer = new byte[128 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                control.check(false);
                output.write(buffer, 0, count);
                hash.update(buffer, 0, count);
            }
        }
        return hash.digest();
    }

    private byte[] digest(Pinned file) throws Exception {
        Os.lseek(file.file.getFileDescriptor(), 0, OsConstants.SEEK_SET);
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(file.file.getFileDescriptor()))) {
            byte[] bytes = new byte[64 * 1024]; int count;
            while ((count = in.read(bytes)) != -1) { control.check(false); hash.update(bytes, 0, count); }
        }
        return hash.digest();
    }

    private static void applyMetadata(Pinned target, StructStat source, boolean directory) {
        // No owner, SELinux label, xattr, setuid or setgid cloning. Keep owner access so
        // interrupted destinations remain recoverable, including non-root shared copies.
        try { Os.fchmod(target.file.getFileDescriptor(), (source.st_mode & 0777) | (directory ? 0700 : 0600)); }
        catch (Exception unsupportedOnVolume) { }
        new File(target.proc()).setLastModified(source.st_mtime * 1000L);
    }

    private static void inheritOwner(Pinned target, Pinned parent) {
        if (Os.geteuid() != 0) return;
        try {
            StructStat owner = Os.fstat(parent.file.getFileDescriptor());
            Os.fchown(target.file.getFileDescriptor(), owner.st_uid, owner.st_gid);
        } catch (Exception enforcedByVolume) { }
    }

    private static JSONObject record(String path, StructStat stat) throws Exception {
        return new JSONObject().put("path", path).put("directory", OsConstants.S_ISDIR(stat.st_mode))
                .put("device", stat.st_dev).put("inode", stat.st_ino);
    }

    private static void verify(JSONObject item, StructStat stat) throws Exception {
        if (stat.st_dev != item.getLong("device") || stat.st_ino != item.getLong("inode")
                || item.getBoolean("directory") != OsConstants.S_ISDIR(stat.st_mode)
                || !(OsConstants.S_ISREG(stat.st_mode) || OsConstants.S_ISDIR(stat.st_mode))) {
            throw new IOException("A selected item was replaced. Select it again after refreshing.");
        }
    }

    private static boolean sameIdentity(StructStat a, StructStat b) { return a.st_dev == b.st_dev && a.st_ino == b.st_ino && a.st_mode == b.st_mode; }
    private static boolean sameSnapshot(StructStat a, StructStat b) {
        return sameIdentity(a, b) && a.st_size == b.st_size && a.st_mtime == b.st_mtime && a.st_ctime == b.st_ctime;
    }

    private void protectSource(String path, StructStat stat, boolean move) throws IOException {
        if (!OsConstants.S_ISDIR(stat.st_mode)) return;
        String active = request.optString("active");
        String data = request.optString("protectedData");
        String apk = request.optString("protectedApk");
        if (active.startsWith(path + "/") || (move && (data.equals(path) || data.startsWith(path + "/") || apk.startsWith(path + "/")))) {
            throw new IOException("The app's active data and installation roots cannot be operated on as a whole.");
        }
        if (parent(path).equals("/") || path.equals("/data/local") || path.equals("/data/user")
                || path.equals("/data/user_de") || path.equals("/data/media") || path.equals("/data/system")
                || path.equals("/data/app") || path.matches("/(?:data/(?:user|user_de|media)|storage/emulated)/[0-9]+")) {
            throw new IOException("Filesystem and system directory roots cannot be " + (move ? "moved." : "copied as a whole."));
        }
    }

    private static void verifyDestination(String path, Pinned destination) throws Exception {
        try (Pinned current = Pinned.absolute(path, true)) {
            if (!sameIdentity(current.stat, destination.stat)) throw new IOException("The destination folder changed. Choose it again.");
        }
    }

    private static boolean ancestor(Pinned destination, StructStat source) throws Exception {
        // Search permission is sufficient to check ancestors. Opening each one
        // O_RDONLY wrongly requires listing /data and breaks ordinary app copies.
        String path=destination.proc()+"/.";StructStat current=Os.stat(path);
        for(int level=0;level<=512;level++){
            if(sameIdentity(current,source))return true;
            path+="/..";StructStat parent=Os.stat(path);
            if(sameIdentity(parent,current))return false;current=parent;
        }
        throw new IOException("The destination ancestry is too deep.");
    }

    private static boolean exists(String path) throws Exception {
        try { Os.lstat(path); return true; }
        catch (ErrnoException absent) { if (absent.errno == OsConstants.ENOENT) return false; throw absent; }
    }

    static String requirePath(String path) throws IOException {
        if (path == null || !path.startsWith("/") || path.equals("/") || path.endsWith("/")
                || path.indexOf('\0') >= 0 || path.getBytes(StandardCharsets.UTF_8).length > 4096) throw new IOException("Choose a direct absolute file or folder path.");
        for (String part : path.substring(1).split("/", -1)) requireName(part);
        return path;
    }

    private static void requireName(String name) throws IOException {
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.indexOf('/') >= 0 || name.indexOf('\0') >= 0) throw new IOException("Invalid file or folder name.");
    }

    private static String basename(String path) { return path.substring(path.lastIndexOf('/') + 1); }
    private static String parent(String path) { int at = path.lastIndexOf('/'); return at == 0 ? "/" : path.substring(0, at); }

    static String processStart(int pid) throws IOException { return processFields(pid)[19]; }
    private static String[] processFields(int pid) throws IOException {
        if (pid <= 1) throw new IOException("Invalid operation process.");
        String raw = FileOperationProcess.readSmall(new File("/proc/" + pid + "/stat"), 8192);
        int end = raw.lastIndexOf(')');
        if (end < 0) throw new IOException("Invalid process identity.");
        String[] fields = raw.substring(end + 1).trim().split("\\s+");
        if (fields.length < 20) throw new IOException("Incomplete process identity.");
        return fields;
    }

    private void emit(JSONObject message) throws IOException {
        if (output != null) {
            output.write((message.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
        } else if (localProgress != null && "progress".equals(message.optString("event"))) {
            localProgress.onProgress(message.optInt("completed"), message.optInt("total"), message.optJSONArray("completedIndices"));
        }
    }

    private static final class Stopped extends IOException {
        private static final long serialVersionUID = 1L;
        Stopped() { super("File operation stopped. Completed items were retained."); }
    }

    private static final class Control {
        final File marker; final int owner; final String start; final long deadline;
        final BooleanSupplier cancelled;
        long checked;

        Control(JSONObject request, BooleanSupplier cancelled) throws Exception {
            this.cancelled = cancelled;
            if (cancelled == null) {
                marker = new File(requirePath(request.getString("active"))); owner = request.getInt("ownerPid");
                start = request.getString("ownerStart"); deadline = request.getLong("deadline");
            } else {
                marker = null; owner = 0; start = ""; deadline = SystemClock.elapsedRealtime() + 30 * 60 * 1000L;
            }
        }
        void check(boolean force) throws Exception {
            long now = SystemClock.elapsedRealtime();
            if (cancelled != null) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted() || now >= deadline) throw new Stopped();
                return;
            }
            if (!force && now - checked < 100) return;
            checked = now;
            if (!marker.isFile() || now >= deadline || !start.equals(processStart(owner))) throw new Stopped();
        }
    }

    private static final class Pinned implements AutoCloseable {
        final ParcelFileDescriptor file; final StructStat stat;
        Pinned(FileDescriptor fd) throws Exception {
            ParcelFileDescriptor copy = null;
            try {
                copy = ParcelFileDescriptor.dup(fd);
                StructStat captured = Os.fstat(copy.getFileDescriptor());
                file = copy; stat = captured;
            } catch (Exception failure) {
                if (copy != null) copy.close();
                throw failure;
            }
            finally { Os.close(fd); }
        }
        static int flags(boolean directory) {
            int flags = OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW | OsConstants.O_NONBLOCK;
            // O_DIRECTORY is not in the public Android SDK. fstat below verifies
            // directories after a no-follow/nonblocking open, including API 26.
            if (android.os.Build.VERSION.SDK_INT >= 27) flags |= OsConstants.O_CLOEXEC;
            return flags;
        }
        static Pinned absolute(String path, boolean directory) throws Exception {
            if (!path.equals(new File(path).getCanonicalPath())) throw new IOException("A selected path now contains a symbolic link. Refresh and select it again.");
            Pinned result = raw(path, directory);
            try {
                if (!path.equals(Os.readlink(result.proc()))) throw new IOException("A selected path changed. Refresh and select it again.");
                return result;
            } catch (Exception error) { result.close(); throw error; }
        }
        static Pinned raw(String path, boolean directory) throws Exception {
            StructStat expected = Os.lstat(path);
            if (directory ? !OsConstants.S_ISDIR(expected.st_mode) : !OsConstants.S_ISREG(expected.st_mode)) throw new IOException("Only folders and regular files can be selected.");
            Pinned result = new Pinned(Os.open(path, flags(directory), 0));
            if (!sameIdentity(expected, result.stat) || (directory ? !OsConstants.S_ISDIR(result.stat.st_mode) : !OsConstants.S_ISREG(result.stat.st_mode))) {
                result.close(); throw new IOException("Only folders and regular files can be selected.");
            }
            return result;
        }
        static Pinned create(String path) throws Exception {
            int flags = OsConstants.O_RDWR | OsConstants.O_CREAT | OsConstants.O_EXCL | OsConstants.O_NOFOLLOW | OsConstants.O_NONBLOCK;
            if (android.os.Build.VERSION.SDK_INT >= 27) flags |= OsConstants.O_CLOEXEC;
            return new Pinned(Os.open(path, flags, 0600));
        }
        String proc() { return "/proc/" + Os.getpid() + "/fd/" + file.getFd(); }
        @Override public void close() throws IOException { file.close(); }
    }

    /** Disk-backed reverse creation log: cleanup only unlinks the inodes this copy created. */
    private static final class Journal implements AutoCloseable {
        final RandomAccessFile data; final File path; final Pinned destination;
        boolean unrecorded;
        Journal(File path, Pinned destination) throws Exception { this.path = path; this.destination = destination; data = new RandomAccessFile(path, "rw"); data.setLength(0); }
        void created() { unrecorded = true; }
        void add(String relative, StructStat stat) throws Exception {
            byte[] bytes = new JSONObject().put("path", relative).put("device", stat.st_dev).put("inode", stat.st_ino)
                    .put("directory", OsConstants.S_ISDIR(stat.st_mode)).toString().getBytes(StandardCharsets.UTF_8);
            data.writeInt(bytes.length); data.write(bytes); data.writeInt(bytes.length);
            unrecorded = false;
        }
        boolean rollback() {
            boolean complete = true;
            try {
                long end = data.length();
                while (end > 0) {
                    data.seek(end - 4); int length = data.readInt();
                    if (length < 1 || length > 64 * 1024 || end < length + 8L) return false;
                    long begin = end - length - 8L;
                    data.seek(begin); if (data.readInt() != length) return false;
                    byte[] bytes = new byte[length]; data.readFully(bytes); end = begin;
                    JSONObject item = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                    String relative = item.getString("path");
                    String[] parts = relative.split("/", -1);
                    Pinned parent = null;
                    try {
                        Pinned current = destination;
                        for (int i = 0; i < parts.length - 1; i++) {
                            requireName(parts[i]); Pinned next = Pinned.raw(current.proc() + "/" + parts[i], true);
                            if (parent != null) parent.close(); parent = next; current = next;
                        }
                        requireName(parts[parts.length - 1]); String target = current.proc() + "/" + parts[parts.length - 1];
                        StructStat stat = Os.lstat(target);
                        if (stat.st_dev == item.getLong("device") && stat.st_ino == item.getLong("inode")) Os.remove(target);
                        else complete = false;
                    } catch (Exception error) { complete = false; }
                    finally { if (parent != null) try { parent.close(); } catch (IOException ignored) { } }
                }
            } catch (Exception error) { return false; }
            return complete && !unrecorded;
        }
        @Override public void close() throws IOException { data.close(); path.delete(); }
    }
}
