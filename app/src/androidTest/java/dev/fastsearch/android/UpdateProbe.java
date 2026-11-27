package dev.fastsearch.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import dev.fastsearch.core.FileEntry;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Offline add/change/delete, no-op reuse and cancelled transaction tests on isolated caches. */
public final class UpdateProbe extends Instrumentation {
    private boolean includeRoot=true,folderSnapshots,liveOnly;private Activity foreground;private Context target;private File fixture;private String id;private FileIndex index;private int assertions;
    private final List<String> ids=new ArrayList<>();private final StringBuilder report=new StringBuilder();
    @Override public void onCreate(Bundle args){super.onCreate(args);includeRoot=args==null||!"false".equals(args.getString("root"));liveOnly=args!=null&&"live".equals(args.getString("phase"));start();}
    @Override public void onStart(){boolean passed=false;try{target=getTargetContext();foreground=startActivitySync(new android.content.Intent(target,MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("permissionProbe",true));if(!liveOnly){exercise(false);folderSnapshots=true;exercise(false);folderSafety();folderSnapshots=false;if(includeRoot)exercise(true);}discovery(false);if(includeRoot)discovery(true);passed=true;}
        catch(Throwable failure){java.io.StringWriter trace=new java.io.StringWriter();failure.printStackTrace(new java.io.PrintWriter(trace));report.append(trace);}
        finally{try{cleanup();}catch(Exception failure){passed=false;report.append("Cleanup: ").append(failure);}}
        Bundle result=new Bundle();result.putBoolean("passed",passed);result.putInt("assertions",assertions);
        result.putString("stream",report.append(passed?"PASS ":"FAIL ").append(assertions).append(" incremental update assertions.\n").toString());finish(passed?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);}
    private void exercise(boolean root)throws Exception{
        id="IndexProbeUpdate"+SystemClock.elapsedRealtimeNanos();ids.add(id);fixture=new File(target.getCacheDir(),"IndexProbe-Update-"+SystemClock.elapsedRealtimeNanos()).getCanonicalFile();
        check(fixture.mkdir(),"create owned fixture");File docs=new File(fixture,"Documents");check(docs.mkdir(),"create fixture directory");
        File changed=new File(docs,"changed.txt"),deleted=new File(docs,"deleted.txt"),renamed=new File(docs,"renamed.txt");
        write(changed,"before");write(deleted,"delete me");write(renamed,"rename me");for(int n=0;n<400;n++)write(new File(docs,"file-"+n+".txt"),"unchanged");
        File replaced=new File(docs,"replaced.txt");write(replaced,"old regular file");
        index=open(root);AtomicBoolean liveCount=new AtomicBoolean();index.addListener(state->{if(state.scanning&&state.discoveredItems>0)liveCount.set(true);});
        index.startScan();await(()->index.getState().lastScanMillis>0&&!index.getState().scanning,"initial bounded scan");
        check(liveCount.get(),"scan publishes live discovered count before commit");
        long nativeStamp=NativeDiscovery.modified(java.util.Collections.singletonList(docs.getPath()))[0];
        check(find(docs).modified==nativeStamp,"saved folder stamp matches native precision");
        report.append("Directory stamps: cached=").append(find(docs).modified).append(" native=").append(nativeStamp).append(" NIO=").append(java.nio.file.Files.getLastModifiedTime(docs.toPath()).toMillis()).append('\n');
        long baseline=index.getState().lastScanMillis;close();
        write(changed,"longer changed bytes");check(deleted.delete(),"delete offline fixture");File moved=new File(docs,"moved.txt");check(renamed.renameTo(moved),"rename offline fixture");
        File addedFolder=new File(docs,"new-folder");check(addedFolder.mkdir(),"new offline folder");File added=new File(addedFolder,"new.txt");write(added,"new bytes");
        android.system.Os.symlink(fixture.getPath(),new File(docs,"loop").getPath());
        check(replaced.delete(),"replace cached regular file");android.system.Os.symlink(docs.getPath(),replaced.getPath());
        index=open(root);index.load();await(()->index.getState().loaded&&!index.getState().scanning,"saved cache loads without scan");
        check(find(deleted)!=null&&find(renamed)!=null&&find(added)==null,"immediate cache is retained before reconciliation");
        check(index.needsCatchUp(),"reloaded scope needs one offline catch-up");
        AtomicBoolean automaticLoading=new AtomicBoolean();index.addListener(state->{if(state.scanning)automaticLoading.set(true);});
        index.checkChanges();await(()->!index.hasPendingWork()&&index.getState().lastScanMillis>baseline,"automatic folder delta commits");waitForIdleSync();
        check(!automaticLoading.get(),"automatic reopen catch-up keeps cached results out of whole-index loading state");
        check(index.fullScanStartsForProbe()==0,"routine refresh of saved index never starts full build");
        check(index.getState().error.isEmpty(),"delta has no access/process/SQL error");
        check(find(deleted)==null&&find(renamed)==null,"deleted and old renamed paths removed");
        check(find(moved)!=null&&find(added)!=null&&find(addedFolder)!=null,"new renamed and nested paths indexed");
        check(find(changed)!=null&&find(changed).size==changed.length(),"changed file metadata updated");
        check(find(new File(docs,"loop"))==null,"reconcile does not index or traverse symlink loop");
        check(find(replaced)==null,"cached file replaced by symlink is removed from both snapshot formats");
        check(index.getState().status.contains("Checked 2 cached folders"),"automatic catch-up checks folders instead of every cached file");
        check(!index.needsCatchUp(),"completed catch-up is not requested again on Activity return");
        long caughtUp=index.getState().lastScanMillis;List<FileEntry> same=index.getState().entries;
        int checks=index.wholeReconcileStartsForProbe();for(int n=0;n<8;n++)index.checkChanges();SystemClock.sleep(350);
        check(index.getState().lastScanMillis==caughtUp&&index.getState().entries==same&&!index.getState().scanning,"repeated resume requests do not run another check or replace results");
        check(index.wholeReconcileStartsForProbe()==checks,"Activity reopens do not repeat the process catch-up");
        report.append(root?"Root ":"Shared ").append(index.getState().status).append('\n');
        File forced=new File(docs,"forced-after-operation.txt");write(forced,"forced update");long prior=index.getState().lastScanMillis;
        index.reconcile(true);await(()->!index.getState().scanning&&index.getState().lastScanMillis>prior,"forced update bypasses recent-check throttle");
        check(find(forced)!=null,"explicit Refresh bypasses recent-check throttle");close();
        index=open(root);index.load();await(()->index.getState().loaded&&!index.getState().scanning,"reload delta persistence");
        check(find(deleted)==null&&find(added)!=null,"delta persisted without rebuilding database");
        List<FileEntry> saved=index.getState().entries;long previous=index.getState().lastScanMillis;SystemClock.sleep(5);
        index.reconcile();await(()->!index.getState().scanning&&index.getState().lastScanMillis>previous,"no-op check completes");
        check(index.getState().entries==saved,"no-op reconciliation retains exact searchable snapshot");
        check(index.getState().status.contains("listed 0 changed/new folders"),"no-op check does not enumerate unchanged directories");close();
        File cancelled=new File(docs,"cancelled.txt");write(cancelled,"must not commit");
        index=open(root);index.load();await(()->index.getState().loaded&&!index.getState().scanning,"cache before cancellation");
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);index.setTransactionProbe(()->{entered.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException failure){Thread.currentThread().interrupt();}});
        index.reconcile();check(entered.await(20,TimeUnit.SECONDS),"delta reaches real SQLite transaction");index.cancelScan();release.countDown();
        await(()->!index.getState().scanning,"cancelled delta settles");check(find(cancelled)==null,"cancelled delta retains previous memory snapshot");close();
        index=open(root);index.load();await(()->index.getState().loaded&&!index.getState().scanning,"reload cancelled transaction");
        check(find(cancelled)==null,"cancelled delta rolled back on disk");close();delete(fixture);fixture=null;
    }
    private void folderSafety()throws Exception{
        id="IndexProbeFolderSafety"+SystemClock.elapsedRealtimeNanos();ids.add(id);fixture=new File(target.getCacheDir(),"IndexProbe-FolderSafety-"+SystemClock.elapsedRealtimeNanos()).getCanonicalFile();check(fixture.mkdir(),"folder safety fixture");
        File denied=new File(fixture,"denied"),tree=new File(fixture,"tree"),replacement=new File(fixture,"replacement");check(denied.mkdir()&&tree.mkdir(),"folder safety directories");
        File retained=new File(denied,"retain-until-readable.txt"),oldChild=new File(tree,"old-child.txt");write(retained,"saved");write(oldChild,"old");write(replacement,"old file");
        index=open(false);index.startScan();await(()->!index.hasPendingWork()&&index.getState().lastScanMillis>0,"folder safety baseline");close();
        check(retained.delete(),"offline inaccessible child deletion");check(denied.setLastModified(System.currentTimeMillis()+2000),"changed denied-folder stamp");
        check(oldChild.delete()&&tree.delete(),"replace directory tree");write(tree,"now a regular file");check(replacement.delete()&&replacement.mkdir(),"replace file with directory");File newChild=new File(replacement,"new-child.txt");write(newChild,"new");
        android.system.Os.chmod(denied.getPath(),0000);
        try{index=open(false);index.load();await(()->!index.hasPendingWork(),"folder safety cache");index.checkChanges();await(()->!index.hasPendingWork(),"folder safety automatic catch-up");
            check(find(retained)!=null,"inaccessible listing retains unseen cached children");
            check(find(tree)!=null&&!find(tree).directory&&find(oldChild)==null,"directory-to-file replacement removes old descendants");
            check(find(replacement)!=null&&find(replacement).directory&&find(newChild)!=null,"file-to-directory replacement discovers new descendants; "+index.getState().status+"; error="+index.getState().error);
        }finally{android.system.Os.chmod(denied.getPath(),0700);}
        index.refresh();await(()->!index.hasPendingWork(),"readable denied folder refresh");check(find(retained)==null,"explicit verification removes previously inaccessible missing child");close();delete(fixture);fixture=null;
        // Interrupt a listing after its first bounded names chunk. It must not certify completeness.
        fixture=new File(target.getCacheDir(),"IndexProbe-PartialListing-"+SystemClock.elapsedRealtimeNanos()).getCanonicalFile();check(fixture.mkdir(),"partial listing fixture");for(int i=0;i<400;i++)write(new File(fixture,"part-"+i),"x");
        AtomicBoolean stop=new AtomicBoolean(),complete=new AtomicBoolean(),chunk=new AtomicBoolean();
        org.json.JSONObject request=new org.json.JSONObject().put("roots",new org.json.JSONArray().put(fixture.getPath())).put("denied",new org.json.JSONArray()).put("foldersOnly",true).put("verifyFiles",false);
        org.json.JSONObject result=IndexReconciler.run(request,java.util.Collections.singletonList(new FileEntry(fixture.getPath(),true,0,0)),()->{if(stop.get())throw new java.io.IOException("Injected incomplete directory read");},message->{
            if(message.has("listing")){if(message.optBoolean("complete"))complete.set(true);if(message.getJSONArray("seen").length()==256){chunk.set(true);stop.set(true);}}});
        check(chunk.get()&&!complete.get()&&result.getInt("accessErrors")>0,"partial names chunk never marks a failed listing complete");delete(fixture);fixture=null;
    }
    private void discovery(boolean root)throws Exception{
        id="IndexProbeDiscover"+SystemClock.elapsedRealtimeNanos();ids.add(id);
        fixture=new File(target.getCacheDir(),"IndexProbe-Discover-"+SystemClock.elapsedRealtimeNanos()).getCanonicalFile();
        check(fixture.mkdir(),"create discovery fixture");File folder=new File(fixture,"Download");check(folder.mkdir(),"create download fixture");
        File deleted=new File(folder,"delete-offline.txt");write(deleted,"old");
        index=open(root);index.startScan();await(()->index.getState().lastScanMillis>0&&!index.hasPendingWork(),"discovery baseline");
        int whole=index.wholeReconcileStartsForProbe(),full=index.fullScanStartsForProbe();
        check(NativeDiscovery.availableForProbe(),"C++ discovery library is loaded");
        File added=new File(folder,"new-😀.txt");write(added,"new");check(deleted.delete(),"delete before foreground discovery");
        // Force a distinct folder mtime even on filesystems with coarse timestamp granularity.
        check(folder.setLastModified(System.currentTimeMillis()+2000),"set changed directory stamp");
        long start=SystemClock.elapsedRealtime();index.discoverOnOpen(folder.getPath());
        await(()->!index.hasPendingWork()&&find(added)!=null,"foreground native discovery finds new file");
        long latency=SystemClock.elapsedRealtime()-start;
        check(find(deleted)==null,"complete shallow listing removes deleted child");
        check(!index.getState().scanning&&index.wholeReconcileStartsForProbe()==whole&&index.fullScanStartsForProbe()==full,"foreground discovery does not rebuild or check whole index");
        List<FileEntry> unchanged=index.getState().entries;SystemClock.sleep(550);index.discoverOnOpen(folder.getPath());
        await(()->!index.hasPendingWork(),"unchanged discovery settles");check(index.getState().entries==unchanged,"unchanged native discovery retains snapshot");
        // Simulate devices whose FUSE mount does not propagate the other writer's watch
        // events. This calls the exact foreground poll used by the Activity.
        File elsewhere=new File(fixture,"elsewhere");
        check(elsewhere.mkdir(),"create outside-common destination");
        check(fixture.setLastModified(System.currentTimeMillis()+3000),"set changed root stamp");
        index.discoverOnOpen(fixture.getPath());
        await(()->!index.hasPendingWork()&&find(elsewhere)!=null,"index new move destination");
        check(index.getState().watchCount==0,"tests use foreground discovery without FileObserver");
        String foreground=folder.getPath();
        index.probeForegroundChanges(foreground);
        await(()->!index.hasPendingWork(),"foreground stamp baseline");
        File created=new File(folder,"fresh.txt"),renamed=new File(folder,"renamed-fresh.txt");
        File moved=new File(elsewhere,"moved-fresh.txt");
        long stamp=System.currentTimeMillis()+10_000;
        write(created,"fresh");check(folder.setLastModified(stamp),"changed source stamp");
        long createMs=awaitForeground(foreground,()->find(created)!=null,"external create");
        check(created.renameTo(renamed)&&folder.setLastModified(stamp+2000),"external rename");
        long renameMs=awaitForeground(foreground,()->find(created)==null&&find(renamed)!=null,"external rename");
        check(renamed.renameTo(moved)&&folder.setLastModified(stamp+4000)
                &&elsewhere.setLastModified(stamp+4000),"external move");
        long moveMs=awaitForeground(elsewhere.getPath(),()->find(renamed)==null&&find(moved)!=null,"external move");
        check(moved.delete()&&elsewhere.setLastModified(stamp+6000),"external delete");
        long deleteMs=awaitForeground(elsewhere.getPath(),()->find(moved)==null,"external delete");
        check(index.fullScanStartsForProbe()==full&&index.wholeReconcileStartsForProbe()==whole,
                "foreground create, rename, move and delete never reconcile the whole index");
        report.append("Foreground create/rename/move/delete (ms): ")
                .append(createMs).append('/').append(renameMs).append('/').append(moveMs).append('/').append(deleteMs).append('\n');
        index.setProbeLiveWatching(true);index.refresh();await(()->!index.hasPendingWork(),"enable live watches");
        check(index.getState().watchCount>0,"shared and root mode request live watches");
        File live=new File(folder,"live.txt");start=SystemClock.elapsedRealtime();write(live,"live");
        await(()->find(live)!=null,"live event reaches index without reopen or scan");long liveLatency=SystemClock.elapsedRealtime()-start;
        check(live.delete(),"delete live fixture");await(()->find(live)==null,"live deletion reaches index");
        report.append(root?"Root ":"Shared ").append("native foreground discovery ").append(latency).append("ms; live event ").append(liveLatency).append("ms\n");
        close();delete(fixture);fixture=null;
    }
    private long awaitForeground(String folder,BooleanSupplier condition,String label)throws Exception{
        long start=SystemClock.elapsedRealtime();
        for(int i=0;i<12;i++){
            index.probeForegroundChanges(folder);
            long deadline=SystemClock.elapsedRealtime()+150;
            while(SystemClock.elapsedRealtime()<deadline){
                if(condition.getAsBoolean()&&!index.hasPendingWork())return SystemClock.elapsedRealtime()-start;
                SystemClock.sleep(10);
            }
        }
        throw new AssertionError("Foreground check timed out: "+label);
    }
    private FileIndex open(boolean root)throws Exception{FileIndex opened=FileIndex.forProbe(target,fixture,id);opened.setProbeLiveWatching(false);opened.setProbeFolderSnapshots(folderSnapshots);opened.setRootEnabled(root);return opened;}
    private FileEntry find(File file){String path=file.getAbsolutePath();for(FileEntry entry:index.getState().entries)if(entry.path().equals(path))return entry;return null;}
    private void close()throws Exception{index.shutdown();check(index.awaitShutdown(15_000),"index workers and helper stop");index=null;}
    private void await(BooleanSupplier condition,String label){long deadline=SystemClock.elapsedRealtime()+30_000;while(SystemClock.elapsedRealtime()<deadline){if(condition.getAsBoolean())return;SystemClock.sleep(50);}FileIndex.State state=index.getState();throw new AssertionError("Timed out: "+label+"; root="+index.isRootEnabled()+"; scanning="+state.scanning+"; status="+state.status+"; error="+state.error);}
    private void check(boolean value,String label){assertions++;if(!value)throw new AssertionError(label);}
    private static void write(File file,String text)throws Exception{try(FileOutputStream out=new FileOutputStream(file)){out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));}}
    private static void delete(File file)throws Exception{if(!java.nio.file.Files.isSymbolicLink(file.toPath())){File[] children=file.listFiles();if(children!=null)for(File child:children)delete(child);}if(!file.delete())throw new java.io.IOException("Fixture cleanup failed");}
    private void cleanup()throws Exception{if(foreground!=null)runOnMainSync(foreground::finish);if(index!=null)close();if(fixture!=null)delete(fixture);if(target!=null)for(String name:ids){target.deleteDatabase(name+"-root-filenames.db");target.deleteDatabase(name+"-shared-filenames.db");target.deleteSharedPreferences(name+"-filename-index");}}
}
