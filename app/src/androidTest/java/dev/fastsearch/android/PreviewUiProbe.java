package dev.fastsearch.android;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.Os;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.MediaController;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import dev.fastsearch.core.FileEntry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real viewer input and lifecycle checks. Run only in the reserved device test window. */
public final class PreviewUiProbe extends Instrumentation {
    private static final String MARKER = "dev.fastsearch.preview.ui.fixture";
    private Bundle arguments;
    private Context target;
    private Application application;
    private Tracker tracker;
    private File fixture, screenshots;
    private boolean ownsFixture, videoAvailable;
    private Uri imageUri, pdfUri, textUri, archiveUri, wavUri, videoUri;
    private int assertions, rotations, screenshotCount, uncollected;
    private long lastGesture;
    private final List<String> tokens = new ArrayList<>();
    private final StringBuilder report = new StringBuilder();

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); this.arguments = arguments; start(); }
    @Override public void onStart() {
        boolean passed = false;
        try {
            target = getTargetContext(); application = (Application) target.getApplicationContext();
            tracker = new Tracker(); application.registerActivityLifecycleCallbacks(tracker);
            fixtures();if(arguments!=null&&"archive".equals(arguments.getString("phase"))){archive();collectActivities();passed=true;}
            else if(arguments!=null&&"archiveNested".equals(arguments.getString("phase"))){nestedArchive();passed=true;}
            else if(arguments!=null&&"archiveFolders".equals(arguments.getString("phase"))){archiveFolders();passed=true;}
            else if(arguments!=null&&"extract".equals(arguments.getString("phase"))){extractArchive();passed=true;}
            else if(arguments!=null&&"streamExtract".equals(arguments.getString("phase"))){extractStreamArchives();passed=true;}
            else if(arguments!=null&&"tarMembers".equals(arguments.getString("phase"))){previewTarMembers();passed=true;}
            else if(arguments!=null&&"sevenZip".equals(arguments.getString("phase"))){extractSevenZip();passed=true;}
            else if(arguments!=null&&"apk".equals(arguments.getString("phase"))){apkActions();passed=true;}
            else if(arguments!=null&&"apkContents".equals(arguments.getString("phase"))){apkContents();passed=true;}
            else if(arguments!=null&&"unknownText".equals(arguments.getString("phase"))){unknownText();passed=true;}
            else if(arguments!=null&&"codeText".equals(arguments.getString("phase"))){codeText();passed=true;}
            else if(arguments!=null&&"office".equals(arguments.getString("phase"))){officeSummary();passed=true;}
            else if(arguments!=null&&"viewerDelete".equals(arguments.getString("phase"))){
                viewerDelete();passed=true;
            }
            else if(arguments!=null&&"zipDrag".equals(arguments.getString("phase"))){
                FileViewerActivity viewer=open(archiveUri,"entries.zip");
                dragToZipPdf(viewer);
                await(()->archivePage(viewer,1),8000,"releasing over the ZIP row opens only its PDF");
                close(viewer);passed=true;
            }
            else if(arguments!=null&&"photos".equals(arguments.getString("phase"))){
                image();browsing();passed=true;
            }
            else if(arguments!=null&&"media".equals(arguments.getString("phase"))){
                check(videoAvailable,"required seekable video fixture");
                media(wavUri,"silent.wav",true);media(videoUri,"synthetic.mp4",false);passed=true;
            }
            else if(arguments!=null&&"hex".equals(arguments.getString("phase"))){hexInspection();passed=true;}
            else if(arguments!=null&&"back".equals(arguments.getString("phase"))){backExit();collectActivities();passed=true;}
            else if(arguments!=null&&"lifecycle".equals(arguments.getString("phase"))){cancelledOpen();lateDelivery();repeatedCleanup();collectActivities();passed=true;}
            else{image(); browsing(); aspectTransitions(); pdf(); text(); archive(); media(wavUri, "silent.wav", true);
            if (videoAvailable) media(videoUri, "synthetic.mp4", false);
            revisits(); unsupportedSwipes(); backExit(); cancelledOpen(); lateDelivery(); repeatedCleanup(); collectActivities(); passed = true;}
        } catch (Throwable failure) {
            java.io.StringWriter trace = new java.io.StringWriter(); failure.printStackTrace(new java.io.PrintWriter(trace)); report.append(trace);
        } finally {
            try { cleanup(); } catch (Throwable failure) { passed = false; report.append("Cleanup failed: ").append(failure.getClass().getSimpleName()).append('\n'); }
        }
        Bundle result = new Bundle(); result.putBoolean("passed", passed); result.putInt("assertions", assertions);
        result.putInt("rotations", rotations); result.putInt("screenshots", screenshotCount); result.putBoolean("videoTested", videoAvailable);
        result.putInt("uncollectedActivities", uncollected); if (screenshots != null) result.putString("screenshotDirectory", screenshots.getPath());
        result.putString("stream", report.append(passed ? "PASS " : "FAIL ").append(assertions).append(" viewer UI assertions; ")
                .append(rotations).append(" rotations; ").append(screenshotCount).append(" synthetic screenshots.\n").toString());
        finish(passed ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }
    private void fixtures() throws Exception {
        String run = Long.toString(SystemClock.elapsedRealtimeNanos());
        fixture = new File(target.getCacheDir(), "PreviewUiProbe-" + run).getCanonicalFile(); ownsFixture = fixture.mkdir(); check(ownsFixture, "create owned fixture root");
        File output = target.getExternalFilesDir(null); if (output == null) output = target.getFilesDir();
        screenshots = new File(output, "preview-ui-" + run); check(screenshots.mkdir(), "create screenshot output");
        File image = new File(fixture, "synthetic.png"); PreviewUiFixtures.image(image); imageUri = issue(image);
        PreviewUiFixtures.aspectImage(new File(fixture,"landscape.png"),1200,600,1);
        PreviewUiFixtures.aspectImage(new File(fixture,"rotated.jpg"),600,1000,6);
        PreviewUiFixtures.aspectImage(new File(fixture,"panorama.png"),1600,240,1);
        File pdf = new File(fixture, "three-pages.pdf"); PreviewUiFixtures.pdf(pdf); pdfUri = issue(pdf);
        File text = new File(fixture, "literal.html"); PreviewUiFixtures.text(text); textUri = issue(text);
        File zip = new File(fixture, "entries.zip"); PreviewUiFixtures.archive(zip, pdf); archiveUri = issue(zip);
        File wav = new File(fixture, "silent.wav"); PreviewUiFixtures.wav(wav); wavUri = issue(wav);
        if (arguments == null || !"false".equals(arguments.getString("video"))) {
            File video = new File(fixture, "synthetic.mp4");
            try {
                if(arguments!=null&&"media".equals(arguments.getString("phase"))){
                    // Synthetic 12-second H.264 test pattern, not sensitive media.
                    try(java.io.InputStream input=getContext().getAssets().open("samples/PlaybackProbe.mp4");
                        java.io.FileOutputStream outputStream=new java.io.FileOutputStream(video)){
                        byte[] buffer=new byte[16*1024];int count;
                        while((count=input.read(buffer))!=-1)outputStream.write(buffer,0,count);
                    }
                }else PreviewUiFixtures.video(video);
                videoUri=issue(video);videoAvailable=true;
            }
            catch (Exception unsupported) { report.append("Optional platform video generation unavailable: ").append(unsupported.getClass().getSimpleName()).append(".\n"); }
        }
        report.append("Generated PNG, PDF, literal HTML, ZIP and 30-second silent PCM WAV fixtures.\n");
    }
    private Uri issue(File file) throws Exception {
        Uri uri = RootFileProvider.issue(target, new FileEntry(file.getCanonicalPath(), false, file.length(), file.lastModified()), false);
        tokens.add(uri.getPathSegments().get(0)); return uri;
    }
    private FileViewerActivity open(Uri uri, String name) {
        Intent intent = FileViewerActivity.intent(target, uri, name, new File(fixture, name).getPath(), -1).putExtra(MARKER, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        FileViewerActivity activity = (FileViewerActivity) startActivitySync(intent);
        await(() -> snapshot(activity).ready, 15000, "viewer completes provider-backed preview and layout"); waitForIdleSync();
        main(()->{
            View notice=(View)field(activity,"notice");
            LinearLayout chrome=(LinearLayout)notice.getParent();
            check(chrome.indexOfChild(notice)==1,"viewer header has no permanent decorative progress line");
            check(((android.widget.ProgressBar)field(activity,"selectionProgress")).getParent()==chrome,
                    "real file-operation progress still exists");
        });
        return activity;
    }
    private void viewerDelete() throws Exception {
        java.util.List<FileEntry> entries=new ArrayList<>();
        for(String name:new String[]{"synthetic.png","literal.html"}){
            File file=new File(fixture,name);entries.add(new FileEntry(file.getCanonicalPath(),false,file.length(),file.lastModified()));
        }
        PreviewSession session=PreviewSession.create(entries,entries.get(0).path(),false);
        Intent intent=FileViewerActivity.intent(target,imageUri,"synthetic.png",entries.get(0).path(),-1)
                .putExtra(PreviewSession.EXTRA,session.id).putExtra(MARKER,true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        FileViewerActivity viewer=(FileViewerActivity)startActivitySync(intent);
        await(()->snapshot(viewer).ready,15000,"viewer fixture ready");
        main(()->viewer.findViewById(R.id.preview_select).performClick());
        main(()->viewer.findViewById(R.id.preview_next).performClick());
        await(()->session.position==1&&snapshot(viewer).ready,15000,"next viewer fixture ready");
        main(()->{
            viewer.findViewById(R.id.preview_select).performClick();
            View delete=viewer.findViewById(R.id.preview_delete_selected);
            check(session.selected.size()==2&&delete.isShown()&&delete.isEnabled(),"pinned Delete visible with two selected files");
            delete.performClick();
        });
        clickAccessible("Cancel");
        check(new File(entries.get(0).path()).isFile()&&new File(entries.get(1).path()).isFile()
                &&session.selected.size()==2,"viewer confirmation cancellation preserves selection and both fixtures");
        close(viewer);PreviewSession.release(session.id);
    }

    private void image() throws Exception {
        FileViewerActivity activity = open(imageUri, "synthetic.png");
        check(snapshot(activity).image != null, "real image view is visible"); capture(activity, "01-image");
        ZoomPreviewView view = snapshot(activity).image;
        main(() -> doubleTap(view)); check(snapshot(activity).zoom > 1.5f, "double tap zooms the visible image");
        Snapshot before = snapshot(activity); main(() -> pan(view));Snapshot panned=snapshot(activity);
        check(Math.hypot(panned.centerX-before.centerX,panned.centerY-before.centerY) > .02f, "drag pans a zoomed image along an overflowing axis");
        float beforePinch = snapshot(activity).zoom; main(() -> pinch(view));
        check(snapshot(activity).zoom > beforePinch && snapshot(activity).zoom <= 8, "two-pointer pinch zoom obeys its cap");
        float savedZoom = snapshot(activity).zoom; capture(activity, "02-image-zoom");
        FileViewerActivity rotated = rotate(activity); assertStopped(activity);
        check(Math.abs(snapshot(rotated).zoom - savedZoom) < .12f, "image zoom survives rotation"); capture(rotated, "03-image-rotated"); close(rotated);
    }
    private void browsing()throws Exception{
        clearViewStates();
        List<FileEntry> entries=new ArrayList<>();for(String name:new String[]{"synthetic.png","three-pages.pdf","literal.html","entries.zip"}){
            File file=new File(fixture,name);entries.add(new FileEntry(file.getCanonicalPath(),false,file.length(),file.lastModified()));}
        PreviewSession session=PreviewSession.create(entries,entries.get(0).path(),false);
        Intent intent=FileViewerActivity.intent(target,imageUri,"synthetic.png",entries.get(0).path(),-1).putExtra(PreviewSession.EXTRA,session.id).putExtra(MARKER,true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        FileViewerActivity activity=(FileViewerActivity)startActivitySync(intent);await(()->snapshot(activity).ready,15000,"browsing session ready");
        main(()->activity.findViewById(R.id.preview_select).performClick());check(session.selected.size()==1,"viewer select adds current file");
        main(()->{FrameLayout stage=activity.findViewById(R.id.preview_stage);float x=stage.getWidth()*.8f,y=stage.getHeight()*.4f;long time=gestureTime();
            touch(stage,time,time,MotionEvent.ACTION_DOWN,x,y);touch(stage,time,time+40,MotionEvent.ACTION_MOVE,x-stage.getWidth()*.25f,y);
            check(stage.getChildAt(stage.getChildCount()-1).getTranslationX()<0,"preview follows horizontal finger motion before release");
            touch(stage,time,time+100,MotionEvent.ACTION_MOVE,x-stage.getWidth()*.6f,y);touch(stage,time,time+140,MotionEvent.ACTION_UP,x-stage.getWidth()*.6f,y);});
        await(()->session.position==1&&snapshot(activity).ready,15000,"swipe settles into next file");check(snapshot(activity).pages==3,"swipe opens native PDF preview");
        check(session.selected.size()==1,"selection persists while browsing next file");
        main(()->activity.findViewById(R.id.preview_select).performClick());check(session.selected.size()==2,"selection accumulates across file types");
        main(()->activity.findViewById(R.id.preview_previous).performClick());await(()->session.position==0&&snapshot(activity).ready,15000,"previous file transitions back");
        main(()->doubleTap(snapshot(activity).image));check(snapshot(activity).zoom>1.5f,"browsed photo can zoom");
        main(()->{FrameLayout stage=activity.findViewById(R.id.preview_stage);float x=stage.getWidth()*.8f,y=stage.getHeight()*.4f;long time=gestureTime();
            touch(stage,time,time,MotionEvent.ACTION_DOWN,x,y);touch(stage,time,time+40,MotionEvent.ACTION_MOVE,x-stage.getWidth()*.3f,y);touch(stage,time,time+100,MotionEvent.ACTION_UP,x-stage.getWidth()*.5f,y);});
        SystemClock.sleep(250);check(session.position==0,"zoomed-image pan does not navigate files");
        clickAccessible("More file actions");clickAccessible("Delete file");clickAccessible("Cancel");
        check(new File(entries.get(0).path()).isFile(),"viewer delete cancellation retains original file");
        main(()->activity.findViewById(R.id.preview_path).performClick());clickAccessible("Copy path");
        AtomicReference<String> copied=new AtomicReference<>();main(()->{android.content.ClipData clip=target.getSystemService(android.content.ClipboardManager.class).getPrimaryClip();copied.set(clip==null?"":clip.getItemAt(0).getText().toString());});
        check(entries.get(0).path().equals(copied.get()),"path dialog copies full unshortened path");
        main(()->activity.findViewById(R.id.preview_copy).performClick());await(()->FileClipboard.count(target)==2,15000,"viewer copies both selected files to identity clipboard");
        FileViewerActivity rotated=rotate(activity);check(session.selected.size()==2&&session.position==0,"selected strip and position survive rotation");
        capture(rotated,"04-browsing-selected");main(rotated::onBackPressed);check(session.selected.isEmpty()&&!destroyed(rotated),"one Back clears preview selection without closing");close(rotated);PreviewSession.release(session.id);FileClipboard.clear(target);
    }
    private UiAutomation automation(){return getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);}
    private void clickAccessible(String text){
        try{await(()->clickWindowText(automation().getRootInActiveWindow(),text),5000,"click "+text);
        }catch(AssertionError failure){try{FileViewerActivity active=tracker.current();if(active!=null)capture(active,"dialog-failure");}catch(Exception ignored){}throw failure;}waitForIdleSync();
    }
    private void clickPopupItem(String label) {
        // OEM PopupMenu exposes a radio row whose accessibility click may be acknowledged
        // by its container without selecting the item. Tap the exact visible label instead.
        await(() -> {
            AccessibilityNodeInfo root=automation().getRootInActiveWindow();
            if(root==null)return false;
            try{
                java.util.List<AccessibilityNodeInfo> matches=root.findAccessibilityNodeInfosByText(label);
                try{
                    for(AccessibilityNodeInfo node:matches){
                        if(!node.refresh()||!node.isVisibleToUser()||!label.contentEquals(String.valueOf(node.getText())))continue;
                        Rect rect=new Rect();node.getBoundsInScreen(rect);
                        if(rect.isEmpty())continue;
                        long time=SystemClock.uptimeMillis();
                        MotionEvent down=MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,rect.centerX(),rect.centerY(),0);
                        MotionEvent up=MotionEvent.obtain(time,time+40,MotionEvent.ACTION_UP,rect.centerX(),rect.centerY(),0);
                        try{return automation().injectInputEvent(down,true)&&automation().injectInputEvent(up,true);}
                        finally{down.recycle();up.recycle();}
                    }
                    return false;
                }finally{for(AccessibilityNodeInfo node:matches)node.recycle();}
            }finally{root.recycle();}
        },8000,"tap popup item "+label);
        waitForIdleSync();
    }
    private boolean clickWindowText(AccessibilityNodeInfo root,String text){if(root==null)return false;
        try{List<AccessibilityNodeInfo> matches=root.findAccessibilityNodeInfosByText(text);try{for(AccessibilityNodeInfo node:matches){
            if(!node.refresh()||!node.isVisibleToUser()||!node.isEnabled()||
                    (!text.equalsIgnoreCase(String.valueOf(node.getText()))&&!text.equalsIgnoreCase(String.valueOf(node.getContentDescription()))))continue;
            AccessibilityNodeInfo click=node;
            try{for(int up=0;up<3&&click!=null;up++){
                if(click.isClickable()&&click.isEnabled()&&click.performAction(AccessibilityNodeInfo.ACTION_CLICK))return true;
                AccessibilityNodeInfo parent=click.getParent();if(click!=node)click.recycle();click=parent;
            }}finally{if(click!=null&&click!=node)click.recycle();}
            Rect bounds=new Rect();node.getBoundsInScreen(bounds);
            if(!bounds.isEmpty()){
                long time=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,bounds.centerX(),bounds.centerY(),0);
                MotionEvent release=MotionEvent.obtain(time,time+30,MotionEvent.ACTION_UP,bounds.centerX(),bounds.centerY(),0);
                try{if(automation().injectInputEvent(down,true)&&automation().injectInputEvent(release,true))return true;}
                finally{down.recycle();release.recycle();}
            }
        }return false;}finally{for(AccessibilityNodeInfo node:matches)node.recycle();}}finally{root.recycle();}}

    private void aspectTransitions()throws Exception{
        clearViewStates();
        List<FileEntry> entries=new ArrayList<>();
        for(String name:new String[]{"synthetic.png","landscape.png","rotated.jpg","panorama.png","three-pages.pdf"}){
            File file=new File(fixture,name);entries.add(new FileEntry(file.getCanonicalPath(),false,file.length(),file.lastModified()));}
        PreviewSession session=PreviewSession.create(entries,entries.get(0).path(),false);
        Intent intent=FileViewerActivity.intent(target,imageUri,"synthetic.png",entries.get(0).path(),-1)
                .putExtra(PreviewSession.EXTRA,session.id).putExtra(MARKER,true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        FileViewerActivity activity=(FileViewerActivity)startActivitySync(intent);
        await(()->snapshot(activity).ready,15000,"aspect session ready");
        try{
            for(int destination:new int[]{1,2,3,4,3,2,1,0}){
                CountDownLatch blocked=new CountDownLatch(1),release=new CountDownLatch(1);
                Future<?> barrier=worker().submit(()->{blocked.countDown();try{release.await(20,TimeUnit.SECONDS);}catch(InterruptedException stopped){Thread.currentThread().interrupt();}});
                check(blocked.await(3,TimeUnit.SECONDS),"hold full decode to inspect incoming frame");
                try{
                    float direction=destination>session.position?-1:1;long time=gestureTime();
                    main(()->{FrameLayout stage=activity.findViewById(R.id.preview_stage);float x=stage.getWidth()*.5f,y=stage.getHeight()*.35f;
                        touch(stage,time,time,MotionEvent.ACTION_DOWN,x,y);touch(stage,time,time+40,MotionEvent.ACTION_MOVE,x+direction*stage.getWidth()*.35f,y);});
                    await(()->incoming(activity)!=null,10000,"incoming fitted thumbnail decodes while finger is held");
                    AtomicReference<RectF> before=new AtomicReference<>();
                    main(()->{ImageView picture=incoming(activity);check(picture.getScaleType()==ImageView.ScaleType.FIT_CENTER,"async and cached previews keep fit scale");
                        RectF bounds=pictureBounds(picture);before.set(bounds);
                        float expected=destination==0?720f/1080:destination==1?2:destination==2?1000f/600:destination==3?1600f/240:600f/800;
                        // Target-sized thumbnails round one dimension to a pixel. Keep the
                        // rotation check in bitmap pixels and the stricter handoff bounds below.
                        float width=picture.getDrawable().getIntrinsicWidth(),height=picture.getDrawable().getIntrinsicHeight();
                        float rounding=expected>=1?Math.abs(height-width/expected):Math.abs(width-height*expected);
                        check(rounding<=.51f,"incoming aspect includes EXIF rotation within pixel rounding: destination="+destination+", expected="+expected+", bounds="+bounds+", bitmap="+width+"x"+height);
                        FrameLayout stage=activity.findViewById(R.id.preview_stage);
                        check(bounds.left>=-1&&bounds.top>=-1&&bounds.right<=stage.getWidth()+1&&bounds.bottom<=stage.getHeight()+1,"incoming image is fitted without cropping");
                        float x=stage.getWidth()*.5f,y=stage.getHeight()*.35f;
                        touch(stage,time,time+100,MotionEvent.ACTION_MOVE,x+direction*stage.getWidth()*.6f,y);
                        touch(stage,time,time+140,MotionEvent.ACTION_UP,x+direction*stage.getWidth()*.6f,y);});
                    await(()->session.position==destination&&snapshot(activity).pending,3000,"settled incoming frame remains while full decode waits");
                    main(()->{ViewGroup content=(ViewGroup)field(activity,"previewContent");ImageView picture=find(content,ImageView.class);
                        check(picture!=null&&picture.getDrawable() instanceof BitmapDrawable,"handoff retains decoded incoming frame");
                        sameBounds(before.get(),pictureBounds(picture),"settling preserves thumbnail geometry");});
                    release.countDown();barrier.get(5,TimeUnit.SECONDS);
                    await(()->snapshot(activity).ready,15000,"full aspect preview ready");
                    main(()->{ZoomPreviewView image=snapshot(activity).image;check(image!=null,"native fitted image replaces thumbnail");
                        float left=(Float)member(image,"left"),top=(Float)member(image,"top"),fit=(Float)member(image,"fit");
                        RectF after=new RectF(left,top,left+(Float)member(image,"imageWidth")*fit,top+(Float)member(image,"imageHeight")*fit);
                        after.offset(image.getLeft(),image.getTop());
                        sameBounds(before.get(),after,"full decode preserves incoming frame bounds");});
                    await(()->{AtomicBoolean gone=new AtomicBoolean();main(()->gone.set(member(field(activity,"previewContent"),"cover")==null));return gone.get();},3000,"incoming cover releases after native layout");
                }finally{release.countDown();barrier.get(5,TimeUnit.SECONDS);}
            }
            capture(activity,"09-aspect-transition");
        }finally{close(activity);PreviewSession.release(session.id);}
    }
    private ImageView incoming(FileViewerActivity activity){AtomicReference<ImageView> result=new AtomicReference<>();main(()->{
        FrameLayout stage=activity.findViewById(R.id.preview_stage);
        if(stage.getChildCount()==2){ImageView image=find(stage.getChildAt(0),ImageView.class);
            if(image!=null&&image.getDrawable() instanceof BitmapDrawable&&image.getWidth()>0)result.set(image);}});return result.get();}
    private static RectF pictureBounds(ImageView image){RectF bounds=new RectF(0,0,image.getDrawable().getIntrinsicWidth(),image.getDrawable().getIntrinsicHeight());
        image.getImageMatrix().mapRect(bounds);bounds.offset(image.getLeft()+image.getPaddingLeft(),image.getTop()+image.getPaddingTop());return bounds;}
    private void sameBounds(RectF a,RectF b,String label){check(Math.abs(a.left-b.left)<2&&Math.abs(a.top-b.top)<2&&Math.abs(a.right-b.right)<2&&Math.abs(a.bottom-b.bottom)<2,label+": "+a+" / "+b);}
    private static Object member(Object owner,String name){try{Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);}
        catch(ReflectiveOperationException failure){throw new AssertionError(name,failure);}}

    private void hexInspection()throws Exception{
        File binary=new File(fixture,"byte-layout.bin");
        byte[] known=new byte[1520];
        known[0]=65; known[1]=66; known[2]=0;
        for(int at:new int[]{513,1040}){known[at]=79;known[at+1]=(byte)162;known[at+2]=30;}
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(binary)){out.write(known);}
        FileEntry own=new FileEntry(binary.getCanonicalPath(),false,binary.length(),binary.lastModified());
        PreviewSession session=PreviewSession.create(java.util.Collections.singletonList(own),own.path(),false);
        Intent intent=FileViewerActivity.intent(target,issue(binary),own.name,own.path(),own.size)
                .putExtra(PreviewSession.EXTRA,session.id).putExtra(MARKER,true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        FileViewerActivity viewer=(FileViewerActivity)startActivitySync(intent);
        await(()->{
            AtomicBoolean ready=new AtomicBoolean();
            main(()->{View actions=(View)field(viewer,"moreAction");
                ready.set(actions!=null&&actions.isShown()&&field(viewer,"pending")==null);});
            return ready.get();
        },15000,"unsupported binary viewer shows file actions");
        clickAccessible("More file actions");clickAccessible(HexInspectionSheet.LABEL);
        await(()->hexText(viewer,"00000000  41 42 00"),5000,"real bytes in offset/hex view");
        check(hexText(viewer,"|AB")&&hexText(viewer,"Read only"),"ASCII and read-only mode are explicit");
        hexButton(viewer,"Next page");
        await(()->hexText(viewer,"00000200"),5000,"paged view starts at 512");
        hexInput(viewer,"Byte offset","0x1");hexButton(viewer,"Go");
        await(()->hexText(viewer,"00000000"),5000,"direct absolute offset restores containing page");
        hexInput(viewer,"Hex bytes to find","4F A2 1E");hexButton(viewer,"Find");
        await(()->hexText(viewer,"Match at 0x201"),5000,"search finds the first exact byte match");
        hexButton(viewer,"Find next");
        await(()->hexText(viewer,"Match at 0x410"),5000,"find next navigates to another match");
        hexInput(viewer,"Hex bytes to find","1");hexButton(viewer,"Find next");
        await(()->hexText(viewer,"Use complete hex bytes"),5000,"incomplete byte input is not executed");
        check(java.util.Arrays.equals(known,java.nio.file.Files.readAllBytes(binary.toPath())),
                "view, navigation, and search preserve the owned file bytes");
        try(java.io.FileOutputStream changed=new java.io.FileOutputStream(binary,true)){changed.write(99);}
        hexButton(viewer,"Previous page");
        await(()->hexText(viewer,"The file changed"),5000,"different file identity stops further reads");
        sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
        await(()->{
            AtomicBoolean hidden=new AtomicBoolean();main(()->hidden.set(field(viewer,"activeSheet")==null));return hidden.get();
        },5000,"system back returns to the viewer");
        close(viewer);
    }
    private void hexButton(FileViewerActivity viewer,String name){
        main(()->{
            AppSheet sheet=(AppSheet)field(viewer,"activeSheet");
            Button selected=sheet==null?null:button(sheet.getWindow().getDecorView(),name);
            check(selected!=null&&selected.isEnabled()&&selected.performClick(),"hex tool button "+name);
        });
    }
    private void hexInput(FileViewerActivity viewer,String description,String text){
        main(()->{
            AppSheet sheet=(AppSheet)field(viewer,"activeSheet");
            android.widget.EditText selected=sheet==null?null:hexField(sheet.getWindow().getDecorView(),description);
            check(selected!=null,"hex tool input "+description);selected.setText(text);
        });
    }
    private static android.widget.EditText hexField(View candidate,String description){
        if(candidate instanceof android.widget.EditText&&description.equals(candidate.getContentDescription()))
            return (android.widget.EditText)candidate;
        if(candidate instanceof ViewGroup){
            ViewGroup group=(ViewGroup)candidate;
            for(int i=0;i<group.getChildCount();i++){
                android.widget.EditText found=hexField(group.getChildAt(i),description);
                if(found!=null)return found;
            }
        }
        return null;
    }
    private boolean hexText(FileViewerActivity viewer,String substring){
        AtomicBoolean found=new AtomicBoolean();
        main(()->{
            AppSheet sheet=(AppSheet)field(viewer,"activeSheet");
            found.set(sheet!=null&&sheet.isShowing()&&hexText(sheet.getWindow().getDecorView(),substring));
        });
        return found.get();
    }
    private static boolean hexText(View candidate,String substring){
        if(candidate instanceof TextView&&((TextView)candidate).getText().toString().contains(substring))return true;
        if(candidate instanceof ViewGroup){
            ViewGroup group=(ViewGroup)candidate;
            for(int i=0;i<group.getChildCount();i++)if(hexText(group.getChildAt(i),substring))return true;
        }
        return false;
    }

    private void pdf() throws Exception {
        FileViewerActivity activity = open(pdfUri, "three-pages.pdf"); check(snapshot(activity).pages == 3 && snapshot(activity).page == 0, "PDF count and first page");
        click(activity, "Next"); await(() -> snapshot(activity).ready && snapshot(activity).page == 1, 10000, "PDF next page"); capture(activity, "04-pdf-page-two");
        FileViewerActivity rotated = rotate(activity); assertStopped(activity); check(snapshot(rotated).page == 1 && snapshot(rotated).pages == 3, "PDF page survives rotation");
        click(rotated, "Previous"); await(() -> snapshot(rotated).ready && snapshot(rotated).page == 0, 10000, "PDF previous page"); close(rotated);
    }
    private void text() throws Exception {
        FileViewerActivity activity = open(textUri, "literal.html");
        click(activity,"Source");
        main(() -> {
            ScrollView scroll = find(content(activity), ScrollView.class); check(scroll != null, "source text scroll view exists");
            TextView text = find(scroll, TextView.class); check(text != null && text.getText().toString().contains("<script>literal text only</script>"), "HTML source mode retains literal markup");
            android.graphics.Rect noticeBounds=new android.graphics.Rect(),toolbarBounds=new android.graphics.Rect();((View)field(activity,"notice")).getGlobalVisibleRect(noticeBounds);button(content(activity),"Read").getGlobalVisibleRect(toolbarBounds);check(noticeBounds.bottom<=toolbarBounds.top,"document notice does not cover reader controls");
            check(!containsClass(content(activity), "android.webkit.WebView"), "text preview contains no WebView"); scroll.scrollTo(0, Math.max(300, scroll.getHeight() * 2));
        });
        int saved = snapshot(activity).scroll; check(saved > 0, "long text scrolls"); capture(activity, "05-plain-text");
        FileViewerActivity rotated = rotate(activity); assertStopped(activity);
        await(() -> Math.abs(snapshot(rotated).scroll - saved) < 40, 4000, "text scroll restores after layout"); close(rotated);
    }
    private void dragToZipPdf(FileViewerActivity activity) {
        chooseArchiveRow(activity,"folder",true);
        await(()->archiveFolderPath(activity).equals("folder"),5000,"ZIP gesture folder opens");
        waitForIdleSync();
        int[][] centers=new int[2][];
        View[] pdf=new View[1];
        main(() -> {
            ListView entries=find(content(activity),ListView.class);
            View first=null;
            for(int i=0;i<entries.getChildCount();i++){
                View row=entries.getChildAt(i);
                TextView label=(TextView)((LinearLayout)((LinearLayout)row).getChildAt(1)).getChildAt(0);
                if("embedded.pdf".contentEquals(label.getText()))pdf[0]=row;
                else if(first==null&&entries.getAdapter().isEnabled(entries.getFirstVisiblePosition()+i))first=row;
            }
            check(first!=null&&pdf[0]!=null,"ZIP entry choices visible before drag");
            centers[0]=centerOnScreen(first);
            centers[1]=centerOnScreen(pdf[0]);
        });
        long time=SystemClock.uptimeMillis();
        zipTouch(time,MotionEvent.ACTION_DOWN,centers[0]);
        SystemClock.sleep(220);
        zipTouch(time,MotionEvent.ACTION_MOVE,centers[1]);
        main(()->check(pdf[0].isPressed()&&field(activity,"activeSheet")==null,
                "ZIP highlight follows without opening"));
        zipTouch(time,MotionEvent.ACTION_UP,centers[1]);
    }
    private static int[] centerOnScreen(View view){
        int[] xy=new int[2];view.getLocationOnScreen(xy);xy[0]+=view.getWidth()/2;xy[1]+=view.getHeight()/2;return xy;
    }
    private void zipTouch(long time,int action,int[] at){
        MotionEvent e=MotionEvent.obtain(time,SystemClock.uptimeMillis(),action,at[0],at[1],0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try{check(automation().injectInputEvent(e,true),"real Android ZIP gesture event "+action);}
        finally{e.recycle();}
    }
    private void archive() throws Exception {
        FileViewerActivity activity = open(archiveUri, "entries.zip");
        main(() -> { ListView list = find(content(activity), ListView.class); check(list != null && list.getCount() == 1, "ZIP root shows its folder and hides unsafe records"); });
        ActivityMonitor chooser = addMonitor(new android.content.IntentFilter(Intent.ACTION_CHOOSER),
                new ActivityResult(Activity.RESULT_CANCELED, null), true);
        try {
            main(() -> ((View)field(activity,"moreAction")).performClick());
            clickAccessible("Share file");
            await(() -> chooser.getHits() == 1, 5000, "viewer Share opens chooser");
            dragToZipPdf(activity);
            await(() -> archivePage(activity, 1), 8000, "embedded PDF displays page 1 of 3");
            main(() -> {
                AppSheet sheet = (AppSheet) field(activity, "activeSheet");
                ZoomPreviewView page = find(sheet.content, ZoomPreviewView.class);
                doubleTap(page);
                check(page.state()[0] > 1.5f, "embedded PDF page zooms");
            });
            clickAccessible("Next page");
            await(() -> archivePage(activity, 2), 8000, "embedded PDF displays page 2 of 3");
            clickAccessible("Previous page");
            await(() -> archivePage(activity, 1), 8000, "embedded PDF can return to page 1");
            clickAccessible("Share archive entry");
            await(() -> chooser.getHits() == 2, 8000, "embedded PDF Share opens chooser");
            check(archivePage(activity, 1), "sharing keeps the original PDF page open");
            capture(activity, "06-embedded-pdf");
            main(() -> ((AppSheet) field(activity, "activeSheet")).dismiss());
            archiveShareBytes();
        } finally { removeMonitor(chooser); }
        chooseArchiveRow(activity,"safe-note.txt",false);
        await(() -> {
            java.util.concurrent.atomic.AtomicBoolean visible = new java.util.concurrent.atomic.AtomicBoolean();
            main(() -> {
                AppSheet sheet = (AppSheet) field(activity, "activeSheet");
                if (sheet == null) return;
                for (int i = 0; i < sheet.content.getChildCount(); i++) {
                    View child = sheet.content.getChildAt(i);
                    if (child instanceof TextView && ((TextView) child).getText().toString().contains("Archived note streamed from ZIP."))
                        visible.set(true);
                }
            });
            return visible.get();
        }, 5000, "entry contents shown in the archive");
        main(() -> ((AppSheet) field(activity, "activeSheet")).dismiss());
        check(!new File(fixture, "folder").exists(), "ZIP entries were not extracted"); capture(activity, "06-archive");
        FileViewerActivity rotated = rotate(activity); assertStopped(activity);
        main(() -> { ListView list = find(content(rotated), ListView.class); check(list != null && list.getCount() == 3, "ZIP selected folder survives rotation"); }); close(rotated);
    }

    private void nestedArchive()throws Exception{
        byte[] doc=archiveFixture(java.util.Collections.singletonMap("word/document.xml",
            ("<w:document xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'>"
                    +"<w:body><w:p><w:r><w:t>Owned archived document</w:t></w:r></w:p></w:body></w:document>")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        java.util.Map<String,byte[]> inside=new java.util.LinkedHashMap<>();
        inside.put("inside-note.docx",doc);
        inside.put("inside-audio.wav",java.nio.file.Files.readAllBytes(new File(fixture,"silent.wav").toPath()));
        try(java.io.InputStream video=getContext().getAssets().open("samples/PlaybackProbe.mp4")){
            java.io.ByteArrayOutputStream encoded=new java.io.ByteArrayOutputStream();
            byte[] data=new byte[16*1024];int n;
            while((n=video.read(data))!=-1)encoded.write(data,0,n);
            inside.put("inside-video.mp4",encoded.toByteArray());
        }
        byte[] nested=archiveFixture(inside);
        File outer=new File(fixture,"outer-owned.zip");
        java.nio.file.Files.write(outer.toPath(),archiveFixture(java.util.Collections.singletonMap("inner-owned.zip",nested)));
        FileViewerActivity parent=open(issue(outer),outer.getName());
        FileViewerActivity inner=openNestedEntry(parent,"inner-owned.zip");
        Uri innerUri=inner.getIntent().getData();
        check(ArchivePreviewSources.depth(innerUri)==1,"nested viewer is backed by a bounded private snapshot");
        FileViewerActivity word=openNestedEntry(inner,"inside-note.docx");
        Uri wordUri=word.getIntent().getData();
        check(ArchivePreviewSources.depth(wordUri)==2,"document inside nested archive has distinct depth");
        main(()->{
            Object shown=field(word,"shown");
            OfficePreview.Result rendered=(OfficePreview.Result)field(shown,"office");
            check(rendered!=null&&rendered.sections.get(0).text.contains("Owned archived document"),
                    "archived DOCX renders through the ordinary Office viewer");
        });
        close(word);
        check(!sourceStillOpen(wordUri),"closing archived document releases the private source");
        FileViewerActivity audio=openNestedEntry(inner,"inside-audio.wav");
        Uri audioUri=audio.getIntent().getData();
        await(()->snapshot(audio).prepared,15000,"archived WAV prepares through the ordinary media player");
        close(audio);
        check(!sourceStillOpen(audioUri),"closing archived audio releases the private source");
        FileViewerActivity video=openNestedEntry(inner,"inside-video.mp4");
        Uri videoUri=video.getIntent().getData();
        await(()->snapshot(video).prepared,15000,"archived MP4 prepares through the ordinary media player");
        close(video);
        check(!sourceStillOpen(videoUri),"closing archived video releases the private source");
        check(sourceStillOpen(innerUri),"Back keeps the parent archive available");
        close(inner);
        check(!sourceStillOpen(innerUri),"closing nested archive releases its source");
        close(parent);
        check(outer.isFile()&&!new File(fixture,"inner-owned.zip").exists(),
                "viewer never extracts an archive member to the user's folder");
    }

    private static byte[] archiveFixture(java.util.Map<String,byte[]> entries)throws java.io.IOException{
        java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
        try(java.util.zip.ZipOutputStream zip=new java.util.zip.ZipOutputStream(bytes)){
            for(java.util.Map.Entry<String,byte[]> element:entries.entrySet()){
                zip.putNextEntry(new java.util.zip.ZipEntry(element.getKey()));
                zip.write(element.getValue());zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private FileViewerActivity openNestedEntry(FileViewerActivity parent,String entry){
        String[] parts=entry.split("/");
        for(int i=0;i<parts.length-1;i++)chooseArchiveRow(parent,parts[i],true);
        ActivityMonitor monitor=addMonitor(FileViewerActivity.class.getName(),null,false);
        try{
            chooseArchiveRow(parent,parts[parts.length-1],false);
            FileViewerActivity child=(FileViewerActivity)waitForMonitorWithTimeout(monitor,15000);
            check(child!=null&&child!=parent,"opened "+entry+" inside Anything");
            await(()->snapshot(child).ready,15000,"nested reader ready for "+entry);
            return child;
        }finally{removeMonitor(monitor);}
    }
    private String archiveFolderPath(FileViewerActivity viewer){
        AtomicReference<String> current=new AtomicReference<>();
        main(()->current.set((String)field(viewer,"archiveFolder")));
        return current.get();
    }
    private void chooseArchiveRow(FileViewerActivity parent,String name,boolean directory){
        main(()->{
            ListView list=(ListView)field(parent,"archiveList");
            for(int i=0;i<list.getCount();i++){
                ArchiveFolderTree.Node row=(ArchiveFolderTree.Node)list.getAdapter().getItem(i);
                if(name.equals(row.leaf)&&directory==row.folder){
                    check(list.getAdapter().isEnabled(i)&&list.performItemClick(null,i,i),"activate archive row "+name);
                    return;
                }
            }
            throw new AssertionError("Missing archive row: "+name);
        });
    }
    private int[] archiveViewport(FileViewerActivity activity){
        int[] position={-1,Integer.MIN_VALUE};
        main(()->{
            ListView list=(ListView)field(activity,"archiveList");
            if(list!=null&&list.getChildCount()>0){
                position[0]=list.getFirstVisiblePosition();
                position[1]=list.getChildAt(0).getTop()-list.getListPaddingTop();
            }
        });
        return position;
    }
    private void archiveFolders() throws Exception {
        File file=new File(fixture,"virtual-folders.zip");
        java.util.Map<String,byte[]> entries=new java.util.LinkedHashMap<>();
        entries.put("A/Empty/",new byte[0]);
        entries.put("A/B/C/inside.zip",archiveFixture(java.util.Collections.singletonMap("one/two/readme.txt",
                "private nested data".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        entries.put("../unsafe.txt",new byte[0]);
        entries.put("a/../also-unsafe.txt",new byte[0]);
        for(int i=0;i<120;i++)entries.put(String.format(java.util.Locale.ROOT,"A/%03d.txt",i),new byte[0]);
        java.nio.file.Files.write(file.toPath(),archiveFixture(entries));
        FileViewerActivity top=open(issue(file),file.getName());
        main(()->{
            ListView list=(ListView)field(top,"archiveList");
            check(list.getCount()==1&&containsVisibleTextFragment(content(top),"Unsafe or ambiguous paths were hidden"),
                    "root shows direct folders and warns about hidden invalid paths");
        });
        chooseArchiveRow(top,"A",true);
        main(()->((ListView)field(top,"archiveList")).setSelectionFromTop(47,0));
        int[] expectedMark={47,0};
        await(()->java.util.Arrays.equals(archiveViewport(top),expectedMark),5000,"scroll deep into A");
        chooseArchiveRow(top,"B",true);
        AtomicReference<int[]> mark=new AtomicReference<>();
        main(()->mark.set((int[])((java.util.Map<?,?>)field(top,"archiveFolderScroll")).get("A")));
        check(java.util.Arrays.equals(mark.get(),expectedMark),"save A at row 47 and offset 0; got "+java.util.Arrays.toString(mark.get()));
        chooseArchiveRow(top,"C",true);
        check("A/B/C".equals(archiveFolderPath(top)),"three levels of folders");
        FileViewerActivity nested=openNestedEntry(top,"inside.zip");
        Uri privateSource=nested.getIntent().getData();
        check(ArchivePreviewSources.depth(privateSource)==1,"nested archive retains a private source");
        chooseArchiveRow(nested,"one",true);chooseArchiveRow(nested,"two",true);
        check("one/two".equals(archiveFolderPath(nested)),"inner archive has independent navigation");
        close(nested);
        check(!sourceStillOpen(privateSource),"closing nested archive releases its private source");
        await(()->snapshot(top).ready,15000,"parent viewer returns after nested archive");
        check("A/B/C".equals(archiveFolderPath(top)),"parent folder survives opening a nested archive");
        main(top::onBackPressed);
        check("A/B".equals(archiveFolderPath(top)),"Android Back goes up one folder");
        main(top::onBackPressed);
        check("A".equals(archiveFolderPath(top)),"second Back goes up one more folder");
        try{
            await(()->java.util.Arrays.equals(archiveViewport(top),expectedMark),5000,"restore exact row and offset in A");
        }catch(AssertionError failure){
            throw new AssertionError(failure.getMessage()+"; expected="+java.util.Arrays.toString(expectedMark)
                    +"; actual="+java.util.Arrays.toString(archiveViewport(top)),failure);
        }
        chooseArchiveRow(top,"Empty",true);
        main(()->check(((ListView)field(top,"archiveList")).getCount()==0,"explicit empty directory is navigable"));
        main(()->{
            TextView root=exactText(content(top),"Archive");
            check(root!=null&&root.performClick(),"breadcrumb jumps to archive root");
        });
        check(archiveFolderPath(top).isEmpty()&&!new File(fixture,"A").exists(),"no files were extracted");
        close(top);
    }

    private boolean sourceStillOpen(Uri uri){
        try(android.os.ParcelFileDescriptor descriptor=ArchivePreviewSources.open(uri)){return descriptor!=null;}
        catch(java.io.IOException closed){return false;}
    }

    private void extractArchive() throws Exception {
        File zip = new File(fixture,"clean.zip");
        try (java.util.zip.ZipOutputStream output = new java.util.zip.ZipOutputStream(new FileOutputStream(zip))) {
            output.putNextEntry(new java.util.zip.ZipEntry("Documents/Note.txt"));
            output.write(new byte[]{8,14,26});
            output.closeEntry();
        }
        FileViewerActivity activity = open(issue(zip),"clean.zip");
        File destination = new File(fixture,"clean");
        File note = new File(destination,"Documents/Note.txt");
        check(!destination.exists(),"opening an archive does not extract it");
        int[] extractCenter = new int[2];
        main(()->{
            Button button = button(content(activity),"Extract all…");
            button.getLocationOnScreen(extractCenter);
            extractCenter[0]+=button.getWidth()/2; extractCenter[1]+=button.getHeight()/2;
            check(extractCenter[1] > target.getResources().getDisplayMetrics().heightPixels * .7,
                    "extract is a lower-screen primary action");
        });
        click(activity,"Extract all…");
        main(()->{
            AppSheet sheet = (AppSheet)field(activity,"activeSheet");
            Button confirm = button(sheet.actions,"Extract here");
            int[] center = new int[2];
            confirm.getLocationOnScreen(center);
            center[0]+=confirm.getWidth()/2; center[1]+=confirm.getHeight()/2;
            float density = target.getResources().getDisplayMetrics().density;
            check(Math.abs(center[0]-extractCenter[0]) <= 24*density &&
                    Math.abs(center[1]-extractCenter[1]) <= 72*density,"confirm is near the original extract button");
        });
        main(()->{
            AppSheet sheet=(AppSheet)field(activity,"activeSheet");
            View browse=description(sheet.content,"Browse extraction destination");
            check(browse!=null&&browse.performClick(),"archive offers a visual folder destination picker");
            FolderPathDialog picker=(FolderPathDialog)field(activity,"extractFolderPicker");
            check(picker!=null,"destination picker opened");
            picker.dismiss();
            check(sheet.isShowing(),"closing destination picker returns to archive confirmation");
            find(sheet.content,EditText.class).setText("relative");
        });
        confirmExtraction(activity);
        main(()->{
            AppSheet sheet=(AppSheet)field(activity,"activeSheet");
            check(sheet!=null&&sheet.isShowing()&&find(sheet.content,EditText.class).getError()!=null,
                    "invalid destination stays open for correction");
        });
        setExtractDestination(activity);
        confirmExtraction(activity);
        await(()->extractNotice(activity).startsWith("Extracted to "),45000,"explicit extraction finishes in chosen folder");
        check(java.util.Arrays.equals(java.nio.file.Files.readAllBytes(note.toPath()),new byte[]{8,14,26}),
                "Files contains the exact extracted bytes");
        java.nio.file.Files.write(note.toPath(),new byte[]{99});
        click(activity,"Extract all…");
        setExtractDestination(activity);
        confirmExtraction(activity);
        await(()->extractNotice(activity).contains("same name already exists"),45000,"existing destination is refused");
        check(java.util.Arrays.equals(java.nio.file.Files.readAllBytes(note.toPath()),new byte[]{99}),
                "existing destination was not replaced");
        close(activity);
        check(fixtureDescriptors()==0,"extraction viewer closes its file descriptors");
        ArchiveExtraction.removeStaging(destination.toPath());
        check(!destination.exists(),"owned extracted test fixture cleaned up");
    }

    private void extractSevenZip() throws Exception {
        File archive = new File(fixture,"synthetic.7z");
        // Exact bytes of the independent 7-Zip 26.06 solid fixture used by the host tests.
        byte[] bytes = android.util.Base64.decode(
                "N3q8ryccAAQtXAjT4QEAAAAAAAAiAAAAAAAAABqwBsbhtSEBWl0AJxvKhmSSsFHcOzIVeM8qb/6vWzyKnBF2IqQw4UcMoal"
                + "OyzlpziL0eU1cFwOaAlzh5E+NPHJLovjJsDpha8Qts8ggUCc8uZo55e85E0n4DbQaKiknizbx33YLtVMOsCT/4IaBIl/dH6"
                + "oVnYLGsnBrHKt4YOMoYOHhvzliMiv+vl1xhFKuKhVGNY7fw6lirBU7pQv4Q0NMEaZMhaTKT9UxH/FjFD3I0mnHm+cZV/d39"
                + "wP2/3KlnTYuxqKPuwzXmwb8eOILv02F++l09MA9fI1oA3LA2numttwglvcpOc5fxaz6Bqf0g3XxxsVIIjDQd4Enl4iS8rLU"
                + "7T0FYBkW8a+oeiO4AKsTnXmtEUREuzlYsFE/ZLxCbyfqwrYi0FPfmFAIta+6F4MoJCf4LkV6KzskwgvatXYVOXe0NIeDiVN"
                + "FxzX/F4zJXTh7vJUHhy5YCJBS9vG6UoqzNd5/AAAAAIEzB64P1Tn9wZck0/6zcBaxaPt1R3xXaJMTUDSsPoY1odnhv6+y7B"
                + "KgNjQJVUm81j32xHuni8Z5s8KZRQmDV1r7NyqUlBLjTMC9NnRGn2eSWX075m4iIjh/T3olAKLYA0nMJ/LMrXYGf2adw3+7n"
                + "bmxksTsIbOVVwdMIBwAFwaBYgEJfwAHCwEAASMDAQEFXQAQAAAMgLIKATbpZV0AAA==",android.util.Base64.DEFAULT);
        java.nio.file.Files.write(archive.toPath(),bytes);
        FileViewerActivity activity = open(issue(archive),archive.getName());
        File destination = new File(fixture,"synthetic");
        File note = new File(destination,"docs/雪.txt");
        check(!destination.exists(),"opening the 7z preview does not extract");
        main(()->{
            Object result=field(activity,"shown");
            SevenZipArchive.Listing list=(SevenZipArchive.Listing)field(result,"sevenZip");
            check(list.count==5&&list.entries.stream().anyMatch(item->item.name.equals("docs/雪.txt")),
                    "viewer lists the independently produced archive directory");
        });
        FileViewerActivity child=openNestedEntry(activity,"docs/雪.txt");
        main(()->{
            PreviewText.Result text=(PreviewText.Result)field(field(child,"shown"),"text");
            check(text!=null&&text.text.contains("Note from a synthetic 7z fixture"),"7z member bytes rendered without extraction");
        });
        Uri childUri=child.getIntent().getData();
        close(child);
        check(!sourceStillOpen(childUri)&&!destination.exists(),"closing 7z preview releases source without extracting");
        await(()->snapshot(activity).ready,15000,"7z parent ready after member preview");
        check("docs".equals(archiveFolderPath(activity)),"7z retains its folder on returning from the member");
        click(activity,"Extract all…");
        setExtractDestination(activity);
        confirmExtraction(activity);
        await(()->extractNotice(activity).startsWith("Extracted to "),45_000,"explicit 7z extraction publishes into chosen folder");
        check(new String(java.nio.file.Files.readAllBytes(note.toPath()),java.nio.charset.StandardCharsets.UTF_8)
                .equals("Note from a synthetic 7z fixture.\n"),"Unicode path and exact archive content");
        check(new File(destination,"empty-dir").isDirectory(),"empty folders preserved");
        close(activity);
        ArchiveExtraction.removeStaging(destination.toPath());
        check(fixtureDescriptors()==0,"7z viewer and extraction close their descriptors");
    }

    private void previewTarMembers() throws Exception {
        byte[] doc=archiveFixture(java.util.Collections.singletonMap("word/document.xml",
                ("<w:document xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'>"
                +"<w:body><w:p><w:r><w:t>Owned TAR member document</w:t></w:r></w:p></w:body></w:document>")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        byte[] wav=java.nio.file.Files.readAllBytes(new File(fixture,"silent.wav").toPath());
        for(String suffix:new String[]{".tar",".tar.gz",".tgz"}){
            File archive=new File(fixture,"member-test"+suffix);
            try(java.io.OutputStream raw=new FileOutputStream(archive);
                java.io.OutputStream out=suffix.equals(".tar")?raw:new java.util.zip.GZIPOutputStream(raw)){
                writeTestTarRecord(out,"./",new byte[0],(byte)'5');
                writeTestTarRecord(out,"./Documents/Deep/member.docx",doc);
                writeTestTar(out,"./Media/Deep/member.wav",wav);
            }
            FileViewerActivity parent=open(issue(archive),archive.getName());
            File destination=new File(fixture,"member-test");
            check(!destination.exists(),"opening "+suffix+" does not extract");
            FileViewerActivity word=openNestedEntry(parent,"Documents/Deep/member.docx");
            Uri wordUri=word.getIntent().getData();
            check(ArchivePreviewSources.depth(wordUri)==1,"archive document has a bounded private source");
            main(()->{
                OfficePreview.Result rendered=(OfficePreview.Result)field(field(word,"shown"),"office");
                check(rendered!=null&&!rendered.sections.isEmpty()
                        &&rendered.sections.get(0).text.contains("Owned TAR member document"),
                        "ordinary Office viewer renders the "+suffix+" member");
            });
            close(word);
            check(!sourceStillOpen(wordUri),"Back releases the private document source");
            await(()->snapshot(parent).ready,15000,"Back restores the TAR listing");
            check("Documents/Deep".equals(archiveFolderPath(parent)),"TAR returns to the exact folder");
            main(parent::onBackPressed);main(parent::onBackPressed);
            FileViewerActivity audio=openNestedEntry(parent,"Media/Deep/member.wav");
            Uri audioUri=audio.getIntent().getData();
            await(()->snapshot(audio).prepared,15000,"ordinary media player prepares the "+suffix+" member");
            close(audio);
            check(!sourceStillOpen(audioUri),"Back releases the private audio source");
            close(parent);
            check(archive.isFile()&&!destination.exists(),"member previews do not extract to the user's folder");
        }
        check(fixtureDescriptors()==0,"TAR member preview closes fixture descriptors");
    }

    private void extractStreamArchives() throws Exception {
        byte[] content = {8, 14, 26};
        for (String suffix : new String[]{".tar", ".tar.gz", ".tgz", ".gz"}) {
            String stem = "example" + suffix.replace(".", "-") + ".txt";
            String filename = stem + suffix;
            File source = new File(fixture,filename);
            try (java.io.OutputStream raw = new FileOutputStream(source);
                 java.io.OutputStream output = suffix.endsWith("gz") ? new java.util.zip.GZIPOutputStream(raw) : raw) {
                if (suffix.equals(".gz")) output.write(content);
                else writeTestTar(output,"Folder/Note.txt",content);
            }
            FileViewerActivity activity = open(issue(source),filename);
            File destination = new File(fixture,stem);
            File note = new File(destination,suffix.equals(".gz") ? stem : "Folder/Note.txt");
            check(!destination.exists(),"opening " + suffix + " does not extract");
            if(!suffix.equals(".gz"))main(()->{
                TarArchiveListing.Listing members=(TarArchiveListing.Listing)field(field(activity,"shown"),"tar");
                check(members!=null&&members.stop==TarArchiveListing.Stop.COMPLETE&&members.entries.size()==1
                        &&members.entries.get(0).name.equals("Folder/Note.txt")&&members.entries.get(0).size==3,
                        "native TAR listing shows actual names and sizes without extracting "+suffix);
            });
            click(activity,"Extract all…");
            setExtractDestination(activity);
            confirmExtraction(activity);
            await(()->extractNotice(activity).startsWith("Extracted to "),45000,"explicit extraction finishes for " + suffix);
            check(java.util.Arrays.equals(java.nio.file.Files.readAllBytes(note.toPath()),content),
                    "output contains the exact bytes for " + suffix);
            close(activity);
            ArchiveExtraction.removeStaging(destination.toPath());
        }
        File evil = new File(fixture,"escape.tar");
        try (FileOutputStream output = new FileOutputStream(evil)) {
            // Hide a malformed path after the listing cap; explicit Extract must still revalidate every record.
            for(int i=0;i<TarArchiveListing.MAX_ENTRIES;i++)writeTestTarRecord(output,"first-"+i,new byte[0]);
            writeTestTar(output,"../outside.txt",content);
        }
        FileViewerActivity bad = open(issue(evil),evil.getName());
        click(bad,"Extract all…");
        setExtractDestination(bad);
        confirmExtraction(bad);
        await(()->extractNotice(bad).contains("unsafe"),45000,"unsafe TAR is rejected through viewer");
        check(!new File(fixture,"escape").exists()&&!new File(fixture,"outside.txt").exists(),
                "unsafe TAR publishes nothing to destination");
        close(bad);
        check(fixtureDescriptors()==0,"TAR/GZIP previews close their file descriptors");
    }

    private static void writeTestTar(java.io.OutputStream output,String name,byte[] content) throws IOException {
        writeTestTarRecord(output,name,content);
        output.write(new byte[1024]);
    }

    private static void writeTestTarRecord(java.io.OutputStream output,String name,byte[] content) throws IOException {
        writeTestTarRecord(output,name,content,(byte)'0');
    }
    private static void writeTestTarRecord(java.io.OutputStream output,String name,byte[] content,byte type) throws IOException {
        byte[] header = new byte[512];
        putTestTar(header,0,name);
        putTestTar(header,100,"0000644");
        putTestTar(header,124,String.format(java.util.Locale.ROOT,"%011o",content.length));
        java.util.Arrays.fill(header,148,156,(byte)' ');
        header[156]=type;
        putTestTar(header,257,"ustar\00000");
        int checksum=0;
        for(byte value:header)checksum+=value&255;
        putTestTar(header,148,String.format(java.util.Locale.ROOT,"%06o\0 ",checksum));
        output.write(header);
        output.write(content);
        output.write(new byte[(512-content.length%512)%512]);
    }

    private static void putTestTar(byte[] target,int offset,String value) {
        byte[] bytes=value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        System.arraycopy(bytes,0,target,offset,bytes.length);
    }

    private void confirmExtraction(FileViewerActivity activity) {
        main(()->{
            AppSheet sheet=(AppSheet)field(activity,"activeSheet");
            check(sheet!=null&&sheet.isShowing(),"archive confirmation sheet is visible");
            Button confirm=button(sheet.actions,"Extract here");
            check(confirm!=null&&confirm.isEnabled()&&confirm.performClick(),"activate the archive confirmation button");
        });
        waitForIdleSync();
    }

    private void setExtractDestination(FileViewerActivity activity) {
        main(()->{
            AppSheet sheet = (AppSheet)field(activity,"activeSheet");
            EditText destination = find(sheet.content,EditText.class);
            destination.setText(fixture.getPath());
        });
    }

    private String extractNotice(FileViewerActivity activity) {
        java.util.concurrent.atomic.AtomicReference<String> value = new java.util.concurrent.atomic.AtomicReference<>("");
        main(()->value.set(((TextView)field(activity,"notice")).getText().toString()));
        return value.get();
    }

    private void apkContents() throws Exception {
        File apk = new File(fixture,"SyntheticPackage.apk");
        String sample = "A <b>literal</b> asset";
        try (java.util.zip.ZipFile source = new java.util.zip.ZipFile(target.getApplicationInfo().sourceDir);
             java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(new FileOutputStream(apk))) {
            // Use the test application's build-time Android manifest/resources; all content
            // read by this test is local synthetic data and the fixture never gets installed.
            for(String entryName:new String[]{"AndroidManifest.xml","resources.arsc"}) {
                java.util.zip.ZipEntry entry = source.getEntry(entryName);
                if(entry==null)continue;
                out.putNextEntry(new java.util.zip.ZipEntry(entryName));
                try(java.io.InputStream in=source.getInputStream(entry)) {
                    byte[] bytes=new byte[8192];for(int n;(n=in.read(bytes))!=-1;)out.write(bytes,0,n);
                }
                out.closeEntry();
            }
            out.putNextEntry(new java.util.zip.ZipEntry("assets/FixtureDoc.html"));
            out.write(sample.getBytes(java.nio.charset.StandardCharsets.UTF_8));out.closeEntry();
            out.putNextEntry(new java.util.zip.ZipEntry("classes.dex"));out.write(new byte[]{1,2,3});out.closeEntry();
        }
        FileViewerActivity viewer = open(issue(apk),apk.getName());
        try {
            click(viewer,"Browse package contents");
            AtomicReference<AppSheet> contents=new AtomicReference<>();
            AtomicReference<EditText> filter=new AtomicReference<>();
            AtomicReference<ListView> list=new AtomicReference<>();
            await(()->{
                main(()->{
                    AppSheet sheet=(AppSheet)field(viewer,"activeSheet");
                    if(sheet!=null&&sheet.isShowing()) {
                        contents.set(sheet);filter.set(find(sheet.content,EditText.class));list.set(find(sheet.content,ListView.class));
                    }
                });
                return filter.get()!=null&&list.get()!=null&&filter.get().isEnabled();
            },12000,"APK listing ready");
            main(()->{
                check(list.get().getCount()>=3,"APK contains platform files and synthetic asset");
                check(button(contents.get().content,"Extract all…")==null&&button(contents.get().content,"Install APK")==null,
                        "APK contents offers neither extraction nor installer");
                filter.get().setText("FixtureDoc");
                check(list.get().getCount()==1,"filter finds one package path");
            });
            main(()->{
                ListView visible=list.get();
                ZipDirectory.Entry asset=(ZipDirectory.Entry)visible.getItemAtPosition(0);
                check("assets/FixtureDoc.html".equals(asset.name)
                        &&visible.performItemClick(visible.getChildAt(0),0,visible.getItemIdAtPosition(0)),"activate the filtered HTML asset row");
            });
            await(()->{
                AtomicBoolean visible=new AtomicBoolean();
                main(()->visible.set(containsVisibleText(contents.get().content,sample)));
                return visible.get();
            },12000,"APK text stays literal in the read-only detail");
            main(()->{
                Button back=button(contents.get().content,"Back to package contents");
                check(back!=null&&back.isShown()&&back.performClick(),"return to package contents from asset detail");
                check(list.get().isShown()&&list.get().getCount()==1,"Back retains listing and filter");
                filter.get().setText("classes.dex");
                check(list.get().getCount()==1,"DEX filter reaches bytecode");
            });
            main(()->{
                ListView visible=list.get();
                ZipDirectory.Entry dex=(ZipDirectory.Entry)visible.getItemAtPosition(0);
                check("classes.dex".equals(dex.name)&&visible.performItemClick(visible.getChildAt(0),0,visible.getItemIdAtPosition(0)),
                        "activate the actual filtered DEX row rather than the OEM accessibility container");
                check(containsVisibleText(contents.get().content,"Go back to the APK and use Browse DEX classes."),
                        "DEX row is metadata-only and explains the separate tool");
                Button back=button(contents.get().content,"Back to package contents");
                check(back!=null&&back.performClick(),"return to package contents from DEX metadata");
                filter.get().setText("AndroidManifest.xml");
                check(list.get().getCount()==1,"manifest filter finds the actual compiled XML entry");
            });
            main(()->{
                ListView visible=list.get();
                ZipDirectory.Entry manifest=(ZipDirectory.Entry)visible.getItemAtPosition(0);
                check("AndroidManifest.xml".equals(manifest.name)
                        &&visible.performItemClick(visible.getChildAt(0),0,visible.getItemIdAtPosition(0)),
                        "open the binary manifest row without installing the APK");
            });
            await(()->{
                AtomicBoolean xmlShown=new AtomicBoolean();
                main(()->xmlShown.set(containsVisibleTextFragment(contents.get().content,"<manifest ")
                        &&containsVisibleTextFragment(contents.get().content,"package=\""+target.getPackageName()+"\"")
                        &&containsVisibleTextFragment(contents.get().content,"</manifest>")));
                return xmlShown.get();
            },12000,"Android manifest is reconstructed as visible literal XML text");
            main(()->{
                Button back=button(contents.get().content,"Back to package contents");
                check(back!=null&&back.performClick(),"return to package contents from decoded XML");
                filter.get().setText("resources.arsc");
                ListView visible=list.get();
                check(visible.getCount()==1,"resource table filter reaches the actual APK table");
                ZipDirectory.Entry resources=(ZipDirectory.Entry)visible.getItemAtPosition(0);
                check("resources.arsc".equals(resources.name)
                        &&visible.performItemClick(visible.getChildAt(0),0,visible.getItemIdAtPosition(0)),
                        "open the compiled resource table row");
            });
            await(()->{
                AtomicBoolean resourcesShown=new AtomicBoolean();
                main(()->resourcesShown.set(containsVisibleTextFragment(contents.get().content,"Compiled resources · read only")
                        &&containsVisibleTextFragment(contents.get().content,"0x7f")
                        &&containsVisibleTextFragment(contents.get().content,":drawable/")));
                return resourcesShown.get();
            },12000,"compiled resource table presents verified type and entry names");
        } finally { close(viewer); }
    }

    private static boolean containsVisibleTextFragment(View root,String expected){
        if(!root.isShown())return false;
        if(root instanceof TextView&&((TextView)root).getText().toString().contains(expected))return true;
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)
            if(containsVisibleTextFragment(((ViewGroup)root).getChildAt(i),expected))return true;
        return false;
    }

    private static boolean containsVisibleText(View root,String expected) {
        if(!root.isShown())return false;
        if(root instanceof TextView&&expected.contentEquals(((TextView)root).getText()))return true;
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)
            if(containsVisibleText(((ViewGroup)root).getChildAt(i),expected))return true;
        return false;
    }

    private void apkActions() throws Exception {
        File apk = new File(fixture,"Synthetic.apk");
        java.nio.file.Files.copy(new File(target.getApplicationInfo().sourceDir).toPath(),apk.toPath());
        // Match the explicit system handoff, not the preview's own content URI. On API 26
        // a MIME-only monitor can also match a local preview Intent with no action.
        ActivityMonitor blocked = new ActivityMonitor() {
            @Override public ActivityResult onStartActivity(Intent intent) {
                return Intent.ACTION_VIEW.equals(intent.getAction())
                        && ApkInstallAction.MIME.equals(intent.getType())
                        && intent.getData()!=null
                        && (target.getPackageName()+".apk").equals(intent.getData().getAuthority())
                        ? new ActivityResult(Activity.RESULT_CANCELED,null) : null;
            }
        };
        addMonitor(blocked);
        try {
            FileViewerActivity activity = open(issue(apk),"Synthetic.apk");
            check(blocked.getHits()==0,"opening APK never launches an installer");
            main(()->{
                Object result = field(activity,"shown");
                ApkInstallAction.Details details = (ApkInstallAction.Details)field(result,"apk");
                check(details.packageName.equals(target.getPackageName()),"viewer shows actual inspected APK");
                check((button(content(activity),"Install APK")!=null)==ApkInstallAction.supported(),
                        "explicit Install APK is visible only in supported editions");
            });
            click(activity,"Inspect APK");
            AtomicReference<AppSheet> inspection=new AtomicReference<>();
            await(()->{
                main(()->{
                    AppSheet current=(AppSheet)field(activity,"activeSheet");
                    if(current!=null&&current.isShowing()&&containsVisibleText(current.content,"Split APK declarations"))inspection.set(current);
                });
                return inspection.get()!=null;
            },12000,"read-only APK inspection sections ready");
            main(()->{
                AppSheet current=inspection.get();
                check(containsVisibleText(current.content,"APK file SHA-256")
                        &&containsVisibleText(current.content,"Requested permissions")
                        &&containsVisibleText(current.content,"Signer certificate SHA-256")
                        &&containsVisibleText(current.content,"Native library ABIs"),"APK inspector exposes all metadata sections");
                check(button(current.content,"Install APK")==null&&blocked.getHits()==0,"inspecting does not offer or open the installer");
                current.dismiss();
            });
            if(ApkInstallAction.supported()){
                click(activity,"Install APK");
                await(()->blocked.getHits()==1,6000,"explicit click reaches Android installer intent");
            }
            close(activity);
        } finally { removeMonitor(blocked); }
    }

    private void unknownText() throws Exception {
        File custom = new File(fixture,"Workshop.customtext");
        java.nio.file.Files.write(custom.toPath(),"Plain custom note".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Intent intent=FileViewerActivity.intent(target,issue(custom),custom.getName(),custom.getPath(),custom.length())
                .putExtra(MARKER,true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        FileViewerActivity activity=(FileViewerActivity)startActivitySync(intent);
        await(()->{
            AtomicReference<Boolean> ready=new AtomicReference<>(false);
            main(()->ready.set(button(content(activity),"Read as text")!=null));
            return ready.get();
        },12000,"unknown type offers explicit safe text mode");
        click(activity,"Read as text");
        await(()->snapshot(activity).ready,12000,"plain text fallback loads");
        main(()->{
            PreviewText.Result rendered=(PreviewText.Result)field(field(activity,"shown"),"text");
            check("Plain custom note".equals(rendered.text),"unknown extension decoded as literal text");
        });
        close(activity);
    }

    private void codeText() throws Exception {
        File source = new File(fixture,"viewer-script.ts");
        String literal = "const name: string = \"hello\";\r\n// keep literal <script>\r\nexport { name };";
        java.nio.file.Files.write(source.toPath(),literal.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        FileViewerActivity activity = open(issue(source),source.getName());
        main(() -> {
            ScrollView scroll=find(content(activity),ScrollView.class);
            TextView body=find(scroll,TextView.class);
            android.text.Spanned rendered=(android.text.Spanned)body.getText();
            check(rendered.toString().contains("<script>"),"TypeScript source stays literal");
            check(rendered.getSpans(0,rendered.length(),android.text.style.ForegroundColorSpan.class).length>=3,"code has colored spans");
            check(rendered.getSpans(0,rendered.length(),CodePreviewStyle.LineNumber.class).length==3,"gutter has one number per logical line");
            check(!containsClass(content(activity),"android.webkit.WebView"),"native source does not run code");
            check(button(content(activity),"Syntax: JavaScript / TypeScript · Auto")!=null,"extension selects TypeScript automatically");
        });
        main(()->{
            View copy=description(content(activity),"Copy text");
            check(copy!=null&&copy.performClick(),"activate the visible Copy text control");
            android.content.ClipboardManager copied=activity.getSystemService(android.content.ClipboardManager.class);
            check(literal.equals(copied.getPrimaryClip().getItemAt(0).getText().toString()),"copy preserves CRLF without copied line numbers");
        });
        close(activity);
        File transport = new File(fixture,"fixture-transport.ts");
        byte[] packets = new byte[4*188];
        for(int i=0;i<4;i++)packets[i*188]=0x47;
        java.nio.file.Files.write(transport.toPath(),packets);
        FileViewerActivity.PreviewCheck media=FileViewerActivity.checkForProbe(target,issue(transport),transport.getName(),0);
        check(media.mediaDescriptor&&media.error==null,"transport stream .ts keeps media path");
        File plain = new File(fixture,"example-code.txt");
        java.nio.file.Files.write(plain.toPath(),"def f():\n  return 5".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        FileViewerActivity note = open(issue(plain),plain.getName());
        main(()->{
            TextView body=find(find(content(note),ScrollView.class),TextView.class);
            android.text.Spanned rendered=(android.text.Spanned)body.getText();
            check(rendered.getSpans(0,rendered.length(),android.text.style.ForegroundColorSpan.class).length==0,"ordinary TXT has no synthetic colors");
            check(rendered.getSpans(0,rendered.length(),CodePreviewStyle.LineNumber.class).length==2,"ordinary TXT keeps line numbers");
        });
        click(note,"Syntax: Plain text · Auto");
        clickPopupItem("Python");
        await(()->{
            AtomicBoolean result=new AtomicBoolean();
            main(()->{if(field(note,"pending")==null&&button(content(note),"Syntax: Python")!=null)result.set(true);});
            return result.get();
        },12000,"TXT language changes to Python");
        main(()->{
            TextView body=find(find(content(note),ScrollView.class),TextView.class);
            android.text.Spanned rendered=(android.text.Spanned)body.getText();
            check(rendered.getSpans(0,rendered.length(),android.text.style.ForegroundColorSpan.class).length>=3,"override colors Python in .txt");
        });
        FileViewerActivity rotated=rotate(note);
        main(()->check(button(content(rotated),"Syntax: Python")!=null,"language override survives rotation"));
        close(rotated);
    }


    private void officeSummary() throws Exception {
        File word = new File(fixture,"sample-notes.docx");
        String ns = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(word))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("word/document.xml"));
            String body = "<w:document xmlns:w=\"" + ns + "\"><w:body><w:p><w:r><w:t>Review timeline &amp; actions</w:t></w:r></w:p></w:body></w:document>";
            zip.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();
        }
        FileViewerActivity activity = open(issue(word),word.getName());
        main(()->{
            check(button(content(activity),"Open in another app")!=null,"Office preview retains Open in another app");
            check(!containsClass(content(activity),"android.webkit.WebView"),"Office preview is native and read-only");
            OfficePreview.Result parsed=(OfficePreview.Result)field(field(activity,"shown"),"office");
            check(parsed.sections.size()==1&&"Review timeline & actions".equals(parsed.sections.get(0).text),"DOCX displays text with safe XML escapes");
            check(ExternalFileActions.mimeFor(word.getName()).equals(OfficePreview.mime(OfficePreview.Format.WORD)),"handler receives actual Office MIME");
        });
        close(activity);
        String storage="http://schemas.openxmlformats.org/spreadsheetml/2006/main";
        String relation="http://schemas.openxmlformats.org/officeDocument/2006/relationships";
        String packageNs="http://schemas.openxmlformats.org/package/2006/relationships";
        File workbook=new File(fixture,"field-workbook.xlsx");
        officeFixture(workbook,
            "xl/workbook.xml","<workbook xmlns='"+storage+"' xmlns:r='"+relation+"'><sheets><sheet name='Field test' r:id='s'/></sheets></workbook>",
            "xl/_rels/workbook.xml.rels","<Relationships xmlns='"+packageNs+"'><Relationship Id='s' Type='"+relation+"/worksheet' Target='worksheets/result.xml'/></Relationships>",
            "xl/worksheets/result.xml","<worksheet xmlns='"+storage+"'><sheetData><row r='7'><c r='A7' t='inlineStr'><is><t>Passes</t></is></c><c r='B7'><f>1+2</f><v>3</v></c></row></sheetData></worksheet>");
        FileViewerActivity sheet=open(issue(workbook),workbook.getName());
        main(()->{
            OfficePreview.Result data=(OfficePreview.Result)field(field(sheet,"shown"),"office");
            check(data.sections.size()==1&&data.sections.get(0).title.equals("Field test"),"XLSX opens its named worksheet");
            String row=data.sections.get(0).text;
            check(row.contains("7   A Passes")&&row.contains("B 3")&&!row.contains("1+2"),"XLSX shows cached cells without executing a formula");
            check(ExternalFileActions.mimeFor(workbook.getName()).equals(OfficePreview.mime(data.format)),"XLSX handler MIME");
        });
        close(sheet);
        visualPresentation();
    }

    private void visualPresentation()throws Exception{
        File presentation=new File(fixture,"field-brief.pptx");
        byte[] packageBytes=slideFixture();
        java.nio.file.Files.write(presentation.toPath(),packageBytes);
        FileViewerActivity viewer=open(issue(presentation),presentation.getName());
        await(()->visibleSlide(viewer,0,"Next steps"),12_000,"first visual slide follows the presentation order");
        main(()->{
            PresentationSlide slide=(PresentationSlide)field(field(viewer,"shown"),"slide");
            check(slide.picture.getWidth()>slide.picture.getHeight()&&slide.count==2,"native wide slide canvas and count");
            android.graphics.Bitmap sample=android.graphics.Bitmap.createBitmap(160,90,android.graphics.Bitmap.Config.ARGB_8888);
            try{
                android.graphics.Canvas draw=new android.graphics.Canvas(sample);
                draw.scale(160f/slide.picture.getWidth(),90f/slide.picture.getHeight());
                draw.drawPicture(slide.picture);
                check(sample.getPixel(2,2)==0xffe9f2fa,"slide background draws at expected location");
                check(sample.getPixel(88,40)==0xff394d80,"shape fill renders on the slide canvas");
                check(sample.getPixel(132,17)==0xffc03edd,"embedded image renders without external access");
            }finally{sample.recycle();}
            ZoomPreviewView zoom=find(content(viewer),ZoomPreviewView.class);
            check(zoom!=null&&zoom.isShown(),"visual default is the zoomable slide");
            zoom.zoomBy(2);check(zoom.state()[0]>1.5f,"slide supports zoom and pan");
            Button toggle=button(content(viewer),"Slide text");check(toggle!=null&&toggle.performClick(),"slide has selectable text fallback");
            TextView selected=visibleTextView(content(viewer),"Next steps");
            check(selected!=null&&selected.isTextSelectable(),"slide text is real selectable text");
            Button visual=button(content(viewer),"Slide view");check(visual!=null&&visual.performClick(),"return to slide view without rerender");
            Button next=button(content(viewer),"Next slide");check(next!=null&&next.isEnabled()&&next.performClick(),"go to slide 2");
        });
        await(()->visibleSlide(viewer,1,"Second slide"),12_000,"next slide is loaded lazily");
        main(()->{
            Button previous=button(content(viewer),"Previous slide");check(previous!=null&&previous.isEnabled()&&previous.performClick(),"return to slide 1");
        });
        await(()->visibleSlide(viewer,0,"Next steps"),12_000,"previous slide keeps presentation order");
        check(ExternalFileActions.mimeFor(presentation.getName()).equals(OfficePreview.mime(OfficePreview.Format.SLIDES)),"PPTX handler MIME");
        close(viewer);

        File zipped=new File(fixture,"owned-slides.zip");
        java.nio.file.Files.write(zipped.toPath(),archiveFixture(java.util.Collections.singletonMap("inside.pptx",packageBytes)));
        FileViewerActivity outer=open(issue(zipped),zipped.getName());
        FileViewerActivity member=openNestedEntry(outer,"inside.pptx");
        Uri entry=member.getIntent().getData();
        await(()->visibleSlide(member,0,"Next steps"),12_000,"verified archive PPTX uses the same visual slide viewer");
        check(ArchivePreviewSources.depth(entry)==1,"archived slide uses isolated seekable descriptor");
        close(member);
        check(!sourceStillOpen(entry),"closing slide viewer releases archive member");
        close(outer);
    }

    private boolean visibleSlide(FileViewerActivity viewer,int index,String text)throws Exception{
        AtomicBoolean ready=new AtomicBoolean();
        main(()->{
            Object shown=field(viewer,"shown");
            if(shown==null)return;
            PresentationSlide slide=(PresentationSlide)field(shown,"slide");
            ready.set(slide!=null&&slide.index==index&&slide.text.equals(text));
        });
        return ready.get();
    }

    private static TextView visibleTextView(View root,String expected){
        if(!root.isShown())return null;
        if(root instanceof TextView&&expected.contentEquals(((TextView)root).getText()))return (TextView)root;
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){
            TextView found=visibleTextView(((ViewGroup)root).getChildAt(i),expected);
            if(found!=null)return found;
        }
        return null;
    }

    private static byte[] slideFixture()throws IOException{
        String slides="http://schemas.openxmlformats.org/presentationml/2006/main";
        String drawing="http://schemas.openxmlformats.org/drawingml/2006/main";
        String relation="http://schemas.openxmlformats.org/officeDocument/2006/relationships";
        String packageNs="http://schemas.openxmlformats.org/package/2006/relationships";
        String deck="<p:presentation xmlns:p='"+slides+"' xmlns:r='"+relation+"'><p:sldIdLst>"
                +"<p:sldId id='10' r:id='first'/><p:sldId id='11' r:id='second'/></p:sldIdLst>"
                +"<p:sldSz cx='10000000' cy='5625000'/></p:presentation>";
        String rel="<Relationships xmlns='"+packageNs+"'><Relationship Id='first' Type='"+relation
                +"/slide' Target='slides/slide9.xml'/><Relationship Id='second' Type='"+relation
                +"/slide' Target='slides/slide1.xml'/></Relationships>";
        String header="<p:sld xmlns:p='"+slides+"' xmlns:a='"+drawing+"' xmlns:r='"+relation+"'><p:cSld>";
        String bg="<p:bg><p:bgPr><a:solidFill><a:srgbClr val='E9F2FA'/></a:solidFill></p:bgPr></p:bg>";
        String shape="<p:sp><p:spPr><a:xfrm><a:off x='1000000' y='1000000'/><a:ext cx='6000000' cy='2000000'/>"
                +"</a:xfrm><a:prstGeom prst='rect'/><a:solidFill><a:srgbClr val='394D80'/></a:solidFill></p:spPr>"
                +"<p:txBody><a:p><a:r><a:rPr sz='3000'><a:solidFill><a:srgbClr val='FFFFFF'/></a:solidFill></a:rPr><a:t>";
        String pic="<p:pic><p:blipFill><a:blip r:embed='pic'/></p:blipFill><p:spPr><a:xfrm>"
                +"<a:off x='7200000' y='600000'/><a:ext cx='2200000' cy='1600000'/></a:xfrm></p:spPr></p:pic>";
        String textEnd="</a:t></a:r></a:p></p:txBody></p:sp>";
        String end="</p:spTree></p:cSld></p:sld>";
        String picRel="<Relationships xmlns='"+packageNs+"'><Relationship Id='pic' Type='"+relation
                +"/image' Target='../media/owned.png'/></Relationships>";
        java.util.Map<String,byte[]> data=new java.util.LinkedHashMap<>();
        java.nio.charset.Charset utf8=java.nio.charset.StandardCharsets.UTF_8;
        data.put("ppt/presentation.xml",deck.getBytes(utf8));data.put("ppt/_rels/presentation.xml.rels",rel.getBytes(utf8));
        data.put("ppt/slides/slide9.xml",(header+bg+"<p:spTree>"+shape+"Next steps"+textEnd+pic+end).getBytes(utf8));
        data.put("ppt/slides/slide1.xml",(header+bg+"<p:spTree>"+shape+"Second slide"+textEnd+end).getBytes(utf8));
        data.put("ppt/slides/_rels/slide9.xml.rels",picRel.getBytes(utf8));
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(32,32,android.graphics.Bitmap.Config.ARGB_8888);
        java.io.ByteArrayOutputStream png=new java.io.ByteArrayOutputStream();
        try{bitmap.eraseColor(0xffc03edd);if(!bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,png))
                throw new IOException("Test bitmap couldn't be encoded.");}
        finally{bitmap.recycle();}
        data.put("ppt/media/owned.png",png.toByteArray());
        return archiveFixture(data);
    }

    private static void officeFixture(File file,String... entries)throws IOException {
        try(java.util.zip.ZipOutputStream zip=new java.util.zip.ZipOutputStream(new FileOutputStream(file))){
            for(int i=0;i<entries.length;i+=2){
                zip.putNextEntry(new java.util.zip.ZipEntry(entries[i]));
                zip.write(entries[i+1].getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }


    private boolean archivePage(FileViewerActivity activity, int number) {
        AtomicReference<Boolean> ready = new AtomicReference<>(false);
        main(() -> {
            AppSheet sheet = (AppSheet) field(activity, "activeSheet");
            if (sheet == null) return;
            ZoomPreviewView page = find(sheet.content, ZoomPreviewView.class);
            if (page == null || page.getHeight() <= 0) return;
            for (int i = 0; i < sheet.actions.getChildCount(); i++) {
                View child = sheet.actions.getChildAt(i);
                if (child instanceof TextView && ("Page " + number + " of 3").contentEquals(((TextView) child).getText()))
                    ready.set(true);
            }
        });
        return ready.get();
    }

    private void archiveShareBytes() throws Exception {
        File archive = new File(fixture, "entries.zip");
        File original = new File(fixture, "three-pages.pdf");
        Uri grant;
        try (java.io.FileInputStream input = new java.io.FileInputStream(archive)) {
            ZipDirectory.Entry entry = ZipDirectory.read(input.getChannel()).entries.stream()
                    .filter(item -> item.name.equals("folder/embedded.pdf")).findFirst().get();
            grant = RootFileProvider.issueArchiveEntry(target, archiveUri, archive.getPath(), false,
                    RootFileProvider.identity(android.system.Os.fstat(input.getFD())), entry);
            tokens.add(grant.getPathSegments().get(0));
        }
        try (android.database.Cursor info = target.getContentResolver().query(grant, null, null, null, null)) {
            check(info != null && info.moveToFirst() && "embedded.pdf".equals(info.getString(0))
                    && info.getLong(1) == original.length(), "shared PDF exposes entry name and size");
        }
        try (android.os.ParcelFileDescriptor shared = target.getContentResolver().openFileDescriptor(grant, "r");
             java.io.FileInputStream expected = new java.io.FileInputStream(original);
             java.io.FileInputStream actual = new java.io.FileInputStream(shared.getFileDescriptor())) {
            for (int value; (value = expected.read()) != -1;) {
                if (actual.read() != value) throw new AssertionError("Shared file bytes differ from the PDF entry.");
            }
            check(actual.read() == -1, "shared stream contains the complete unaltered PDF");
        }
        try (android.os.ParcelFileDescriptor ignored = target.getContentResolver().openFileDescriptor(grant, "rw")) {
            throw new AssertionError("Archive share opened for writing.");
        } catch (java.io.FileNotFoundException expected) {
            check(true, "archive sharing grants are read-only");
        }
    }
    private void media(Uri uri, String name, boolean background) throws Exception {
        FileViewerActivity activity = open(uri, name); await(() -> snapshot(activity).prepared && snapshot(activity).playing, 15000, "native media preparation and playback");
        check(snapshot(activity).duration >= (background ? 29000 : 10000), "generated media duration");
        nativeKey(activity, KeyEvent.KEYCODE_MEDIA_PAUSE); await(() -> !snapshot(activity).playing, 3000, "native controller pauses");
        main(()->{check(activity.findViewById(R.id.media_volume)!=null,"audio and video expose volume controls");check((activity.findViewById(R.id.media_brightness)!=null)==!background,"brightness belongs only to video");
            activity.findViewById(R.id.media_speed).performClick();AppSheet sheet=(AppSheet)field(activity,"activeSheet");Button option=button(sheet.content,"1.5×");check(option!=null,"playback speed choice available");option.performClick();
            check(Math.abs((Float)field(activity,"playbackSpeed")-1.5f)<.01f&&!snapshot(activity).playing,"playback speed choice preserves paused media");});

        await(() -> {
            AtomicReference<Boolean> ready = new AtomicReference<>(false);
            main(() -> { MediaController control = (MediaController) field(activity, "controller"); SeekBar seek = find(control, SeekBar.class); ready.set(seek != null && seek.getWidth() > 0); }); return ready.get();
        }, 3000, "native seek control layout");
        main(() -> {
            MediaController control = (MediaController) field(activity, "controller"); control.show(0); SeekBar seek = find(control, SeekBar.class);
            float x = seek.getPaddingLeft() + (seek.getWidth() - seek.getPaddingLeft() - seek.getPaddingRight()) * .25f; long time = gestureTime();
            touch(seek, time, time, MotionEvent.ACTION_DOWN, x, seek.getHeight() / 2f); touch(seek, time, time + 40, MotionEvent.ACTION_UP, x, seek.getHeight() / 2f);
        });
        await(() -> !snapshot(activity).seeking && snapshot(activity).position >= snapshot(activity).duration / 7, 5000, "native seek changes position");
        int paused = snapshot(activity).position; check(!snapshot(activity).playing, "seeking keeps paused state"); capture(activity, background ? "07-audio-controls" : "08-video-controls");
        FileViewerActivity rotated = rotate(activity); assertStopped(activity);
        await(() -> snapshot(rotated).prepared && !snapshot(rotated).seeking, 15000, "rotated media prepares and seeks");
        check(!snapshot(rotated).playing && Math.abs(snapshot(rotated).position - paused) < 1500, "rotation preserves pause and position");
        main(()->check(Math.abs((Float)field(rotated,"playbackSpeed")-1.5f)<.01f,"playback speed choice survives rotation"));
        nativeKey(rotated, KeyEvent.KEYCODE_MEDIA_PLAY); await(() -> snapshot(rotated).playing, 3000, "native controller resumes");
        main(()->check(Math.abs(((android.media.MediaPlayer)field(rotated,"player")).getPlaybackParams().getSpeed()-1.5f)<.01f,"resumed player uses selected playback speed"));
        int resumed = snapshot(rotated).position; await(() -> snapshot(rotated).position >= resumed + 300, 4000, "playback advances");
        int beforePlayingRotation = snapshot(rotated).position; FileViewerActivity playingRotation = rotate(rotated); assertStopped(rotated);
        await(() -> snapshot(playingRotation).prepared && snapshot(playingRotation).playing && !snapshot(playingRotation).seeking, 15000, "playing media resumes after rotation");
        check(snapshot(playingRotation).position >= beforePlayingRotation - 700 && snapshot(playingRotation).position <= beforePlayingRotation + 4000, "playing rotation preserves position");
        if (background) {
            int beforeStop = snapshot(playingRotation).position; FileViewerActivity cover = open(textUri, "literal.html");
            await(() -> !snapshot(playingRotation).started, 4000, "covered media stops"); assertStopped(playingRotation); close(cover);
            await(() -> snapshot(playingRotation).prepared && snapshot(playingRotation).playing, 15000, "returning media recreates released player");
            check(snapshot(playingRotation).position >= beforeStop - 700 && snapshot(playingRotation).position <= beforeStop + 4000, "stop/start preserves playback position");
        }
        close(playingRotation);
    }
    private FileViewerActivity rotate(FileViewerActivity activity) {
        int orientation = activity.getResources().getConfiguration().orientation;
        int requested = orientation == Configuration.ORIENTATION_LANDSCAPE ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        Uri expected = activity.getIntent().getData(); main(() -> activity.setRequestedOrientation(requested));
        await(() -> { FileViewerActivity next = tracker.current(); return next != null && next != activity && expected.equals(next.getIntent().getData()) && snapshot(next).ready; }, 15000, "orientation change recreates viewer");
        FileViewerActivity rotated = tracker.current(); check(rotated.getResources().getConfiguration().orientation != orientation, "orientation actually changed"); rotations++; return rotated;
    }
    private void close(FileViewerActivity activity) { main(activity::finish); await(() -> destroyed(activity), 8000, "viewer closes"); assertStopped(activity); awaitIdleWorker(); }
    private void assertStopped(FileViewerActivity activity) {
        Snapshot state = snapshot(activity); check(!state.started && !state.player && !state.imagePresent && !state.pending && !state.result, "stop releases player, bitmap, task and result");
        main(() -> check(((FrameLayout) field(activity, "stage")).getChildCount() == 0, "stop clears child views"));
    }
    private void cancelledOpen() throws Exception {
        ThreadPoolExecutor worker = worker(); CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1);
        Future<?> barrier = worker.submit(() -> { blocked.countDown(); try { release.await(20, TimeUnit.SECONDS); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); } });
        check(blocked.await(3, TimeUnit.SECONDS), "preview worker barrier begins"); FileViewerActivity activity = null;
        try {
            Intent intent = FileViewerActivity.intent(target, imageUri, "synthetic.png", new File(fixture, "synthetic.png").getPath(), -1).putExtra(MARKER, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = (FileViewerActivity) startActivitySync(intent); final FileViewerActivity closing = activity;
            check(snapshot(closing).pending && !snapshot(closing).result, "real preview queues while worker is busy");
            main(closing::finish); await(() -> destroyed(closing), 5000, "close during pending preview"); assertStopped(closing); check(worker.getQueue().isEmpty(), "cancel removes queued preview");
        } finally { release.countDown(); barrier.get(5, TimeUnit.SECONDS); }
        awaitIdleWorker(); if (activity != null) assertStopped(activity); check(fixtureDescriptors() == 0, "cancel leaves no fixture descriptor");
    }
    private void clearViewStates(){synchronized(field(null,"VIEW_STATES")){((java.util.Map<?,?>)field(null,"VIEW_STATES")).clear();}}
    private void revisits()throws Exception{
        ((android.util.LruCache<?,?>)field(null,"CACHE")).evictAll();clearViewStates();
        long imageBegin=SystemClock.elapsedRealtime();FileViewerActivity firstImage=open(imageUri,"synthetic.png");long imageCold=SystemClock.elapsedRealtime()-imageBegin;
        long imageColdCpu=(Long)member(field(firstImage,"shown"),"cpuNanos");Bitmap imagePixels=(Bitmap)member(field(firstImage,"shown"),"bitmap");
        main(()->doubleTap(snapshot(firstImage).image));float imageZoom=snapshot(firstImage).zoom;close(firstImage);
        imageBegin=SystemClock.elapsedRealtime();FileViewerActivity againImage=open(imageUri,"synthetic.png");long imageWarm=SystemClock.elapsedRealtime()-imageBegin;
        long imageWarmCpu=(Long)member(field(againImage,"shown"),"cpuNanos");
        check((Boolean)member(field(againImage,"shown"),"cached")&&member(field(againImage,"shown"),"bitmap")==imagePixels,"image reopen reuses validated decoded pixels");
        check(Math.abs(snapshot(againImage).zoom-imageZoom)<.1f,"image reopen restores zoom");close(againImage);
        report.append("Image launch through ready layout: cold ").append(imageCold).append(" ms; warm ").append(imageWarm).append(" ms. Worker CPU: cold ")
            .append(imageColdCpu/1_000_000d).append(" ms; warm ").append(imageWarmCpu/1_000_000d).append(" ms.\n");
        ((android.util.LruCache<?,?>)field(null,"CACHE")).evictAll();clearViewStates();
        long begin=SystemClock.elapsedRealtime();FileViewerActivity first=open(pdfUri,"three-pages.pdf");long cold=SystemClock.elapsedRealtime()-begin;
        long coldCpu=(Long)member(field(first,"shown"),"cpuNanos");
        click(first,"Next");await(()->snapshot(first).ready&&snapshot(first).page==1,10000,"PDF revisit fixture second page ready");
        main(()->doubleTap(snapshot(first).image));float zoom=snapshot(first).zoom;
        Bitmap pixels=(Bitmap)member(field(first,"shown"),"bitmap");close(first);
        begin=SystemClock.elapsedRealtime();FileViewerActivity again=open(pdfUri,"three-pages.pdf");long warm=SystemClock.elapsedRealtime()-begin;
        long warmCpu=(Long)member(field(again,"shown"),"cpuNanos");
        check(snapshot(again).page==1&&Math.abs(snapshot(again).zoom-zoom)<.1f,"PDF reopen restores page and zoom");
        check((Boolean)member(field(again,"shown"),"cached")&&member(field(again,"shown"),"bitmap")==pixels,"PDF reopen reuses the exact decoded bitmap after validating metadata");close(again);
        FileViewerActivity text=open(textUri,"literal.html");main(()->((ScrollView)field(text,"textScroll")).scrollTo(0,900));waitForIdleSync();int scroll=snapshot(text).scroll;close(text);
        FileViewerActivity textAgain=open(textUri,"literal.html");await(()->Math.abs(snapshot(textAgain).scroll-scroll)<20,4000,"text reopen restores scroll");
        check((Boolean)member(field(textAgain,"shown"),"cached"),"text reopen reuses bounded decoded content");close(textAgain);
        File mutable=new File(fixture,"mutable.txt");java.nio.file.Files.write(mutable.toPath(),"before".getBytes(java.nio.charset.StandardCharsets.UTF_8));Uri grant=issue(mutable);
        FileViewerActivity original=open(grant,"mutable.txt");close(original);
        java.nio.file.Files.write(mutable.toPath(),"edited".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        FileViewerActivity modified=open(grant,"mutable.txt");
        check(!(Boolean)member(field(modified,"shown"),"cached"),"same-size modified file invalidates preview cache");
        check(((PreviewText.Result)member(field(modified,"shown"),"text")).text.equals("edited"),"modified file shows current bytes");close(modified);
        check(mutable.delete(),"delete cached fixture");java.nio.file.Files.write(mutable.toPath(),"replacement".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        FileViewerActivity replacement=open(issue(mutable),"mutable.txt");check(!(Boolean)member(field(replacement,"shown"),"cached"),"replaced inode cannot reuse old preview");close(replacement);
        Uri deleted=issue(mutable);check(mutable.delete(),"delete preview fixture before reopening");
        FileViewerActivity missing=(FileViewerActivity)startActivitySync(FileViewerActivity.intent(target,deleted,"mutable.txt",mutable.getPath(),0).putExtra(MARKER,true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(()->field(missing,"pending")==null,3000,"deleted preview finishes loading");
        check(field(missing,"shown")==null,"deleted file cannot use cached content");close(missing);
        android.util.LruCache<?,?> cache=(android.util.LruCache<?,?>)field(null,"CACHE");check(cache.size()<=cache.maxSize()&&cache.maxSize()<=24*1024*1024,"shared preview cache stays within byte limit");
        report.append("PDF launch through ready layout: cold ").append(cold).append(" ms; warm ").append(warm).append(" ms. Worker CPU: cold ")
                .append(coldCpu/1_000_000d).append(" ms; warm ").append(warmCpu/1_000_000d).append(" ms. Warm bitmap identity, page/zoom, text scroll and modified/replaced/deleted invalidation checked.\n");
    }
    private void unsupportedSwipes()throws Exception{
        clearViewStates();File unsupported=new File(fixture,"unavailable.unknown");java.nio.file.Files.write(unsupported.toPath(),new byte[]{1,2,3});
        List<FileEntry> entries=new ArrayList<>();for(String name:new String[]{"synthetic.png","unavailable.unknown","three-pages.pdf"}){File file=new File(fixture,name);entries.add(new FileEntry(file.getCanonicalPath(),false,file.length(),file.lastModified()));}
        PreviewSession session=PreviewSession.create(entries,entries.get(1).path(),false);
        FileViewerActivity activity=(FileViewerActivity)startActivitySync(FileViewerActivity.intent(target,null,unsupported.getName(),unsupported.getPath(),unsupported.length())
                .putExtra(PreviewSession.EXTRA,session.id).putExtra(MARKER,true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(()->field(activity,"pending")==null&&activity.findViewById(R.id.preview_stage).getWidth()>0,5000,"unsupported frame ready");
        swipeStage(activity,-1);await(()->session.position==2&&snapshot(activity).ready,12000,"unsupported frame swipes forward");
        swipeStage(activity,1);await(()->session.position==1&&field(activity,"pending")==null,5000,"swipe returns to unsupported frame");
        swipeStage(activity,1);await(()->session.position==0&&snapshot(activity).ready,12000,"unsupported frame swipes backward");
        check(session.position==0,"both unsupported-frame swipe directions work without buttons");close(activity);PreviewSession.release(session.id);
    }
    private void swipeStage(FileViewerActivity activity,int direction){main(()->{FrameLayout stage=activity.findViewById(R.id.preview_stage);float x=stage.getWidth()*.5f,y=stage.getHeight()*.4f;long time=gestureTime();
        touch(stage,time,time,MotionEvent.ACTION_DOWN,x,y);touch(stage,time,time+40,MotionEvent.ACTION_MOVE,x+direction*stage.getWidth()*.3f,y);
        touch(stage,time,time+100,MotionEvent.ACTION_MOVE,x+direction*stage.getWidth()*.6f,y);touch(stage,time,time+140,MotionEvent.ACTION_UP,x+direction*stage.getWidth()*.6f,y);});}
    private void backExit()throws Exception{
        automation();
        FileViewerActivity audio=open(wavUri,"silent.wav");await(()->snapshot(audio).prepared,15000,"Back test media prepares");
        main(()->((MediaController)field(audio,"controller")).show(0));waitForIdleSync();
        await(()->{AtomicBoolean focused=new AtomicBoolean();main(()->{MediaController control=(MediaController)field(audio,"controller");
            focused.set(audio.hasWindowFocus()&&control.isShowing()&&control.getWindowToken()==audio.getWindow().getDecorView().getWindowToken());});return focused.get();},3000,"embedded native controls share the Activity window");
        systemBack();SystemClock.sleep(140);boolean first=destroyed(audio);backState(audio,"first");
        if(!first){systemBack();SystemClock.sleep(140);backState(audio,"second");}
        await(()->destroyed(audio),5000,"double Back exits media preview with native controls visible");assertStopped(audio);
        report.append("Native controller Back exit required ").append(first?1:2).append(" key press(es).\n");
        FileViewerActivity image=open(imageUri,"synthetic.png");main(()->doubleTap(snapshot(image).image));
        main(()->{image.onBackPressed();image.onBackPressed();});await(()->destroyed(image),5000,"double Back exits zoomed image preview");assertStopped(image);
    }
    private void systemBack(){long time=SystemClock.uptimeMillis();
        check(automation().injectInputEvent(new KeyEvent(time,time,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BACK,0),true),"system accepts Back down");
        check(automation().injectInputEvent(new KeyEvent(time,time+30,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BACK,0),true),"system accepts Back up");}
    private void backState(FileViewerActivity activity,String press){main(()->{MediaController control=(MediaController)field(activity,"controller");
        report.append("Back ").append(press).append(": finishing=").append(activity.isFinishing()).append("; destroyed=").append(activity.isDestroyed())
            .append("; windowFocus=").append(activity.hasWindowFocus()).append("; controllerShowing=").append(control!=null&&control.isShowing())
            .append("; controllerFocus=").append(control!=null&&control.hasWindowFocus()).append('.').append('\n');});}
    private void repeatedCleanup() throws Exception {
        awaitIdleWorker(); int baseline = descriptors();
        reportFixtureDescriptors("lifecycle baseline");
        for (int i = 0; i < 4; i++) {
            FileViewerActivity image = open(imageUri, "synthetic.png"); close(image);
            reportFixtureDescriptors("after PNG close " + (i+1));
            FileViewerActivity pdf = open(pdfUri, "three-pages.pdf"); close(pdf);
            reportFixtureDescriptors("after PDF close " + (i+1));
            FileViewerActivity audio = open(wavUri, "silent.wav");
            await(() -> snapshot(audio).prepared, 15000, "repeated media prepares"); close(audio);
            reportFixtureDescriptors("after WAV close " + (i+1));
        }
        File rotated = new File(fixture,"rotated.jpg");
        FileViewerActivity jpeg = open(issue(rotated),rotated.getName());
        check((Integer)member(field(jpeg,"shown"),"orientation")==6,"owned EXIF stream preserves JPEG rotation");
        close(jpeg);
        reportFixtureDescriptors("after oriented JPEG close");

        if(Build.VERSION.SDK_INT<28) {
            File png = new File(fixture,"synthetic.png");
            // Exercise the same bounded thumbnail decoder used by cells, independently
            // of the file viewer and the disk/memory thumbnail cache.
            java.lang.reflect.Method decode=ThumbnailLoader.class.getDeclaredMethod("decode",FileEntry.class,
                    int.class,boolean.class,boolean.class);
            decode.setAccessible(true);
            try(ThumbnailLoader loader=new ThumbnailLoader(target)) {
                Bitmap bitmap=(Bitmap)decode.invoke(loader,new FileEntry(png.getPath(),false,png.length(),png.lastModified()),
                        96,false,false);
                try { check(bitmap!=null,"Android 8 thumbnail decodes PNG through owned EXIF stream"); }
                finally { if(bitmap!=null)bitmap.recycle(); }
            }
            reportFixtureDescriptors("after legacy PNG thumbnail");
        }

        // Let legitimate asynchronous media teardown settle, not GC. A descriptor
        // routed to the wrong owner will still be open and fail with its fixture name.
        long deadline=SystemClock.elapsedRealtime()+2000;
        List<String> remaining=fixtureDescriptorNames();
        while(!remaining.isEmpty()&&SystemClock.elapsedRealtime()<deadline) {
            SystemClock.sleep(50); remaining=fixtureDescriptorNames();
        }
        reportFixtureDescriptors("after bounded settle");
        check(remaining.isEmpty(),"repeated opens leave no fixture descriptor: "+remaining);
        check(descriptors()<=baseline+6,"process descriptor count stays bounded after warmup");
        check(worker().getActiveCount()==0&&worker().getQueue().isEmpty()&&worker().getPoolSize()<=1,"preview executor stays idle and bounded");
    }
    private void lateDelivery() throws Exception {
        FileViewerActivity activity = open(imageUri, "synthetic.png");
        Bitmap bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        try (ParcelFileDescriptor descriptor = target.getContentResolver().openFileDescriptor(imageUri, "r")) {
            check(descriptor != null, "late delivery owns a real provider descriptor");
            Runnable delivery = delivery(activity, bitmap, descriptor);
            main(() -> {
                activity.finish(); delivery.run();
                check(bitmap.isRecycled() && !descriptor.getFileDescriptor().valid(), "finishing Activity rejects and closes late bitmap and descriptor immediately");
            });
        } finally { if (!bitmap.isRecycled()) bitmap.recycle(); }
        await(() -> destroyed(activity), 8000, "late-delivery Activity closes"); assertStopped(activity);
        Bitmap destroyedBitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        try {
            Runnable delivery = delivery(activity, destroyedBitmap, null); main(delivery);
            check(destroyedBitmap.isRecycled(), "destroyed Activity closes an already completed late result");
        } finally { if (!destroyedBitmap.isRecycled()) destroyedBitmap.recycle(); }
        awaitIdleWorker();
    }
    private Runnable delivery(FileViewerActivity activity, Bitmap bitmap, ParcelFileDescriptor descriptor) throws Exception {
        Class<?> resultClass = nested("PreviewResult"), deliveryClass = nested("Delivery");
        Constructor<?> resultConstructor = resultClass.getDeclaredConstructor(); resultConstructor.setAccessible(true); Object result = resultConstructor.newInstance();
        Field pixels = resultClass.getDeclaredField("bitmap"); pixels.setAccessible(true); pixels.set(result, bitmap);
        Field media = resultClass.getDeclaredField("media"); media.setAccessible(true); media.set(result, descriptor);
        Constructor<?> deliveryConstructor = deliveryClass.getDeclaredConstructor(WeakReference.class, resultClass, int.class, AtomicBoolean.class); deliveryConstructor.setAccessible(true);
        return (Runnable) deliveryConstructor.newInstance(new WeakReference<>(activity), result, (Integer) field(activity, "generation"), new AtomicBoolean());
    }
    private static Class<?> nested(String name) {
        for (Class<?> type : FileViewerActivity.class.getDeclaredClasses()) if (type.getSimpleName().equals(name)) return type;
        throw new AssertionError("Viewer nested type unavailable: " + name);
    }
    private void collectActivities() {
        waitForIdleSync();
        for (int attempt = 0; attempt < 20; attempt++) {
            Runtime.getRuntime().gc(); System.runFinalization(); SystemClock.sleep(100); waitForIdleSync(); uncollected = tracker.alive(); if (uncollected == 0) break;
        }
        report.append("Finished viewer Activities still reachable after bounded GC: ").append(uncollected).append(".\n");
        if (arguments == null || !"false".equals(arguments.getString("strictGc"))) check(uncollected == 0, "finished viewer Activities are collectible");
    }
    private void capture(FileViewerActivity activity, String label) throws Exception {
        waitForIdleSync(); UiAutomation automation = automation();
        await(()->{AccessibilityNodeInfo node=automation.getRootInActiveWindow();try{return node!=null&&node.getPackageName()!=null&&target.getPackageName().contentEquals(node.getPackageName());}
            finally{if(node!=null)node.recycle();}},5000,"target screenshot window ready");
        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        try { check(root != null && root.getPackageName() != null && target.getPackageName().contentEquals(root.getPackageName()), "screenshot belongs to target app"); }
        finally { if (root != null) root.recycle(); }
        AtomicReference<Rect> region = new AtomicReference<>();
        main(() -> {
            View view = content(activity).getChildAt(0); int[] at = new int[2]; view.getLocationOnScreen(at);
            region.set(new Rect(at[0] + view.getPaddingLeft(), at[1] + view.getPaddingTop(), at[0] + view.getWidth() - view.getPaddingRight(), at[1] + view.getHeight() - view.getPaddingBottom()));
        });
        Bitmap screen = automation.takeScreenshot(); check(screen != null, "capture synthetic screen"); Bitmap crop = null;
        try {
            Rect bounds = region.get(); check(bounds.intersect(0, 0, screen.getWidth(), screen.getHeight()) && bounds.width() > 0 && bounds.height() > 0, "crop to app content");
            crop = Bitmap.createBitmap(screen, bounds.left, bounds.top, bounds.width(), bounds.height()); File file = new File(screenshots, label + ".png");
            try (FileOutputStream out = new FileOutputStream(file)) { check(crop.compress(Bitmap.CompressFormat.PNG, 100, out), "write screenshot"); }
            check(file.length() > 2000, "screenshot has rendered content"); screenshotCount++;
        } finally { if (crop != null && crop != screen) crop.recycle(); screen.recycle(); }
    }
    private void nativeKey(FileViewerActivity activity, int key) {
        main(() -> {
            MediaController control = (MediaController) field(activity, "controller"); check(control != null, "Android playback controller exists"); control.show(0);
            check(activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key)), "Activity routes media key to native controller"); activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
        });
    }
    private static TextView exactText(View view,String value){
        if(view instanceof TextView&&value.contentEquals(((TextView)view).getText()))return (TextView)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){
            TextView child=exactText(((ViewGroup)view).getChildAt(i),value);
            if(child!=null)return child;
        }
        return null;
    }
    private void click(FileViewerActivity activity, String label) {
        main(() -> { Button button = button(content(activity), label); check(button != null && button.isEnabled(), "enabled " + label + " control exists"); check(button.performClick(), "activate " + label); });
    }
    private void doubleTap(ZoomPreviewView view) {
        float x = view.getWidth() / 2f, y = view.getHeight() / 2f; long time = gestureTime();
        touch(view, time, time, MotionEvent.ACTION_DOWN, x, y); touch(view, time, time + 35, MotionEvent.ACTION_UP, x, y);
        touch(view, time + 100, time + 100, MotionEvent.ACTION_DOWN, x, y); touch(view, time + 100, time + 135, MotionEvent.ACTION_UP, x, y);
    }
    private void pan(ZoomPreviewView view) {
        float x = view.getWidth() / 2f, y = view.getHeight() / 2f; long time = gestureTime(); touch(view, time, time, MotionEvent.ACTION_DOWN, x, y);
        touch(view, time, time + 40, MotionEvent.ACTION_MOVE, x - view.getWidth() * .15f, y - view.getHeight() * .15f);
        touch(view, time, time + 80, MotionEvent.ACTION_MOVE, x - view.getWidth() * .28f, y - view.getHeight() * .28f);
        touch(view, time, time + 100, MotionEvent.ACTION_UP, x - view.getWidth() * .28f, y - view.getHeight() * .28f);
    }
    private void pinch(ZoomPreviewView view) {
        long time = gestureTime(); float x = view.getWidth() / 2f, y = view.getHeight() / 2f, spread = view.getWidth() * .18f;
        touch(view, time, time, MotionEvent.ACTION_DOWN, x - spread, y);
        multi(view, time, time + 30, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), x, y, spread);
        multi(view, time, time + 60, MotionEvent.ACTION_MOVE, x, y, spread * 1.25f); multi(view, time, time + 90, MotionEvent.ACTION_MOVE, x, y, spread * 1.6f);
        multi(view, time, time + 120, MotionEvent.ACTION_MOVE, x, y, spread * 1.9f);
        multi(view, time, time + 150, MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), x, y, spread * 1.9f);
        touch(view, time, time + 180, MotionEvent.ACTION_UP, x - spread * 1.9f, y);
    }
    private long gestureTime() { lastGesture = Math.max(SystemClock.uptimeMillis(), lastGesture + 500); return lastGesture; }
    private static void touch(View view, long down, long time, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, time, action, x, y, 0); event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { view.dispatchTouchEvent(event); } finally { event.recycle(); }
    }
    private static void multi(View view, long down, long time, int action, float x, float y, float spread) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2]; MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[2];
        for (int i = 0; i < 2; i++) {
            properties[i] = new MotionEvent.PointerProperties(); properties[i].id = i; properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coordinates[i] = new MotionEvent.PointerCoords(); coordinates[i].x = x + (i == 0 ? -spread : spread); coordinates[i].y = y; coordinates[i].pressure = 1; coordinates[i].size = 1;
        }
        MotionEvent event = MotionEvent.obtain(down, time, action, 2, properties, coordinates, 0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        try { view.dispatchTouchEvent(event); } finally { event.recycle(); }
    }
    private Snapshot snapshot(FileViewerActivity activity) { AtomicReference<Snapshot> result = new AtomicReference<>(); main(() -> result.set(new Snapshot(activity))); return result.get(); }
    private static final class Snapshot {
        final boolean started, player, prepared, playing, seeking, pending, result, imagePresent, ready;
        final int page, pages, position, duration, scroll;
        final float zoom, centerX, centerY;
        final ZoomPreviewView image;
        Snapshot(FileViewerActivity activity) {
            started = (Boolean) field(activity, "started"); prepared = (Boolean) field(activity, "prepared"); seeking = (Boolean) field(activity, "seeking");
            pending = field(activity, "pending") != null; result = field(activity, "shown") != null; image = (ZoomPreviewView) field(activity, "image"); imagePresent = image != null;
            page = (Integer) field(activity, "page"); pages = (Integer) field(activity, "pageCount"); MediaPlayer media = (MediaPlayer) field(activity, "player"); player = media != null;
            playing = prepared && media != null && media.isPlaying(); position = prepared && media != null ? media.getCurrentPosition() : (Integer) field(activity, "mediaPosition"); duration = prepared && media != null ? media.getDuration() : 0;
            float[] state = image == null ? new float[]{1, .5f, .5f} : image.state(); zoom = state[0]; centerX = state[1];centerY=state[2];
            ScrollView text = (ScrollView) field(activity, "textScroll"); scroll = text == null ? 0 : text.getScrollY(); FrameLayout stage = (FrameLayout) field(activity, "stage");
            ready = started && result && !pending && stage.getWidth() > 0 && !stage.isLayoutRequested() && (image == null || image.getWidth() > 0);
        }
    }
    private static Object field(Object owner, String name) {
        try { Field field = (owner == null ? FileViewerActivity.class : owner.getClass()).getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
        catch (ReflectiveOperationException failure) { throw new AssertionError("Viewer field unavailable: " + name, failure); }
    }
    private static ThreadPoolExecutor worker() { return (ThreadPoolExecutor) field(null, "PREVIEWS"); }
    private void awaitIdleWorker() { await(() -> worker().getActiveCount() == 0 && worker().getQueue().isEmpty(), 12000, "preview worker becomes idle"); waitForIdleSync(); }
    private interface Condition { boolean matches() throws Exception; }
    private void await(Condition condition, long timeout, String label) {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < deadline) {
            try { if (condition.matches()) return; } catch (Exception failure) { throw new AssertionError(label, failure); } SystemClock.sleep(30);
        }
        throw new AssertionError("Timeout: " + label);
    }
    private void main(Runnable runnable) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) { runnable.run(); return; }
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runOnMainSync(() -> { try { runnable.run(); } catch (Throwable error) { failure.set(error); } });
        Throwable error = failure.get(); if (error instanceof Error) throw (Error) error;
        if (error instanceof RuntimeException) throw (RuntimeException) error;
        if (error != null) throw new AssertionError(error);
    }
    private boolean destroyed(Activity activity) { AtomicBoolean done = new AtomicBoolean(); main(() -> done.set(activity.isDestroyed())); return done.get(); }
    private void check(boolean condition, String label) { assertions++; if (!condition) throw new AssertionError(label); }
    private static ViewGroup content(Activity activity) { return (ViewGroup) activity.findViewById(android.R.id.content); }
    private static <T extends View> T find(View root, Class<T> type) {
        if (type.isInstance(root)) return type.cast(root);
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) { T match = find(((ViewGroup) root).getChildAt(i), type); if (match != null) return match; } return null;
    }
    private static View description(View root,String label){
        if(root.getContentDescription()!=null&&label.contentEquals(root.getContentDescription()))return root;
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){
            View match=description(((ViewGroup)root).getChildAt(i),label);
            if(match!=null)return match;
        }
        return null;
    }
    private static Button button(View root, String label) {
        if (root instanceof Button && label.contentEquals(((Button) root).getText())) return (Button) root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) { Button match = button(((ViewGroup) root).getChildAt(i), label); if (match != null) return match; } return null;
    }
    private static boolean containsClass(View root, String name) {
        if (root.getClass().getName().equals(name)) return true;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) if (containsClass(((ViewGroup) root).getChildAt(i), name)) return true; return false;
    }
    private int fixtureDescriptors() throws Exception { return fixtureDescriptorNames().size(); }
    private List<String> fixtureDescriptorNames() throws Exception {
        List<String> owned = new ArrayList<>();
        File[] files = new File("/proc/self/fd").listFiles();
        if(files==null)throw new IOException("Descriptor list unavailable.");
        String prefix=fixture.getCanonicalPath()+"/";
        for(File fd:files) {
            String path;
            try { path=Os.readlink(fd.getPath()); } catch(Exception racedClose) { continue; }
            if(path.startsWith(prefix))owned.add(new File(path).getName());
        }
        java.util.Collections.sort(owned);
        return owned;
    }
    private void reportFixtureDescriptors(String step)throws Exception {
        List<String> owned=fixtureDescriptorNames();
        report.append(step).append(": ").append(owned.size()).append(" fixture descriptor(s) ").append(owned).append((char)10);
    }
    private static int descriptors() throws IOException { String[] files = new File("/proc/self/fd").list(); if (files == null) throw new IOException("Descriptor list unavailable."); return files.length; }
    private static final class Tracker implements Application.ActivityLifecycleCallbacks {
        private final List<WeakReference<FileViewerActivity>> opened = new ArrayList<>();
        private volatile WeakReference<FileViewerActivity> resumed = new WeakReference<>(null);
        synchronized int alive() { int count = 0; for (WeakReference<FileViewerActivity> reference : opened) if (reference.get() != null) count++; return count; }
        synchronized List<WeakReference<FileViewerActivity>> all() { return new ArrayList<>(opened); }
        synchronized boolean allDestroyed() { for (WeakReference<FileViewerActivity> reference : opened) { FileViewerActivity activity = reference.get(); if (activity != null && !activity.isDestroyed()) return false; } return true; }
        FileViewerActivity current() { return resumed.get(); }
        private boolean owned(Activity activity) { return activity instanceof FileViewerActivity && activity.getIntent().getBooleanExtra(MARKER, false); }
        @Override public synchronized void onActivityCreated(Activity activity, Bundle saved) { if (owned(activity)) opened.add(new WeakReference<>((FileViewerActivity) activity)); }
        @Override public void onActivityResumed(Activity activity) { if (owned(activity)) resumed = new WeakReference<>((FileViewerActivity) activity); }
        @Override public void onActivityPaused(Activity activity) { if (resumed.get() == activity) resumed = new WeakReference<>(null); }
        @Override public void onActivityStarted(Activity activity) { }
        @Override public void onActivityStopped(Activity activity) { }
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle saved) { }
        @Override public void onActivityDestroyed(Activity activity) { if (resumed.get() == activity) resumed = new WeakReference<>(null); }
    }
    private void cleanup() throws Exception {
        Throwable failure = null;
        try {
            if (tracker != null) {
                for (WeakReference<FileViewerActivity> reference : tracker.all()) { FileViewerActivity activity = reference.get(); if (activity != null && !activity.isDestroyed()) main(activity::finish); }
                await(() -> { AtomicBoolean done = new AtomicBoolean(); main(() -> done.set(tracker.allDestroyed())); return done.get(); }, 8000, "cleanup closes all fixture Activities"); awaitIdleWorker();
            }
        } catch (Throwable error) { failure = error; }
        finally { if (application != null && tracker != null) application.unregisterActivityLifecycleCallbacks(tracker); }
        try {
            if (target != null) {
                SharedPreferences.Editor edit = target.getSharedPreferences("file-grants", Context.MODE_PRIVATE).edit(); for (String token : tokens) edit.remove(token);
                if (!edit.commit()) throw new IOException("Fixture grant cleanup failed.");
            }
        } catch (Throwable error) { if (failure == null) failure = error; }
        try {
            if (ownsFixture) {
                if (!fixture.getName().startsWith("PreviewUiProbe-") || !fixture.getParentFile().getCanonicalFile().equals(target.getCacheDir().getCanonicalFile())) throw new IOException("Invalid fixture root.");
                File[] files = fixture.listFiles(); if (files == null) throw new IOException("Fixture list unavailable.");
                for (File file : files) if (!file.delete()) throw new IOException("Fixture file cleanup failed."); if (!fixture.delete()) throw new IOException("Fixture root cleanup failed.");
            }
        } catch (Throwable error) { if (failure == null) failure = error; }
        if (failure != null) throw new IOException("Viewer cleanup incomplete.", failure);
    }
}
