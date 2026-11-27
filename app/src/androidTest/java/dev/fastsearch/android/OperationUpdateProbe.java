package dev.fastsearch.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import dev.fastsearch.core.FileEntry;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Actual foreground service operations against isolated indexes, with unrelated rows as a sweep sentinel. */
public final class OperationUpdateProbe extends Instrumentation {
    private Context target;
    private File fixture;
    private FileIndex index;
    private MainActivity activity;
    private String id;
    private int assertions, builds, checks;
    private boolean includeRoot=true;
    private long scanTime;
    private final StringBuilder report=new StringBuilder();
    private final AtomicBoolean scanning=new AtomicBoolean();
    @Override public void onCreate(Bundle args){super.onCreate(args);includeRoot=args==null||!"false".equals(args.getString("root"));start();}
    @Override public void onStart(){boolean passed=false;
        try{target=getTargetContext();exercise(false);if(includeRoot)exercise(true);passed=true;}
        catch(Throwable error){java.io.StringWriter trace=new java.io.StringWriter();error.printStackTrace(new java.io.PrintWriter(trace));report.append(trace);}
        finally{try{cleanup();}catch(Exception error){passed=false;report.append(error);}}
        Bundle result=new Bundle();result.putBoolean("passed",passed);result.putInt("assertions",assertions);
        result.putString("stream",report.append(passed?"PASS ":"FAIL ").append(assertions).append(" operation-index assertions.\n").toString());
        finish(passed?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
    private void exercise(boolean root)throws Exception{
        id="IndexProbeOperation"+SystemClock.elapsedRealtimeNanos();
        fixture=new File(target.getCacheDir(),"IndexProbe-Operation-"+SystemClock.elapsedRealtimeNanos()).getCanonicalFile();
        check(fixture.mkdir(),"owned operation fixture");
        File source=directory(fixture,"source"),destination=directory(fixture,"destination"),other=directory(fixture,"unrelated");
        File item=new File(source,"item.txt"),collision=new File(source,"collision.txt");write(item,"owned bytes");write(collision,"source bytes");
        File existing=new File(destination,"collision.txt");write(existing,"keep existing");
        File folder=directory(source,"tree");for(int i=0;i<6;i++)write(new File(folder,"part-"+i+".txt"),"part");
        File sentinel=new File(other,"sentinel.txt");write(sentinel,"old");for(int i=0;i<240;i++)write(new File(other,"unchanged-"+i+".txt"),"unchanged");
        index=FileIndex.forProbe(target,fixture,id);index.setProbeLiveWatching(false);index.setRootEnabled(root);
        index.startScan();await(()->index.getState().lastScanMillis>0&&!index.getState().scanning,"initial fixture index");
        FileIndex.installForProbe(index);MainActivity.setProbeIndex(index);
        activity=(MainActivity)startActivitySync(new Intent(target,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
        index.addListener(state->{if(state.scanning)scanning.set(true);});waitForIdleSync();scanning.set(false);
        builds=index.fullScanStartsForProbe();checks=index.wholeReconcileStartsForProbe();scanTime=index.getState().lastScanMillis;
        long sentinelSize=find(sentinel).size;write(sentinel,"unrelated change must not be verified by this operation");
        List<FileEntry> before=index.getState().entries;
        FileClipboard.save(target,Collections.singletonList(entry(item)),false,root);
        runOnMainSync(()->{try{FileOperationService.start(activity,destination.getPath());}catch(Exception e){throw new RuntimeException(e);}});
        finished(false);File copied=new File(destination,item.getName());
        check(copied.isFile()&&find(copied)!=null,"real copy service commits destination delta before completion");
        check(before.stream().noneMatch(e->e.path().equals(copied.getPath())),"previous published snapshot remains immutable");unchanged(sentinel,sentinelSize);
        FileClipboard.Snapshot deletion=FileClipboard.capture(target,Collections.singletonList(entry(copied)),true,root);
        long start=SystemClock.elapsedRealtime();runOnMainSync(()->{try{FileOperationService.delete(activity,deletion);}catch(Exception e){throw new RuntimeException(e);}});
        finished(false);check(!copied.exists()&&find(copied)==null,"real delete service removes the file from disk and index");unchanged(sentinel,sentinelSize);
        report.append(root?"Root":"Shared").append(" service delete through index commit: ").append(SystemClock.elapsedRealtime()-start).append(" ms.\n");
        FileClipboard.save(target,Collections.singletonList(entry(item)),true,root);
        runOnMainSync(()->{try{FileOperationService.start(activity,destination.getPath());}catch(Exception e){throw new RuntimeException(e);}});
        finished(false);check(!item.exists()&&find(item)==null&&find(copied)!=null,"real move service updates both source and destination");unchanged(sentinel,sentinelSize);
        check(FileClipboard.count(target)==0,"acknowledged move clipboard is cleared");
        FileClipboard.save(target,Collections.singletonList(entry(collision)),false,root);
        runOnMainSync(()->{try{FileOperationService.start(activity,destination.getPath());}catch(Exception e){throw new RuntimeException(e);}});
        finished(true);check(existing.length()=="keep existing".length()&&collision.exists(),"failed preflight preserves collision and source");unchanged(sentinel,sentinelSize);
        String clipboardBefore=FileClipboard.read(target).id;
        File dual=new File(source,"dual-pane.txt");write(dual,"pane bytes");
        FileClipboard.Snapshot directCopy=FileClipboard.capture(target,Collections.singletonList(entry(dual)),false,root);
        runOnMainSync(()->{try{FileOperationService.transfer(activity,destination.getPath(),directCopy);}catch(Exception e){throw new RuntimeException(e);}});
        finished(false);File directTarget=new File(destination,dual.getName());
        check(dual.isFile()&&directTarget.isFile()&&find(directTarget)!=null,"direct pane copy preserves source and commits index changes");
        check(clipboardBefore.equals(FileClipboard.read(target).id),"direct transfer does not replace the user's clipboard");
        FileClipboard.Snapshot conflictingMove=FileClipboard.capture(target,Collections.singletonList(entry(dual)),true,root);
        write(directTarget,"keep pane target");
        runOnMainSync(()->{try{FileOperationService.transfer(activity,destination.getPath(),conflictingMove);}catch(Exception e){throw new RuntimeException(e);}});
        finished(true);
        check(dual.isFile()&&directTarget.length()=="keep pane target".length(),"direct move refuses to replace an existing file");
        runOnMainSync(()->{try{FileOperationService.transfer(activity,other.getPath(),conflictingMove);}catch(Exception e){throw new RuntimeException(e);}});
        finished(false);
        check(!dual.exists()&&new File(other,dual.getName()).isFile(),"direct pane move commits after a safe confirmation");
        check(clipboardBefore.equals(FileClipboard.read(target).id),"direct move does not clear unrelated clipboard data");
        unchanged(sentinel,sentinelSize);
        File part=new File(folder,"part-0.txt");check(part.delete(),"simulate a deletion interrupted within a selected directory");
        File added=new File(folder,"new-after-partial.txt");write(added,"new");
        JSONObject partial=index.reconcilePaths(Arrays.asList(folder.getPath(),added.getPath())).get(30,TimeUnit.SECONDS);
        check(find(part)==null&&find(added)!=null&&find(new File(folder,"part-1.txt"))!=null,"partial-tree recovery removes missing entries and retains surviving files");
        check(partial.getInt("snapshotRows")<=8&&partial.getInt("checkedRows")<20,"partial recovery payload and metadata work stay within the selected tree");unchanged(sentinel,sentinelSize);
        File renameSource=new File(source,"rename.txt");write(renameSource,"rename bytes");index.reconcilePaths(Collections.singletonList(renameSource.getPath())).get(30,TimeUnit.SECONDS);
        FileClipboard.Snapshot renameSnapshot=FileClipboard.capture(target,Collections.singletonList(entry(renameSource)),true,root);
        runOnMainSync(()->{try{FileOperationService.rename(activity,renameSnapshot,Collections.singletonMap(renameSource.getPath(),"renamed.txt"));}catch(Exception e){throw new RuntimeException(e);}});
        finished(false);File renameTarget=new File(source,"renamed.txt");check(!renameSource.exists()&&find(renameSource)==null&&renameTarget.exists()&&find(renameTarget)!=null,"real rename service updates old and new paths");unchanged(sentinel,sentinelSize);
        FileClipboard.Snapshot tree=FileClipboard.capture(target,Collections.singletonList(entry(folder)),true,root);
        runOnMainSync(()->{try{FileOperationService.delete(activity,tree);}catch(Exception e){throw new RuntimeException(e);}});
        finished(false);check(!folder.exists()&&index.getState().entries.stream().noneMatch(e->e.path().equals(folder.getPath())||e.path().startsWith(folder.getPath()+"/")),"real recursive delete removes cached descendants");unchanged(sentinel,sentinelSize);
        File cancel=new File(source,"cancel.txt");write(cancel,"before");index.reconcilePaths(Collections.singletonList(cancel.getPath())).get(30,TimeUnit.SECONDS);
        List<FileEntry> saved=index.getState().entries;write(cancel,"changed but cancelled");
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        index.setTransactionProbe(()->{entered.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        CompletableFuture<JSONObject> pending=index.reconcilePaths(Collections.singletonList(cancel.getPath()));
        check(entered.await(20,TimeUnit.SECONDS),"targeted update reaches real SQLite transaction");index.cancelScan();release.countDown();
        boolean cancelled=false;try{pending.get(30,TimeUnit.SECONDS);}catch(java.util.concurrent.ExecutionException expected){cancelled=true;}
        check(cancelled&&index.getState().entries==saved&&find(cancel).size=="before".length(),"cancelled targeted transaction retains the exact snapshot");index.setTransactionProbe(null);
        closeActivity();index.shutdown();check(index.awaitShutdown(15_000),"operation index workers close");index=null;
        index=FileIndex.forProbe(target,fixture,id);index.setProbeLiveWatching(false);index.setRootEnabled(root);index.load();await(()->index.getState().loaded,"reload operation deltas");
        check(find(item)==null&&find(copied)!=null&&find(folder)==null&&find(cancel).size=="before".length(),"targeted changes persist and cancelled data stays rolled back");
        check(index.fullScanStartsForProbe()==0&&index.wholeReconcileStartsForProbe()==0,"cache reopen alone performs no rebuild or metadata sweep");
        cleanup();
    }
    private void unchanged(File sentinel,long size){
        check(index.fullScanStartsForProbe()==builds,"operation starts no full build");
        check(index.wholeReconcileStartsForProbe()==checks,"operation starts no whole-cache verification");
        check(index.getState().lastScanMillis==scanTime,"operation does not advance whole-index verification timestamp");
        check(!scanning.get()&&!index.getState().scanning,"operation never publishes whole-index loading state");
        check(find(sentinel).size==size,"unrelated changed file is not visited or refreshed");
    }
    private void finished(boolean failure){await(()->!FileOperationService.getState().running&&!busy(),"real operation service finishes after index commit");waitForIdleSync();
        check(FileOperationService.getState().error.isEmpty()!=failure,"operation outcome matches expected success or preflight failure: "+FileOperationService.getState().error);}
    private boolean busy(){try{java.lang.reflect.Field f=FileOperationService.class.getDeclaredField("BUSY");f.setAccessible(true);return ((AtomicBoolean)f.get(null)).get();}catch(Exception e){throw new AssertionError(e);}}
    private FileEntry find(File file){for(FileEntry e:index.getState().entries)if(e.path().equals(file.getPath()))return e;return null;}
    private static FileEntry entry(File file){return new FileEntry(file.getPath(),file.isDirectory(),file.length(),file.lastModified());}
    private File directory(File parent,String name){File dir=new File(parent,name);check(dir.mkdir(),"create fixture directory");return dir;}
    private static void write(File file,String text)throws Exception{try(FileOutputStream out=new FileOutputStream(file)){out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));}}
    private void await(BooleanSupplier ready,String label){long deadline=SystemClock.elapsedRealtime()+40_000;while(SystemClock.elapsedRealtime()<deadline){if(ready.getAsBoolean())return;SystemClock.sleep(30);}throw new AssertionError(label+"; operation="+FileOperationService.getState().status+"; index="+index.getState().error);}
    private void check(boolean value,String label){assertions++;if(!value)throw new AssertionError(label);}
    private void closeActivity(){if(activity!=null){runOnMainSync(activity::finish);waitForIdleSync();activity=null;}MainActivity.setProbeIndex(null);}
    private void cleanup()throws Exception{closeActivity();if(index!=null){index.setTransactionProbe(null);index.shutdown();index.awaitShutdown(15_000);index=null;}
        if(fixture!=null&&fixture.exists())remove(fixture);fixture=null;
        if(id!=null&&target!=null){target.deleteDatabase(id+"-root-filenames.db");target.deleteDatabase(id+"-shared-filenames.db");target.deleteSharedPreferences(id+"-filename-index");FileClipboard.clear(target);}id=null;}
    private static void remove(File path)throws Exception{if(!java.nio.file.Files.isSymbolicLink(path.toPath())){File[] children=path.listFiles();if(children!=null)for(File child:children)remove(child);}if(!path.delete())throw new java.io.IOException("Owned fixture cleanup failed.");}
}
