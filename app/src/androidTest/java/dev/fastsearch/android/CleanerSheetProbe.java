package dev.fastsearch.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import dev.fastsearch.core.FileEntry;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Runs the actual Cleaner view against an isolated synthetic scanner; never touches shared files. */
public final class CleanerSheetProbe extends Instrumentation {
    private int checks;
    private boolean realShared,previewOnly;
    private final AtomicReference<Throwable> callbackFailure=new AtomicReference<>();
    @Override public void onCreate(Bundle args){super.onCreate(args);
        realShared=args!=null&&"true".equals(args.getString("emulatorShared"));
        previewOnly=args!=null&&"preview".equals(args.getString("phase"));
        start();
    }
    @Override public void onStart(){
        Bundle result=new Bundle();
        Activity host=null;boolean passed=false;
        try {
            if(!getTargetContext().getPackageName().endsWith(".fresh"))throw new AssertionError("Isolated build required");
            host=startActivitySync(new Intent(getTargetContext(),MainActivity.class)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("permissionProbe",true));
            if(previewOnly){
                check(realShared,"the shared preview phase requires emulatorShared=true");
                exerciseInPlacePreview(host);
            }else{
                if(realShared){exerciseSharedEngine();exerciseThumbnailCache();exerciseDownloadRecommendations();}
                exercise(host);exerciseDuplicateReview(host);verifySidebar(host);
            }
            passed=true;
            result.putString("stream","PASS "+checks+" Cleaner UI assertions."+System.lineSeparator());
        }catch(Throwable failure){result.putString("stream",android.util.Log.getStackTraceString(failure));}
        finally{if(host!=null){Activity open=host;runOnMainSync(open::finish);}}
        result.putBoolean("passed",passed);result.putInt("assertions",checks);
        finish(passed?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
    private void exerciseSharedEngine()throws Exception {
        String fingerprint=android.os.Build.FINGERPRINT;
        check(fingerprint.contains("generic")||fingerprint.contains("sdk_gphone")||fingerprint.contains("sdk_phone"),
                "real shared fixture writes are allowed only on the disposable emulator");
        java.nio.file.Path root=android.os.Environment.getExternalStorageDirectory().getCanonicalFile().toPath();
        java.nio.file.Path download=root.resolve(android.os.Environment.DIRECTORY_DOWNLOADS);
        java.nio.file.Path scope=java.nio.file.Files.createTempDirectory(download,"Anything-Cleaner-Check-");
        java.nio.file.Path one=scope.resolve("original.a21probe"),two=scope.resolve("copy.a21probe"),different=scope.resolve("different.a21probe");
        try{
            java.nio.file.Files.write(one,new byte[]{5,9,1,7});
            java.nio.file.Files.write(two,new byte[]{5,9,1,7});
            java.nio.file.Files.write(different,new byte[]{5,9,2,7});
            CleanerSheet.Request request=new CleanerSheet.Request(scope.toString(),CleanerSheet.Mode.DUPLICATES,
                    0L,0L,"",Collections.emptyList(),Collections.singleton(root.toString()));
            CleanerSheet.Result result=CleanerReviewScanner.INSTANCE.scan(request,()->false,(message,visited)->{});
            check(!result.limited&&result.rows.size()==2&&result.rows.get(0).group.equals(result.rows.get(1).group),
                    "actual Android shared-folder scan groups only the exact duplicate pair");
            check(result.rows.stream().filter(row->row.suggestedKeep).count()==1,"one independent keeper is suggested");
            check(result.duplicateGroups.size()==1&&result.duplicateGroups.get(0).proof.members.size()==2
                    &&result.duplicateGroups.get(0).possibleBytes()==4L,"real scan carries exact proof and reclaimable bytes");
            CleanerSheet.Request defaultScope=new CleanerSheet.Request(null,CleanerSheet.Mode.DUPLICATES,
                    0L,0L,"a21probe",Collections.emptyList(),Collections.singleton(root.toString()));
            CleanerSheet.Result standard=CleanerReviewScanner.INSTANCE.scan(defaultScope,()->false,(message,visited)->{});
            check(standard.rows.size()==2&&standard.rows.stream().allMatch(row->row.entry.parent.equals(scope.toString())),
                    "default storage scan finds the exact synthetic pair without a chosen folder");
            check(java.nio.file.Files.exists(one)&&java.nio.file.Files.exists(two)&&java.nio.file.Files.exists(different),
                    "actual engine scan leaves every fixture intact");
        }finally{
            java.nio.file.Files.deleteIfExists(one);java.nio.file.Files.deleteIfExists(two);
            java.nio.file.Files.deleteIfExists(different);java.nio.file.Files.deleteIfExists(scope);
        }
    }
    private void exerciseDownloadRecommendations()throws Exception{
        java.nio.file.Path volume=android.os.Environment.getExternalStorageDirectory().getCanonicalFile().toPath();
        java.nio.file.Path downloads=volume.resolve(android.os.Environment.DIRECTORY_DOWNLOADS);
        String suffix=Long.toString(android.os.SystemClock.elapsedRealtimeNanos());
        java.nio.file.Path older=downloads.resolve("Anything-older-"+suffix+".apk");
        java.nio.file.Path recent=downloads.resolve("Anything-recent-"+suffix+".apk");
        java.nio.file.Path backup=downloads.resolve("Anything-backup-"+suffix+".backup.apk");
        try{
            java.nio.file.Files.write(older,new byte[]{1,2,3});
            java.nio.file.Files.write(recent,new byte[]{4});
            java.nio.file.Files.write(backup,new byte[]{5,6});
            CleanerSheet.Request request=new CleanerSheet.Request(downloads.toString(),CleanerSheet.Mode.DOWNLOADS,
                    0L,0L,"apk",Collections.emptyList(),Collections.singleton(volume.toString()));
            CleanerSheet.Result scanned=CleanerReviewScanner.INSTANCE.scan(request,()->false,(message,visited)->{});
            CleanerSheet.Row olderRow=scanned.rows.stream().filter(row->row.entry.pathEquals(older.toString())).findFirst().get();
            check(!olderRow.suggestedReview&&olderRow.reason.contains("newer"),"fresh installer is discoverable but not recommended");
            CleanerSheet.Row backupRow=scanned.rows.stream().filter(row->row.entry.pathEquals(backup.toString())).findFirst().get();
            check(!backupRow.suggestedReview&&backupRow.reason.contains("backup"),"backup installer stays excluded");
            check(scanned.rows.stream().anyMatch(row->row.entry.pathEquals(recent.toString())),
                    "other Downloads results stay reviewable");
            // API 26 shared storage can refuse app-level timestamp writes. Advance
            // only the rule clock against the actual observed row metadata instead.
            dev.fastsearch.core.CleanerRules.Filter filter=new dev.fastsearch.core.CleanerRules.Filter(
                    Collections.singleton(volume.toString()),0L,0L,downloads.toString(),"apk");
            dev.fastsearch.core.CleanerRecommendations.Item aged=dev.fastsearch.core.CleanerRecommendations.classify(
                    olderRow.entry,olderRow.entry.modified+40L*24*60*60*1000,filter,false);
            check(aged!=null&&aged.rule==dev.fastsearch.core.CleanerRecommendations.Rule.DOWNLOAD_INSTALLER,
                    "an observed installer becomes a time-based review suggestion after the threshold");
            check(java.nio.file.Files.exists(older)&&java.nio.file.Files.exists(recent)&&java.nio.file.Files.exists(backup),
                    "recommendation engine does not change files");
        }finally{
            java.nio.file.Files.deleteIfExists(older);
            java.nio.file.Files.deleteIfExists(recent);
            java.nio.file.Files.deleteIfExists(backup);
        }
    }
    private void exerciseThumbnailCache()throws Exception{
        java.nio.file.Path isolated=java.nio.file.Files.createTempDirectory(getTargetContext().getCacheDir().toPath(),
                "anything-owned-cache-probe-");
        android.content.Context sandbox=new android.content.ContextWrapper(getTargetContext()){
            @Override public java.io.File getCacheDir(){return isolated.toFile();}
        };
        java.nio.file.Path directory=isolated.resolve(".thumbnails-v2");
        java.nio.file.Files.createDirectories(directory);
        java.nio.file.Path one=directory.resolve(hex('a')+"-"+hex('a')+".thumb");
        java.nio.file.Path changed=directory.resolve(hex('b')+"-"+hex('b')+".thumb");
        java.nio.file.Path added=directory.resolve(hex('c')+"-"+hex('c')+".thumb");
        java.nio.file.Path unknown=directory.resolve("not-a-thumbnail");
        java.nio.file.Path link=directory.resolve(hex('d')+"-"+hex('d')+".thumb");
        try{
            java.nio.file.Files.write(one,new byte[]{2,3,4});
            java.nio.file.Files.write(changed,new byte[]{4,5,6});
            java.nio.file.Files.write(unknown,new byte[]{7});
            java.nio.file.Files.createSymbolicLink(link,unknown);
            ThumbnailStore.CacheReview review=ThumbnailStore.reviewCache(sandbox);
            check(review.entries.size()==2&&review.bytes==6,"only real generated files are previewed");
            check(java.nio.file.Files.exists(one),"preview changes nothing");
            java.nio.file.Files.write(changed,new byte[]{1,2,3,4});
            java.nio.file.Files.write(added,new byte[]{5});
            ThumbnailStore.CacheReceipt receipt=ThumbnailStore.clearReviewedCache(sandbox,review);
            check(receipt.removed==1&&receipt.bytes==3&&receipt.skipped==2,"receipt reflects one real removal and skips");
            check(!java.nio.file.Files.exists(one)&&java.nio.file.Files.exists(changed)&&java.nio.file.Files.exists(added)
                    &&java.nio.file.Files.isSymbolicLink(link)&&java.nio.file.Files.size(unknown)==1,
                    "changed, added, link and unrelated files stay intact");
        }finally{
            for(java.nio.file.Path file:new java.nio.file.Path[]{one,changed,added,link,unknown})
                java.nio.file.Files.deleteIfExists(file);
            java.nio.file.Files.deleteIfExists(directory);
            java.nio.file.Files.deleteIfExists(isolated);
        }
    }
    private static String hex(char value){char[] chars=new char[64];java.util.Arrays.fill(chars,value);return new String(chars);}

    private void exercise(Activity host)throws Exception {
        String volume="/storage/emulated/0",scope=volume+"/CleanerSynthetic";
        AtomicReference<CleanerSheet.Request> requested=new AtomicReference<>();
        AtomicInteger scans=new AtomicInteger();
        CountDownLatch started=new CountDownLatch(1),stopped=new CountDownLatch(1);
        AtomicBoolean dismissed=new AtomicBoolean();
        CleanerSheet.Scanner fake=(request,cancel,progress)->{
            requested.set(request);
            if(scans.incrementAndGet()==1){
                started.countDown();
                try{
                    while(!cancel.getAsBoolean())Thread.sleep(20);
                }finally{stopped.countDown();}
                throw new java.io.InterruptedIOException("synthetic");
            }
            FileEntry first=new FileEntry(scope+"/original.txt",false,100,10);
            FileEntry second=new FileEntry(scope+"/alternative.txt",false,100,20);
            return new CleanerSheet.Result(Arrays.asList(new CleanerSheet.Row(first,"Original name","Exact match 1",true),
                    new CleanerSheet.Row(second,"Identical bytes","Exact match 1",false)),24,1,false);
        };
        AtomicReference<CleanerSheet> panel=new AtomicReference<>();
        AtomicReference<AppSheet> shown=new AtomicReference<>();
        main(()->{
            java.lang.reflect.Constructor<CleanerSheet> create=CleanerSheet.class.getDeclaredConstructor(Activity.class,
                    FileIndex.class,java.util.Map.class,CleanerSheet.Scanner.class,java.util.function.Consumer.class,java.util.function.Consumer.class);
            create.setAccessible(true);
            panel.set(create.newInstance(host,FileIndex.get(host),Collections.singletonMap(volume,"Internal storage"),
                    fake,(java.util.function.Consumer<AppSheet>)ignored->dismissed.set(true),null));
            shown.set((AppSheet)get(panel.get(),"sheet"));
            shown.get().show();
            check(button(shown.get().actions,"Scan storage").isEnabled()&&scans.get()==0,
                    "storage scan is available without a folder and opening doesn’t scan");
            check(find(shown.get().content,"Choose a folder (optional)")!=null,"folder choice is optional");
            check(find(shown.get().content,"Scanning never changes your files. Cleanup is separate and asks first.")!=null,
                    "read-only intent is visible");
            check(!CleanerSheet.allowedScope(Collections.singleton(volume),volume+"9/Download")
                    &&!CleanerSheet.allowedScope(Collections.singleton(volume),volume+"/Android/data"),
                    "lookalike and private folder rejected");
            button(shown.get().actions,"Scan storage").performClick();
        });
        check(started.await(6,TimeUnit.SECONDS),"scan executes in worker");
        check(requested.get().scope==null&&requested.get().sharedRoots.equals(Collections.singletonList(volume)),
                "standard scan passes all allowed shared volumes without requiring a chosen folder");
        main(()->button(shown.get().actions,"Cancel scan").performClick());
        check(stopped.await(6,TimeUnit.SECONDS),"worker observes cancellation");
        main(()->{
            check(find(shown.get().content,"Scan canceled. No files changed.")!=null,"clear canceled status");
            choose(panel.get(),scope);
            check(scans.get()==1&&button(shown.get().actions,"Scan folder").isEnabled(),
                    "choosing a shared folder narrows the next explicit scan");
            button(shown.get().actions,"Scan folder").performClick();
        });
        await(()->readRows(panel.get())==2,"virtual result adapter receives both duplicates");
        CleanerSheet.Request request=requested.get();
        check(scope.equals(request.scope)&&request.mode==CleanerSheet.Mode.DUPLICATES&&request.excludedFolders.isEmpty(),
                "exact scope and parameters handed to engine");
        main(()->{
            ListView files=(ListView)get(panel.get(),"files");
            CleanerSheet.Row a=(CleanerSheet.Row)files.getAdapter().getItem(0);
            CleanerSheet.Row b=(CleanerSheet.Row)files.getAdapter().getItem(1);
            check(a.suggestedKeep&&!b.suggestedKeep&&a.group.equals(b.group),"group retains at least one suggested keep");
            check(find(shown.get().actions,"Delete")==null&&find(shown.get().content,"Remove")==null,
                    "no destructive control");
            shown.get().dismiss();
        });
        await(dismissed::get,"dismiss callback completed");
    }
    private void exerciseDuplicateReview(Activity host)throws Exception{
        String volume="/storage/emulated/0";
        String scope=volume+"/DuplicateReviewSynthetic";
        FileEntry vault=new FileEntry(scope+"/Vault/original.txt",false,4,10);
        FileEntry backup=new FileEntry(scope+"/Download/restore.bak",false,4,10);
        FileEntry copy=new FileEntry(scope+"/Download/copy.txt",false,4,20);
        FileEntry other=new FileEntry(scope+"/Temp/copy.txt",false,4,30);
        dev.fastsearch.core.DuplicateFinder.Access access=new dev.fastsearch.core.DuplicateFinder.Access(){
            @Override public boolean eligible(FileEntry entry){return true;}
            @Override public dev.fastsearch.core.DuplicateFinder.ReadFile open(FileEntry entry){
                dev.fastsearch.core.DuplicateFinder.Stamp stamp=new dev.fastsearch.core.DuplicateFinder.Stamp(entry.path(),"1",4,1);
                java.io.InputStream bytes=new java.io.ByteArrayInputStream(new byte[]{5,9,1,7});
                return new dev.fastsearch.core.DuplicateFinder.ReadFile(){
                    @Override public dev.fastsearch.core.DuplicateFinder.Stamp stamp(){return stamp;}
                    @Override public java.io.InputStream stream(){return bytes;}
                    @Override public boolean stillSame(dev.fastsearch.core.DuplicateFinder.Stamp before){return stamp.same(before);}
                    @Override public void close(){}
                };
            }
        };
        CleanerSheet.Scanner scanner=(request,cancel,progress)->CleanerReviewScanner.duplicates(
                dev.fastsearch.core.DuplicateFinder.scan(Arrays.asList(vault,backup,copy,other),access,
                    dev.fastsearch.core.DuplicateFinder.Limits.DEFAULT,cancel),new CleanerReviewScanner.WalkTotals(),cancel);
        AtomicReference<CleanerSheet> panel=new AtomicReference<>();
        AtomicReference<AppSheet> shown=new AtomicReference<>();
        main(()->{
            java.lang.reflect.Constructor<CleanerSheet> create=CleanerSheet.class.getDeclaredConstructor(Activity.class,
                    FileIndex.class,java.util.Map.class,CleanerSheet.Scanner.class,java.util.function.Consumer.class,java.util.function.Consumer.class);
            create.setAccessible(true);
            panel.set(create.newInstance(host,FileIndex.get(host),Collections.singletonMap(volume,"Internal storage"),scanner,null,null));
            shown.set((AppSheet)get(panel.get(),"sheet"));shown.get().show();
            button(shown.get().actions,"Scan storage").performClick();
        });
        await(()->readRows(panel.get())==5,"virtual list shows one exact-group header above four files");
        main(()->{
            CleanerSheet screen=panel.get();
            ListView files=(ListView)get(screen,"files");
            DuplicateReview.Group group=(DuplicateReview.Group)files.getAdapter().getItem(0);
            View heading=files.getAdapter().getView(0,null,files);
            check(find(heading,"Exact match 1  ·  4 copies")!=null
                    &&group.possibleBytes()==8L,"header identifies exact group and logical extras");
            check(screen.duplicateReview.snapshot().isEmpty(),"scanning selects nothing automatically");
            button(shown.get().content,"Select extras").performClick();
            check(screen.duplicateReview.snapshot().size()==2&&screen.duplicateReview.snapshot().stream()
                    .allMatch(choice->choice.keeper.entry.pathEquals(vault.path())&&!choice.target.protectedCopy),
                    "one tap selects only eligible files with a protected keeper");
            check(((TextView)get(screen,"selectionStatus")).getText().toString().contains("Nothing is deleted."),
                    "selection discloses that no files are modified");
            button(shown.get().content,"Clear selection").performClick();
            check(screen.duplicateReview.snapshot().isEmpty(),"clear only resets review selection");
            files.performItemClick(null,0,0);
            AppSheet groupDialog=(AppSheet)get(screen,"secondary");
            button(groupDialog.actions,"Select extras in group").performClick();
            check(screen.duplicateReview.snapshot().size()==2,"group action selects only its extras");
        });
        await(()->{
            AtomicBoolean dismissed=new AtomicBoolean();
            try{main(()->dismissed.set(get(panel.get(),"secondary")==null));}
            catch(Exception failure){throw new AssertionError(failure);}
            return dismissed.get();
        },"the group dialog dismisses before opening a file row");
        main(()->{
            CleanerSheet screen=panel.get();
            ListView files=(ListView)get(screen,"files");
            DuplicateReview.Group group=(DuplicateReview.Group)files.getAdapter().getItem(0);
            int position=-1;
            for(int i=1;i<files.getAdapter().getCount();i++){
                CleanerSheet.Row entry=(CleanerSheet.Row)files.getAdapter().getItem(i);
                if(entry.entry.pathEquals(copy.path()))position=i;
            }
            check(position>0,"eligible copy still has a file row");
            files.performItemClick(null,position,position);
            AppSheet details=(AppSheet)get(screen,"secondary");
            button(details.content,"Keep this copy").performClick();
            check(group.keeper().entry.pathEquals(copy.path())&&screen.duplicateReview.snapshot().size()==1
                    &&screen.duplicateReview.snapshot().get(0).keeper.entry.pathEquals(copy.path()),
                    "changing keeper removes that file from the staged selection");
            ((android.widget.EditText)get(screen,"resultSearch")).setText("Download");
        });
        await(()->{
            AtomicBoolean hidden=new AtomicBoolean();
            try{main(()->hidden.set(((TextView)get(panel.get(),"selectionStatus")).getText().toString().contains("1 hidden by filter")));}
            catch(Exception failure){throw new AssertionError(failure);}
            return hidden.get();
        },"filter retains and counts hidden selections");
        main(()->((android.widget.EditText)get(panel.get(),"resultSearch")).setText(""));
        await(()->readRows(panel.get())==5,"clearing the filter restores all group rows");
        main(()->{
            button(shown.get().content,"Show selected").performClick();
            check(((ListView)get(panel.get(),"files")).getCount()==2,"selected-only view shows one exact group and its selected extra");
            check(button(shown.get().content,"Recheck selected").isEnabled(),"a bounded selection offers a non-mutating recheck");
            shown.get().dismiss();
        });
    }

    private void exerciseInPlacePreview(Activity host)throws Exception {
        String hardware=android.os.Build.HARDWARE;
        check("ranchu".equals(hardware)||"goldfish".equals(hardware),
                "shared fixture writes require a disposable Android emulator");
        java.nio.file.Path volume=android.os.Environment.getExternalStorageDirectory().getCanonicalFile().toPath();
        java.nio.file.Path downloads=volume.resolve(android.os.Environment.DIRECTORY_DOWNLOADS);
        java.nio.file.Files.createDirectories(downloads);
        java.nio.file.Path scratch=java.nio.file.Files.createTempDirectory(downloads,"Anything-Cleaner-Preview-");
        java.nio.file.Path vault=scratch.resolve("Vault");
        java.nio.file.Path replacement=scratch.resolve("same-content-replacement.tmp");
        String text="Owned synthetic Cleaner read-only preview content.";
        byte[] bytes=text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final int extras=24;
        java.util.ArrayList<java.nio.file.Path> fixtures=new java.util.ArrayList<>();
        AtomicReference<CleanerSheet> panel=new AtomicReference<>();
        AtomicReference<AppSheet> owner=new AtomicReference<>(),detail=new AtomicReference<>();
        AtomicReference<CleanerSheet.Row> selected=new AtomicReference<>();
        final int[] position=new int[2];
        try{
            java.nio.file.Files.createDirectory(vault);
            fixtures.add(java.nio.file.Files.write(vault.resolve("review-fixture-original.txt"),bytes));
            for(int i=0;i<extras;i++)fixtures.add(java.nio.file.Files.write(scratch.resolve(
                    String.format(java.util.Locale.ROOT,"review-fixture-%02d.txt",i)),bytes));
            main(()->{
                java.lang.reflect.Constructor<CleanerSheet> ctor=CleanerSheet.class.getDeclaredConstructor(Activity.class,
                        FileIndex.class,java.util.Map.class,CleanerSheet.Scanner.class,java.util.function.Consumer.class,java.util.function.Consumer.class);
                ctor.setAccessible(true);
                panel.set(ctor.newInstance(host,FileIndex.get(host),Collections.singletonMap(volume.toString(),"Internal storage"),
                        CleanerReviewScanner.INSTANCE,null,null));
                owner.set((AppSheet)get(panel.get(),"sheet"));
                set(host,"activeSheet",owner.get());
                java.lang.reflect.Method open=MainActivity.class.getDeclaredMethod("openCleanerPreview",
                        AppSheet.class,FileEntry.class,String.class,Runnable.class);
                open.setAccessible(true);
                set(panel.get(),"preview",(CleanerSheet.PreviewLaunch)(sheet,entry,revision,returned)->{
                    try{open.invoke(host,sheet,entry,revision,returned);}
                    catch(ReflectiveOperationException failed){throw new AssertionError(failed);}
                });
                owner.get().show();
                choose(panel.get(),scratch.toString());
                button(owner.get().actions,"Scan folder").performClick();
            });
            await(()->readRows(panel.get())==extras+2,"production scan finds one exact group and its owned files");
            main(()->((android.widget.EditText)get(panel.get(),"resultSearch")).setText("review-fixture"));
            await(()->mainCondition(()->"review-fixture".equals(get(panel.get(),"resultQuery"))),
                    "result text filter applies before opening preview");
            main(()->{
                button(owner.get().content,"Select matching extras").performClick();
                button(owner.get().content,"Show selected").performClick();
                ListView list=(ListView)get(panel.get(),"files");
                check(list.getCount()==extras+1,"selected-only keeps the group and every eligible owned extra");
                list.setSelectionFromTop(6,-8);
            });
            await(()->mainCondition(()->{
                ListView list=(ListView)get(panel.get(),"files");
                return list.getFirstVisiblePosition()>=5&&list.getChildAt(0)!=null;
            }),"list reaches a noninitial scroll position");
            main(()->{
                ListView list=(ListView)get(panel.get(),"files");
                position[0]=list.getFirstVisiblePosition();position[1]=list.getChildAt(0).getTop();
                selected.set((CleanerSheet.Row)list.getAdapter().getItem(position[0]));
                View row=list.getAdapter().getView(position[0],null,list);
                check(byDescription(row,"Preview "+selected.get().entry.name)!=null,"row shows a separate Preview action");
                list.performItemClick(null,position[0],position[0]);
                detail.set((AppSheet)get(panel.get(),"secondary"));
                check(detail.get()!=null&&detail.get().isShowing(),"review detail stays mounted for the preview handoff");
            });

            ActivityMonitor monitor=addMonitor(FileViewerActivity.class.getName(),null,false);
            FileViewerActivity viewer;
            try{
                main(()->button(detail.get().content,"Preview file").performClick());
                viewer=(FileViewerActivity)monitor.waitForActivityWithTimeout(6500);
            }finally{removeMonitor(monitor);}
            check(viewer!=null,"detail button reaches the real built-in viewer");
            await(()->mainCondition(()->{
                Object shown=get(viewer,"shown");
                return Boolean.TRUE.equals(get(viewer,"cleanerReview"))&&shown!=null
                        &&get(shown,"text") instanceof PreviewText.Result
                        &&text.equals(((PreviewText.Result)get(shown,"text")).text);
            }),"viewer decodes real text in read-only review mode");
            main(()->{
                check(viewer.getIntent().getData()==null&&!viewer.getIntent().hasExtra(Intent.EXTRA_STREAM),
                        "internal preview uses no sharing URI");
                PreviewSession session=(PreviewSession)get(viewer,"session");
                check(session!=null&&!session.root&&session.entries.size()==1
                        &&session.entries.get(0).pathEquals(selected.get().entry.path()),"single ordinary reviewed path, no Root");
                check(!viewer.findViewById(R.id.preview_select).isShown()
                        &&!viewer.findViewById(R.id.preview_copy).isShown(),"file selection and transfer controls are not exposed");
            });
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
            await(()->mainCondition(()->get(host,"activeSheet")==owner.get()&&get(host,"cleanerPreviewOwner")==null),
                    "hardware Back returns to the original Cleaner instance");
            await(()->mainCondition(()->{
                ListView list=(ListView)get(panel.get(),"files");
                return list.getFirstVisiblePosition()==position[0]&&list.getChildAt(0)!=null
                        &&list.getChildAt(0).getTop()==position[1];
            }),"list anchor and exact pixel offset restore");
            main(()->{
                ListView list=(ListView)get(panel.get(),"files");
                check(get(panel.get(),"secondary")==detail.get()&&detail.get().isShowing(),"same file detail is visible first");
                check("review-fixture".equals(get(panel.get(),"resultQuery"))&&Boolean.TRUE.equals(get(panel.get(),"onlySelected"))
                        &&panel.get().duplicateReview.snapshot().size()==extras&&list.getCount()==extras+1
                        &&list.getAdapter().getItem(0) instanceof DuplicateReview.Group,
                        "text filter, selected-only view, group and selections survive Back");
                detail.get().dismiss();
            });
            await(()->mainCondition(()->get(panel.get(),"secondary")==null),"file detail dismisses before row Preview");
            ActivityMonitor directMonitor=addMonitor(FileViewerActivity.class.getName(),null,false);
            try{
                main(()->{
                    ListView list=(ListView)get(panel.get(),"files");
                    View action=byDescription(list.getChildAt(0),"Preview "+selected.get().entry.name);
                    check(action!=null&&action.isShown()&&action.performClick(),"visible result-row Preview opens directly");
                });
                Activity direct=directMonitor.waitForActivityWithTimeout(6500);
                check(direct instanceof FileViewerActivity,"row Preview reaches the same native viewer path");
                await(()->mainCondition(()->Boolean.TRUE.equals(get(direct,"cleanerReview"))&&get(direct,"shown")!=null),
                        "row preview finishes in read-only mode");
                sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
            }finally{removeMonitor(directMonitor);}
            await(()->mainCondition(()->{
                ListView list=(ListView)get(panel.get(),"files");
                return get(host,"cleanerPreviewOwner")==null&&get(panel.get(),"secondary")==null
                        &&list.getFirstVisiblePosition()==position[0]&&list.getChildAt(0)!=null
                        &&list.getChildAt(0).getTop()==position[1]&&panel.get().duplicateReview.selectedCount()==extras;
            }),"Back from a direct row returns to its list, exactly where the player opened it");

            java.nio.file.Path target=java.nio.file.Paths.get(selected.get().entry.path());
            check(Arrays.equals(bytes,java.nio.file.Files.readAllBytes(target)),"file is unchanged by preview");
            java.nio.file.Files.write(replacement,bytes);
            java.nio.file.Files.move(replacement,target,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            ActivityMonitor stale=addMonitor(FileViewerActivity.class.getName(),null,false);
            try{
                main(()->{
                    ListView list=(ListView)get(panel.get(),"files");
                    View row=list.getAdapter().getView(position[0],null,list);
                    View direct=byDescription(row,"Preview "+selected.get().entry.name);
                    check(direct!=null&&direct.performClick(),"same Preview action is tappable directly on its row");
                });
                await(()->mainCondition(()->get(panel.get(),"previewTask")==null),"stale-source check completes");
                check(stale.waitForActivityWithTimeout(500)==null,"same-content replacement is refused: identity no longer matches");
            }finally{removeMonitor(stale);}
            check(Arrays.equals(bytes,java.nio.file.Files.readAllBytes(target)),"stale preview also leaves the owned source intact");
        }finally{
            if(owner.get()!=null)main(()->{owner.get().dismiss();if(get(host,"activeSheet")==owner.get())set(host,"activeSheet",null);});
            for(java.nio.file.Path path:fixtures)java.nio.file.Files.deleteIfExists(path);
            java.nio.file.Files.deleteIfExists(replacement);
            java.nio.file.Files.deleteIfExists(vault);java.nio.file.Files.deleteIfExists(scratch);
        }
    }

    private interface CheckedCondition {boolean matches()throws Exception;}
    private boolean mainCondition(CheckedCondition condition){
        AtomicBoolean result=new AtomicBoolean();
        try{main(()->result.set(condition.matches()));}
        catch(Exception unable){throw new AssertionError(unable);}
        return result.get();
    }
    private static void set(Object owner,String name,Object value)throws Exception{
        java.lang.reflect.Field field=owner.getClass().getDeclaredField(name);
        field.setAccessible(true);field.set(owner,value);
    }

    private void verifySidebar(Activity host)throws Exception {
        for(String[] shortcut:new String[][]{{"Cleaner","Likely junk"},{"Duplicate finder","Exact duplicates"}}){
            main(()->{
                host.findViewById(R.id.browse_locations).performClick();
                StorageDrawer drawer=(StorageDrawer)get(host,"storageDrawer");
                View route=drawer==null?null:drawer.findAction(shortcut[0]);
                check(route!=null&&route.performClick(),"sidebar offers "+shortcut[0]);
            });
            main(()->{
                AppSheet panel=(AppSheet)get(host,"activeSheet");
                View mode=panel==null?null:byDescription(panel.content,"Scan type: "+shortcut[1]);
                check(panel!=null&&panel.isShowing()&&mode instanceof Button&&mode.isSelected(),
                        shortcut[0]+" directly opens its matching scan category");
                check(find(panel.content,"Scope")!=null&&find(panel.content,"Filters")!=null
                        &&find(panel.content,"Backups")==null&&find(panel.content,"Archives")==null,
                        "review categories are separate and default controls remain visible");
                panel.dismiss();
            });
        }
    }

    private int readRows(CleanerSheet panel){
        AtomicInteger rows=new AtomicInteger();
        try{main(()->rows.set(((ListView)get(panel,"files")).getAdapter().getCount()));}
        catch(Exception failure){throw new AssertionError(failure);}
        return rows.get();
    }
    private static void choose(CleanerSheet panel,String path)throws Exception{
        java.lang.reflect.Method select=CleanerSheet.class.getDeclaredMethod("choose",String.class);
        select.setAccessible(true);select.invoke(panel,path);
    }
    private static Object get(Object owner,String name)throws Exception{
        java.lang.reflect.Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);
    }
    private static View find(View view,String title){
        if(view instanceof TextView&&title.contentEquals(((TextView)view).getText()))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){
            View child=find(((ViewGroup)view).getChildAt(i),title);if(child!=null)return child;
        }
        return null;
    }
    private static View byDescription(View label,String description){
        if(description.equals(label.getContentDescription()))return label;
        if(label instanceof ViewGroup)for(int i=0;i<((ViewGroup)label).getChildCount();i++){
            View found=byDescription(((ViewGroup)label).getChildAt(i),description);if(found!=null)return found;
        }
        return null;
    }

    private static Button button(View view,String label){
        View result=find(view,label);
        if(!(result instanceof Button)){
            java.util.List<String> visible=new java.util.ArrayList<>();
            buttonLabels(view,visible);
            throw new AssertionError("Missing "+label+"; panel button labels: "+visible);
        }
        return (Button)result;
    }
    private static void buttonLabels(View view,java.util.List<String> out){
        if(out.size()>=16||view.getVisibility()!=View.VISIBLE)return;
        if(view instanceof Button&&((Button)view).getText().length()>0)out.add(((Button)view).getText().toString());
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)
            buttonLabels(((ViewGroup)view).getChildAt(i),out);
    }
    private void await(BooleanSupplier condition,String label) {
        long deadline=SystemClock.elapsedRealtime()+6000;
        while(!condition.getAsBoolean()&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(25);
        check(condition.getAsBoolean(),label);
    }
    private void check(boolean pass,String label){if(!pass)throw new AssertionError(label);checks++;}
    private interface Checked {void run()throws Exception;}
    private void main(Checked callback)throws Exception{
        callbackFailure.set(null);
        runOnMainSync(()->{try{callback.run();}catch(Throwable failure){callbackFailure.set(failure);}});
        Throwable failure=callbackFailure.get();
        if(failure!=null)throw new RuntimeException(failure);
    }
}
