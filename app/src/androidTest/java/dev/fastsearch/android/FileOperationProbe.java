package dev.fastsearch.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import dev.fastsearch.core.FileEntry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded synthetic operation tests. Never starts the production index or changes phone UI. */
public final class FileOperationProbe extends Instrumentation {
    private Context target;
    private File fixture;
    private String previousClipboard;
    private int assertions;
    private final StringBuilder report = new StringBuilder();

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); boolean passed = false;
        try { target = getTargetContext(); runChecks(); passed = true; }
        catch (Throwable failure) {
            java.io.StringWriter text = new java.io.StringWriter();
            failure.printStackTrace(new java.io.PrintWriter(text)); report.append(text);
        } finally {
            try { cleanup(); } catch (Throwable failure) { passed = false; report.append("Cleanup failed: ").append(failure).append('\n'); }
        }
        result.putString("stream", report.append(passed ? "PASS " : "FAIL ").append(assertions).append(" file-operation assertions.\n").toString());
        result.putBoolean("passed", passed); result.putInt("assertions", assertions);
        finish(passed ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private void runChecks() throws Exception {
        previousClipboard = target.getSharedPreferences("file-clipboard", 0).getString("selection", null);
        fixture = new File(target.getCacheDir(), "FileOperationProbe-" + SystemClock.elapsedRealtimeNanos()).getCanonicalFile();
        check(fixture.mkdir(), "create bounded fixture");
        File sources = directory(fixture, "sources"), destination = directory(fixture, "destination");
        String[] names = {"normal.txt", "space ' quote.txt", "line\nbreak.txt", "日本語-é.txt", "$(touch BAD).txt"};
        List<FileEntry> selection = new ArrayList<>();
        for (String name : names) { File file = new File(sources, name); write(file, "fixture " + name); selection.add(entry(file)); }
        File tree = directory(sources, "tree"), nested = directory(tree, "nested");
        write(new File(nested, "child.txt"), "nested bytes");
        File outside = new File(fixture, "outside.txt"); write(outside, "outside unchanged");
        Os.symlink(outside.getPath(), new File(tree, "external-link").getPath());
        Os.symlink(tree.getPath(), new File(tree, "loop").getPath());
        selection.add(entry(tree));
        FileClipboard.save(target, selection, false, false);
        check(FileClipboard.count(target) == 6 && !FileClipboard.isMove(target), "private shared clipboard stores six top-level items");
        JSONObject copied = paste(destination);
        if(!copied.optBoolean("ok"))report.append("Synthetic copy response: ").append(copied).append('\n');
        check(copied.getBoolean("ok") && copied.getJSONArray("completedIndices").length() == 6, "shared multi-file and folder copy completes");
        for (String name : names) {
            check(read(new File(destination, name)).equals("fixture " + name), "exact bytes for synthetic special filename");
            check(new File(sources, name).isFile(), "copy retains its source");
        }
        check(read(new File(destination, "tree/nested/child.txt")).equals("nested bytes"), "nested bytes copied");
        check(OsConstants.S_ISLNK(Os.lstat(new File(destination, "tree/external-link").getPath()).st_mode), "external link remains a link");
        check(Os.readlink(new File(destination, "tree/external-link").getPath()).equals(outside.getPath()), "external link target is preserved without reading it");
        check(OsConstants.S_ISLNK(Os.lstat(new File(destination, "tree/loop").getPath()).st_mode), "symlink loop is not traversed");
        check(read(outside).equals("outside unchanged") && !new File(sources, "BAD").exists(), "outside fixture unchanged and shell-like filename never executes");
        check(FileClipboard.count(target) == 6, "copy clipboard remains reusable");
        report.append("Shared copy: multiple files, folders, Unicode/newline/quote names, external links and loops passed.\n");

        File collision = directory(fixture, "collision");
        File collisionItem = new File(collision, names[0]); write(collisionItem, "keep existing");
        JSONObject blocked = paste(collision);
        check(!blocked.getBoolean("ok") && read(collisionItem).equals("keep existing"), "collision never overwrites existing bytes");
        check(collision.list().length == 1, "whole-batch preflight runs before any copying");
        File broken = directory(fixture, "broken-collision");
        Os.symlink(new File(fixture, "missing").getPath(), new File(broken, names[0]).getPath());
        check(!paste(broken).getBoolean("ok"), "broken destination symlink is a collision");
        check(OsConstants.S_ISLNK(Os.lstat(new File(broken, names[0]).getPath()).st_mode), "broken collision link remains untouched");
        FileClipboard.save(target, Collections.singletonList(entry(tree)), false, false);
        check(!paste(tree).getBoolean("ok"), "self copy is rejected");
        check(!paste(nested).getBoolean("ok"), "descendant copy is rejected");
        check(!new File(tree, "tree").exists() && !new File(nested, "tree").exists(), "rejected descendant copies create no destination");

        File alpha = directory(sources, "alpha"), sibling = directory(sources, "alpha-sibling");
        File alphaChild = new File(alpha, "item.txt"); write(alphaChild, "alpha");
        FileClipboard.save(target, Arrays.asList(entry(alpha), entry(sibling), entry(alphaChild)), false, false);
        check(FileClipboard.count(target) == 2, "parent selection absorbs child even with interleaved sibling name");
        List<FileEntry> tooMany = new ArrayList<>();
        for (int i = 0; i <= FileClipboard.MAX_ITEMS; i++) tooMany.add(entry(alphaChild));
        boolean bounded = false;
        try { FileClipboard.save(target, tooMany, false, false); } catch (Exception expected) { bounded = true; }
        check(bounded && FileClipboard.count(target) == 2, "oversized selection rejects without replacing clipboard");

        File stale = new File(sources, "stale.txt"); write(stale, "first");
        FileClipboard.save(target, Collections.singletonList(entry(stale)), false, false);
        check(stale.renameTo(new File(sources, "old-stale.txt")), "retain selected old inode"); write(stale, "other");
        File staleDestination = directory(fixture, "stale-destination");
        check(!paste(staleDestination).getBoolean("ok") && staleDestination.list().length == 0, "replaced source inode rejects before mutation");

        File special = directory(sources, "special"); write(new File(special, "regular.txt"), "ordinary");
        root("/system/bin/mkfifo " + q(new File(special, "pipe")));
        FileClipboard.save(target, Collections.singletonList(entry(special)), false, true);
        File specialDestination = directory(fixture, "special-destination");
        JSONObject specialResult = paste(specialDestination);
        check(!specialResult.getBoolean("ok") && !new File(specialDestination, "special").exists(), "unsupported FIFO rolls back only the newly created destination");
        check(new File(special, "regular.txt").isFile() && OsConstants.S_ISFIFO(Os.lstat(new File(special, "pipe").getPath()).st_mode), "special-file failure retains all source entries");

        File secret = new File(sources, "root-only.txt"); write(secret, "root synthetic bytes");
        root("chown 0:0 " + q(secret) + " && chmod 0600 " + q(secret));
        boolean denied = false;
        try { FileClipboard.save(target, Collections.singletonList(entry(secret)), false, false); } catch (Exception expected) { denied = true; }
        check(denied, "shared clipboard cannot capture root-only source");
        FileClipboard.save(target, Collections.singletonList(entry(secret)), false, true);
        File protectedDestination = directory(fixture, "protected-destination");
        check(paste(protectedDestination).getBoolean("ok"), "captured root clipboard can copy protected synthetic file");
        check(read(new File(protectedDestination, secret.getName())).equals("root synthetic bytes"), "root copy inherits destination ownership and exact bytes");
        report.append("Collision, stale identity, selection bound, FIFO rollback and protected root-copy checks passed.\n");

        deletionChecks(outside);
        checkAtomicNoReplace();
        File moving = new File(sources, "moving.txt"); write(moving, "move bytes");
        long inode = Os.lstat(moving.getPath()).st_ino;
        FileClipboard.save(target, Collections.singletonList(entry(moving)), true, false);
        check(FileClipboard.isMove(target), "cut intent is stored privately");
        FileClipboard.Snapshot moveClipboard = FileClipboard.read(target);
        File movedDestination = directory(fixture, "moved-destination");
        JSONObject moved = paste(movedDestination);
        check(moved.getBoolean("ok") && !moving.exists(), "same-filesystem move removes source name after successful rename");
        File movedFile = new File(movedDestination, moving.getName());
        check(read(movedFile).equals("move bytes") && Os.lstat(movedFile.getPath()).st_ino == inode, "move preserves source inode and bytes");
        FileClipboard.completed(target, moveClipboard, moved.getJSONArray("completedIndices"));
        check(FileClipboard.count(target) == 0, "committed cut clipboard entries are removed");
        File movingTree = directory(sources, "moving-tree"); write(new File(movingTree, "child.txt"), "move tree");
        FileClipboard.save(target, Collections.singletonList(entry(movingTree)), true, false);
        JSONObject movedTree = paste(movedDestination);
        check(movedTree.getBoolean("ok") && !movingTree.exists() && read(new File(movedDestination, "moving-tree/child.txt")).equals("move tree"), "same-filesystem folder move works");
        FileClipboard.Snapshot oldClipboard = FileClipboard.read(target);
        FileClipboard.save(target, Collections.singletonList(entry(stale)), false, false);
        FileClipboard.completed(target, oldClipboard, movedTree.getJSONArray("completedIndices"));
        check(FileClipboard.count(target) == 1 && !FileClipboard.isMove(target), "finishing an older move cannot clear a newer clipboard");

        File cancelSource = new File(sources, "cancel-large.bin");
        try (RandomAccessFile sparse = new RandomAccessFile(cancelSource, "rw")) { sparse.setLength(64L * 1024 * 1024); }
        FileClipboard.save(target, Collections.singletonList(entry(cancelSource)), false, true);
        File cancelDestination = directory(fixture, "cancel-destination");
        FileClipboard.Snapshot cancelClipboard = FileClipboard.read(target);
        AtomicReference<JSONObject> cancelResult = new AtomicReference<>();
        AtomicReference<Throwable> cancelFailure = new AtomicReference<>();
        try (FileOperationProcess process = new FileOperationProcess(target, cancelClipboard.request(cancelDestination.getPath()), true, null)) {
            Thread operation = new Thread(() -> { try { cancelResult.set(process.run()); } catch (Throwable failure) { cancelFailure.set(failure); } }, "operation-probe");
            operation.start();
            File partial = new File(cancelDestination, cancelSource.getName());
            long deadline = SystemClock.elapsedRealtime() + 20_000;
            while (operation.isAlive() && !partial.exists() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(5);
            check(partial.exists(), "copy reaches exclusively created destination before cancellation");
            process.cancel(); operation.join(10_000);
            check(!operation.isAlive(), "live privileged copy cancellation returns promptly");
            check(cancelFailure.get() == null && cancelResult.get() != null && cancelResult.get().optBoolean("cancelled"), "helper reports cooperative cancellation");
            check(cancelSource.isFile() && cancelSource.length() == 64L * 1024 * 1024, "cancellation retains full source");
            check(!partial.exists(), "cancellation rolls back the incomplete destination");
        }
        try (FileOperationProcess process = new FileOperationProcess(target, cancelClipboard.request(cancelDestination.getPath()), true, null)) {
            process.cancel(); boolean stopped = false;
            try { process.run(); } catch (Exception expected) { stopped = true; }
            check(stopped && cancelDestination.list().length == 0, "pre-launch cancellation never starts copying");
        }
        report.append("Same-filesystem file/folder moves, clipboard generation safety and live/pre-launch cancellation passed.\n");
    }

    private void deletionChecks(File outside)throws Exception{
        File keep=new File(fixture,"clipboard-kept.txt");write(keep,"clipboard bytes");FileClipboard.save(target,Collections.singletonList(entry(keep)),false,false);
        String clipboardId=FileClipboard.read(target).id;
        for(boolean rootMode:new boolean[]{false,true}){
            File tree=directory(fixture,"delete-tree-"+rootMode);File nested=directory(tree,"nested");write(new File(nested,"日本語 ' item.txt"),"delete fixture");
            Os.symlink(outside.getPath(),new File(tree,"external-link").getPath());Os.symlink(tree.getPath(),new File(tree,"loop").getPath());
            File single=new File(fixture,"delete-single-"+rootMode+".txt");write(single,"single fixture");
            FileClipboard.Snapshot captured=FileClipboard.capture(target,Arrays.asList(entry(tree),entry(single),entry(nested)),true,rootMode);
            check(captured.entries.length()==2&&FileClipboard.read(target).id.equals(clipboardId),"delete capture absorbs child and preserves copy clipboard");
            JSONObject result=deleteCaptured(captured);check(result.optBoolean("ok")&&result.getJSONArray("completedIndices").length()==2,"shared/root recursive delete acknowledges confirmed top-level items");
            check(!tree.exists()&&!single.exists()&&read(outside).equals("outside unchanged"),"delete removes only fixtures without following outside link or loop");
        }
        File first=new File(fixture,"delete-valid.txt"),stale=new File(fixture,"delete-stale.txt");write(first,"first retained");write(stale,"old inode");
        FileClipboard.Snapshot captured=FileClipboard.capture(target,Arrays.asList(entry(first),entry(stale)),true,false);
        check(stale.renameTo(new File(fixture,"delete-original.txt")),"retain confirmed old inode");write(stale,"new inode");
        check(!deleteCaptured(captured).optBoolean("ok")&&read(first).equals("first retained")&&read(stale).equals("new inode"),"whole-batch delete preflight rejects replaced identity before removing valid item");
        captured=FileClipboard.capture(target,Collections.singletonList(entry(first)),true,false);
        try(FileOperationProcess process=new FileOperationProcess(target,captured.deleteRequest(),false,null)){process.cancel();boolean stopped=false;
            try{process.run();}catch(Exception expected){stopped=true;}check(stopped&&first.isFile(),"cancelled-before-start delete leaves confirmed file untouched");}
        FileClipboard.save(target,Collections.singletonList(entry(first)),false,false);JSONObject deleted=deleteCaptured(captured);FileClipboard.deleted(target,captured,deleted.getJSONArray("completedIndices"));
        check(!first.exists()&&FileClipboard.count(target)==0,"deleted item is invalidated from clipboard");
        boolean protectedRoot=false;try{FileClipboard.capture(target,Collections.singletonList(entry(new File("/data"))),true,true);}catch(Exception expected){protectedRoot=true;}
        check(protectedRoot,"filesystem root cannot be confirmed for destructive operation");
        report.append("Delete: shared/root recursion, outside/loop links, stale batch identity, pre-launch cancellation, clipboard invalidation and protected roots passed.\n");
    }
    private JSONObject deleteCaptured(FileClipboard.Snapshot captured)throws Exception{
        try(FileOperationProcess process=new FileOperationProcess(target,captured.deleteRequest(),captured.root,null)){return process.run();}
    }

    private void checkAtomicNoReplace() throws Exception {
        NativeMoves.load(new File(target.getApplicationInfo().nativeLibraryDir, "libfileops.so").getCanonicalPath());
        File from = new File(fixture, "atomic-source-é-\ud83d\ude80.txt");
        File to = new File(fixture, "atomic-target-é-\ud83d\ude80.txt");
        write(from, "source retained"); write(to, "existing target retained");
        long sourceInode = Os.lstat(from.getPath()).st_ino, targetInode = Os.lstat(to.getPath()).st_ino;
        int error = NativeMoves.renameNoReplace(from.getPath().getBytes(StandardCharsets.UTF_8), to.getPath().getBytes(StandardCharsets.UTF_8));
        check(error == OsConstants.EEXIST, "native no-replace directly rejects an existing target");
        check(read(from).equals("source retained") && Os.lstat(from.getPath()).st_ino == sourceInode, "atomic collision preserves source inode and bytes");
        check(read(to).equals("existing target retained") && Os.lstat(to.getPath()).st_ino == targetInode, "atomic collision preserves target inode and bytes");
        File link = new File(fixture, "atomic-broken-link");
        String missing = new File(fixture, "atomic-missing").getPath();
        Os.symlink(missing, link.getPath());
        error = NativeMoves.renameNoReplace(from.getPath().getBytes(StandardCharsets.UTF_8), link.getPath().getBytes(StandardCharsets.UTF_8));
        check(error == OsConstants.EEXIST && Os.readlink(link.getPath()).equals(missing), "native no-replace preserves a broken target link");
        check(NativeMoves.renameNoReplace(new byte[4096], to.getPath().getBytes(StandardCharsets.UTF_8)) == OsConstants.ENAMETOOLONG, "native move bounds path allocation");
        check(NativeMoves.renameNoReplace(new byte[] {'/', 'x', 0}, to.getPath().getBytes(StandardCharsets.UTF_8)) == OsConstants.EINVAL, "native move rejects embedded NUL");
        check(read(from).equals("source retained") && read(to).equals("existing target retained"), "rejected native inputs leave synthetic files unchanged");
        report.append("Atomic no-replace: existing file/link rejection, UTF-8 paths and bounded native inputs passed.\n");
    }

    private JSONObject paste(File destination) throws Exception {
        FileClipboard.Snapshot clipboard = FileClipboard.read(target);
        check(clipboard != null, "clipboard snapshot exists");
        try (FileOperationProcess process = new FileOperationProcess(target, clipboard.request(destination.getCanonicalPath()), clipboard.root, null)) { return process.run(); }
    }
    private File directory(File parent, String name) throws Exception { File file = new File(parent, name); check(file.mkdir(), "create synthetic directory"); return file; }
    private static FileEntry entry(File file) throws Exception { return new FileEntry(file.getCanonicalPath(), file.isDirectory(), file.length(), file.lastModified()); }
    private static void write(File file, String value) throws Exception { try (FileOutputStream out = new FileOutputStream(file)) { out.write(value.getBytes(StandardCharsets.UTF_8)); } }
    private static String read(File file) throws Exception { return FileOperationProcess.readSmall(file, 4096); }
    private void check(boolean value, String description) { if (!value) throw new AssertionError(description); assertions++; }
    private static String q(File file) { return IndexRootScanner.quote(file.getPath()); }
    private void root(String command) throws Exception {
        Process child = new ProcessBuilder(IndexRootScanner.suBinary(), "-c", command).redirectErrorStream(true).start();
        child.getOutputStream().close();
        try (InputStream in = child.getInputStream()) { byte[] bytes = new byte[1024]; while (in.read(bytes) != -1) { } }
        if (child.waitFor() != 0) throw new AssertionError("Bounded synthetic root operation failed.");
    }
    private void cleanup() throws Exception {
        if (target != null) {
            android.content.SharedPreferences.Editor edit = target.getSharedPreferences("file-clipboard", 0).edit();
            if (previousClipboard == null) edit.remove("selection"); else edit.putString("selection", previousClipboard);
            edit.commit();
        }
        if (fixture != null && fixture.getParentFile().getCanonicalFile().equals(target.getCacheDir().getCanonicalFile())
                && fixture.getName().startsWith("FileOperationProbe-")) root("rm -rf " + q(fixture));
    }
}
