package dev.fastsearch.android;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ComponentCallbacks2;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.graphics.pdf.PdfRenderer;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.ExifInterface;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.MimeTypeMap;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.MediaController;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.util.LruCache;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.text.NumberFormat;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import dev.fastsearch.core.FileEntry;

/** Native previews use no-follow descriptors; external opens use identity-bound grants. */
public final class FileViewerActivity extends Activity {
    private int INK = 0xFF202020, MUTED = 0xFF60656B, CHROME = 0xFFF0F1FA, ACCENT = 0xFF1A2FFB;
    private UiTheme theme;
    private AppSheet activeSheet;
    private boolean sharingArchiveEntry;
    private static final int MAX_ARCHIVE_IMAGE_BYTES = 16 * 1024 * 1024;
    private boolean readerSource;
    private int readerSize=16;
    private float playbackSpeed=1;
    static final int MAX_BITMAP_EDGE = 2048, MAX_BITMAP_PIXELS = 4 * 1024 * 1024;
    private static final int MAX_PDF_EDGE=1536;
    static final long MAX_DOCUMENT_BYTES = 128L * 1024 * 1024;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ThreadPoolExecutor PREVIEWS = createExecutor();
    private static final String EXTRA_NAME = "preview.name", EXTRA_PATH = "preview.path", EXTRA_SIZE = "preview.size";
    private enum Kind { IMAGE, SVG, VIDEO, AUDIO, PDF, TEXT, MARKDOWN, HTML, ZIP, OTHER }
    private static final int CACHE_BYTES=(int)Math.min(24L*1024*1024,Math.max(4L*1024*1024,Runtime.getRuntime().maxMemory()/16));
    private static final LruCache<String, PreviewData> CACHE=new LruCache<String, PreviewData>(CACHE_BYTES){
        @Override protected int sizeOf(String key,PreviewData data){return data.bytes;}
    };
    private static final java.util.LinkedHashMap<String,ViewState> VIEW_STATES=new java.util.LinkedHashMap<String,ViewState>(128,.75f,true){
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<String,ViewState> eldest){return size()>128;}
    };
    private static final AtomicBoolean CACHE_CALLBACKS=new AtomicBoolean();

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable mediaTimeout = new MediaTimeout(this);
    private final BroadcastReceiver noisy = new NoisyReceiver(this);
    private FrameLayout stage, mediaAnchor;
    private PreviewContent previewContent;
    private TextView details, notice;
    private ZoomPreviewView image;
    private ScrollView textScroll;
    private ListView archiveList;
    private SurfaceView surface;
    private MediaPlayer player;
    private MediaController controller;
    private AudioManager audio;
    private AudioFocusRequest audioFocus;
    private boolean prepared, surfaceReady, seeking, playWhenReady = true, mediaCompleted, hasFocus, resumeAfterFocus, noisyRegistered;
    private int videoWidth, videoHeight, mediaPosition, focusGeneration;
    private Uri uri;
    private String name, path, mime;
    private long size;
    private Kind kind;
    private boolean started, destroyed;
    private int generation, page, pageCount, scrollY, archivePosition, archiveTop;
    private float zoom = 1, centerX = .5f, centerY = .5f;
    private Future<?> pending;
    private PreviewJob job;
    private PreviewResult shown;
    private String stateKey;
    private boolean restoreOnLoad=true;
    private PreviewSession session;
    private ThumbnailLoader thumbnails;
    private TextView title, filePosition;
    private ImageButton location;
    private LinearLayout fileNavigation;
    private ImageButton previousFile, nextFile, selectFile, copyFile, moveFile, pasteFile;
    private View navigationPlaceholder;
    private View loadingPlaceholder;
    private LinearLayout selectedStrip;
    private HorizontalScrollView selectedScroll;
    private ProgressBar selectionProgress;
    private boolean selectionBusy, pasteAfterPreview;
    private final ThreadPoolExecutor actions = new ThreadPoolExecutor(1,1,15,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(2), task -> new Thread(task,"preview-file-action"),new ThreadPoolExecutor.AbortPolicy());

    public static Intent intent(Context context, Uri uri, String name, String path, long size) {
        return new Intent(context, FileViewerActivity.class).setData(uri)
                .putExtra(EXTRA_NAME, name).putExtra(EXTRA_PATH, path).putExtra(EXTRA_SIZE, size);
    }

    private static ThreadPoolExecutor createExecutor() {
        ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(2), action -> {
                    Thread thread = new Thread(action, "file-preview"); thread.setDaemon(true); return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        worker.allowCoreThreadTimeOut(true);
        return worker;
    }

    @Override public void onCreate(Bundle saved) {
        theme=UiTheme.apply(this);INK=theme.ink;MUTED=theme.muted;CHROME=theme.chrome;ACCENT=theme.accent;
        super.onCreate(saved);
        readerSize=Math.max(12,Math.min(26,getSharedPreferences("ui",0).getInt("readerSize",16)));readerSource=saved!=null&&saved.getBoolean("readerSource",false);
        if(saved!=null){playbackSpeed=saved.getFloat("playbackSpeed",1);WindowManager.LayoutParams attributes=getWindow().getAttributes();attributes.screenBrightness=saved.getFloat("brightness",-1);getWindow().setAttributes(attributes);}
        uri = getIntent().getData();
        name = getIntent().getStringExtra(EXTRA_NAME);
        path = getIntent().getStringExtra(EXTRA_PATH);
        size = getIntent().getLongExtra(EXTRA_SIZE, -1);
        session = PreviewSession.get(getIntent().getStringExtra(PreviewSession.EXTRA));
        registerCacheCallbacks(getApplicationContext());
        thumbnails = new ThumbnailLoader(this);
        if (name == null || name.isEmpty()) name = "File";
        if (path == null) path = "";
        mime = mimeFor(name); kind = kindFor(name, mime);
        if (saved != null) {
            restoreOnLoad=false;
            name=saved.getString("fileName",name);path=saved.getString("filePath",path);size=saved.getLong("fileSize",size);
            String restoredUri=saved.getString("fileUri");if(restoredUri!=null)uri=Uri.parse(restoredUri);
            if(session!=null)session.position=Math.max(0,Math.min(session.entries.size()-1,saved.getInt("filePosition",session.position)));
            page = Math.max(0, saved.getInt("page", 0));
            mediaPosition = Math.max(0, saved.getInt("mediaPosition", 0));
            playWhenReady = saved.getBoolean("playing", false);
            mediaCompleted = saved.getBoolean("completed", false);
            zoom = saved.getFloat("zoom", 1); centerX = saved.getFloat("centerX", .5f); centerY = saved.getFloat("centerY", .5f);
            scrollY = Math.max(0, saved.getInt("scrollY", 0));
            archivePosition = Math.max(0, saved.getInt("archivePosition", 0)); archiveTop = saved.getInt("archiveTop", 0);
        }
        mime=mimeFor(name);kind=kindFor(name,mime);
        if(saved!=null&&uri==null&&session==null&&!path.isEmpty()){
            session=PreviewSession.create(java.util.Collections.singletonList(new FileEntry(path,false,size,-1)),path,saved.getBoolean("fileRoot",false));
            getIntent().putExtra(PreviewSession.EXTRA,session.id);
        }
        buildUi();
        if(Build.VERSION.SDK_INT>=34){overrideActivityTransition(OVERRIDE_TRANSITION_OPEN,R.anim.preview_enter,0);
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE,0,R.anim.preview_exit);}
        else overridePendingTransition(R.anim.preview_enter,0);
        if(Build.VERSION.SDK_INT>=33)getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,this::handleBack);
    }

    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed(){handleBack();}
    private void handleBack(){if(session!=null&&!session.selected.isEmpty()){session.selected.clear();updateFileControls();}else finish();}
    @Override public boolean dispatchKeyEvent(KeyEvent event){int key=event.getKeyCode();
        if(controller!=null&&(key==KeyEvent.KEYCODE_HEADSETHOOK||key==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE||key==KeyEvent.KEYCODE_SPACE
                ||key==KeyEvent.KEYCODE_MEDIA_PLAY||key==KeyEvent.KEYCODE_MEDIA_STOP||key==KeyEvent.KEYCODE_MEDIA_PAUSE))return controller.dispatchKeyEvent(event);
        return super.dispatchKeyEvent(event);}
    private static void registerCacheCallbacks(Context context){
        if(!CACHE_CALLBACKS.compareAndSet(false,true))return;
        context.registerComponentCallbacks(new ComponentCallbacks2(){
            @Override public void onConfigurationChanged(Configuration configuration){}
            @Override public void onLowMemory(){CACHE.evictAll();synchronized(VIEW_STATES){VIEW_STATES.clear();}}
            @Override public void onTrimMemory(int level){if(level>=TRIM_MEMORY_BACKGROUND||level==TRIM_MEMORY_RUNNING_CRITICAL)CACHE.evictAll();
                else if(level==TRIM_MEMORY_RUNNING_LOW)CACHE.trimToSize(CACHE_BYTES/2);}
        });
    }

    @Override protected void onStart() {
        super.onStart(); started = true;thumbnails.resume();
        {
            IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, filter, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(noisy, filter);
            noisyRegistered = true;
        }
        load();
    }

    @Override protected void onStop() {
        thumbnails.pause();
        if(activeSheet!=null&&!sharingArchiveEntry){activeSheet.dismiss();activeSheet=null;}
        rememberFile(); started = false; cancelLoad(); releaseMedia(); clearShown();
        ((PreviewStage)stage).reset();navigationPlaceholder=loadingPlaceholder=null;stage.removeAllViews();previewContent=null; image = null; textScroll = null; archiveList = null; mediaAnchor = null; surface = null;
        if (noisyRegistered) { unregisterReceiver(noisy); noisyRegistered = false; }
        super.onStop();
    }

    @Override protected void onResume() {
        super.onResume();
        sharingArchiveEntry = false;
    }

    @Override protected void onDestroy() {
        if (activeSheet != null) activeSheet.dismiss();
        destroyed = true; cancelLoad(); releaseMedia(); clearShown(); ui.removeCallbacksAndMessages(null);
        actions.shutdownNow();thumbnails.close();stage.animate().cancel();
        if(session!=null&&isFinishing()){String id=session.id;MAIN.postDelayed(()->PreviewSession.release(id),60_000);}
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        rememberView(); snapshotMedia();
        out.putString("fileName",name);out.putString("filePath",path);out.putLong("fileSize",size);out.putString("fileUri",uri==null?null:uri.toString());
        out.putBoolean("fileRoot",session!=null&&session.root);
        if(session!=null)out.putInt("filePosition",session.position);
        out.putFloat("playbackSpeed",playbackSpeed);out.putFloat("brightness",getWindow().getAttributes().screenBrightness);out.putBoolean("readerSource",readerSource);out.putInt("page", page); out.putInt("mediaPosition", mediaPosition); out.putBoolean("playing", playWhenReady);
        out.putBoolean("completed", mediaCompleted);
        out.putFloat("zoom", zoom); out.putFloat("centerX", centerX); out.putFloat("centerY", centerY);
        out.putInt("scrollY", scrollY); out.putInt("archivePosition", archivePosition); out.putInt("archiveTop", archiveTop);
        super.onSaveInstanceState(out);
    }

    private void buildUi() {
        theme.bars(this);
        getWindow().setStatusBarColor(theme.surface);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(theme.surface);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        setContentView(root);
        LinearLayout bar = row(); bar.setPadding(dp(4),dp(4),dp(4),dp(4));
        ImageButton back = actionIcon(R.drawable.ic_back,"Back to results",View.NO_ID);
        back.setOnClickListener(view -> handleBack()); bar.addView(back);
        LinearLayout identity = new LinearLayout(this); identity.setOrientation(LinearLayout.VERTICAL);
        identity.setPadding(dp(4),dp(2),dp(4),dp(2));
        title = text(name, 17, INK);title.setTypeface(getResources().getFont(R.font.aeonik_medium)); title.setSingleLine(true); title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        title.setOnLongClickListener(view->{selectCurrent();return true;});
        identity.addView(title);
        details=text(metadata(),12,MUTED);details.setSingleLine(true);details.setEllipsize(TextUtils.TruncateAt.END);
        identity.addView(details); bar.addView(identity,new LinearLayout.LayoutParams(0,-2,1));
        location=actionIcon(R.drawable.ic_folder,"Show full path",R.id.preview_path);
        location.setTooltipText(folderLabel());location.setImageTintList(ColorStateList.valueOf(ACCENT));
        location.setBackground(controlBackground(theme.selected,24));location.setOnClickListener(view->showPath());
        bar.addView(location);
        ImageButton share=actionIcon(R.drawable.ic_share,"Share file",View.NO_ID);
        share.setOnClickListener(view->sendCurrentFile(true));bar.addView(share);
        ImageButton more=actionIcon(R.drawable.ic_more,"More file actions",View.NO_ID);
        more.setOnClickListener(view->showFileActions(view));bar.addView(more);root.addView(bar);
        notice = text("", 12, MUTED); notice.setPadding(dp(12), dp(6), dp(12), dp(6)); notice.setBackgroundColor(CHROME);notice.setVisibility(View.GONE);
        root.addView(notice,new LinearLayout.LayoutParams(-1,-2));
        FrameLayout viewport=new FrameLayout(this);
        stage = new PreviewStage();stage.setId(R.id.preview_stage);stage.setBackgroundColor(stageColor(kind));viewport.addView(stage,new FrameLayout.LayoutParams(-1,-1));
        root.addView(viewport,new LinearLayout.LayoutParams(-1,0,1));
        selectionProgress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);selectionProgress.setIndeterminate(true);selectionProgress.setVisibility(View.GONE);
        root.addView(selectionProgress,new LinearLayout.LayoutParams(-1,dp(2)));
        selectedScroll=new HorizontalScrollView(this);selectedScroll.setOnScrollChangeListener((v,x,y,oldX,oldY)->thumbnails.viewportChanged());selectedScroll.setHorizontalScrollBarEnabled(false);selectedScroll.setBackgroundColor(CHROME);
        selectedStrip=row();selectedStrip.setPadding(dp(8),dp(4),dp(8),dp(4));selectedScroll.addView(selectedStrip);root.addView(selectedScroll,new LinearLayout.LayoutParams(-1,dp(52)));
        LinearLayout navigation=row();navigation.setPadding(dp(8),dp(4),dp(8),dp(4));navigation.setGravity(Gravity.CENTER_VERTICAL|Gravity.END);
        fileNavigation=row();fileNavigation.setBackground(panel(CHROME,24));navigation.addView(fileNavigation);
        previousFile=actionIcon(R.drawable.ic_back,"Previous file",R.id.preview_previous);previousFile.setOnClickListener(view->navigate(-1));fileNavigation.addView(previousFile);
        filePosition=text("",12,MUTED);filePosition.setGravity(Gravity.CENTER);filePosition.setSingleLine(true);filePosition.setEllipsize(TextUtils.TruncateAt.END);
        fileNavigation.addView(filePosition,new LinearLayout.LayoutParams(dp(64),dp(48)));
        nextFile=actionIcon(R.drawable.ic_back,"Next file",R.id.preview_next);nextFile.setRotation(180);nextFile.setOnClickListener(view->navigate(1));fileNavigation.addView(nextFile);
        selectFile=actionIcon(R.drawable.ic_select,"Select file",R.id.preview_select);selectFile.setOnClickListener(view->toggleCurrent());navigation.addView(selectFile);
        copyFile=actionIcon(R.drawable.ic_copy,"Copy files",R.id.preview_copy);copyFile.setOnClickListener(view->prepareClipboard(false));navigation.addView(copyFile);
        moveFile=actionIcon(R.drawable.ic_cut,"Move files",R.id.preview_move);moveFile.setOnClickListener(view->prepareClipboard(true));navigation.addView(moveFile);
        pasteFile=actionIcon(R.drawable.ic_paste,"Paste files",R.id.preview_paste);pasteFile.setOnClickListener(view->{pasteAfterPreview=true;finish();});navigation.addView(pasteFile);
        HorizontalScrollView dock=new HorizontalScrollView(this);dock.setFillViewport(true);dock.setHorizontalScrollBarEnabled(false);
        dock.addView(navigation,new FrameLayout.LayoutParams(-2,-2));root.addView(dock);updateFileControls();
    }

    private ImageButton actionIcon(int resource,String label,int id){ImageButton button=new ImageButton(this,null,0);button.setId(id);button.setImageResource(resource);
        button.setImageTintList(android.content.res.ColorStateList.valueOf(INK));button.setContentDescription(label);button.setTooltipText(label);
        button.setBackground(controlBackground(Color.TRANSPARENT,24));button.setPadding(dp(12),dp(12),dp(12),dp(12));
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(48),dp(48)));return button;}
    private GradientDrawable panel(int color,int radius) {
        GradientDrawable background=AppSheet.shape(color,dp(radius)); background.setStroke(dp(1),theme.line);return background;
    }
    private RippleDrawable controlBackground(int fill,int radius) {
        return new RippleDrawable(ColorStateList.valueOf(theme.line),AppSheet.shape(fill,dp(radius)),AppSheet.shape(Color.WHITE,dp(radius)));
    }
    private void showFileActions(View anchor) {
        android.widget.PopupMenu menu=theme.popup(this,anchor);
        menu.getMenu().add(0,1,0,"Open in another app");
        menu.getMenu().add(0,2,1,"Rename").setEnabled(!selectionBusy);
        menu.getMenu().add(0,R.id.preview_delete,2,"Delete file").setEnabled(!selectionBusy);
        menu.setOnMenuItemClickListener(item->{
            if(item.getItemId()==1)openExternal();
            else if(item.getItemId()==2)renameCurrent();
            else deleteCurrent();
            return true;
        });
        menu.show();
    }
    private FileEntry currentEntry(){return session==null?new FileEntry(path,false,size,0):session.entries.get(session.position);}
    private String folderLabel(){String parent=new java.io.File(path).getParent();return parent==null||parent.equals("/")?"Location":new java.io.File(parent).getName();}
    private void showPath(){if(activeSheet!=null)activeSheet.dismiss();activeSheet=new AppSheet(this,"File location");
        TextView full=activeSheet.text(path,14,INK);full.setTextIsSelectable(true);full.setLineSpacing(dp(4),1);activeSheet.content.addView(full);
        activeSheet.action("Close",false,false,null);activeSheet.action("Copy path",true,false,()->{
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("File path",path));haptic(location);});activeSheet.show();}
    private void haptic(View source){if(getSharedPreferences("ui",0).getBoolean("haptics",true))source.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);}
    private void ensureSession(){if(session!=null)return;ArrayList<FileEntry> entries=new ArrayList<>();entries.add(currentEntry());session=PreviewSession.create(entries,path,false);getIntent().putExtra(PreviewSession.EXTRA,session.id);}
    private void selectCurrent(){ensureSession();FileEntry entry=currentEntry();if(session.selected.containsKey(entry.path()))return;
        if(!session.toggle(entry)){Toast.makeText(this,"Choose up to 512 items at once",Toast.LENGTH_SHORT).show();return;}haptic(stage);updateFileControls();}
    private void toggleCurrent(){ensureSession();if(!session.toggle(currentEntry()))Toast.makeText(this,"Choose up to 512 items at once",Toast.LENGTH_SHORT).show();else haptic(selectFile);updateFileControls();}
    private void updateFileControls(){
        boolean multiple=session!=null&&session.entries.size()>1;
        fileNavigation.setVisibility(multiple?View.VISIBLE:View.GONE);
        previousFile.setEnabled(multiple&&session.position>0);nextFile.setEnabled(multiple&&session.position+1<session.entries.size());
        filePosition.setText(multiple?(session.position+1)+" / "+session.entries.size():"");
        boolean selected=session!=null&&session.selected.containsKey(path);selectFile.setImageResource(selected?R.drawable.ic_check:R.drawable.ic_select);
        selectFile.setContentDescription(selected?"Deselect file":"Select file");selectFile.setImageTintList(android.content.res.ColorStateList.valueOf(selected?ACCENT:INK));
        selectFile.setEnabled(!selectionBusy);copyFile.setEnabled(!selectionBusy);moveFile.setEnabled(!selectionBusy);
        pasteFile.setVisibility(FileClipboard.count(this)>0?View.VISIBLE:View.GONE);selectionProgress.setVisibility(selectionBusy?View.VISIBLE:View.GONE);
        selectedStrip.removeAllViews();int count=session==null?0:session.selected.size();selectedScroll.setVisibility(count>0?View.VISIBLE:View.GONE);
        if(count>0){TextView label=text(Integer.toString(count),12,INK);label.setContentDescription(count+" selected files");label.setPadding(dp(4),0,dp(12),0);selectedStrip.addView(label,new LinearLayout.LayoutParams(-2,dp(44)));
            ArrayList<FileEntry> chosen=new ArrayList<>(session.selected.values());for(int i=Math.max(0,chosen.size()-24);i<chosen.size();i++){
                FileEntry entry=chosen.get(i);ImageView mini=new ImageView(this);mini.setContentDescription("Selected "+entry.name);mini.setPadding(dp(2),dp(2),dp(2),dp(2));
                thumbnails.bind(mini,entry,dp(44),session.root);selectedStrip.addView(mini,new LinearLayout.LayoutParams(dp(48),dp(44)));
                mini.setOnClickListener(view->{for(int n=0;n<session.entries.size();n++)if(session.entries.get(n).path().equals(entry.path())){navigateTo(n);break;}});
                mini.setOnLongClickListener(view->{session.selected.remove(entry.path());haptic(view);updateFileControls();return true;});}}
    }
    private void navigate(int direction){if(session!=null)((PreviewStage)stage).goTo(session.position+direction);}
    private void navigateTo(int position){if(session==null||position<0||position>=session.entries.size()||position==session.position)return;
        rememberFile();cancelLoad();releaseMedia();clearShown();stateKey=null;restoreOnLoad=true;session.position=position;FileEntry entry=currentEntry();name=entry.name;path=entry.path();size=entry.size;uri=null;
        readerSource=false;mime=mimeFor(name);kind=kindFor(name,mime);page=pageCount=mediaPosition=scrollY=archivePosition=archiveTop=0;zoom=1;centerX=centerY=.5f;mediaCompleted=false;playWhenReady=true;
        title.setText(name);location.setTooltipText(folderLabel());details.setText(metadata());updateFileControls();load();}
    private void renameCurrent(){if(selectionBusy)return;ensureSession();ArrayList<FileEntry> chosen=new ArrayList<>(session.selected.values());if(chosen.isEmpty())chosen.add(currentEntry());
        activeSheet=FileRename.show(this,chosen,session.root,actions,this::finish);}
    private void deleteCurrent(){if(selectionBusy)return;ensureSession();ArrayList<FileEntry> chosen=new ArrayList<>(session.selected.values());if(chosen.isEmpty())chosen.add(currentEntry());
        activeSheet=FileDeletion.confirm(this,chosen,session.root,actions,this::finish);}
    private void prepareClipboard(boolean move){if(selectionBusy)return;ensureSession();ArrayList<FileEntry> chosen=new ArrayList<>(session.selected.values());
        if(chosen.isEmpty())chosen.add(currentEntry());boolean root=session.root;selectionBusy=true;updateFileControls();Context context=getApplicationContext();
        try{actions.execute(()->{String error=null;try{FileClipboard.save(context,chosen,move,root);}catch(Exception failure){error=failure.getMessage();}String failure=error;
            ui.post(()->{if(destroyed||isFinishing())return;selectionBusy=false;updateFileControls();if(failure==null){haptic(copyFile);Toast.makeText(this,move?"Choose a destination to move":"Choose a destination to paste",Toast.LENGTH_SHORT).show();}
                else Toast.makeText(this,failure,Toast.LENGTH_LONG).show();});});}catch(java.util.concurrent.RejectedExecutionException busy){selectionBusy=false;updateFileControls();}}
    @Override public void finish(){Intent result=new Intent().putExtra("preview.paste",pasteAfterPreview);if(session!=null)result.putExtra(PreviewSession.EXTRA,session.id);setResult(RESULT_OK,result);super.finish();
        if(Build.VERSION.SDK_INT<34)overridePendingTransition(0,R.anim.preview_exit);}

    private final class PreviewStage extends FrameLayout {
        private float downX,downY,drag;
        private boolean swiping,settling;
        private int peekPosition=-1;
        private FrameLayout peek;
        private android.animation.ValueAnimator animator;
        private android.view.VelocityTracker velocity;
        private final int slop=android.view.ViewConfiguration.get(FileViewerActivity.this).getScaledTouchSlop();
        private final GestureDetector gestures;
        PreviewStage(){super(FileViewerActivity.this);setClipChildren(true);gestures=new GestureDetector(FileViewerActivity.this,new GestureDetector.SimpleOnGestureListener(){
            @Override public boolean onDown(MotionEvent event){return true;}
            @Override public void onLongPress(MotionEvent event){if(!swiping&&!settling&&kind!=Kind.TEXT&&kind!=Kind.MARKDOWN&&kind!=Kind.HTML&&textScroll==null)selectCurrent();}});}
        @Override public boolean dispatchTouchEvent(MotionEvent event){
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN){
                if(velocity!=null)velocity.recycle();velocity=android.view.VelocityTracker.obtain();
                downX=event.getX();downY=event.getY();swiping=false;
            }
            if(velocity!=null)velocity.addMovement(event);
            gestures.onTouchEvent(event);
            boolean handled=super.dispatchTouchEvent(event);
            if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL){if(velocity!=null){velocity.recycle();velocity=null;}}
            return handled;
        }
        @Override public boolean onInterceptTouchEvent(MotionEvent event){
            if(settling)return true;
            if(event.getPointerCount()>1){if(swiping)settle(false);return false;}
            if(event.getActionMasked()==MotionEvent.ACTION_MOVE&&canSwipe(event)){
                swiping=true;follow(event.getX()-downX);return true;
            }
            return swiping;
        }
        @Override public boolean onTouchEvent(MotionEvent event){
            if(settling)return true;
            // Empty/error frames have no child touch target, so interception alone never sees their MOVE.
            if(event.getActionMasked()==MotionEvent.ACTION_MOVE&&!swiping&&canSwipe(event))swiping=true;
            if(event.getActionMasked()==MotionEvent.ACTION_MOVE&&swiping){follow(event.getX()-downX);return true;}
            if(event.getActionMasked()==MotionEvent.ACTION_UP){
                if(swiping){float speed=0;if(velocity!=null){velocity.computeCurrentVelocity(1000);speed=velocity.getXVelocity();}
                    boolean fling=Math.abs(speed)>dp(450)&&Math.abs(drag)>slop*2&&Math.signum(speed)==Math.signum(drag);
                    settle(peek!=null&&(Math.abs(drag)>getWidth()*.2f||fling));}
                else performClick();return true;
            }
            if(event.getActionMasked()==MotionEvent.ACTION_CANCEL){if(swiping)settle(false);return true;}
            return true;
        }
        private boolean canSwipe(MotionEvent event){return event.getPointerCount()==1&&session!=null&&session.entries.size()>1
                &&(image==null||image.state()[0]<=1.05f)&&Math.abs(event.getX()-downX)>slop*1.5f
                &&Math.abs(event.getX()-downX)>Math.abs(event.getY()-downY)*1.35f;}
        private void follow(float distance){
            if(session==null)return;int position=session.position+(distance<0?1:-1);
            boolean available=position>=0&&position<session.entries.size();
            if(!available){removePeek();distance*=.18f;}
            else if(peekPosition!=position){removePeek();peekPosition=position;FileEntry entry=session.entries.get(position);
                Kind nextKind=kindFor(entry.name,mimeFor(entry.name));
                peek=new FrameLayout(FileViewerActivity.this);peek.setBackgroundColor(stageColor(nextKind));
                ImageView picture=new ImageView(FileViewerActivity.this);
                boolean visual=nextKind==Kind.IMAGE||nextKind==Kind.SVG||nextKind==Kind.VIDEO||nextKind==Kind.PDF;
                if(visual)thumbnails.bindPreview(picture,entry,Math.min(getWidth(),dp(512)),session.root);
                else{picture.setImageResource(FileTypes.ICONS[FileTypes.category(entry)]);picture.setImageTintList(android.content.res.ColorStateList.valueOf(nextKind==Kind.AUDIO?ACCENT:UiTheme.fileColor(FileViewerActivity.this,FileTypes.category(entry))));}
                FrameLayout.LayoutParams pictureLayout=visual?new FrameLayout.LayoutParams(-1,-1):new FrameLayout.LayoutParams(dp(64),dp(64),Gravity.CENTER);
                if(nextKind==Kind.PDF||nextKind==Kind.SVG||nextKind==Kind.IMAGE){
                    int inset=nextKind==Kind.PDF?dp(16):dp(8);
                    pictureLayout.setMargins(inset,inset,inset,dp(66));
                }
                peek.addView(picture,pictureLayout);
                addView(peek,0,new FrameLayout.LayoutParams(-1,-1));
            }
            drag=Math.max(-getWidth(),Math.min(getWidth(),distance));
            for(int i=0;i<getChildCount();i++){View child=getChildAt(i);child.setTranslationX(child==peek?(drag<0?getWidth():-getWidth())+drag:drag);}
        }
        void goTo(int position){if(session==null||position<0||position>=session.entries.size()||position==session.position||settling)return;
            if(!android.animation.ValueAnimator.areAnimatorsEnabled()){navigateTo(position);return;}
            float direction=position>session.position?-1:1;follow(direction*slop*2);settleTo(position);}
        private void settle(boolean commit){if(commit)settleTo(peekPosition);else animateTo(0,-1);}
        private void settleTo(int position){animateTo(position>session.position?-getWidth():getWidth(),position);}
        private void animateTo(float target,int destination){
            if(!android.animation.ValueAnimator.areAnimatorsEnabled()){complete(destination);return;}
            settling=true;swiping=false;float from=drag;
            animator=android.animation.ValueAnimator.ofFloat(from,target);animator.setDuration(destination<0?160:210);
            animator.setInterpolator(new android.view.animation.PathInterpolator(.2f,.8f,.2f,1));
            animator.addUpdateListener(value->{drag=(float)value.getAnimatedValue();for(int i=0;i<getChildCount();i++){
                View child=getChildAt(i);child.setTranslationX(child==peek?(target<0||from<0?getWidth():-getWidth())+drag:drag);}});
            animator.addListener(new android.animation.AnimatorListenerAdapter(){@Override public void onAnimationEnd(android.animation.Animator value){animator=null;complete(destination);}});animator.start();
        }
        private void complete(int destination){
            settling=false;swiping=false;drag=0;
            if(destination>=0&&peek!=null){FrameLayout ready=peek;removeView(ready);ready.setTranslationX(0);peek=null;peekPosition=-1;navigationPlaceholder=ready;}
            else removePeek();
            for(int i=0;i<getChildCount();i++)getChildAt(i).setTranslationX(0);
            if(destination>=0&&started&&!destroyed)navigateTo(destination);
        }
        private void removePeek(){if(peek!=null)removeView(peek);peek=null;peekPosition=-1;}
        void reset(){if(animator!=null){animator.removeAllListeners();animator.removeAllUpdateListeners();animator.cancel();animator=null;}
            if(velocity!=null){velocity.recycle();velocity=null;}removePeek();settling=swiping=false;drag=0;
            for(int i=0;i<getChildCount();i++)getChildAt(i).setTranslationX(0);
            long now=android.os.SystemClock.uptimeMillis();MotionEvent cancel=MotionEvent.obtain(now,now,MotionEvent.ACTION_CANCEL,0,0,0);gestures.onTouchEvent(cancel);cancel.recycle();}
        @Override public boolean performClick(){super.performClick();return true;}
        @Override protected void onDetachedFromWindow(){reset();super.onDetachedFromWindow();}
    }

    /** Keep the incoming frame until the replacement has laid out; never resize it during the handoff. */
    private static final class PreviewContent extends FrameLayout {
        private View cover;
        private android.view.ViewTreeObserver.OnPreDrawListener reveal;
        PreviewContent(Context context){super(context);}
        void coverWith(View view,boolean waitForVideo){
            cover=view;addView(view,new FrameLayout.LayoutParams(-1,-1));
            if(!waitForVideo)reveal();
        }
        void reveal(){
            if(cover==null||reveal!=null)return;
            reveal=()->{
                getViewTreeObserver().removeOnPreDrawListener(reveal);reveal=null;
                View frame=cover;
                if(frame!=null){
                    if(android.animation.ValueAnimator.areAnimatorsEnabled())frame.animate().alpha(0).setDuration(100)
                            .withEndAction(()->{removeView(frame);if(cover==frame)cover=null;}).start();
                    else{removeView(frame);cover=null;}
                }
                return true;
            };
            getViewTreeObserver().addOnPreDrawListener(reveal);invalidate();
        }
        @Override protected void onDetachedFromWindow(){
            if(reveal!=null){if(getViewTreeObserver().isAlive())getViewTreeObserver().removeOnPreDrawListener(reveal);reveal=null;}
            if(cover!=null){cover.animate().withEndAction(null).cancel();cover=null;}
            super.onDetachedFromWindow();
        }
    }

    private void load() {
        if (!started || destroyed || isFinishing()) return;
        cancelLoad(); clearShown(); image = null; textScroll = null; archiveList = null;
        notice.setVisibility(View.GONE);((PreviewStage)stage).reset();stage.removeAllViews();
        stage.setBackgroundColor(stageColor(kind));
        previewContent=new PreviewContent(this);stage.addView(previewContent,new FrameLayout.LayoutParams(-1,-1));
        if ((uri == null && session == null) || (uri != null && (!"content".equals(uri.getScheme()) || !(getPackageName() + ".files").equals(uri.getAuthority())))) {
            failure("File access is unavailable. Return to results and open the file again."); return;
        }
        View placeholder=navigationPlaceholder;navigationPlaceholder=null;
        loadingPlaceholder=placeholder;
        previewContent.addView(placeholder==null?new LoadingSkeleton(this,LoadingSkeleton.PREVIEW):placeholder,new FrameLayout.LayoutParams(-1,-1));
        job = new PreviewJob(this, getApplicationContext(), uri, kind, page, generation);
        job.restoreState=restoreOnLoad;restoreOnLoad=false;
        if(uri==null&&session!=null){job.entry=currentEntry();job.allowRoot=session.root;}
        try { pending = PREVIEWS.submit(job); }
        catch (java.util.concurrent.RejectedExecutionException busy) { job.cancel(); job = null; failure("Another preview is finishing. Reopen this file in a moment."); }
    }

    private void cancelLoad() {
        generation++;
        if (job != null) { job.cancel(); job = null; }
        if (pending != null) { pending.cancel(true); pending = null; }
        PREVIEWS.purge();
    }

    private void accept(PreviewResult result, int expectedGeneration) {
        if (!started || destroyed || isFinishing() || isDestroyed() || generation != expectedGeneration) { result.close(); return; }
        pending = null; job = null;
        if(result.uri!=null)uri=result.uri;
        if (result.error != null) { String error = result.error; result.close(); failure(error); return; }
        shown = result;
        stateKey=result.key;
        if(result.state!=null)restoreViewState(result.state);
        if (result.size >= 0) size = result.size;
        details.setText(metadata());View cover=loadingPlaceholder;loadingPlaceholder=null;previewContent.removeAllViews();
        if (kind == Kind.IMAGE || kind == Kind.PDF || kind==Kind.SVG&&!readerSource) showImage(result);
        else if (kind == Kind.TEXT || kind==Kind.MARKDOWN || kind==Kind.HTML || kind==Kind.SVG) showDocument(result);
        else if (kind == Kind.ZIP) showArchive(result.archive);
        else showMedia(result);
        if(cover!=null&&shown==result)previewContent.coverWith(cover,kind==Kind.VIDEO);
    }

    private void showImage(PreviewResult result) {
        LinearLayout column = new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL);
        image = new ZoomPreviewView(this); image.setContentDescription(name + ". Pinch or double tap to zoom. Drag to pan.");
        image.setBackgroundColor(stageColor(kind));
        image.setOnLongClickListener(view->{selectCurrent();return true;});
        if(result.picture!=null)image.setPicture(result.picture);else image.setImage(result.bitmap, result.orientation); image.restore(zoom, centerX, centerY);
        LinearLayout.LayoutParams canvas=new LinearLayout.LayoutParams(-1,0,1);
        int inset=kind==Kind.PDF?dp(16):dp(8);
        canvas.setMargins(inset,inset,inset,dp(4));
        column.addView(image,canvas);
        if (kind == Kind.PDF) {
            page = result.page; pageCount = result.pageCount;
            image.enablePageEdge(()->pageCount,p->{if(p!=page)changePage(p);});
            LinearLayout navigation = row();
            Button previous = button("Previous"); previous.setEnabled(page > 0); previous.setOnClickListener(view -> changePage(page - 1));
            navigation.addView(previous, new LinearLayout.LayoutParams(dp(84), dp(48)));
            TextView position = text((page + 1) + " / " + pageCount, 13, INK); position.setGravity(Gravity.CENTER);
            position.setContentDescription("Page " + (page + 1) + " of " + pageCount); navigation.addView(position, new LinearLayout.LayoutParams(dp(68), dp(48)));
            Button next = button("Next"); next.setEnabled(page + 1 < pageCount); next.setOnClickListener(view -> changePage(page + 1));
            navigation.addView(next, new LinearLayout.LayoutParams(dp(72), dp(48)));
            addViewerTools(column,navigation);
            details.setText(metadata() + " · " + pageCount + (pageCount == 1 ? " page" : " pages"));
        } else {
            LinearLayout tools=row();
            Button less=button("−");less.setContentDescription("Zoom out");less.setOnClickListener(v->image.zoomBy(.8f));tools.addView(less,new LinearLayout.LayoutParams(dp(48),dp(48)));
            Button fit=button("Fit");fit.setContentDescription("Fit image");fit.setOnClickListener(v->image.fit());tools.addView(fit,new LinearLayout.LayoutParams(dp(52),dp(48)));
            Button more=button("+");more.setContentDescription("Zoom in");more.setOnClickListener(v->image.zoomBy(1.25f));tools.addView(more,new LinearLayout.LayoutParams(dp(48),dp(48)));
            if(kind==Kind.SVG){
                Button source=button("Source");source.setOnClickListener(v->{rememberView();readerSource=true;previewContent.removeAllViews();image=null;showDocument(result);});
                tools.addView(source,new LinearLayout.LayoutParams(dp(76),dp(48)));
            }
            addViewerTools(column,tools);
            if(result.width>0&&result.height>0)details.setText(metadata() + " · " + result.width + " × " + result.height);
            if (name.toLowerCase(Locale.ROOT).endsWith(".gif")) note("Still image preview. Use Open for animation.");
        }
        previewContent.addView(column, new FrameLayout.LayoutParams(-1, -1));
    }

    private void addViewerTools(LinearLayout column, LinearLayout tools) {
        tools.setBackground(panel(theme.surface,24));
        LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(-2,-2);
        layout.gravity=Gravity.CENTER_HORIZONTAL;
        layout.setMargins(0,dp(4),0,dp(10));column.addView(tools,layout);
    }

    private void changePage(int value) {
        if (value < 0 || value >= pageCount) return;
        page = value; zoom = 1; centerX = centerY = .5f; load();
    }

    private void showDocument(PreviewResult preview) {
        PreviewText.Result result=preview.text;LinearLayout column=new LinearLayout(this);column.setOrientation(LinearLayout.VERTICAL);
        LinearLayout tools=row();tools.setBackground(panel(CHROME,16));tools.setPadding(dp(12),0,dp(4),0);
        TextView content=text("",readerSize,INK);content.setGravity(Gravity.TOP);content.setPadding(dp(20),dp(16),dp(20),dp(28));content.setLineSpacing(dp(4),1.12f);
        content.setTextIsSelectable(true);content.setLinkTextColor(ACCENT);simpleTextLayout(content);
        Runnable bind=()->{boolean rich=!readerSource&&preview.formatted!=null;
            content.setText(rich?preview.formatted:result.text.isEmpty()?"This file is empty.":result.text);
            if(rich)content.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());};bind.run();
        if(preview.formatted!=null||kind==Kind.SVG){Button mode=button(kind==Kind.SVG?"Image":readerSource?"Read":"Source");
            mode.setContentDescription(kind==Kind.SVG?"Show SVG image":readerSource?"Show formatted document":"Show source text");
            mode.setOnClickListener(v->{if(kind==Kind.SVG){readerSource=false;previewContent.removeAllViews();textScroll=null;showImage(preview);}
                else{readerSource=!readerSource;bind.run();mode.setText(readerSource?"Read":"Source");}});
            tools.addView(mode,new LinearLayout.LayoutParams(0,dp(48),1));}
        else tools.addView(text(result.encoding,12,MUTED),new LinearLayout.LayoutParams(0,dp(48),1));
        Button smaller=button("A−"),larger=button("A+");smaller.setContentDescription("Smaller text");larger.setContentDescription("Larger text");
        smaller.setOnClickListener(v->resizeReader(content,-2));larger.setOnClickListener(v->resizeReader(content,2));
        tools.addView(smaller,new LinearLayout.LayoutParams(dp(48),dp(48)));tools.addView(larger,new LinearLayout.LayoutParams(dp(48),dp(48)));
        ImageButton copy=actionIcon(R.drawable.ic_copy,"Copy text",View.generateViewId());copy.setOnClickListener(v->getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText(name,content.getText())));tools.addView(copy);
        LinearLayout.LayoutParams toolsLayout=new LinearLayout.LayoutParams(-1,-2);
        toolsLayout.setMargins(dp(10),dp(10),dp(10),0);column.addView(tools,toolsLayout);
        textScroll=new EdgeFastScroll.Document(this);textScroll.setFillViewport(true);textScroll.addView(content,new ScrollView.LayoutParams(-1,-2));
        column.addView(textScroll,new LinearLayout.LayoutParams(-1,0,1));previewContent.addView(column,new FrameLayout.LayoutParams(-1,-1));
        details.setText(metadata());if(result.truncated)note("Showing the first 128 KiB or 2,000 lines. Use Open for the full file.");
        textScroll.addOnLayoutChangeListener(new RestoreScroll(scrollY));
    }
    private void resizeReader(TextView content,int delta){readerSize=Math.max(12,Math.min(26,readerSize+delta));content.setTextSize(readerSize);getSharedPreferences("ui",0).edit().putInt("readerSize",readerSize).apply();}

    @SuppressLint("WrongConstant")
    private static void simpleTextLayout(TextView text) {
        // Layout's API 23 constant has the same value as the API 29 LineBreaker
        // constant used by SDK 36's annotation. Keep this call valid on API 26.
        text.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE);
    }
    private static final class RestoreScroll implements View.OnLayoutChangeListener {
        private final int position;
        RestoreScroll(int position) { this.position = position; }
        @Override public void onLayoutChange(View view, int left, int top, int right, int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
            view.removeOnLayoutChangeListener(this); ((ScrollView) view).scrollTo(0, position);
        }
    }

    private void showArchive(ZipDirectory.Listing result) {
        if (result.entries.isEmpty() && !result.truncated) {
            TextView empty = text("This archive is empty.", 14, MUTED); empty.setGravity(Gravity.CENTER); previewContent.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        } else {
            archiveList = new EdgeFastScroll.List(this); ((EdgeFastScroll.List)archiveList).labels=p->result.entries.get(p).name; archiveList.setPadding(dp(12),dp(8),dp(12),dp(8));
            archiveList.setDivider(new android.graphics.drawable.ColorDrawable(theme.line)); archiveList.setDividerHeight(dp(1));
            archiveList.setAdapter(new ArchiveAdapter(result.entries));
            archiveList.setOnItemClickListener((parent, view, position, id) -> showArchiveEntry(result.entries.get(position)));
            archiveList.setSelectionFromTop(Math.min(archivePosition, Math.max(0, result.entries.size() - 1)), archiveTop);
            previewContent.addView(archiveList, new FrameLayout.LayoutParams(-1, -1));
        }
        details.setText(metadata() + " · " + NumberFormat.getIntegerInstance().format(result.totalEntries) + (result.totalEntries == 1 ? " entry" : " entries"));
        if (result.truncated) note("Showing " + result.entries.size() + " entries within the preview limit. Use Open for the full archive.");
    }

    private void showArchiveEntry(ZipDirectory.Entry entry) {
        if (shown == null) return;
        if (activeSheet != null) activeSheet.dismiss();
        String entryName = entry.name.substring(entry.name.lastIndexOf('/') + 1);
        Kind format = kindFor(entryName, mimeFor(entryName));
        AppSheet sheet = new AppSheet(this, entryName, format == Kind.PDF);
        activeSheet = sheet;
        TextView location = sheet.text(name + "  ›  " + entry.name + "  ·  " + bytes(entry.size), 12, MUTED);
        location.setTextIsSelectable(true);
        location.setMaxLines(2);
        location.setPadding(0, 0, 0, dp(8));
        sheet.content.addView(location, new LinearLayout.LayoutParams(-1, -2));
        TextView status = sheet.text("Loading preview…", 14, INK);
        sheet.content.addView(status);
        String archivePath = path;
        String identity = shown.identity;
        Uri archiveUri = uri;
        boolean root = session != null && session.root;
        Context context = getApplicationContext();
        CancellationSignal signal = new CancellationSignal();
        ArchivePdfReader reader = format == Kind.PDF ? new ArchivePdfReader(sheet, status, entryName) : null;
        if (!entry.encrypted && (entry.method == 0 || entry.method == 8) && entry.size <= ArchiveEntryFile.MAX_BYTES) {
            ImageButton share = actionIcon(R.drawable.ic_share, "Share archive entry", View.NO_ID);
            share.setOnClickListener(v -> {
                share.setEnabled(false);
                try {
                    actions.execute(() -> {
                        Uri grant = null;
                        String error = null;
                        try {
                            grant = RootFileProvider.issueArchiveEntry(context, archiveUri, archivePath, root, identity, entry);
                        } catch (Exception failure) { error = failure.getMessage(); }
                        Uri result = grant;
                        String message = error;
                        ui.post(() -> {
                            if (!isCurrentSheet(sheet)) return;
                            share.setEnabled(true);
                            if (result != null) handOffFile(result, entryName, mimeFor(entryName), true, true);
                            else Toast.makeText(this, message == null ? "This ZIP entry could not be shared." : message, Toast.LENGTH_LONG).show();
                        });
                    });
                } catch (java.util.concurrent.RejectedExecutionException busy) {
                    share.setEnabled(true);
                    Toast.makeText(this, "File access is busy", Toast.LENGTH_SHORT).show();
                }
            });
            sheet.headerAction(share);
        }
        try {
            Future<?> task = PREVIEWS.submit(() -> {
                Object content = null;
                String error = null;
                try (ParcelFileDescriptor descriptor = archiveUri == null
                        ? SafeFileAccess.open(context, archivePath, root)
                        : context.getContentResolver().openFileDescriptor(archiveUri, "r", signal)) {
                    if (descriptor == null || !identity.equals(RootFileProvider.identity(Os.fstat(descriptor.getFileDescriptor()))))
                        throw new IOException("The archive changed. Reopen it to preview its entries.");
                    try (ParcelFileDescriptor.AutoCloseInputStream archive =
                            new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.getFileDescriptor()));
                            java.io.InputStream input = ZipDirectory.open(archive.getChannel(), entry)) {
                        if (reader == null) content = readArchiveEntry(input, format, entry.size);
                        else {
                            reader.prepare(ArchiveEntryFile.copy(context, input, entry.size));
                            content = reader.render(0);
                        }
                    }
                } catch (Exception | OutOfMemoryError failure) {
                    error = archivePreviewError(failure);
                }
                Object preview = content;
                String message = error;
                ui.post(() -> {
                    if (!isCurrentSheet(sheet)) {
                        if (preview instanceof PreviewResult) ((PreviewResult) preview).close();
                        return;
                    }
                    if (reader != null) {
                        reader.show((PreviewResult) preview, message);
                    } else if (preview instanceof android.graphics.drawable.Drawable) {
                        sheet.content.removeView(status);
                        ImageView image = new ImageView(this);
                        image.setImageDrawable((android.graphics.drawable.Drawable) preview);
                        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                        image.setAdjustViewBounds(true);
                        image.setMaxHeight(dp(360));
                        image.setContentDescription(entryName);
                        sheet.content.addView(image, new LinearLayout.LayoutParams(-1, -2));
                    } else {
                        status.setText(preview instanceof CharSequence ? (CharSequence) preview
                                : message == null ? "This entry could not be previewed." : message);
                        status.setTextIsSelectable(true);
                        status.setLineSpacing(dp(3), 1f);
                        simpleTextLayout(status);
                    }
                });
            });
            sheet.setOnDismissListener(dialog -> {
                signal.cancel();
                task.cancel(true);
                if (task instanceof Runnable) PREVIEWS.remove((Runnable) task);
                if (reader != null) reader.close();
                if (activeSheet == sheet) activeSheet = null;
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            status.setText("The preview is busy. Tap the entry again in a moment.");
        }
        sheet.show();
    }

    private boolean isCurrentSheet(AppSheet sheet) {
        return !destroyed && activeSheet == sheet && sheet.isShowing();
    }

    private static String archivePreviewError(Throwable failure) {
        if (failure instanceof SecurityException) return "This PDF is password protected or uses unsupported encryption.";
        if (failure instanceof IOException && failure.getMessage() != null) return failure.getMessage();
        return "This entry could not be previewed.";
    }

    private final class ArchivePdfReader {
        private final AppSheet sheet;
        private final TextView status;
        private final String name;
        private ParcelFileDescriptor source;
        private boolean closed;
        private Future<?> task;
        private ZoomPreviewView pageView;
        private Button previous, next;
        private TextView position;
        private int page, count;

        ArchivePdfReader(AppSheet sheet, TextView status, String name) {
            this.sheet = sheet;
            this.status = status;
            this.name = name;
        }

        synchronized void prepare(ParcelFileDescriptor input) throws IOException {
            if (closed) {
                input.close();
                throw new IOException("Preview stopped.");
            }
            source = input;
        }

        private synchronized ParcelFileDescriptor duplicate() throws IOException {
            if (source == null) throw new IOException("Preview stopped.");
            return ParcelFileDescriptor.dup(source.getFileDescriptor());
        }

        PreviewResult render(int wantedPage) throws Exception {
            PreviewResult result = new PreviewResult();
            boolean ready = false;
            try (ParcelFileDescriptor input = duplicate()) {
                Os.lseek(input.getFileDescriptor(), 0, OsConstants.SEEK_SET);
                renderPdfPage(input, result, wantedPage);
                ready = true;
                return result;
            } finally {
                if (!ready) result.close();
            }
        }

        void show(PreviewResult result, String error) {
            if (result != null) {
                if (pageView == null) {
                    pageView = new ZoomPreviewView(FileViewerActivity.this);
                    pageView.enablePageEdge(()->count,p->{if(p!=page)request(p);});
                    pageView.setContentDescription(name + ". Pinch or double tap to zoom.");
                    sheet.content.addView(pageView, new LinearLayout.LayoutParams(-1, 0, 1));
                    previous = button("Previous page");
                    next = button("Next page");
                    position = sheet.text("", 13, INK);
                    position.setGravity(Gravity.CENTER);
                    position.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
                    sheet.actions.addView(previous, new LinearLayout.LayoutParams(0, dp(48), 1));
                    sheet.actions.addView(position, new LinearLayout.LayoutParams(0, dp(48), 1));
                    sheet.actions.addView(next, new LinearLayout.LayoutParams(0, dp(48), 1));
                    previous.setOnClickListener(v -> request(page - 1));
                    next.setOnClickListener(v -> request(page + 1));
                }
                page = result.page;
                count = result.pageCount;
                pageView.setImage(result.bitmap, 1);
                pageView.fit();
                position.setText("Page " + (page + 1) + " of " + count);
            }
            status.setVisibility(result == null ? View.VISIBLE : View.GONE);
            if (result == null) status.setText(error == null ? "This PDF page could not be previewed." : error);
            if (previous != null) {
                previous.setEnabled(page > 0);
                next.setEnabled(page + 1 < count);
            }
        }

        private void request(int wantedPage) {
            previous.setEnabled(false);
            next.setEnabled(false);
            try {
                task = PREVIEWS.submit(() -> {
                    PreviewResult result = null;
                    String error = null;
                    try { result = render(wantedPage); }
                    catch (Exception | OutOfMemoryError failure) { error = archivePreviewError(failure); }
                    PreviewResult preview = result;
                    String message = error;
                    ui.post(() -> {
                        if (isCurrentSheet(sheet)) show(preview, message);
                        else if (preview != null) preview.close();
                    });
                });
            } catch (java.util.concurrent.RejectedExecutionException busy) {
                show(null, "The preview is busy. Try this page again.");
            }
        }

        synchronized void close() {
            closed = true;
            if (task != null) {
                task.cancel(true);
                if (task instanceof Runnable) PREVIEWS.remove((Runnable) task);
            }
            if (source != null) {
                try { source.close(); } catch (IOException ignored) { }
                source = null;
            }
            if (pageView != null) pageView.setImage(null, 1);
        }
    }

    private static Object readArchiveEntry(java.io.InputStream input, Kind format, long size) throws Exception {
        switch (format) {
            case SVG:
                return new android.graphics.drawable.PictureDrawable(SvgPreview.read(input).picture);
            case IMAGE:
                if (size > MAX_ARCHIVE_IMAGE_BYTES) throw new IOException("Images over 16 MiB exceed the ZIP preview limit.");
                byte[] encoded = new byte[(int) size];
                int used = 0;
                while (used < encoded.length) {
                    PreviewText.cancelled();
                    int count = input.read(encoded, used, encoded.length - used);
                    if (count < 0) throw new IOException("The archived image is incomplete.");
                    used += count;
                }
                if (input.read() != -1) throw new IOException("The archived image size is invalid.");
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(encoded, 0, encoded.length, options);
                if (options.outWidth < 1 || options.outHeight < 1) throw new IOException("This image format could not be decoded.");
                options.inSampleSize = 1;
                while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > MAX_BITMAP_EDGE
                        || (long) (options.outWidth / options.inSampleSize) * (options.outHeight / options.inSampleSize) > MAX_BITMAP_PIXELS)
                    options.inSampleSize *= 2;
                options.inJustDecodeBounds = false;
                PreviewText.cancelled();
                Bitmap bitmap = BitmapFactory.decodeByteArray(encoded, 0, encoded.length, options);
                if (bitmap == null) throw new IOException("This image could not be decoded.");
                return new android.graphics.drawable.BitmapDrawable(null, bitmap);
            case TEXT:
            case MARKDOWN:
            case HTML:
            case OTHER:
                PreviewText.Result text = PreviewText.read(input);
                if (text.truncated) return text.text + "\n\nPreview limited to the first 128 KiB or 2,000 lines.";
                if (text.text.isEmpty()) return "This file is empty.";
                if (format == Kind.MARKDOWN) return RichText.markdown(text.text);
                if (format == Kind.HTML) return RichText.html(text.text);
                return text.text;
            default:
                throw new IOException("In-archive playback or document rendering is unavailable for this format.");
        }
    }

    private final class ArchiveAdapter extends BaseAdapter {
        private final List<ZipDirectory.Entry> entries;
        ArchiveAdapter(List<ZipDirectory.Entry> entries) { this.entries = entries; }
        @Override public int getCount() { return entries.size(); }
        @Override public Object getItem(int position) { return entries.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public boolean areAllItemsEnabled() { return false; }
        @Override public boolean isEnabled(int position) {
            ZipDirectory.Entry entry = entries.get(position);
            return !entry.directory && !entry.encrypted;
        }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            LinearLayout item;
            if (recycled instanceof LinearLayout) item = (LinearLayout) recycled;
            else {
                item = row(); item.setMinimumHeight(dp(58)); item.setPadding(dp(6), dp(8), dp(6), dp(8));
                ImageView icon = new ImageView(FileViewerActivity.this);icon.setPadding(dp(9),dp(9),dp(9),dp(9));
                icon.setBackground(AppSheet.shape(theme.selected,dp(12)));item.addView(icon,new LinearLayout.LayoutParams(dp(40),dp(40)));
                LinearLayout labels = new LinearLayout(FileViewerActivity.this); labels.setPadding(dp(10), 0, 0, 0); labels.setOrientation(LinearLayout.VERTICAL);
                TextView label = text("", 13, INK); label.setSingleLine(true); label.setEllipsize(TextUtils.TruncateAt.MIDDLE); labels.addView(label);
                labels.addView(text("", 11, MUTED)); item.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
                ImageView arrow=new ImageView(FileViewerActivity.this);arrow.setImageResource(R.drawable.ic_chevron_right);
                arrow.setImageTintList(ColorStateList.valueOf(MUTED));item.addView(arrow,new LinearLayout.LayoutParams(dp(18),dp(18)));
            }
            ZipDirectory.Entry entry = entries.get(position);
            ImageView icon = (ImageView) item.getChildAt(0);
            icon.setImageResource(entry.directory ? R.drawable.ic_folder : R.drawable.ic_file);
            icon.setImageTintList(android.content.res.ColorStateList.valueOf(entry.directory ? ACCENT : MUTED));
            LinearLayout labels = (LinearLayout) item.getChildAt(1);
            ((TextView) labels.getChildAt(0)).setText(entry.name);
            ((TextView) labels.getChildAt(1)).setText((entry.directory ? "Folder" : bytes(entry.size)) + (entry.encrypted ? " · encrypted" : ""));
            item.getChildAt(2).setVisibility(entry.directory||entry.encrypted?View.INVISIBLE:View.VISIBLE);
            return item;
        }
    }

    private void rememberView() {
        if (image != null) { float[] state = image.state(); zoom = state[0]; centerX = state[1]; centerY = state[2]; }
        if (textScroll != null) scrollY = textScroll.getScrollY();
        if (archiveList != null) {
            archivePosition = archiveList.getFirstVisiblePosition(); View first = archiveList.getChildAt(0); archiveTop = first == null ? 0 : first.getTop();
        }
    }
    private void rememberFile(){rememberView();snapshotMedia();if(stateKey==null||Build.VERSION.SDK_INT<27)return;
        ViewState state=new ViewState();state.page=page;state.mediaPosition=mediaPosition;state.playing=playWhenReady;state.completed=mediaCompleted;state.readerSource=readerSource;
        state.zoom=zoom;state.centerX=centerX;state.centerY=centerY;state.scrollY=scrollY;state.archivePosition=archivePosition;state.archiveTop=archiveTop;
        synchronized(VIEW_STATES){VIEW_STATES.put(stateKey,state);}}
    private void restoreViewState(ViewState state){page=state.page;mediaPosition=state.mediaPosition;playWhenReady=state.playing;mediaCompleted=state.completed;readerSource=state.readerSource;
        zoom=state.zoom;centerX=state.centerX;centerY=state.centerY;scrollY=state.scrollY;archivePosition=state.archivePosition;archiveTop=state.archiveTop;}

    private void clearShown() {
        if (image != null) image.setImage(null, 1);
        if (shown != null) { shown.close(); shown = null; }
    }

    private void failure(String message) {
        loadingPlaceholder=null;previewContent.removeAllViews();
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackground(panel(theme.surface,20));card.setPadding(dp(20),dp(16),dp(20),dp(8));
        TextView error=text(message,14,MUTED);error.setGravity(Gravity.CENTER);card.addView(error);
        if(uri!=null||session!=null){Button open=button("Open in another app");open.setOnClickListener(v->openExternal());
            card.addView(open,new LinearLayout.LayoutParams(-2,dp(48)));}
        FrameLayout.LayoutParams layout=new FrameLayout.LayoutParams(-1,-2,Gravity.CENTER);
        layout.setMargins(dp(24),0,dp(24),0);previewContent.addView(card,layout);
    }

    private void note(String message) { notice.setText(message); notice.setVisibility(View.VISIBLE); }

    private void openExternal() {
        sendCurrentFile(false);
    }

    private void sendCurrentFile(boolean share) {
        if(uri==null){FileEntry entry=currentEntry();boolean root=session!=null&&session.root;String identity=shown==null?null:shown.identity;Context context=getApplicationContext();
            try{actions.execute(()->{try{Uri grant=RootFileProvider.issue(context,entry,root,identity);ui.post(()->{if(!destroyed&&!isFinishing()&&entry.path().equals(path)){uri=grant;sendCurrentFile(share);}});}
                catch(Exception failure){ui.post(()->{if(!destroyed&&!isFinishing())Toast.makeText(this,failure.getMessage(),Toast.LENGTH_LONG).show();});}});}
            catch(java.util.concurrent.RejectedExecutionException busy){Toast.makeText(this,"File access is busy",Toast.LENGTH_SHORT).show();}return;}
        handOffFile(uri, name, mime, share, false);
    }

    private void handOffFile(Uri file, String label, String mimeType, boolean share, boolean archiveEntry) {
        Intent target = share ? new Intent(Intent.ACTION_SEND).setType(mimeType).putExtra(Intent.EXTRA_STREAM, file)
                : new Intent(Intent.ACTION_VIEW).setDataAndType(file, mimeType);
        target.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).setClipData(ClipData.newRawUri(label, file));
        try {
            sharingArchiveEntry = archiveEntry;
            startActivity(Intent.createChooser(target, share ? "Share file" : "Open file"));
        } catch (ActivityNotFoundException unavailable) {
            sharingArchiveEntry = false;
            Toast.makeText(this, share ? "No installed app can share this file." : "No installed app can open this file.", Toast.LENGTH_SHORT).show();
        } catch (SecurityException denied) {
            sharingArchiveEntry = false;
            Toast.makeText(this, "File access expired. Return to results and open it again.", Toast.LENGTH_LONG).show();
        }
    }

    private String metadata() {
        String type=extension(name);
        if("jpg".equals(type))type="jpeg";
        return (type.isEmpty()?"File":type.toUpperCase(Locale.ROOT))+" · "+bytes(size);
    }
    private int stageColor(Kind format) {
        switch(format){
            case VIDEO: return Color.BLACK;
            case IMAGE: case SVG: return theme.dark?0xFF0C0F15:0xFFF1F3F7;
            case PDF: case AUDIO: return CHROME;
            default: return theme.surface;
        }
    }
    private static String bytes(long value) {
        if (value < 0) return "Size unknown";
        if (value < 1024) return value + " B";
        if (value < 1024L * 1024) return String.format(Locale.getDefault(), "%.1f KiB", value / 1024d);
        if (value < 1024L * 1024 * 1024) return String.format(Locale.getDefault(), "%.1f MiB", value / (1024d * 1024));
        return String.format(Locale.getDefault(), "%.1f GiB", value / (1024d * 1024 * 1024));
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(String value, float size, int color) {
        TextView text = new TextView(this); text.setText(value); text.setTextSize(size); text.setTextColor(color); text.setGravity(Gravity.CENTER_VERTICAL);
        text.setTypeface(getResources().getFont(R.font.aeonik));return text;
    }
    private LinearLayout row() { LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL); return row; }
    private Button button(String label) {
        Button button = new Button(this, null, 0); button.setText(label); button.setAllCaps(false); button.setTextSize(13);
        button.setGravity(Gravity.CENTER);
        button.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled},{}},new int[]{MUTED,ACCENT}));
        button.setTypeface(getResources().getFont(R.font.aeonik_medium));
        button.setMinHeight(0); button.setMinimumHeight(0); button.setMinWidth(0); button.setMinimumWidth(0);
        button.setBackground(controlBackground(Color.TRANSPARENT,24)); return button;
    }

    static String mimeFor(String name) {
        String extension = extension(name);
        if ("apk".equals(extension)) return "application/vnd.android.package-archive";
        if ("md".equals(extension) || "log".equals(extension) || "ini".equals(extension)) return "text/plain";
        if ("mkv".equals(extension)) return "video/x-matroska";
        String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        return type == null ? "application/octet-stream" : type;
    }
    private static String extension(String name) {
        int dot = name.lastIndexOf('.'); return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
    private static Kind kindFor(String name, String mime) {
        String ext = extension(name);
        if ("pdf".equals(ext)) return Kind.PDF;
        if(has("svg svgz",ext))return Kind.SVG;
        if(has("md markdown",ext))return Kind.MARKDOWN;
        if(has("html htm",ext))return Kind.HTML;
        if (has("zip apk jar cbz", ext)) return Kind.ZIP;
        if (has("jpg jpeg png gif webp bmp heic heif avif ico", ext) || (mime.startsWith("image/") && !"svg".equals(ext) && !"svgz".equals(ext))) return Kind.IMAGE;
        if (mime.startsWith("video/") || has("mp4 m4v mkv webm 3gp 3gpp mov avi mpeg mpg ts mts", ext)) return Kind.VIDEO;
        if (mime.startsWith("audio/") || has("mp3 m4a aac wav ogg oga opus flac amr mid midi aif aiff", ext)) return Kind.AUDIO;
        if (mime.startsWith("text/") || has("txt md markdown log json jsonl xml csv tsv html htm css js mjs tsx jsx java kt kts py sh bash zsh ini conf config cfg properties yml yaml toml sql c h cpp hpp rs go swift gradle gitignore env svg", ext)
                || name.equalsIgnoreCase("README") || name.equalsIgnoreCase("LICENSE") || name.equalsIgnoreCase("Makefile")) return Kind.TEXT;
        return Kind.OTHER;
    }
    private static boolean has(String values, String value) { return !value.isEmpty() && (" " + values + " ").contains(" " + value + " "); }

    private void showMedia(PreviewResult result) {
        mediaAnchor = new FrameLayout(this); mediaAnchor.setBackgroundColor(stageColor(kind));
        mediaAnchor.setFocusable(true); mediaAnchor.setClickable(true); mediaAnchor.setContentDescription("Media preview. Tap for playback controls.");
        previewContent.addView(mediaAnchor, new FrameLayout.LayoutParams(-1, -1));
        if (kind == Kind.VIDEO) {
            surface = new SurfaceView(this); mediaAnchor.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
            surface.getHolder().addCallback(new VideoSurfaceCallbacks(this));
            mediaAnchor.addOnLayoutChangeListener(new VideoLayoutListener(this));
        } else {
            ImageView icon = new ImageView(this); icon.setImageResource(R.drawable.ic_audio); icon.setImageTintList(ColorStateList.valueOf(ACCENT));
            icon.setPadding(dp(28),dp(28),dp(28),dp(28));icon.setBackground(panel(theme.selected,28));
            mediaAnchor.addView(icon, new FrameLayout.LayoutParams(dp(112), dp(112), Gravity.CENTER));
        }
        controller = new PlaybackControls(this,kind==Kind.VIDEO,this::handleBack,()->adjustMedia(false),()->adjustMedia(true),this::choosePlaybackSpeed); controller.setAnchorView(mediaAnchor); controller.setMediaPlayer(new MediaControls(this)); controller.setEnabled(false);
        mediaAnchor.setOnClickListener(view -> {
            if(controller!=null&&kind==Kind.VIDEO&&controller.isShowing()&&isPlaying())controller.hide();
            else showController();
        });
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(audioAttributes()); player.setScreenOnWhilePlaying(kind == Kind.VIDEO);
            if (surface != null && surface.getHolder().getSurface().isValid()) { surfaceReady = true; player.setDisplay(surface.getHolder()); }
            MediaCallbacks callbacks = new MediaCallbacks(this);
            player.setOnPreparedListener(callbacks); player.setOnSeekCompleteListener(callbacks);
            player.setOnVideoSizeChangedListener(callbacks); player.setOnCompletionListener(callbacks); player.setOnErrorListener(callbacks);
            player.setOnInfoListener(callbacks);
            player.setDataSource(result.media.getFileDescriptor());
            result.media.close(); result.media = null;
            player.prepareAsync(); ui.postDelayed(mediaTimeout, 20_000);
        } catch (Exception failure) { mediaFailure(); }
    }

    private void layoutVideo() {
        if (surface == null || mediaAnchor == null || videoWidth <= 0 || videoHeight <= 0) return;
        int availableWidth = mediaAnchor.getWidth(), availableHeight = mediaAnchor.getHeight();
        if (availableWidth <= 0 || availableHeight <= 0) return;
        float scale = Math.min(availableWidth / (float) videoWidth, availableHeight / (float) videoHeight);
        int width = Math.max(1, Math.round(videoWidth * scale)), height = Math.max(1, Math.round(videoHeight * scale));
        ViewGroup.LayoutParams current = surface.getLayoutParams();
        if (current.width != width || current.height != height) surface.setLayoutParams(new FrameLayout.LayoutParams(width, height, Gravity.CENTER));
    }

    private void maybeStart() {
        if (!started || destroyed || isFinishing() || !prepared || seeking || !playWhenReady || player == null || (kind == Kind.VIDEO && !surfaceReady)) return;
        if (!requestFocus()) { playWhenReady = false; note("Playback paused because audio focus is unavailable."); return; }
        try { player.start(); notice.setVisibility(View.GONE); } catch (RuntimeException invalid) { mediaFailure(); }
    }
    private void pausePlayer() {
        if (!prepared || player == null) return;
        try { if (player.isPlaying()) player.pause(); mediaPosition = player.getCurrentPosition(); } catch (RuntimeException ignored) { }
    }
    private void snapshotMedia() {
        if (mediaCompleted) { mediaPosition = 0; return; }
        if (prepared && player != null) try { mediaPosition = Math.max(0, player.getCurrentPosition()); } catch (RuntimeException ignored) { }
    }
    private void showController() {
        if (controller != null && prepared && started && !destroyed && !isFinishing() && mediaAnchor != null && mediaAnchor.getWindowToken() != null)
            try { controller.show(kind == Kind.AUDIO || !isPlaying() ? 0 : 4000); } catch (RuntimeException ignored) { }
    }
    private boolean isPlaying() {
        if (!prepared || player == null) return false;
        try { return player.isPlaying(); } catch (RuntimeException ignored) { return false; }
    }
    private void mediaFailure() {
        releaseMedia(); clearShown();
        failure("This media format or codec cannot be played on this device. Use Open to choose another app.");
    }
    private void releaseMedia() {
        ui.removeCallbacks(mediaTimeout);
        if (controller != null) { controller.hide(); controller.setEnabled(false); controller = null; }
        if (player != null) {
            MediaPlayer old = player; player = null; prepared = false;
            old.setOnPreparedListener(null); old.setOnSeekCompleteListener(null); old.setOnVideoSizeChangedListener(null);
            old.setOnCompletionListener(null); old.setOnErrorListener(null);old.setOnInfoListener(null); old.release();
        }
        prepared = false; seeking = false; surfaceReady = false; videoWidth = videoHeight = 0; abandonFocus();
    }

    private AudioAttributes audioAttributes() {
        return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(kind == Kind.VIDEO ? AudioAttributes.CONTENT_TYPE_MOVIE : AudioAttributes.CONTENT_TYPE_MUSIC).build();
    }
    private boolean requestFocus() {
        if (hasFocus) return true;
        if (audio == null) audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audio == null) return false;
        if (audioFocus == null) audioFocus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(audioAttributes())
                .setOnAudioFocusChangeListener(new FocusListener(this, ++focusGeneration), ui).build();
        hasFocus = audio.requestAudioFocus(audioFocus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        return hasFocus;
    }
    private void abandonFocus() {
        focusGeneration++;
        if (audio != null && audioFocus != null) audio.abandonAudioFocusRequest(audioFocus);
        audioFocus = null; hasFocus = false; resumeAfterFocus = false;
    }
    private static final class FocusListener implements AudioManager.OnAudioFocusChangeListener {
        private final WeakReference<FileViewerActivity> owner;
        private final int generation;
        FocusListener(FileViewerActivity owner, int generation) { this.owner = new WeakReference<>(owner); this.generation = generation; }
        @Override public void onAudioFocusChange(int change) {
            FileViewerActivity activity = owner.get();
            if (activity == null || !activity.started || activity.focusGeneration != generation) return;
            if (change == AudioManager.AUDIOFOCUS_GAIN) {
                activity.hasFocus = true;
                if (activity.player != null) activity.player.setVolume(1, 1);
                if (activity.resumeAfterFocus) { activity.resumeAfterFocus = false; activity.maybeStart(); }
            } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                if (activity.player != null) activity.player.setVolume(.2f, .2f);
            } else {
                activity.hasFocus = false; activity.resumeAfterFocus = change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT && activity.playWhenReady;
                if (!activity.resumeAfterFocus) activity.playWhenReady = false;
                activity.pausePlayer(); activity.showController();
            }
        }
    }
    private static String duration(int milliseconds) {
        int seconds = milliseconds / 1000;
        return seconds >= 3600 ? String.format(Locale.getDefault(), "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60);
    }

    private void adjustMedia(boolean brightness){if(activeSheet!=null)activeSheet.dismiss();activeSheet=new AppSheet(this,brightness?"Brightness":"Volume");
        android.widget.SeekBar slider=new android.widget.SeekBar(this);slider.setMinimumHeight(dp(48));slider.setContentDescription(brightness?"Preview brightness":"Media volume");slider.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));slider.setThumbTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        AudioManager manager=getSystemService(AudioManager.class);int maximum=brightness?100:manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);slider.setMax(maximum);
        float light=getWindow().getAttributes().screenBrightness;
        if(light<0)light=android.provider.Settings.System.getInt(getContentResolver(),android.provider.Settings.System.SCREEN_BRIGHTNESS,128)/255f;
        slider.setProgress(brightness?Math.round(light*100):manager.getStreamVolume(AudioManager.STREAM_MUSIC));
        slider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(android.widget.SeekBar bar,int value,boolean user){if(!user)return;if(brightness){WindowManager.LayoutParams settings=getWindow().getAttributes();settings.screenBrightness=Math.max(.01f,value/100f);getWindow().setAttributes(settings);}
                else try{manager.setStreamVolume(AudioManager.STREAM_MUSIC,value,0);}catch(SecurityException denied){Toast.makeText(FileViewerActivity.this,"Volume is controlled by this device",Toast.LENGTH_SHORT).show();}}
            public void onStartTrackingTouch(android.widget.SeekBar bar){}public void onStopTrackingTouch(android.widget.SeekBar bar){}
        });activeSheet.content.addView(slider,new LinearLayout.LayoutParams(-1,dp(56)));activeSheet.action("Done",true,false,null);activeSheet.show();}
    private void choosePlaybackSpeed(){if(!prepared||player==null)return;if(activeSheet!=null)activeSheet.dismiss();activeSheet=new AppSheet(this,"Playback speed");
        for(float value:new float[]{.5f,.75f,1,1.25f,1.5f,2}){Button option=button(value+"×");option.setOnClickListener(v->{boolean wasPlaying=isPlaying();try{
            player.setPlaybackParams(player.getPlaybackParams().setSpeed(value));if(!wasPlaying)player.pause();playbackSpeed=value;
            if(controller instanceof PlaybackControls)((PlaybackControls)controller).speed(value);
        }catch(RuntimeException unsupported){Toast.makeText(this,"Playback speed is unavailable for this file",Toast.LENGTH_SHORT).show();}activeSheet.dismiss();});activeSheet.content.addView(option,new LinearLayout.LayoutParams(-1,dp(48)));}activeSheet.show();}

    private static final class MediaControls implements MediaController.MediaPlayerControl {
        private final WeakReference<FileViewerActivity> owner;
        MediaControls(FileViewerActivity owner) { this.owner = new WeakReference<>(owner); }
        @Override public void start() {
            FileViewerActivity activity = owner.get();
            if (activity != null) {
                activity.playWhenReady = true;
                if (activity.mediaCompleted && activity.prepared && activity.player != null) {
                    activity.mediaCompleted = false; activity.mediaPosition = 0; activity.seeking = true;
                    try { activity.player.seekTo(0L, MediaPlayer.SEEK_CLOSEST); } catch (RuntimeException invalid) { activity.mediaFailure(); }
                } else activity.maybeStart();
            }
        }
        @Override public void pause() { FileViewerActivity activity = owner.get(); if (activity != null) { activity.playWhenReady = false; activity.pausePlayer(); activity.abandonFocus(); } }
        @Override public int getDuration() {
            FileViewerActivity activity = owner.get();
            try { return activity != null && activity.prepared && activity.player != null ? Math.max(0, activity.player.getDuration()) : 0; } catch (RuntimeException invalid) { return 0; }
        }
        @Override public int getCurrentPosition() { FileViewerActivity activity = owner.get(); if (activity == null) return 0; activity.snapshotMedia(); return activity.mediaPosition; }
        @Override public void seekTo(int position) {
            FileViewerActivity activity = owner.get(); if (activity == null) return;
            activity.mediaCompleted = false;
            activity.mediaPosition = Math.max(0, Math.min(position, getDuration()));
            if (activity.prepared && activity.player != null) try { activity.seeking = true; activity.player.seekTo((long) activity.mediaPosition, MediaPlayer.SEEK_CLOSEST); } catch (RuntimeException invalid) { activity.seeking = false; }
        }
        @Override public boolean isPlaying() { FileViewerActivity activity = owner.get(); return activity != null && activity.isPlaying(); }
        @Override public int getBufferPercentage() { return 100; }
        @Override public boolean canPause() { return true; }
        @Override public boolean canSeekBackward() { return getDuration() > 0; }
        @Override public boolean canSeekForward() { return getDuration() > 0; }
        @Override public int getAudioSessionId() {
            FileViewerActivity activity = owner.get();
            try { return activity != null && activity.player != null ? activity.player.getAudioSessionId() : 0; } catch (RuntimeException ignored) { return 0; }
        }
    }
    private static final class NoisyReceiver extends BroadcastReceiver {
        private final WeakReference<FileViewerActivity> owner;
        NoisyReceiver(FileViewerActivity owner) { this.owner = new WeakReference<>(owner); }
        @Override public void onReceive(Context context, Intent intent) {
            FileViewerActivity activity = owner.get();
            if (activity != null && activity.started && !activity.destroyed && !activity.isFinishing()
                    && AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                activity.playWhenReady = false; activity.pausePlayer(); activity.abandonFocus();
            }
        }
    }
    private static final class VideoSurfaceCallbacks implements SurfaceHolder.Callback {
        private final WeakReference<FileViewerActivity> owner;
        VideoSurfaceCallbacks(FileViewerActivity owner) { this.owner = new WeakReference<>(owner); }
        private FileViewerActivity active(SurfaceHolder holder) {
            FileViewerActivity activity = owner.get();
            return activity != null && activity.started && !activity.destroyed && !activity.isFinishing()
                    && activity.surface != null && activity.surface.getHolder() == holder ? activity : null;
        }
        @Override public void surfaceCreated(SurfaceHolder holder) {
            FileViewerActivity activity = active(holder); if (activity == null) return;
            activity.surfaceReady = true;
            if (activity.player != null) try { activity.player.setDisplay(holder); activity.maybeStart(); } catch (RuntimeException invalid) { activity.mediaFailure(); }
        }
        @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { FileViewerActivity activity = active(holder); if (activity != null) activity.layoutVideo(); }
        @Override public void surfaceDestroyed(SurfaceHolder holder) {
            FileViewerActivity activity = active(holder); if (activity == null) return;
            activity.surfaceReady = false; activity.snapshotMedia(); activity.pausePlayer();
            if (activity.player != null) try { activity.player.setDisplay(null); } catch (RuntimeException ignored) { }
        }
    }
    private static final class VideoLayoutListener implements View.OnLayoutChangeListener {
        private final WeakReference<FileViewerActivity> owner;
        VideoLayoutListener(FileViewerActivity owner) { this.owner = new WeakReference<>(owner); }
        @Override public void onLayoutChange(View view, int left, int top, int right, int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
            FileViewerActivity activity = owner.get();
            if (activity != null && activity.started && !activity.destroyed && !activity.isFinishing() && view == activity.mediaAnchor) activity.layoutVideo();
        }
    }
    private static final class MediaCallbacks implements MediaPlayer.OnPreparedListener, MediaPlayer.OnSeekCompleteListener,
            MediaPlayer.OnVideoSizeChangedListener, MediaPlayer.OnCompletionListener, MediaPlayer.OnErrorListener,MediaPlayer.OnInfoListener {
        private final WeakReference<FileViewerActivity> owner;
        MediaCallbacks(FileViewerActivity owner) { this.owner = new WeakReference<>(owner); }
        private FileViewerActivity active(MediaPlayer player) {
            FileViewerActivity activity = owner.get();
            return activity != null && activity.started && !activity.destroyed && !activity.isFinishing() && activity.player == player ? activity : null;
        }
        @Override public void onPrepared(MediaPlayer media) {
            FileViewerActivity activity = active(media); if (activity == null) return;
            activity.prepared = true; activity.ui.removeCallbacks(activity.mediaTimeout); activity.controller.setEnabled(true);
            if(activity.playbackSpeed!=1)try{media.setPlaybackParams(media.getPlaybackParams().setSpeed(activity.playbackSpeed));media.pause();}catch(RuntimeException unsupported){activity.playbackSpeed=1;}
            if(activity.controller instanceof PlaybackControls)((PlaybackControls)activity.controller).speed(activity.playbackSpeed);
            int duration = Math.max(0, media.getDuration()); activity.mediaPosition = Math.min(activity.mediaPosition, Math.max(0, duration - 1));
            activity.videoWidth = media.getVideoWidth(); activity.videoHeight = media.getVideoHeight(); activity.layoutVideo();
            activity.details.setText(activity.metadata() + " · " + duration(duration));
            if (activity.mediaPosition > 0) { activity.seeking = true; media.seekTo((long) activity.mediaPosition, MediaPlayer.SEEK_CLOSEST); }
            else activity.maybeStart();
            activity.showController();
        }
        @Override public void onSeekComplete(MediaPlayer media) { FileViewerActivity activity = active(media); if (activity != null) { activity.seeking = false; activity.maybeStart(); } }
        @Override public void onVideoSizeChanged(MediaPlayer media, int width, int height) {
            FileViewerActivity activity = active(media); if (activity != null) { activity.videoWidth = width; activity.videoHeight = height; activity.layoutVideo(); }
        }
        @Override public void onCompletion(MediaPlayer media) {
            FileViewerActivity activity = active(media); if (activity == null) return;
            activity.playWhenReady = false; activity.mediaCompleted = true; activity.mediaPosition = 0; activity.abandonFocus(); activity.showController();
        }
        @Override public boolean onError(MediaPlayer media, int what, int extra) { FileViewerActivity activity = active(media); if (activity != null) activity.mediaFailure(); return true; }
        @Override public boolean onInfo(MediaPlayer media,int what,int extra){
            FileViewerActivity activity=active(media);
            if(activity!=null&&what==MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START&&activity.previewContent!=null)activity.previewContent.reveal();
            return false;
        }
    }
    private static final class MediaTimeout implements Runnable {
        private final WeakReference<FileViewerActivity> owner;
        MediaTimeout(FileViewerActivity owner) { this.owner = new WeakReference<>(owner); }
        @Override public void run() {
            FileViewerActivity activity = owner.get();
            if (activity != null && activity.started && activity.player != null && !activity.prepared) activity.mediaFailure();
        }
    }

    /** Immutable decoded data only. Evicted bitmaps are reclaimed after the last visible frame releases them. */
    private static final class PreviewData {
        final Bitmap bitmap;
        final android.graphics.Picture picture;
        final android.text.Spanned formatted;
        final PreviewText.Result text;
        final ZipDirectory.Listing archive;
        final int orientation,width,height,page,pageCount,bytes;
        PreviewData(PreviewResult result){bitmap=result.bitmap;picture=result.picture;formatted=result.formatted;text=result.text;archive=result.archive;orientation=result.orientation;
            width=result.width;height=result.height;page=result.page;pageCount=result.pageCount;
            int count=256+result.vectorBytes+(bitmap==null?0:bitmap.getAllocationByteCount())+(text==null?0:text.text.length()*2)+(formatted==null?0:formatted.length()*4);
            if(archive!=null)for(ZipDirectory.Entry entry:archive.entries)count+=64+entry.name.length()*2;
            bytes=count;result.sharedBitmap=true;}
        void copyTo(PreviewResult result){result.bitmap=bitmap;result.picture=picture;result.formatted=formatted;result.text=text;result.archive=archive;result.orientation=orientation;
            result.width=width;result.height=height;result.page=page;result.pageCount=pageCount;result.sharedBitmap=true;result.cached=true;}
    }
    private static final class ViewState {
        int page,mediaPosition,scrollY,archivePosition,archiveTop;
        float zoom=1,centerX=.5f,centerY=.5f;
        boolean playing,completed,readerSource;
    }
    private static final class PreviewResult implements AutoCloseable {
        Uri uri;
        Bitmap bitmap;
        android.graphics.Picture picture;
        android.text.Spanned formatted;
        int vectorBytes;
        ParcelFileDescriptor media;
        PreviewText.Result text;
        ZipDirectory.Listing archive;
        int orientation = 1, width, height, page, pageCount;
        long size = -1;
        long cpuNanos;
        String error,key,identity;
        boolean sharedBitmap,cached;
        ViewState state;
        @Override public void close() {
            if (bitmap != null) { if (!sharedBitmap&&!bitmap.isRecycled()) bitmap.recycle(); bitmap = null; }
            if (media != null) { try { media.close(); } catch (IOException ignored) { } media = null; }
            picture=null;formatted=null;text = null; archive = null;
        }
    }

    private static final class PreviewJob implements Runnable {
        final WeakReference<FileViewerActivity> owner;
        final Context context;
        Uri uri;
        FileEntry entry;
        boolean allowRoot,restoreState;
        final String sourcePath;
        final Kind kind;
        final int requestedPage, generation;
        final AtomicBoolean cancelled = new AtomicBoolean();
        final CancellationSignal signal = new CancellationSignal();
        PreviewJob(FileViewerActivity owner, Context context, Uri uri, Kind kind, int page, int generation) {
            this.owner = new WeakReference<>(owner); this.context = context; this.uri = uri; this.kind = kind; requestedPage = page; this.generation = generation;
            sourcePath=owner==null?String.valueOf(uri):owner.path;allowRoot=owner!=null&&owner.session!=null&&owner.session.root;
        }
        void cancel() { cancelled.set(true); signal.cancel(); }
        @Override public void run() {
            PreviewResult result = read();
            if (cancelled.get() || Thread.currentThread().isInterrupted()) { result.close(); return; }
            if (!MAIN.post(new Delivery(owner, result, generation, cancelled))) result.close();
        }
        PreviewResult read() {
            PreviewResult result = new PreviewResult(); ParcelFileDescriptor file = null;long cpuStart=android.os.Debug.threadCpuTimeNanos();
            try {
                checkCancelled();
                result.uri=uri;
                if(kind==Kind.OTHER){result.error="Preview is unavailable for this file type. Use Open to choose another app.";return result;}
                file = uri==null&&entry!=null?SafeFileAccess.open(context,entry.path(),allowRoot):context.getContentResolver().openFileDescriptor(uri, "r", signal);
                if (file == null) throw new IOException("File access is unavailable.");
                checkCancelled(); result.size = file.getStatSize();
                if (result.size < 0) throw new IOException("This file does not support a seekable preview.");
                result.identity=RootFileProvider.identity(Os.fstat(file.getFileDescriptor()));
                result.key=sourcePath+'\0'+allowRoot+':'+result.identity;
                boolean preciseMetadata=Build.VERSION.SDK_INT>=27;
                if(restoreState&&preciseMetadata)synchronized(VIEW_STATES){result.state=VIEW_STATES.get(result.key);}
                int wantedPage=result.state==null?requestedPage:result.state.page;
                String cacheKey=result.key+':'+kind+':'+(kind==Kind.PDF?wantedPage:0);
                // API 26 exposes only whole-second metadata; re-read rather than reuse an ambiguous identity.
                PreviewData existing=preciseMetadata?CACHE.get(cacheKey):null;
                if(existing!=null){existing.copyTo(result);return result;}
                if (kind == Kind.IMAGE || kind == Kind.PDF) {
                    if (result.size > MAX_DOCUMENT_BYTES) throw new PreviewLimit("Files larger than 128 MiB need another app. Use Open to continue.");
                    if (kind == Kind.IMAGE) decodeImage(file, result); else renderPdf(file, result,wantedPage);
                } else if (kind == Kind.SVG) {
                    try(ParcelFileDescriptor.AutoCloseInputStream input=new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(file.getFileDescriptor()))){
                        SvgPreview.Result vector=SvgPreview.read(input);result.picture=vector.picture;result.vectorBytes=vector.source.length*32;
                        result.width=vector.picture.getWidth();result.height=vector.picture.getHeight();result.text=PreviewText.read(new java.io.ByteArrayInputStream(vector.source));}
                } else if (kind == Kind.TEXT || kind==Kind.MARKDOWN || kind==Kind.HTML) {
                    try (ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(file.getFileDescriptor()))) { result.text = PreviewText.read(input); }
                    checkCancelled();if(kind==Kind.MARKDOWN)result.formatted=RichText.markdown(result.text.text);else if(kind==Kind.HTML)result.formatted=RichText.html(result.text.text);
                } else if (kind == Kind.ZIP) {
                    try (ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(file.getFileDescriptor()))) { result.archive = ZipDirectory.read(input.getChannel()); }
                } else { result.media = file; file = null; }
                checkCancelled();
                if(file!=null){
                    if(!result.identity.equals(RootFileProvider.identity(Os.fstat(file.getFileDescriptor())))){throw new IOException("File changed while loading.");}
                    if(preciseMetadata)CACHE.put(cacheKey,new PreviewData(result));
                }
            } catch (StackOverflowError complex) {
                result.close();result.error="This file is too complex to preview. Use Open to continue.";
            } catch (OutOfMemoryError exhausted) {
                result.close(); result.error = "This file needs too much memory to preview. Use Open to choose another app.";
            } catch (SecurityException denied) {
                result.close(); result.error = kind == Kind.PDF ? "This PDF is protected or cannot be rendered. Use Open to choose another app."
                        : "File access expired or was denied. Return to results and open it again.";
            } catch (Exception failure) {
                result.close();
                if (failure instanceof PreviewLimit || (failure instanceof IOException && (kind == Kind.TEXT || kind == Kind.ZIP || kind==Kind.SVG || kind==Kind.MARKDOWN || kind==Kind.HTML)))
                    result.error = failure.getMessage();
                else result.error = "Could not preview this file. It may have changed, or its format may be unsupported. Use Open to choose another app.";
                if (result.error == null || result.error.length() > 240) result.error = "Could not read this file. Use Open to choose another app.";
            } finally { if (file != null) try { file.close(); } catch (IOException ignored) { }result.cpuNanos=android.os.Debug.threadCpuTimeNanos()-cpuStart; }
            return result;
        }

        private void decodeImage(ParcelFileDescriptor file, PreviewResult result) throws Exception {
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFileDescriptor(file.getFileDescriptor(), null, bounds);
            checkCancelled();
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new PreviewLimit("This image format is unsupported on this device. Use Open to choose another app.");
            result.width = bounds.outWidth; result.height = bounds.outHeight;
            try {
                Os.lseek(file.getFileDescriptor(), 0, OsConstants.SEEK_SET);
                ExifInterface exif = new ExifInterface(file.getFileDescriptor());
                result.orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
            } catch (IOException | RuntimeException unsupportedExif) { result.orientation = 1; }
            checkCancelled(); Os.lseek(file.getFileDescriptor(), 0, OsConstants.SEEK_SET);
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inPreferredConfig = Bitmap.Config.ARGB_8888; options.inScaled = false;
            options.inSampleSize = 1;
            while ((long) Math.ceil(bounds.outWidth / (double) options.inSampleSize) * (long) Math.ceil(bounds.outHeight / (double) options.inSampleSize) > MAX_BITMAP_PIXELS
                    || Math.ceil(bounds.outWidth / (double) options.inSampleSize) > MAX_BITMAP_EDGE || Math.ceil(bounds.outHeight / (double) options.inSampleSize) > MAX_BITMAP_EDGE) {
                if (options.inSampleSize > (1 << 28)) throw new PreviewLimit("This image is too large to preview.");
                options.inSampleSize *= 2;
            }
            result.bitmap = BitmapFactory.decodeFileDescriptor(file.getFileDescriptor(), null, options);
            checkCancelled();
            if (result.bitmap == null) throw new PreviewLimit("This image format is unsupported on this device. Use Open to choose another app.");
            if ((long) result.bitmap.getWidth() * result.bitmap.getHeight() > MAX_BITMAP_PIXELS
                    || result.bitmap.getWidth() > MAX_BITMAP_EDGE || result.bitmap.getHeight() > MAX_BITMAP_EDGE
                    || result.bitmap.getAllocationByteCount() > MAX_BITMAP_PIXELS * 4L) throw new PreviewLimit("This image needs another app to preview.");
        }

        private void renderPdf(ParcelFileDescriptor file, PreviewResult result,int wantedPage) throws Exception {
            checkCancelled();
            renderPdfPage(file, result, wantedPage);
            checkCancelled();
        }
        private void checkCancelled() throws IOException {
            if (cancelled.get()) throw new IOException("Preview stopped."); PreviewText.cancelled();
        }
    }

    private static void renderPdfPage(ParcelFileDescriptor file, PreviewResult result, int wantedPage) throws Exception {
        // Older PdfRenderer constructors leave their input open on failure.
        try (ParcelFileDescriptor input = ParcelFileDescriptor.dup(file.getFileDescriptor());
             PdfRenderer renderer = new PdfRenderer(input)) {
            PreviewText.cancelled();
            result.pageCount = renderer.getPageCount();
            if (result.pageCount <= 0) throw new PreviewLimit("This PDF has no pages.");
            result.page = Math.max(0, Math.min(wantedPage, result.pageCount - 1));
            try (PdfRenderer.Page pdf = renderer.openPage(result.page)) {
                int width = pdf.getWidth(), height = pdf.getHeight();
                if (width <= 0 || height <= 0) throw new PreviewLimit("This PDF page cannot be rendered.");
                float scale = Math.min(MAX_PDF_EDGE / (float) Math.max(width, height),
                        (float) Math.sqrt(MAX_BITMAP_PIXELS / ((double) width * height)));
                int bitmapWidth = Math.max(1, (int) (width * scale)), bitmapHeight = Math.max(1, (int) (height * scale));
                result.bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888);
                result.bitmap.eraseColor(Color.WHITE);
                pdf.render(result.bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                PreviewText.cancelled();
            }
        }
    }

    /** Codec/descriptor checks without launching an Activity or touching a user's files. */
    static PreviewCheck checkForProbe(Context context, Uri uri, String name, int page) {
        PreviewJob task = new PreviewJob(null, context.getApplicationContext(), uri, kindFor(name, mimeFor(name)), page, 0);
        try (PreviewResult result = task.read()) { return new PreviewCheck(result); }
    }
    static final class PreviewCheck {
        final String error, text;
        final int width, height, bitmapWidth, bitmapHeight, orientation, page, pageCount, archiveEntries;
        final boolean truncated, mediaDescriptor;
        PreviewCheck(PreviewResult result) {
            error = result.error; text = result.text == null ? null : result.text.text;
            width = result.width; height = result.height; orientation = result.orientation; page = result.page; pageCount = result.pageCount;

            bitmapWidth = result.bitmap == null ? 0 : result.bitmap.getWidth(); bitmapHeight = result.bitmap == null ? 0 : result.bitmap.getHeight();
            archiveEntries = result.archive == null ? 0 : result.archive.entries.size();
            truncated = result.text != null && result.text.truncated; mediaDescriptor = result.media != null && result.media.getFileDescriptor().valid();
        }
    }

    private static final class Delivery implements Runnable {
        final WeakReference<FileViewerActivity> owner;
        final PreviewResult result;
        final int generation;
        final AtomicBoolean cancelled;
        Delivery(WeakReference<FileViewerActivity> owner, PreviewResult result, int generation, AtomicBoolean cancelled) {
            this.owner = owner; this.result = result; this.generation = generation; this.cancelled = cancelled;
        }
        @Override public void run() {
            FileViewerActivity activity = owner.get();
            if (activity == null || cancelled.get()) result.close(); else activity.accept(result, generation);
        }
    }
    private static final class PreviewLimit extends IOException {
        PreviewLimit(String message) { super(message); }
    }
}
