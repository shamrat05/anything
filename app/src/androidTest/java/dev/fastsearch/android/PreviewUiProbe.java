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
import android.widget.FrameLayout;
import android.widget.ListView;
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
            else if(arguments!=null&&"back".equals(arguments.getString("phase"))){backExit();collectActivities();passed=true;}
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
            try { PreviewUiFixtures.video(video); videoUri = issue(video); videoAvailable = true; }
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
        await(() -> snapshot(activity).ready, 15000, "viewer completes provider-backed preview and layout"); waitForIdleSync(); return activity;
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
    private void archive() throws Exception {
        FileViewerActivity activity = open(archiveUri, "entries.zip");
        main(() -> { ListView list = find(content(activity), ListView.class); check(list != null && list.getCount() == 4, "ZIP rows show entry metadata"); });
        ActivityMonitor chooser = addMonitor(new android.content.IntentFilter(Intent.ACTION_CHOOSER),
                new ActivityResult(Activity.RESULT_CANCELED, null), true);
        try {
            clickAccessible("Share file");
            await(() -> chooser.getHits() == 1, 5000, "viewer Share opens chooser");
            clickAccessible("folder/embedded.pdf");
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
        clickAccessible(ZipDirectory.safeLabel("../labels-only\nentry.txt"));
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
        main(() -> { ListView list = find(content(rotated), ListView.class); check(list != null && list.getCount() == 4, "ZIP listing survives rotation"); }); close(rotated);
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
        for (int i = 0; i < 4; i++) {
            FileViewerActivity image = open(imageUri, "synthetic.png"); close(image); FileViewerActivity pdf = open(pdfUri, "three-pages.pdf"); close(pdf);
            FileViewerActivity audio = open(wavUri, "silent.wav"); await(() -> snapshot(audio).prepared, 15000, "repeated media prepares"); close(audio);
        }
        check(fixtureDescriptors() == 0, "repeated opens leave no fixture descriptor"); check(descriptors() <= baseline + 6, "process descriptor count stays bounded after warmup");
        check(worker().getActiveCount() == 0 && worker().getQueue().isEmpty() && worker().getPoolSize() <= 1, "preview executor stays idle and bounded");
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
        try { Field field = FileViewerActivity.class.getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
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
    private static Button button(View root, String label) {
        if (root instanceof Button && label.contentEquals(((Button) root).getText())) return (Button) root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) { Button match = button(((ViewGroup) root).getChildAt(i), label); if (match != null) return match; } return null;
    }
    private static boolean containsClass(View root, String name) {
        if (root.getClass().getName().equals(name)) return true;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) if (containsClass(((ViewGroup) root).getChildAt(i), name)) return true; return false;
    }
    private int fixtureDescriptors() throws Exception {
        int count = 0; File[] files = new File("/proc/self/fd").listFiles(); if (files == null) throw new IOException("Descriptor list unavailable.");
        for (File fd : files) { String path; try { path = Os.readlink(fd.getPath()); } catch (Exception racedClose) { continue; } if (path.startsWith(fixture.getPath() + "/")) count++; } return count;
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
