package dev.fastsearch.android;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
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
import java.io.File;
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
    private FileHoldActions previewActions;
    private AppSheet activeSheet;
    private MenuDialog languageMenu, previewSelectionMenu;
    private FolderPathDialog extractFolderPicker;
    private boolean sharingArchiveEntry;
    private static final int MAX_ARCHIVE_IMAGE_BYTES = 16 * 1024 * 1024;
    private boolean readerSource, plainTextOverride;
    private CodeSyntax.Language readerLanguage;
    private int readerSize=16;
    private float playbackSpeed=1;
    static final int MAX_BITMAP_EDGE = 2048, MAX_BITMAP_PIXELS = 4 * 1024 * 1024;
    private static final int MAX_PDF_EDGE=1536;
    static final long MAX_DOCUMENT_BYTES = 128L * 1024 * 1024;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ThreadPoolExecutor PREVIEWS = createExecutor();
    static final String EXTRA_MINIMIZED = "preview.minimized", EXTRA_REVEAL = "preview.reveal";
    private static final String EXTRA_RESTORE = "preview.restore";
    private static final String EXTRA_NAME = "preview.name", EXTRA_PATH = "preview.path", EXTRA_SIZE = "preview.size";
    private enum Kind { IMAGE, SVG, VIDEO, AUDIO, PDF, TEXT, MARKDOWN, HTML, OFFICE, ZIP, STREAM_ARCHIVE, SEVEN_Z, APK, DEX, OTHER }
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
    private ImageButton location, focusAction, moreAction;
    private View formatGuideTarget, mediaLoading;
    private SpotlightTour viewerGuide;
    static final String EXTRA_OTHER_PANE="preview.otherPane";
    private String otherPaneTarget;
    private boolean viewerTransfer,viewerTransferSeen,viewerMove;
    private final FileOperationService.Listener viewerTransferListener=this::viewerTransferStatus;
    private View viewerIdentity, navigationDock;
    private boolean focusMode;
    private Bundle minimizeResult;
    private String revealResult;
    private LinearLayout fileNavigation;
    private ImageButton previousFile, nextFile, selectFile, copyFile, moveFile, deleteSelected;
    private View navigationPlaceholder;
    private View loadingPlaceholder;
    private LinearLayout selectedStrip;
    private HorizontalScrollView selectedScroll;
    private LinearLayout selectedTray;
    private ProgressBar selectionProgress;
    private boolean selectionBusy, pasteAfterPreview, extracting;
    private final ThreadPoolExecutor actions = new ThreadPoolExecutor(1,1,15,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(2), task -> new Thread(task,"preview-file-action"),new ThreadPoolExecutor.AbortPolicy());

    public static Intent intent(Context context, Uri uri, String name, String path, long size) {
        return new Intent(context, FileViewerActivity.class).setData(uri)
                .putExtra(EXTRA_NAME, name).putExtra(EXTRA_PATH, path).putExtra(EXTRA_SIZE, size);
    }

    /** Only locally opened file metadata is returned. No URI, descriptor, View or bitmap is docked. */
    static Intent restoreIntent(Context context, Bundle state) {
        return intent(context,null,state.getString("fileName"),state.getString("filePath"),state.getLong("fileSize",-1))
                .putExtra(PreviewSession.EXTRA,state.getString(PreviewSession.EXTRA))
                .putExtra(EXTRA_RESTORE,new Bundle(state));
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
        previewActions=new FileHoldActions(theme,(x,y)->null);
        if(saved==null)saved=getIntent().getBundleExtra(EXTRA_RESTORE);
        getIntent().removeExtra(EXTRA_RESTORE);
        focusMode=saved!=null&&saved.getBoolean("focusMode",false);
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
            if(session!=null){
                int restoredPosition=saved.getInt("filePosition",session.position);
                if(restoredPosition>=0&&restoredPosition<session.entries.size()
                        &&session.entries.get(restoredPosition).pathEquals(path))session.position=restoredPosition;
                else session=null;
            }
            page = Math.max(0, saved.getInt("page", 0));
            mediaPosition = Math.max(0, saved.getInt("mediaPosition", 0));
            playWhenReady = saved.getBoolean("playing", false);
            mediaCompleted = saved.getBoolean("completed", false);
            zoom = saved.getFloat("zoom", 1); centerX = saved.getFloat("centerX", .5f); centerY = saved.getFloat("centerY", .5f);
            scrollY = Math.max(0, saved.getInt("scrollY", 0));
            archivePosition = Math.max(0, saved.getInt("archivePosition", 0)); archiveTop = saved.getInt("archiveTop", 0);
        }
        mime=mimeFor(name);kind=kindFor(name,mime);
        if(saved!=null && saved.getString("readerLanguage")!=null)
            try{readerLanguage=CodeSyntax.Language.valueOf(saved.getString("readerLanguage"));}
            catch(IllegalArgumentException ignored){}
        plainTextOverride=saved!=null&&saved.getBoolean("plainTextOverride")&&canReadAsText();
        if(plainTextOverride)kind=Kind.TEXT;
        if(saved!=null&&uri==null&&session==null&&!path.isEmpty()){
            session=PreviewSession.create(java.util.Collections.singletonList(new FileEntry(path,false,size,-1)),path,saved.getBoolean("fileRoot",false));
            getIntent().putExtra(PreviewSession.EXTRA,session.id);
        }
        otherPaneTarget=getIntent().getStringExtra(EXTRA_OTHER_PANE);
        buildUi();
        if(Build.VERSION.SDK_INT>=34){overrideActivityTransition(OVERRIDE_TRANSITION_OPEN,R.anim.preview_enter,0);
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE,0,R.anim.preview_exit);}
        else overridePendingTransition(R.anim.preview_enter,0);
        if(Build.VERSION.SDK_INT>=33)getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,this::handleBack);
    }

    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed(){handleBack();}
    private void handleBack(){if(previewActions!=null&&previewActions.isOpen()){previewActions.stop();return;}if(viewerGuide!=null){stopViewerGuide();return;}if(focusMode){setFocusMode(false);return;}if(session!=null&&!session.selected.isEmpty()){session.selected.clear();updateFileControls();}else finish();}
    @Override public boolean dispatchKeyEvent(KeyEvent event){int key=event.getKeyCode();
        if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0&&!selectionBusy&&viewerGuide==null){
            if(key==KeyEvent.KEYCODE_ESCAPE&&focusMode){setFocusMode(false);return true;}
            if(event.isAltPressed()&&(key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT)){
                navigate(key==KeyEvent.KEYCODE_DPAD_LEFT?-1:1);return session!=null&&session.entries.size()>1;
            }
        }
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
        super.onStart(); started = true;thumbnails.resume();FileOperationService.addListener(viewerTransferListener);
        {
            IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, filter, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(noisy, filter);
            noisyRegistered = true;
        }
        load();
    }

    @Override protected void onStop() {
        if(languageMenu!=null)languageMenu.dismiss();
        if(previewActions!=null)previewActions.stop();
        FileOperationService.removeListener(viewerTransferListener);stopViewerGuide();thumbnails.pause();
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
        if(previewSelectionMenu!=null)previewSelectionMenu.dismiss();
        if(previewActions!=null)previewActions.stop();
        stopViewerGuide();
        if (activeSheet != null) activeSheet.dismiss();
        destroyed = true; cancelLoad(); releaseMedia(); clearShown(); ui.removeCallbacksAndMessages(null);
        actions.shutdownNow();thumbnails.close();stage.animate().cancel();
        if(isFinishing())ArchivePreviewSources.release(uri);
        if(session!=null&&isFinishing()&&minimizeResult==null){String id=session.id;MAIN.postDelayed(()->PreviewSession.release(id),60_000);}
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        captureViewerState(out);
        out.putString("fileUri",uri==null?null:uri.toString());
        super.onSaveInstanceState(out);
    }
    private void captureViewerState(Bundle out) {
        rememberView(); snapshotMedia();
        out.putString("fileName",name);out.putString("filePath",path);out.putLong("fileSize",size);
        out.putBoolean("focusMode",focusMode);
        out.putBoolean("plainTextOverride",plainTextOverride);
        if(readerLanguage!=null)out.putString("readerLanguage",readerLanguage.name());
        out.putBoolean("fileRoot",session!=null&&session.root);
        if(session!=null)out.putInt("filePosition",session.position);
        out.putFloat("playbackSpeed",playbackSpeed);out.putFloat("brightness",getWindow().getAttributes().screenBrightness);out.putBoolean("readerSource",readerSource);out.putInt("page", page); out.putInt("mediaPosition", mediaPosition); out.putBoolean("playing", playWhenReady);
        out.putBoolean("completed", mediaCompleted);
        out.putFloat("zoom", zoom); out.putFloat("centerX", centerX); out.putFloat("centerY", centerY);
        out.putInt("scrollY", scrollY); out.putInt("archivePosition", archivePosition); out.putInt("archiveTop", archiveTop);
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
        LinearLayout bar=row();bar.setPadding(dp(4),dp(2),dp(4),dp(2));bar.setMinimumHeight(dp(52));
        ImageButton back=actionIcon(R.drawable.ic_back,"Back to files",View.NO_ID);
        back.setOnClickListener(view -> handleBack()); bar.addView(back);
        LinearLayout identity = new LinearLayout(this); viewerIdentity=identity;identity.setOrientation(LinearLayout.VERTICAL);
        identity.setPadding(dp(4),dp(2),dp(4),dp(2));
        title=text(name,15,INK);title.setTypeface(getResources().getFont(R.font.inter_semibold));
        title.setSingleLine(true);title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        title.setOnLongClickListener(view->{selectCurrent();return true;});
        identity.addView(title);
        details=text(metadata(),11,MUTED);details.setSingleLine(true);details.setEllipsize(TextUtils.TruncateAt.END);
        identity.addView(details); bar.addView(identity,new LinearLayout.LayoutParams(0,-2,1));
        location=actionIcon(R.drawable.ic_folder,"Show full path",R.id.preview_path);
        location.setTooltipText("File location");location.setOnClickListener(view->showPath());
        bar.addView(location);
        focusAction=actionIcon(R.drawable.ic_preview_focus,"Enter focus mode",R.id.preview_focus);
        focusAction.setOnClickListener(view->setFocusMode(!focusMode));bar.addView(focusAction);
        moreAction=actionIcon(R.drawable.ic_more,"More file actions",View.NO_ID);
        moreAction.setOnClickListener(view->showFileActions(view));bar.addView(moreAction);root.addView(bar,new LinearLayout.LayoutParams(-1,-2));
        notice=text("",12,MUTED);notice.setPadding(dp(12),dp(6),dp(12),dp(6));
        notice.setBackgroundColor(CHROME);notice.setVisibility(View.GONE);
        notice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(notice,new LinearLayout.LayoutParams(-1,-2));
        FrameLayout viewport=new FrameLayout(this);
        stage = new PreviewStage();stage.setId(R.id.preview_stage);stage.setBackgroundColor(stageColor(kind));viewport.addView(stage,new FrameLayout.LayoutParams(-1,-1));
        root.addView(viewport,new LinearLayout.LayoutParams(-1,0,1));
        selectionProgress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);selectionProgress.setIndeterminate(true);selectionProgress.setVisibility(View.GONE);
        root.addView(selectionProgress,new LinearLayout.LayoutParams(-1,dp(2)));
        selectedScroll=new HorizontalScrollView(this);selectedScroll.setOnScrollChangeListener((v,x,y,oldX,oldY)->thumbnails.viewportChanged());selectedScroll.setHorizontalScrollBarEnabled(false);selectedScroll.setBackgroundColor(CHROME);
        selectedStrip=row();selectedStrip.setPadding(dp(6),0,dp(6),0);selectedScroll.addView(selectedStrip);
        selectedTray=row();selectedTray.setBackgroundColor(CHROME);
        selectedTray.addView(selectedScroll,new LinearLayout.LayoutParams(0,dp(48),1));
        deleteSelected=actionIcon(R.drawable.ic_delete,"Delete selected files",R.id.preview_delete_selected);
        deleteSelected.setOnClickListener(view->deleteSelectedFiles());
        selectedTray.addView(deleteSelected,new LinearLayout.LayoutParams(dp(48),dp(48)));
        root.addView(selectedTray,new LinearLayout.LayoutParams(-1,dp(48)));
        LinearLayout navigation=row();navigation.setPadding(dp(4),0,dp(4),0);navigation.setGravity(Gravity.CENTER_VERTICAL|Gravity.END);
        fileNavigation=row();navigation.addView(fileNavigation);
        previousFile=actionIcon(R.drawable.ic_viewer_previous,"Previous file",R.id.preview_previous);
        previousFile.setOnClickListener(view->navigate(-1));fileNavigation.addView(previousFile);
        filePosition=text("",11,MUTED);filePosition.setGravity(Gravity.CENTER);filePosition.setSingleLine(true);filePosition.setEllipsize(TextUtils.TruncateAt.END);
        fileNavigation.addView(filePosition,new LinearLayout.LayoutParams(-2,dp(48)));
        nextFile=actionIcon(R.drawable.ic_viewer_next,"Next file",R.id.preview_next);
        nextFile.setOnClickListener(view->navigate(1));fileNavigation.addView(nextFile);
        navigation.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
        selectFile=actionIcon(R.drawable.ic_select,"Select file",R.id.preview_select);selectFile.setOnClickListener(view->toggleCurrent());navigation.addView(selectFile);
        copyFile=actionIcon(R.drawable.ic_copy,"Copy files",R.id.preview_copy);copyFile.setOnClickListener(view->prepareClipboard(false));navigation.addView(copyFile);
        moveFile=actionIcon(R.drawable.ic_cut,"Move files",R.id.preview_move);moveFile.setOnClickListener(view->prepareClipboard(true));navigation.addView(moveFile);
        LinearLayout bottom=new LinearLayout(this);bottom.setOrientation(LinearLayout.VERTICAL);navigationDock=bottom;
        bottom.addView(viewerRule(),new LinearLayout.LayoutParams(-1,dp(1)));
        HorizontalScrollView dock=new HorizontalScrollView(this);dock.setFillViewport(true);dock.setHorizontalScrollBarEnabled(false);
        dock.addView(navigation,new FrameLayout.LayoutParams(-2,-2));bottom.addView(dock);root.addView(bottom);
        updateFileControls();setFocusMode(focusMode);
    }

    private void setFocusMode(boolean enabled){
        focusMode=enabled;
        viewerIdentity.setVisibility(enabled?View.INVISIBLE:View.VISIBLE);
        location.setVisibility(enabled?View.GONE:View.VISIBLE);
        navigationDock.setVisibility(enabled?View.GONE:View.VISIBLE);
        selectedTray.setVisibility(!enabled&&session!=null&&!session.selected.isEmpty()?View.VISIBLE:View.GONE);
        focusAction.setImageResource(enabled?R.drawable.ic_preview_exit_focus:R.drawable.ic_preview_focus);
        String action=enabled?"Exit focus mode":"Enter focus mode";
        focusAction.setContentDescription(action);focusAction.setTooltipText(action);focusAction.setSelected(enabled);
    }
    private boolean canReturnFile(){return session!=null&&!session.entries.isEmpty()&&path.startsWith("/")&&!selectionBusy;}
    private void minimize(){
        if(!canReturnFile())return;
        Bundle state=new Bundle();captureViewerState(state);
        state.putString(PreviewSession.EXTRA,session.id);
        // A minimized player must not start audio while the user is browsing or restoring it.
        state.putBoolean("playing",false);
        minimizeResult=state;finish();
    }
    private void revealFile(){
        if(!canReturnFile())return;
        revealResult=path;finish();
    }

    private ImageButton actionIcon(int resource,String label,int id){ImageButton button=new ImageButton(this,null,0);button.setId(id);button.setImageResource(resource);
        button.setImageTintList(android.content.res.ColorStateList.valueOf(INK));button.setContentDescription(label);button.setTooltipText(label);
        button.setBackground(controlBackground(Color.TRANSPARENT,12));button.setPadding(dp(14),dp(14),dp(14),dp(14));
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(48),dp(48)));return button;}
    private View viewerRule(){View view=new View(this);view.setBackgroundColor(theme.line);return view;}
    private GradientDrawable panel(int color,int radius) {
        GradientDrawable background=AppSheet.shape(color,dp(radius)); background.setStroke(dp(1),theme.line);return background;
    }
    private RippleDrawable controlBackground(int fill,int radius) {
        return new RippleDrawable(ColorStateList.valueOf(theme.line),AppSheet.shape(fill,dp(radius)),AppSheet.shape(Color.WHITE,dp(radius)));
    }
    private void showFileActions(View anchor) {
        android.view.Menu menu=theme.popup(this,anchor).getMenu();
        boolean toPane=availablePeerTarget(),hasPath=!path.isEmpty();
        menu.add(0,3,0,"Share file");
        menu.add(0,R.id.preview_copy,1,toPane?"Copy to other pane":"Copy file").setEnabled(hasPath&&!selectionBusy&&!viewerTransfer);
        menu.add(0,R.id.preview_move,2,toPane?"Move to other pane":"Move file").setEnabled(hasPath&&!selectionBusy&&!viewerTransfer);
        menu.add(0,5,3,"Properties");
        if(ArchiveIntegrity.supports(name))
            menu.add(0,ArchiveIntegrity.MENU_ID,4,"Test archive integrity").setEnabled(uri!=null||session!=null);
        menu.add(0,R.id.preview_reveal,4,"Show in Anything Files").setVisible(session!=null).setEnabled(canReturnFile());
        menu.add(0,1,5,"Open in another app").setVisible(kind!=Kind.OFFICE||shown==null||shown.office==null)
                .setEnabled(uri!=null||session!=null);
        menu.add(0,R.id.preview_minimize,6,"Minimize preview").setVisible(session!=null).setEnabled(canReturnFile());
        menu.add(0,2,7,"Rename").setEnabled(hasPath&&!selectionBusy&&!viewerTransfer);
        menu.add(0,R.id.preview_delete,8,"Delete file").setEnabled(hasPath&&!selectionBusy&&!viewerTransfer);
        if(session!=null&&!session.selected.isEmpty())
            menu.add(0,R.id.preview_delete_selected,9,"Delete selected").setEnabled(canDeleteSelected());
        menu.add(0,R.id.preview_paste,9,"Paste into folder").setVisible(FileClipboard.count(this)>0);
        menu.add(0,4,10,"Viewer guide");
        FileEntry entry=currentEntry();
        boolean localHex=session!=null&&!entry.directory&&path.startsWith("/")&&entry.pathEquals(path);
        if(localHex)menu.add(0,R.id.preview_hex,11,HexInspectionSheet.LABEL);
        previewActions.begin(new FileHoldActions.Target(anchor,entry,false,false,menu,label->{
            int itemId=0;
            for(int i=0;i<menu.size();i++){android.view.MenuItem item=menu.getItem(i);
                if(item.isEnabled()&&item.isVisible()&&label.contentEquals(item.getTitle())){itemId=item.getItemId();break;}}
            if(itemId==1)openExternal();
            else if(itemId==2)renameCurrent();
            else if(itemId==R.id.preview_reveal)revealFile();
            else if(itemId==R.id.preview_minimize)minimize();
            else if(itemId==R.id.preview_delete)deleteCurrent();
            else if(itemId==R.id.preview_delete_selected)deleteSelectedFiles();
            else if(itemId==3)sendCurrentFile(true);
            else if(itemId==4)stage.post(this::showViewerGuide);
            else if(itemId==5)showProperties();
            else if(itemId==ArchiveIntegrity.MENU_ID)testArchiveIntegrity();
            else if(itemId==R.id.preview_hex&&localHex)showHex(entry);
            else if(itemId==R.id.preview_copy)fileMenuTransfer(false);
            else if(itemId==R.id.preview_move)fileMenuTransfer(true);
            else if(itemId==R.id.preview_paste){pasteAfterPreview=true;finish();}
        }));
    }
    private void showHex(FileEntry entry){
        if(!entry.pathEquals(path))return;
        if(activeSheet!=null)activeSheet.dismiss();
        HexInspectionSheet inspect=new HexInspectionSheet(this,entry,actions,closed->{if(activeSheet==closed)activeSheet=null;});
        activeSheet=inspect.dialog();inspect.show();
    }
    private void testArchiveIntegrity(){
        if(!ArchiveIntegrity.supports(name)||uri==null&&session==null)return;
        if(activeSheet!=null)activeSheet.dismiss();
        activeSheet=ArchiveIntegrityDialog.open(this,actions,uri,path,session!=null&&session.root,
                shown==null?null:shown.identity,name,sheet->{if(activeSheet==sheet)activeSheet=null;});
    }
    private void showProperties(){
        if(activeSheet!=null)activeSheet.dismiss();
        activeSheet=FileProperties.show(this,currentEntry(),path);
    }
    private boolean availablePeerTarget(){
        if(otherPaneTarget==null||!canReturnFile()||"/".equals(otherPaneTarget))return false;
        return !otherPaneTarget.equals(new java.io.File(path).getParent());
    }
    private void fileMenuTransfer(boolean move){
        if(selectionBusy||viewerTransfer)return;
        if(!availablePeerTarget()){prepareClipboard(move,true);return;}
        FileEntry entry=currentEntry();String target=otherPaneTarget;boolean useRoot=session.root;
        if(activeSheet!=null)activeSheet.dismiss();
        AppSheet sheet=new AppSheet(this,move?"Move to other pane?":"Copy to other pane?");activeSheet=sheet;
        sheet.content.addView(sheet.text(entry.name+"\n\nDestination\n"+target+"\n\nExisting files won’t be replaced.",14,INK));
        sheet.action("Cancel",false,false,null);
        sheet.action(move?"Move here":"Copy here",true,false,()->performPeerTransfer(entry,target,move,useRoot));sheet.show();
    }
    private void performPeerTransfer(FileEntry entry,String target,boolean move,boolean useRoot){
        if(selectionBusy||viewerTransfer||FileOperationService.getState().running)return;
        selectionBusy=true;updateFileControls();Context context=getApplicationContext();
        try{actions.execute(()->{
            try{
                FileClipboard.Snapshot snapshot=FileClipboard.capture(context,java.util.Collections.singletonList(entry),move,useRoot);
                ui.post(()->{
                    selectionBusy=false;
                    if(destroyed||isFinishing()||!started)return;
                    viewerTransfer=true;viewerTransferSeen=false;viewerMove=move;updateFileControls();
                    try{FileOperationService.transfer(this,target,snapshot);}
                    catch(Exception failure){viewerTransfer=false;updateFileControls();showTransferNotice(failure.getMessage());}
                });
            }catch(Exception failure){ui.post(()->{
                selectionBusy=false;
                if(!destroyed&&!isFinishing()){updateFileControls();showTransferNotice(failure.getMessage());}
            });}
        });}catch(java.util.concurrent.RejectedExecutionException busy){
            selectionBusy=false;updateFileControls();showTransferNotice("File access is busy.");
        }
    }
    private void viewerTransferStatus(FileOperationService.State state){
        if(!viewerTransfer||destroyed||isFinishing())return;
        if(state.running){viewerTransferSeen=true;showTransferNotice(state.status);return;}
        if(!viewerTransferSeen)return;
        viewerTransfer=false;updateFileControls();
        if(state.error!=null&&!state.error.isEmpty()){showTransferNotice(state.error);return;}
        showTransferNotice(state.status);
        if(viewerMove&&!state.cancelled&&state.completed>0){
            Toast.makeText(this,"Moved to the other pane",Toast.LENGTH_SHORT).show();finish();
        }
    }
    private void showTransferNotice(String message){
        notice.setText(message==null?"The file operation could not be completed.":message);notice.setVisibility(View.VISIBLE);
    }
    private void showViewerGuide(){
        if(destroyed||isFinishing()||!started)return;
        stopViewerGuide();setFocusMode(false);
        SpotlightTour.Step focus=new SpotlightTour.Step("Give the file more room","Focus mode hides the file controls. Back exits focus without closing the preview.",()->focusAction);
        SpotlightTour.Step original=new SpotlightTour.Step("Find the original file","Tap the location for the full path. When opened from Files or Search, you can jump back to the file.",()->location);
        SpotlightTour.Step more=new SpotlightTour.Step("More file actions","Tap these three dots for actions on the current file. For JPGs, the actions open in two columns with the current file's details.",()->moreAction);
        ArrayList<SpotlightTour.Step> quick=new ArrayList<>();
        ArrayList<SpotlightTour.Topic> topics=new ArrayList<>();
        quick.add(focus);
        boolean multiple=session!=null&&session.entries.size()>1;
        SpotlightTour.Step neighbors=new SpotlightTour.Step("Browse neighboring files","Use the arrows or swipe across an unzoomed preview. When zoomed in, dragging pans the image instead.",()->fileNavigation);
        if(multiple)quick.add(neighbors);
        ArrayList<SpotlightTour.Step> format=new ArrayList<>();
        if(controller instanceof PlaybackControls&&prepared){
            controller.show(0);
            format.add(new SpotlightTour.Step("Play and pause","Tap the preview to show the playback controls. Play, pause or skip 10 seconds at a time.",
                    ()->controller instanceof PlaybackControls?((PlaybackControls)controller).playTarget():null));
            format.add(new SpotlightTour.Step("Seek to a spot","Drag the timeline to move to a different point. The labels show elapsed time and duration.",()->findViewById(R.id.media_seek)));
            format.add(new SpotlightTour.Step("Adjust playback","Use the speed and volume controls."+(kind==Kind.VIDEO?" The brightness control applies to this preview.":""),()->findViewById(R.id.media_speed)));
        }else if(kind==Kind.PDF){
            format.add(new SpotlightTour.Step("Change PDF pages","The arrows below the page go to the previous or next page. The page count is shown between them.",()->formatGuideTarget));
            format.add(new SpotlightTour.Step("Read the fine print","Pinch or double-tap to zoom into the page, then drag to pan.",()->image));
        }else if(kind==Kind.OFFICE&&shown!=null&&shown.slide!=null){
            format.add(new SpotlightTour.Step("Move between slides","The buttons below the slide go to the previous or next slide.",()->formatGuideTarget));
            format.add(new SpotlightTour.Step("Read the slide text","Pinch or double-tap to zoom. Tap Slide text for a selectable text version.",()->viewerGuideControl(previewContent,readerSource?"Slide view":"Slide text")));
        }else if(image!=null){
            format.add(new SpotlightTour.Step("Fit the image","The middle button resets zoom. Use − or +, pinch or double-tap to zoom, then drag to pan.",()->formatGuideTarget));
            if(kind==Kind.SVG)format.add(new SpotlightTour.Step("Read the SVG source","Tap Source below the image to inspect its text. Tap Image to return.",()->viewerGuideControl(previewContent,"Source")));
        }else if(kind==Kind.OFFICE&&textScroll!=null){
            format.add(new SpotlightTour.Step("Read an Office summary","Read the document text, workbook values or slide text here. Use Open in another app for full formatting and editing.",()->formatGuideTarget));
        }else if(textScroll!=null){
            format.add(new SpotlightTour.Step("Read or copy text","Use A− and A+ to resize text. Select a passage or use Copy text to copy what is shown.",()->viewerGuideControl(previewContent,"Larger text")));
            if(kind==Kind.HTML||kind==Kind.MARKDOWN||kind==Kind.SVG)
                format.add(new SpotlightTour.Step("Switch between reading and source","When the file has a formatted view, switch to the source or return to the readable view.",()->viewerGuideControl(previewContent,kind==Kind.SVG?"Show SVG image":readerSource?"Read":"Source")));
            if(kind==Kind.TEXT)format.add(new SpotlightTour.Step("Change syntax colors","Code colors are chosen by filename. Pick another language if a plain text file contains code.",()->previewContent==null?null:previewContent.findViewById(R.id.preview_syntax)));
            format.add(new SpotlightTour.Step("Open the whole document","Large files may show only the first part here. Use More file actions → Open in another app for the full file.",()->moreAction));
        }else if(archiveList!=null){
            format.add(new SpotlightTour.Step("Browse the ZIP","Tap a supported entry to preview it without extracting the whole archive. Eligible entries can be shared separately.",()->archiveList));
            if(canExtract()) format.add(new SpotlightTour.Step("Extract into a new folder",
                    "Choose Extract all, then check the destination. Existing files are not replaced.",()->viewerGuideControl(previewContent,"Extract all…")));
        }else if((kind==Kind.STREAM_ARCHIVE || kind==Kind.SEVEN_Z) && canExtract()){
            format.add(new SpotlightTour.Step("Extract into a new folder",
                    "Choose Extract all, then check the destination. Existing files are not replaced.",()->viewerGuideControl(previewContent,"Extract all…")));
        }else if(kind==Kind.APK||kind==Kind.DEX){
            if(canInstallApk())format.add(new SpotlightTour.Step("Install with Android",
                    "Check the package name and version, then tap Install APK to review it in Android.",()->viewerGuideControl(previewContent,"Install APK")));
            if(kind==Kind.APK)format.add(new SpotlightTour.Step("Browse package contents",
                    "Search paths inside the APK and preview eligible text and images without extracting them.",
                    ()->viewerGuideControl(previewContent,"Browse package contents")));
            format.add(new SpotlightTour.Step("Inspect DEX classes",
                    "Browse classes and read Smali without running or changing the file.",()->viewerGuideControl(previewContent,"Browse DEX classes")));
        }else{
            format.add(new SpotlightTour.Step("When a preview isn't available","Some formats need a different app. Use More file actions → Open in another app.",()->moreAction));
        }
        quick.add(format.get(0));quick.add(original);quick.add(more);
        topics.add(new SpotlightTour.Topic("Controls for this file","The tools available in this preview.",format.toArray(new SpotlightTour.Step[0])));
        topics.add(new SpotlightTour.Topic("Focus & navigation","Keep browsing without leaving the viewer.",
                focus,multiple?neighbors:new SpotlightTour.Step("Open a file alongside its neighbors","Open a file from a folder or search result list to swipe through its neighbors when available.",()->location),original,
                new SpotlightTour.Step("Pause and return later","From a Files or Search preview, choose Minimize preview. Resume from the bar in Anything without starting playback automatically.",()->moreAction)));
        ArrayList<SpotlightTour.Step> actions=new ArrayList<>();
        actions.add(more);
        actions.add(new SpotlightTour.Step("Share or inspect the current file",kind==Kind.APK
                ?"Use Share file or Properties. Opening the preview never starts Android’s installer."
                :"Use Share file, Properties, or Open in another app. The guide doesn't send or edit any file.",()->moreAction));
        actions.add(new SpotlightTour.Step("Copy, move, rename or delete","More file actions has a separate action for each. If a second pane is open, you can choose it as the destination.",()->moreAction));
        if(multiple)actions.add(new SpotlightTour.Step("Select neighboring files","Use the selection control or hold the image. Choose other files before using the actions for the selection.",()->selectFile));
        topics.add(new SpotlightTour.Topic("Preview file actions","Inspect, share or manage files from the viewer.",actions.toArray(new SpotlightTour.Step[0])));
        viewerGuide=new SpotlightTour(this,()->{viewerGuide=null;if(started&&controller!=null)showController();},topics,null,quick.toArray(new SpotlightTour.Step[0]));
        viewerGuide.start();
    }
    private View viewerGuideControl(View current,String label){
        if(current==null)return null;
        if(label.contentEquals(current.getContentDescription()==null?"":current.getContentDescription())
                ||(current instanceof TextView&&label.contentEquals(((TextView)current).getText())))return current;
        if(current instanceof ViewGroup){
            ViewGroup group=(ViewGroup)current;
            for(int i=0;i<group.getChildCount();i++){
                View found=viewerGuideControl(group.getChildAt(i),label);if(found!=null)return found;
            }
        }
        return null;
    }
    private void stopViewerGuide(){if(viewerGuide!=null){SpotlightTour tour=viewerGuide;viewerGuide=null;tour.close();}}
    private boolean hasSelectedFiles(){return session!=null&&!session.selected.isEmpty();}
    private FileEntry currentEntry(){return session==null?new FileEntry(path.isEmpty()?name:path,false,size,0):session.entries.get(session.position);}
    private void showPath(){if(activeSheet!=null)activeSheet.dismiss();activeSheet=new AppSheet(this,"File location");
        TextView full=activeSheet.text(path,14,INK);full.setTextIsSelectable(true);full.setLineSpacing(dp(4),1);activeSheet.content.addView(full);
        if(canReturnFile())activeSheet.action("Show in Files",true,false,this::revealFile);
        else activeSheet.action("Close",false,false,null);
        activeSheet.action("Copy path",false,false,()->{
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
        previousFile.setAlpha(previousFile.isEnabled()?1f:.38f);nextFile.setAlpha(nextFile.isEnabled()?1f:.38f);
        filePosition.setText(multiple?(session.position+1)+" / "+session.entries.size():"");
        filePosition.setContentDescription(multiple?"File "+(session.position+1)+" of "+session.entries.size():null);
        boolean selected=session!=null&&session.selected.containsKey(path);selectFile.setImageResource(selected?R.drawable.ic_check:R.drawable.ic_select);
        selectFile.setContentDescription(selected?"Deselect file":"Select file");selectFile.setImageTintList(android.content.res.ColorStateList.valueOf(selected?ACCENT:INK));
        selectFile.setEnabled(!selectionBusy);copyFile.setEnabled(!selectionBusy);moveFile.setEnabled(!selectionBusy);
        selectionProgress.setVisibility(selectionBusy?View.VISIBLE:View.GONE);
        selectedStrip.removeAllViews();int count=session==null?0:session.selected.size();
        selectedTray.setVisibility(count>0&&!focusMode?View.VISIBLE:View.GONE);
        deleteSelected.setEnabled(canDeleteSelected());
        deleteSelected.setContentDescription(deleteSelected.isEnabled()?"Delete selected files":"Selected files cannot be deleted from here");
        deleteSelected.setAlpha(deleteSelected.isEnabled()?1f:.4f);
        copyFile.setVisibility(count>0?View.VISIBLE:View.GONE);moveFile.setVisibility(count>0?View.VISIBLE:View.GONE);
        if(count>0){TextView label=text(Integer.toString(count),12,INK);label.setContentDescription(count+" selected files");label.setPadding(dp(4),0,dp(12),0);selectedStrip.addView(label,new LinearLayout.LayoutParams(-2,dp(44)));
            ArrayList<FileEntry> chosen=new ArrayList<>(session.selected.values());for(int i=Math.max(0,chosen.size()-24);i<chosen.size();i++){
                FileEntry entry=chosen.get(i);ImageView mini=new ImageView(this);mini.setContentDescription("Selected "+entry.name);mini.setPadding(dp(2),dp(2),dp(2),dp(2));
                thumbnails.bind(mini,entry,dp(44),session.root);selectedStrip.addView(mini,new LinearLayout.LayoutParams(dp(48),dp(48)));
                mini.setOnClickListener(view->{for(int n=0;n<session.entries.size();n++)if(session.entries.get(n).path().equals(entry.path())){navigateTo(n);break;}});
                mini.setOnLongClickListener(view->{showPreviewSelection(view,entry);return true;});}}
    }
    private void showPreviewSelection(View anchor,FileEntry held){
        if(!hasSelectedFiles())return;
        if(previewSelectionMenu!=null)previewSelectionMenu.dismiss();
        int count=session.selected.size();
        boolean ready=!selectionBusy&&!viewerTransfer;
        android.view.Menu model=theme.popup(this,anchor).getMenu();
        model.add("Copy").setIcon(R.drawable.ic_copy).setEnabled(ready);
        model.add("Move").setIcon(R.drawable.ic_cut).setEnabled(ready);
        model.add("Delete selected").setIcon(R.drawable.ic_delete).setEnabled(canDeleteSelected());
        model.add("Deselect this file").setIcon(R.drawable.ic_close);
        model.add("Clear selection").setIcon(R.drawable.ic_close);
        MenuDialog next=new MenuDialog(this,theme,anchor,model,
                count==1?"1 item selected":count+" items selected",2,choice->{
            if(session==null||selectionBusy||viewerTransfer)return;
            switch(choice){
                case "Copy":prepareClipboard(false);break;
                case "Move":prepareClipboard(true);break;
                case "Delete selected":deleteSelectedFiles();break;
                case "Deselect this file":session.selected.remove(held.path());updateFileControls();break;
                case "Clear selection":session.selected.clear();updateFileControls();break;
            }
        });
        previewSelectionMenu=next;
        next.setOnDismissListener(menu->{if(previewSelectionMenu==next)previewSelectionMenu=null;});
        next.show();
    }

    private void navigate(int direction){if(session!=null)((PreviewStage)stage).goTo(session.position+direction);}
    private void navigateTo(int position){if(session==null||position<0||position>=session.entries.size()||position==session.position)return;
        if(previewActions!=null)previewActions.stop();
        if(previewSelectionMenu!=null)previewSelectionMenu.dismiss();
        rememberFile();cancelLoad();releaseMedia();clearShown();stateKey=null;restoreOnLoad=true;session.position=position;FileEntry entry=currentEntry();name=entry.name;path=entry.path();size=entry.size;uri=null;
        readerSource=false;plainTextOverride=false;readerLanguage=null;mime=mimeFor(name);kind=kindFor(name,mime);page=pageCount=mediaPosition=scrollY=archivePosition=archiveTop=0;zoom=1;centerX=centerY=.5f;mediaCompleted=false;playWhenReady=true;
        title.setText(name);details.setText(metadata());updateFileControls();load();}
    private void renameCurrent(){if(selectionBusy||viewerTransfer)return;ensureSession();List<FileEntry> chosen=java.util.Collections.singletonList(currentEntry());
        activeSheet=FileRename.show(this,chosen,session.root,actions,this::finish);}
    private void deleteCurrent(){if(selectionBusy||viewerTransfer)return;ensureSession();List<FileEntry> chosen=java.util.Collections.singletonList(currentEntry());
        activeSheet=FileDeletion.confirm(this,chosen,session.root,actions,this::finish);}
    private boolean canDeleteSelected(){
        if(session==null||session.selected.isEmpty()||selectionBusy||viewerTransfer)return false;
        for(FileEntry entry:session.selected.values())if(!entry.path().startsWith("/"))return false;
        return true;
    }
    private void deleteSelectedFiles(){
        if(!canDeleteSelected())return;
        List<FileEntry> chosen=new ArrayList<>(session.selected.values());
        if(activeSheet!=null)activeSheet.dismiss();
        activeSheet=FileDeletion.confirm(this,chosen,session.root,actions,()->{
            session.selected.clear();finish();
        },this::canDeleteSelected);
    }
    private void prepareClipboard(boolean move){prepareClipboard(move,false);}
    private void prepareClipboard(boolean move,boolean currentOnly){if(selectionBusy||viewerTransfer)return;ensureSession();
        ArrayList<FileEntry> chosen=currentOnly?new ArrayList<>():new ArrayList<>(session.selected.values());
        if(chosen.isEmpty())chosen.add(currentEntry());boolean root=session.root;selectionBusy=true;updateFileControls();Context context=getApplicationContext();
        try{actions.execute(()->{String error=null;try{FileClipboard.save(context,chosen,move,root);}catch(Exception failure){error=failure.getMessage();}String failure=error;
            ui.post(()->{if(destroyed||isFinishing())return;selectionBusy=false;updateFileControls();if(failure==null){haptic(copyFile);Toast.makeText(this,move?"Choose a destination to move":"Choose a destination to paste",Toast.LENGTH_SHORT).show();}
                else Toast.makeText(this,failure,Toast.LENGTH_LONG).show();});});}catch(java.util.concurrent.RejectedExecutionException busy){selectionBusy=false;updateFileControls();}}
    @Override public void finish(){
        Intent result=new Intent().putExtra("preview.paste",pasteAfterPreview);
        if(session!=null)result.putExtra(PreviewSession.EXTRA,session.id);
        if(minimizeResult!=null)result.putExtra(EXTRA_MINIMIZED,minimizeResult);
        if(revealResult!=null)result.putExtra(EXTRA_REVEAL,revealResult);
        setResult(RESULT_OK,result);super.finish();
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
            @Override public void onLongPress(MotionEvent event){if(!swiping&&!settling&&kind!=Kind.TEXT&&kind!=Kind.MARKDOWN&&kind!=Kind.HTML&&textScroll==null&&archiveList==null)selectCurrent();}});}
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
                else{int type=FileTypes.category(entry);picture.setImageResource(UiTheme.fileIcon(FileViewerActivity.this,entry));picture.setImageTintList(UiTheme.fileTint(FileViewerActivity.this,type));}
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
        cancelLoad(); clearShown(); image=null;textScroll=null;archiveList=null;formatGuideTarget=null;mediaLoading=null;
        notice.setVisibility(View.GONE);((PreviewStage)stage).reset();stage.removeAllViews();
        stage.setBackgroundColor(stageColor(kind));
        previewContent=new PreviewContent(this);stage.addView(previewContent,new FrameLayout.LayoutParams(-1,-1));
        if ((uri == null && session == null) || (uri != null && (!"content".equals(uri.getScheme()) || !(getPackageName() + ".files").equals(uri.getAuthority())))) {
            failure("File access is unavailable. Return to results and open the file again."); return;
        }
        View placeholder=navigationPlaceholder;navigationPlaceholder=null;
        loadingPlaceholder=placeholder;
        if(placeholder!=null)previewContent.addView(placeholder,new FrameLayout.LayoutParams(-1,-1));
        else addLoadingIndicator(previewContent,kind==Kind.PDF?"Opening page "+(page+1)
                :kind==Kind.OFFICE&&OfficePreview.format(name)==OfficePreview.Format.SLIDES
                    ?"Opening slide "+(page+1):"Opening preview");
        job = new PreviewJob(this, getApplicationContext(), uri, kind, page, generation);
        job.restoreState=restoreOnLoad;restoreOnLoad=false;
        if(uri==null&&session!=null){job.entry=currentEntry();job.allowRoot=session.root;}
        try { pending = PREVIEWS.submit(job); }
        catch (java.util.concurrent.RejectedExecutionException busy) { job.cancel(); job = null; failure("Another preview is finishing.",true); }
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
        if(result.transportStream){kind=Kind.VIDEO;stage.setBackgroundColor(stageColor(kind));}
        if (result.error != null) { String error = result.error; result.close(); failure(error); return; }
        shown = result;
        stateKey=result.key;
        if(result.state!=null)restoreViewState(result.state);
        if (result.size >= 0) size = result.size;
        details.setText(metadata());View cover=loadingPlaceholder;loadingPlaceholder=null;previewContent.removeAllViews();
        if (kind == Kind.IMAGE || kind == Kind.PDF || kind==Kind.SVG&&!readerSource) showImage(result);
        else if (kind == Kind.TEXT || kind==Kind.MARKDOWN || kind==Kind.HTML || kind==Kind.SVG) showDocument(result);
        else if (kind == Kind.OFFICE) {if(result.slide!=null)showSlides(result.slide);else showOffice(result.office);}
        else if (kind == Kind.ZIP) showArchive(result.archive);
        else if (kind == Kind.STREAM_ARCHIVE) showStreamArchive(StreamArchiveExtraction.format(name),result.tar);
        else if (kind == Kind.SEVEN_Z) showSevenZip(result.sevenZip);
        else if (kind == Kind.APK) showApk(result.apk);
        else if (kind == Kind.DEX) showDexEntry();
        else showMedia(result);
        if(cover!=null&&shown==result){
            PreviewContent current=previewContent;boolean waitsForFrame=kind==Kind.VIDEO&&playWhenReady;
            current.coverWith(cover,waitsForFrame);
            if(waitsForFrame)ui.postDelayed(()->{if(previewContent==current)current.reveal();},2400);
        }
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
            LinearLayout navigation=row();
            ImageButton previous=actionIcon(R.drawable.ic_viewer_previous,"Previous page",View.NO_ID);
            previous.setEnabled(page>0);previous.setAlpha(page>0?1f:.38f);
            previous.setOnClickListener(view->changePage(page-1));navigation.addView(previous);
            TextView position=text((page+1)+" / "+pageCount,12,INK);position.setGravity(Gravity.CENTER);
            position.setContentDescription("Page "+(page+1)+" of "+pageCount);
            navigation.addView(position,new LinearLayout.LayoutParams(dp(60),dp(48)));
            ImageButton next=actionIcon(R.drawable.ic_viewer_next,"Next page",View.NO_ID);
            next.setEnabled(page+1<pageCount);next.setAlpha(page+1<pageCount?1f:.38f);
            next.setOnClickListener(view->changePage(page+1));navigation.addView(next);
            formatGuideTarget=navigation;addViewerTools(column,navigation);
            details.setText(metadata() + " · " + pageCount + (pageCount == 1 ? " page" : " pages"));
        } else {
            LinearLayout tools=row();
            ImageButton less=actionIcon(R.drawable.ic_viewer_zoom_out,"Zoom out",View.NO_ID);less.setOnClickListener(v->image.zoomBy(.8f));tools.addView(less);
            ImageButton fit=actionIcon(R.drawable.ic_viewer_fit,"Fit image",View.NO_ID);fit.setOnClickListener(v->image.fit());tools.addView(fit);
            ImageButton more=actionIcon(R.drawable.ic_viewer_zoom_in,"Zoom in",View.NO_ID);more.setOnClickListener(v->image.zoomBy(1.25f));tools.addView(more);
            formatGuideTarget=fit;
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
        tools.setBackground(panel(theme.surface,14));
        LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(-2,-2);
        layout.gravity=Gravity.CENTER_HORIZONTAL;
        layout.setMargins(0,dp(4),0,dp(10));column.addView(tools,layout);
    }

    private void changePage(int value) {
        if (value < 0 || value >= pageCount) return;
        page = value; zoom = 1; centerX = centerY = .5f; load();
    }

    private void showSlides(PresentationSlide slide){
        page=slide.index;pageCount=slide.count;
        details.setText(metadata()+" · "+pageCount+(pageCount==1?" slide":" slides"));
        LinearLayout column=new LinearLayout(this);column.setOrientation(LinearLayout.VERTICAL);
        FrameLayout slides=new FrameLayout(this);
        column.addView(slides,new LinearLayout.LayoutParams(-1,0,1));

        image=new ZoomPreviewView(this);image.setBackgroundColor(stageColor(kind));
        image.setContentDescription("Slide "+(page+1)+" of "+pageCount+". Pinch or double tap to zoom. Use Slide text to read the content.");
        FrameLayout.LayoutParams drawing=new FrameLayout.LayoutParams(-1,-1);
        int inset=dp(12);drawing.setMargins(inset,inset,inset,inset);
        slides.addView(image,drawing);
        image.setPicture(slide.picture);image.restore(zoom,centerX,centerY);
        if(slide.picture!=null)image.setOnLongClickListener(view->{selectCurrent();return true;});

        TextView outline=text(slide.text.isEmpty()?"No readable text on this slide.":slide.text,16,INK);
        outline.setTextIsSelectable(true);outline.setGravity(Gravity.TOP|Gravity.START);
        outline.setPadding(dp(20),dp(16),dp(20),dp(24));outline.setLineSpacing(dp(4),1.08f);simpleTextLayout(outline);
        textScroll=new EdgeFastScroll.Document(this);textScroll.setFillViewport(true);
        textScroll.addView(outline,new ScrollView.LayoutParams(-1,-2));
        textScroll.addOnLayoutChangeListener(new RestoreScroll(scrollY));
        slides.addView(textScroll,new FrameLayout.LayoutParams(-1,-1));

        if(slide.limited){
            TextView notice=text("Some slide styles or objects couldn’t be rendered.",12,MUTED);
            notice.setGravity(Gravity.CENTER);notice.setPadding(dp(12),dp(3),dp(12),dp(3));
            column.addView(notice,new LinearLayout.LayoutParams(-1,-2));
        }
        column.addView(viewerRule(),new LinearLayout.LayoutParams(-1,dp(1)));
        LinearLayout tools=row();tools.setGravity(Gravity.CENTER);tools.setBackgroundColor(CHROME);
        Button out=button("−"),fit=button("Fit"),in=button("+"),mode=button("");
        out.setContentDescription("Zoom out");fit.setContentDescription("Fit slide");in.setContentDescription("Zoom in");
        out.setOnClickListener(v->image.zoomBy(1/1.5f));fit.setOnClickListener(v->image.fit());in.setOnClickListener(v->image.zoomBy(1.5f));
        for(Button control:new Button[]{out,fit,in})tools.addView(control,new LinearLayout.LayoutParams(dp(48),dp(48)));
        tools.addView(mode,new LinearLayout.LayoutParams(dp(100),dp(48)));
        Runnable showMode=()->{
            image.setVisibility(readerSource?View.GONE:View.VISIBLE);
            textScroll.setVisibility(readerSource?View.VISIBLE:View.GONE);
            mode.setText(readerSource?"Slide view":"Slide text");
            for(Button control:new Button[]{out,fit,in})control.setEnabled(!readerSource);
        };
        showMode.run();mode.setOnClickListener(v->{readerSource=!readerSource;showMode.run();});
        column.addView(tools,new LinearLayout.LayoutParams(-1,dp(48)));

        LinearLayout pages=row();pages.setGravity(Gravity.CENTER);pages.setBackgroundColor(CHROME);
        Button back=button("Previous slide"),next=button("Next slide");
        TextView position=text("Slide "+(page+1)+" / "+pageCount,13,MUTED);
        position.setGravity(Gravity.CENTER);
        position.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        back.setEnabled(page>0);next.setEnabled(page+1<pageCount);
        back.setOnClickListener(v->{scrollY=0;changePage(page-1);});
        next.setOnClickListener(v->{scrollY=0;changePage(page+1);});
        pages.addView(back,new LinearLayout.LayoutParams(0,dp(48),1));
        pages.addView(position,new LinearLayout.LayoutParams(dp(94),dp(48)));
        pages.addView(next,new LinearLayout.LayoutParams(0,dp(48),1));
        column.addView(pages,new LinearLayout.LayoutParams(-1,dp(48)));
        formatGuideTarget=pages;
        previewContent.addView(column,new FrameLayout.LayoutParams(-1,-1));
    }

    private void showOffice(OfficePreview.Result office) {
        LinearLayout column = new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL);
        LinearLayout sections=new LinearLayout(this); sections.setOrientation(LinearLayout.VERTICAL);
        sections.setPadding(dp(18),dp(12),dp(18),dp(24));
        String caveat=office.format==OfficePreview.Format.SHEET
                ?"Visible worksheets only. Up to 40 rows and columns A–L per sheet. Dates can appear as numbers. Formulas show saved values only."
                :office.format==OfficePreview.Format.SLIDES
                    ?"Reads text in presentation order. Images, layout and speaker notes aren't shown."
                    :"Reads the main document text and simple table rows. Images and page layout aren't shown.";
        TextView mode=text("Read-only preview",13,MUTED);
        mode.setPadding(0,dp(4),0,dp(4));sections.addView(mode);
        TextView caption=text(caveat,12,MUTED);
        caption.setPadding(0,dp(3),0,dp(10)); sections.addView(caption);
        for(OfficePreview.Section section:office.sections){
            TextView title=text(section.title,15,INK);
            title.setTypeface(getResources().getFont(R.font.inter_semibold)); title.setPadding(0,dp(13),0,dp(5));
            if(Build.VERSION.SDK_INT>=28)title.setAccessibilityHeading(true);
            sections.addView(title);
            TextView content=text(section.text,office.format==OfficePreview.Format.SHEET?13:15,INK);
            if(office.format==OfficePreview.Format.SHEET)content.setTypeface(android.graphics.Typeface.MONOSPACE);
            content.setTextIsSelectable(true); content.setLineSpacing(dp(4),1.05f); simpleTextLayout(content);
            sections.addView(content);
        }
        textScroll=new EdgeFastScroll.Document(this); textScroll.setFillViewport(true);
        textScroll.addView(sections,new ScrollView.LayoutParams(-1,-2));
        column.addView(textScroll,new LinearLayout.LayoutParams(-1,0,1));
        column.addView(viewerRule(),new LinearLayout.LayoutParams(-1,dp(1)));
        LinearLayout dock=row();dock.setGravity(Gravity.CENTER_VERTICAL|Gravity.END);
        dock.setBackgroundColor(CHROME);dock.setPadding(dp(16),dp(6),dp(16),dp(6));
        Button open=button("Open in another app");open.setPadding(dp(16),0,dp(16),0);
        open.setTextColor(theme.dark?theme.surface:Color.WHITE);open.setBackground(controlBackground(ACCENT,14));
        open.setOnClickListener(v->openExternal());formatGuideTarget=open;
        dock.addView(open,new LinearLayout.LayoutParams(-2,dp(48)));
        column.addView(dock,new LinearLayout.LayoutParams(-1,-2));
        previewContent.addView(column,new FrameLayout.LayoutParams(-1,-1));
        details.setText(metadata()+" · "+office.format.label);
        if(office.limited)note("This is a partial summary. Use Open in another app for the full file.");
        textScroll.addOnLayoutChangeListener(new RestoreScroll(scrollY));
    }

    private void showDocument(PreviewResult preview) {
        formatGuideTarget=null;
        PreviewText.Result result=preview.text;LinearLayout column=new LinearLayout(this);column.setOrientation(LinearLayout.VERTICAL);
        LinearLayout tools=row();tools.setBackground(panel(CHROME,16));tools.setPadding(dp(12),0,dp(4),0);
        TextView content=text("",readerSize,INK);content.setGravity(Gravity.TOP);content.setPadding(dp(14),dp(14),dp(16),dp(24));
        content.setTextIsSelectable(true);content.setLinkTextColor(ACCENT);simpleTextLayout(content);
        Button syntax=button("");syntax.setId(R.id.preview_syntax);syntax.setSingleLine(true);syntax.setEllipsize(TextUtils.TruncateAt.END);
        syntax.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);syntax.setPadding(dp(14),0,dp(14),0);
        syntax.setOnClickListener(v->chooseReaderLanguage(syntax,preview));
        Runnable bind=()->{
            boolean rich=!readerSource&&preview.formatted!=null;
            syntax.setVisibility(rich||preview.code==null||result.text.isEmpty()?View.GONE:View.VISIBLE);
            if(rich){
                content.setTypeface(getResources().getFont(R.font.inter));content.setLineSpacing(dp(4),1.12f);
                content.setText(preview.formatted);
                content.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
            }else{
                // Selectable movement must be reinstalled when changing back from a rich document.
                content.setMovementMethod(android.text.method.ArrowKeyMovementMethod.getInstance());
                CodeSyntax.Model code=preview.code;
                if(code==null){content.setText(result.text.isEmpty()?"This file is empty.":result.text);return;}
                syntax.setText("Syntax: "+code.language.label+(readerLanguage==null?" · Auto":""));
                syntax.setContentDescription("Syntax highlighting: "+code.language.label+(readerLanguage==null?", automatic":""));
                boolean plain=code.language==CodeSyntax.Language.PLAIN;
                content.setTypeface(kind==Kind.TEXT&&plain?getResources().getFont(R.font.inter):android.graphics.Typeface.MONOSPACE);
                content.setLineSpacing(dp(3),1.08f);
                int gutter=Math.max(dp(32),Math.round(content.getPaint().measureText("0000")*.8f)+dp(20));
                int tab=Math.round(content.getPaint().measureText("    "));
                content.setText(CodePreviewStyle.style(code,theme.dark,MUTED,gutter,dp(10),Math.max(1,tab)));
            }
            if(result.truncated)note("Showing the first 128 KiB or 2,000 lines. Use Open for the full file.");
            else if(!rich&&preview.code!=null&&preview.code.limited)note("Syntax colors are limited for dense files. All preview text is visible.");
            else notice.setVisibility(View.GONE);
        };bind.run();
        if(preview.formatted!=null||kind==Kind.SVG){Button mode=button(kind==Kind.SVG?"Image":readerSource?"Read":"Source");
            mode.setContentDescription(kind==Kind.SVG?"Show SVG image":readerSource?"Show formatted document":"Show source text");
            mode.setOnClickListener(v->{if(kind==Kind.SVG){readerSource=false;previewContent.removeAllViews();textScroll=null;showImage(preview);}
                else{readerSource=!readerSource;bind.run();mode.setText(readerSource?"Read":"Source");
                    mode.setContentDescription(readerSource?"Show formatted document":"Show source text");}});
            tools.addView(mode,new LinearLayout.LayoutParams(0,dp(48),1));}
        else tools.addView(text(result.encoding,12,MUTED),new LinearLayout.LayoutParams(0,dp(48),1));
        Button smaller=button("A−"),larger=button("A+");smaller.setContentDescription("Smaller text");larger.setContentDescription("Larger text");
        smaller.setOnClickListener(v->{resizeReader(content,-2);bind.run();});larger.setOnClickListener(v->{resizeReader(content,2);bind.run();});
        tools.addView(smaller,new LinearLayout.LayoutParams(dp(48),dp(48)));tools.addView(larger,new LinearLayout.LayoutParams(dp(48),dp(48)));
        ImageButton copy=actionIcon(R.drawable.ic_copy,"Copy text",View.generateViewId());copy.setOnClickListener(v->{
            CharSequence copied=!readerSource&&preview.formatted!=null?preview.formatted:result.text;
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText(name,copied));
        });tools.addView(copy);
        LinearLayout.LayoutParams toolsLayout=new LinearLayout.LayoutParams(-1,-2);
        toolsLayout.setMargins(dp(10),dp(10),dp(10),0);column.addView(tools,toolsLayout);
        LinearLayout.LayoutParams syntaxLayout=new LinearLayout.LayoutParams(-2,dp(48));
        syntaxLayout.setMargins(dp(10),0,dp(10),0);column.addView(syntax,syntaxLayout);
        textScroll=new EdgeFastScroll.Document(this);textScroll.setFillViewport(true);textScroll.addView(content,new ScrollView.LayoutParams(-1,-2));
        column.addView(textScroll,new LinearLayout.LayoutParams(-1,0,1));previewContent.addView(column,new FrameLayout.LayoutParams(-1,-1));
        details.setText(metadata());
        textScroll.addOnLayoutChangeListener(new RestoreScroll(scrollY));
    }

    private void chooseReaderLanguage(View anchor,PreviewResult preview) {
        if(languageMenu!=null)languageMenu.dismiss();
        android.view.Menu menu=theme.popup(this,anchor).getMenu();
        menu.add(1,1,0,"Automatic ("+CodeSyntax.detect(name,preview.code.display).label+")");
        for(CodeSyntax.Language language:CodeSyntax.Language.values())
            menu.add(1,language.ordinal()+2,language.ordinal()+1,language.label);
        menu.setGroupCheckable(1,true,true);
        menu.findItem(readerLanguage==null?1:readerLanguage.ordinal()+2).setChecked(true);
        MenuDialog choices=new MenuDialog(this,theme,anchor,menu,"Syntax",label->{
            if(shown!=preview)return;
            for(int i=0;i<menu.size();i++){
                android.view.MenuItem item=menu.getItem(i);
                if(!label.contentEquals(item.getTitle()))continue;
                CodeSyntax.Language chosen=item.getItemId()==1?null:CodeSyntax.Language.values()[item.getItemId()-2];
                if(chosen!=readerLanguage){rememberView();readerLanguage=chosen;load();}
                break;
            }
        });
        languageMenu=choices;
        choices.setOnDismissListener(dialog->{if(languageMenu==choices)languageMenu=null;});
        choices.show();
    }
    private void resizeReader(TextView content,int delta){readerSize=Math.max(12,Math.min(26,readerSize+delta));content.setTextSize(readerSize);getSharedPreferences("ui",0).edit().putInt("readerSize",readerSize).apply();}

    @SuppressLint("WrongConstant")
    static void simpleTextLayout(TextView text) {
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

    private boolean canInstallApk() {
        return kind == Kind.APK && shown != null && shown.apk != null
                && ApkInstallAction.supported() && shown.apk.canInstall();
    }

    private void installApk() {
        if (canInstallApk()) shown.apk.install(this);
    }

    private void showApk(ApkInstallAction.Details apk) {
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20),dp(12),dp(20),dp(16));
        TextView label = text(apk.label,20,INK);
        label.setTypeface(getResources().getFont(R.font.inter_semibold));
        body.addView(label,new LinearLayout.LayoutParams(-1,-2));
        TextView metadata = text(apk.packageName + "\nVersion " + apk.version + "\nMinimum Android API "
                + apk.minSdk + " · Targets API " + apk.targetSdk,14,MUTED);
        metadata.setPadding(0,dp(8),0,dp(12));
        metadata.setTextIsSelectable(true);
        body.addView(metadata,new LinearLayout.LayoutParams(-1,-2));
        boolean installable = canInstallApk();
        if (installable) {
            body.addView(text("Android reviews the installation. Only install APKs from sources you trust.",13,MUTED));
        } else {
            String reason = apk.installUnavailableReason();
            if (reason != null) body.addView(text(reason,13,MUTED));
        }
        addPreviewAction(body,"Inspect APK",()->{
            if(shown==null||shown.apk!=apk||kind!=Kind.APK)return;
            if(activeSheet!=null)activeSheet.dismiss();
            AppSheet inspection=ApkInspectionSheet.create(this,apk,actions,closed->{if(activeSheet==closed)activeSheet=null;});
            activeSheet=inspection;inspection.show();
        });
        addPreviewAction(body,"Browse package contents",()->{
            if(shown!=null&&shown.apk==apk&&kind==Kind.APK)showApkContents(apk);
        });
        addPreviewAction(body,"Browse DEX classes",this::browseDex);
        scroll.addView(body,new ScrollView.LayoutParams(-1,-2));
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if (installable) {
            column.addView(viewerRule(),new LinearLayout.LayoutParams(-1,dp(1)));
            LinearLayout actions=row();
            actions.setGravity(Gravity.CENTER_VERTICAL|Gravity.END);
            actions.setBackgroundColor(CHROME);
            actions.setPadding(dp(16),dp(6),dp(16),dp(6));
            Button install=button("Install APK");
            install.setTextColor(theme.dark?theme.surface:Color.WHITE);
            install.setBackground(controlBackground(ACCENT,14));
            install.setPadding(dp(18),0,dp(18),0);
            install.setOnClickListener(v->installApk());
            actions.addView(install,new LinearLayout.LayoutParams(-2,dp(48)));
            column.addView(actions,new LinearLayout.LayoutParams(-1,-2));
        }
        previewContent.addView(column,new FrameLayout.LayoutParams(-1,-1));
    }

    private void showApkContents(ApkInstallAction.Details apk) {
        if(activeSheet!=null)activeSheet.dismiss();
        ApkContents panel=new ApkContents(apk);
        activeSheet=panel.sheet;
        panel.show();
    }

    private final class ApkContents {
        final ApkInstallAction.Details apk;
        final AppSheet sheet;
        final LinearLayout listingPanel, detailPanel;
        final TextView count;
        final android.widget.EditText filter;
        final EdgeFastScroll.List list;
        List<ZipDirectory.Entry> shownEntries=new ArrayList<>();
        ZipDirectory.Listing listing;
        Future<?> pending;
        private volatile java.util.Map.Entry<ZipDirectory.Entry,ApkResources.Table> verifiedResourceTable;
        private volatile ApkResources.Table xmlResourceNames;
        private volatile boolean xmlResourceTableTried;
        int request;
        boolean inDetail;

        ApkContents(ApkInstallAction.Details apk) {
            this.apk=apk;
            sheet=new AppSheet(FileViewerActivity.this,"APK contents",true);
            listingPanel=new LinearLayout(FileViewerActivity.this);
            listingPanel.setOrientation(LinearLayout.VERTICAL);
            detailPanel=new LinearLayout(FileViewerActivity.this);
            detailPanel.setOrientation(LinearLayout.VERTICAL);
            detailPanel.setVisibility(View.GONE);
            sheet.content.addView(listingPanel,new LinearLayout.LayoutParams(-1,0,1));
            sheet.content.addView(detailPanel,new LinearLayout.LayoutParams(-1,0,1));
            filter=new android.widget.EditText(FileViewerActivity.this);
            filter.setSingleLine(true);
            filter.setHint("Filter package paths");
            filter.setContentDescription("Filter package paths");
            filter.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(200)});
            filter.setMinHeight(dp(48));
            filter.setTextSize(14);
            filter.setEnabled(false);
            filter.addTextChangedListener(new android.text.TextWatcher(){
                @Override public void beforeTextChanged(CharSequence text,int start,int count,int after){}
                @Override public void onTextChanged(CharSequence text,int start,int before,int count){ applyFilter(); }
                @Override public void afterTextChanged(android.text.Editable editable){}
            });
            listingPanel.addView(filter,new LinearLayout.LayoutParams(-1,-2));
            count=sheet.text("Reading package directory…",13,MUTED);
            count.setPadding(dp(4),dp(8),dp(4),dp(8));
            count.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            listingPanel.addView(count,new LinearLayout.LayoutParams(-1,-2));
            list=new EdgeFastScroll.List(FileViewerActivity.this);list.enableReleasePick();
            list.setDivider(new android.graphics.drawable.ColorDrawable(theme.line));
            list.setDividerHeight(dp(1));
            list.labels=p->p>=0&&p<shownEntries.size()?shownEntries.get(p).name:"";
            list.setOnItemClickListener((p,v,i,id)->{if(i>=0&&i<shownEntries.size())showEntry(shownEntries.get(i));});
            listingPanel.addView(list,new LinearLayout.LayoutParams(-1,0,1));
            sheet.setBackNavigation(this::backToContents);
            sheet.setOnDismissListener(dialog->{
                cancel();
                clearDetail();
                if(activeSheet==sheet)activeSheet=null;
            });
        }

        void show() {
            listingPanel.setFocusableInTouchMode(true);
            listingPanel.requestFocus();
            sheet.show();
            sheet.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            int ticket=++request;
            try {
                pending=PREVIEWS.submit(()->{
                    ZipDirectory.Listing contents=null;
                    String error=null;
                    try (ParcelFileDescriptor.AutoCloseInputStream input=
                                 new ParcelFileDescriptor.AutoCloseInputStream(apk.openContents())) {
                        contents=ZipDirectory.read(input.getChannel());
                    } catch (Exception | OutOfMemoryError failure) {
                        error="This package directory couldn’t be listed. Reopen the APK to try again.";
                    }
                    ZipDirectory.Listing result=contents;String message=error;
                    ui.post(()->{
                        if(!isCurrentSheet(sheet)||ticket!=request)return;
                        if(result==null){count.setText(message);return;}
                        listing=result;
                        filter.setEnabled(true);
                        applyFilter();
                    });
                });
            } catch(java.util.concurrent.RejectedExecutionException busy) {
                count.setText("The viewer is busy. Reopen package contents to retry.");
            }
        }

        private void applyFilter() {
            if(listing==null)return;
            String term=filter.getText().toString().trim().toLowerCase(Locale.ROOT);
            List<ZipDirectory.Entry> filtered=new ArrayList<>();
            for(ZipDirectory.Entry entry:listing.entries) {
                if(term.isEmpty()||entry.name.toLowerCase(Locale.ROOT).contains(term))filtered.add(entry);
            }
            shownEntries=filtered;
            list.setAdapter(new ArchiveAdapter(filtered,true));
            String found=NumberFormat.getIntegerInstance().format(filtered.size());
            String total=NumberFormat.getIntegerInstance().format(listing.totalEntries);
            if(listing.truncated)
                count.setText("Showing "+found+" match(es) in the first "+listing.entries.size()+" of "+total+" package entries.");
            else if(term.isEmpty()) count.setText(total+(listing.totalEntries==1?" package entry":" package entries"));
            else count.setText(found+(filtered.size()==1?" match":" matches")+" · "+total+" entries");
        }

        private void showEntry(ZipDirectory.Entry entry) {
            if(inDetail||!isCurrentSheet(sheet))return;
            cancel();
            inDetail=true;
            android.view.inputmethod.InputMethodManager ime=getSystemService(android.view.inputmethod.InputMethodManager.class);
            if(ime!=null)ime.hideSoftInputFromWindow(filter.getWindowToken(),0);
            filter.clearFocus();
            listingPanel.setVisibility(View.GONE);
            detailPanel.setVisibility(View.VISIBLE);
            ScrollView scroller=new EdgeFastScroll.Document(FileViewerActivity.this);
            scroller.setFillViewport(false);
            LinearLayout body=new LinearLayout(FileViewerActivity.this);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(dp(10),dp(6),dp(10),dp(12));
            TextView label=sheet.text(entry.name,16,INK);
            label.setTextIsSelectable(true);
            label.setTypeface(getResources().getFont(R.font.inter_semibold));
            body.addView(label,new LinearLayout.LayoutParams(-1,-2));
            TextView info=sheet.text(ApkPackageContents.type(entry)+(entry.directory?"":" · "+bytes(entry.size)),13,MUTED);
            info.setPadding(0,dp(6),0,dp(12));
            body.addView(info,new LinearLayout.LayoutParams(-1,-2));
            TextView status=sheet.text("",14,INK);
            status.setTextIsSelectable(true);
            simpleTextLayout(status);
            body.addView(status,new LinearLayout.LayoutParams(-1,-2));
            scroller.addView(body,new ScrollView.LayoutParams(-1,-2));
            detailPanel.addView(scroller,new LinearLayout.LayoutParams(-1,0,1));
            Button back=button("Back to package contents");
            back.setOnClickListener(v->backToContents());
            detailPanel.addView(back,new LinearLayout.LayoutParams(-1,dp(48)));
            ApkPackageContents.Format format=ApkPackageContents.format(entry);
            if(format==ApkPackageContents.Format.NONE) {
                status.setText(ZipDirectory.isDexEntry(entry.name)
                        ?"Go back to the APK and use Browse DEX classes."
                        :"Only the name and type are available for this entry.");
                return;
            }
            status.setText("Loading read-only preview…");
            if(format==ApkPackageContents.Format.XML||format==ApkPackageContents.Format.RESOURCE_TABLE)
                status.setTypeface(android.graphics.Typeface.MONOSPACE);
            int ticket=++request;
            try {
                pending=PREVIEWS.submit(()->{
                    Object rendered=null;String error=null;
                    try (ParcelFileDescriptor.AutoCloseInputStream input=
                                 new ParcelFileDescriptor.AutoCloseInputStream(apk.openContents())) {
                        java.nio.channels.FileChannel channel=input.getChannel();
                        if(format==ApkPackageContents.Format.RESOURCE_TABLE){
                            rendered=readResourceTable(channel,entry).describe();
                        }else{
                            byte[] data=ApkPackageContents.readVerified(channel,entry,format);
                            if(format==ApkPackageContents.Format.XML){
                                ApkResources.Table table=ApkBinaryXml.looksLike(data)?resourcesForXml(channel):null;
                                rendered=ApkPackageContents.decodeXml(data,table);
                            }else rendered=readArchiveEntry(new java.io.ByteArrayInputStream(data),
                                            format==ApkPackageContents.Format.TEXT?Kind.TEXT:Kind.IMAGE,data.length);
                        }
                    } catch (Exception | OutOfMemoryError failure) {
                        error=archivePreviewError(failure);
                    }
                    Object ready=rendered;String message=error;
                    ui.post(()->{
                        if(!isCurrentSheet(sheet)||ticket!=request||!inDetail) {
                            recycle(ready);
                            return;
                        }
                        if(ready instanceof android.graphics.drawable.BitmapDrawable) {
                            body.removeView(status);
                            android.graphics.drawable.BitmapDrawable drawable=(android.graphics.drawable.BitmapDrawable)ready;
                            ImageView image=new ImageView(FileViewerActivity.this);
                            image.setContentDescription(entry.name);
                            image.setAdjustViewBounds(true);
                            image.setMaxHeight(dp(400));
                            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                            image.setImageDrawable(drawable);
                            body.addView(image,new LinearLayout.LayoutParams(-1,-2));
                        } else {
                            status.setText(ready instanceof CharSequence?(CharSequence)ready:
                                    message==null?"This package entry couldn’t be previewed.":message);
                        }
                    });
                });
            } catch(java.util.concurrent.RejectedExecutionException busy) {
                status.setText("The viewer is busy. Go back and tap this entry to retry.");
            }
        }

        private ApkResources.Table readResourceTable(java.nio.channels.FileChannel channel,ZipDirectory.Entry entry)
                throws IOException{
            java.util.Map.Entry<ZipDirectory.Entry,ApkResources.Table> cached=verifiedResourceTable;
            if(cached!=null&&cached.getKey()==entry)return cached.getValue();
            ApkResources.Table table=ApkResources.read(ApkPackageContents.readVerified(
                    channel,entry,ApkPackageContents.Format.RESOURCE_TABLE));
            PreviewText.cancelled();
            verifiedResourceTable=new java.util.AbstractMap.SimpleImmutableEntry<>(entry,table);
            return table;
        }

        private ApkResources.Table resourcesForXml(java.nio.channels.FileChannel channel){
            if(xmlResourceTableTried||listing==null)return xmlResourceNames;
            ZipDirectory.Entry match=null;
            try{
                int inspected=0;
                for(ZipDirectory.Entry entry:listing.entries){
                    if((inspected++&511)==0)PreviewText.cancelled();
                    if(!"resources.arsc".equals(entry.name))continue;
                    // Multiple ZIP entries with the same name are ambiguous.
                    if(match!=null){xmlResourceTableTried=true;return null;}
                    match=entry;
                }
                if(match!=null)xmlResourceNames=readResourceTable(channel,match);
            }catch(IOException unavailable){
                // A missing, malformed, oversized or unresolved table never prevents XML preview.
            }
            if(!Thread.currentThread().isInterrupted())xmlResourceTableTried=true;
            return xmlResourceNames;
        }

        private void recycle(Object rendered) {
            if(rendered instanceof android.graphics.drawable.BitmapDrawable)
                ((android.graphics.drawable.BitmapDrawable)rendered).getBitmap().recycle();
        }

        private void clearDetail() {
            detailPanel.removeAllViews();
        }

        private boolean backToContents() {
            if(!inDetail)return false;
            cancel();
            clearDetail();
            inDetail=false;
            detailPanel.setVisibility(View.GONE);
            listingPanel.setVisibility(View.VISIBLE);
            listingPanel.requestFocus();
            return true;
        }

        private void cancel() {
            request++;
            if(pending!=null) {
                pending.cancel(true);
                if(pending instanceof Runnable)PREVIEWS.remove((Runnable)pending);
                pending=null;
            }
        }
    }

    private void showDexEntry() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(12), dp(20), dp(16));
        body.addView(text("Dalvik bytecode", 20, INK));
        body.addView(text("Read Smali without running or changing this file.", 14, MUTED));
        addPreviewAction(body, "Browse DEX classes", this::browseDex);
        previewContent.addView(body, new FrameLayout.LayoutParams(-1, -2));
    }

    private void browseDex() {
        if (shown == null || shown.identity == null || (kind != Kind.APK && kind != Kind.DEX)) return;
        startActivity(DexViewerActivity.intent(this, uri, name, path, shown.identity,
                session != null && session.root, kind == Kind.APK));
    }

    private boolean canExtract() {
        if (shown == null) return false;
        if (kind == Kind.STREAM_ARCHIVE) return StreamArchiveExtraction.format(name) != null
                && shown.size<=ArchiveExtraction.MAX_BYTES && (shown.tar==null||!shown.tar.requiresDedicatedApp());
        if (kind == Kind.SEVEN_Z) return shown.sevenZip != null && shown.sevenZip.count > 0;
        return kind == Kind.ZIP && shown.archive != null && !shown.archive.truncated
                && !shown.archive.entries.isEmpty() && ArchiveExtraction.supports(extension(name));
    }

    private void chooseExtraction() {
        if (!canExtract() || extracting) return;
        if (activeSheet != null) activeSheet.dismiss();
        AppSheet sheet = new AppSheet(this,"Extract archive");
        activeSheet = sheet;
        sheet.setOnDismissListener(dialog->{
            if(extractFolderPicker!=null){extractFolderPicker.dismiss();extractFolderPicker=null;}
            if(activeSheet==sheet)activeSheet=null;
        });
        String downloads = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS).getAbsolutePath();
        String parent = path.isEmpty() ? null : new java.io.File(path).getParent();
        // Never use root to create output. Shared storage roots in the index can use the backing path.
        String shared = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
        if (parent != null && shared.startsWith("/storage/emulated/")) {
            String backing = "/data/media/" + new java.io.File(shared).getName();
            if (parent.equals(backing) || parent.startsWith(backing + "/"))
                parent = shared + parent.substring(backing.length());
        }
        StreamArchiveExtraction.Format stream = StreamArchiveExtraction.format(name);
        String base = (stream == null ? name.substring(0,Math.max(0,name.lastIndexOf('.'))) : stream.stem(name)).trim();
        try { ArchiveExtraction.validateLeaf(base); }
        catch (IOException invalid) { base = "Extracted files"; }
        sheet.content.addView(sheet.text("Files go into a new folder. Nothing existing will be replaced.",13,MUTED));
        android.widget.EditText destination = new android.widget.EditText(this);
        destination.setSingleLine(true);
        destination.setText(parent == null || parent.startsWith("/data/") ? downloads : parent);
        destination.setContentDescription("Destination folder");
        android.widget.EditText folder = new android.widget.EditText(this);
        folder.setSingleLine(true);
        folder.setText(base);
        folder.setContentDescription("New folder name");
        sheet.content.addView(sheet.text("Destination folder",13,MUTED));
        LinearLayout destinationRow=row();
        destinationRow.addView(destination,new LinearLayout.LayoutParams(0,-2,1));
        ImageButton browse=actionIcon(R.drawable.ic_folder,"Browse extraction destination",View.generateViewId());
        browse.setImageTintList(android.content.res.ColorStateList.valueOf(UiTheme.fileColor(this,2)));
        browse.setOnClickListener(v->chooseExtractionFolder(destination));
        destinationRow.addView(browse,new LinearLayout.LayoutParams(dp(48),dp(48)));
        sheet.content.addView(destinationRow);
        sheet.content.addView(sheet.text("New folder name",13,MUTED));
        sheet.content.addView(folder,new LinearLayout.LayoutParams(-1,-2));
        sheet.action("Cancel",false,false,sheet::dismiss);
        Button extract = sheet.action("Extract here",true,false,null);
        extract.setOnClickListener(view->{
            String to = destination.getText().toString().trim(), leaf = folder.getText().toString().trim();
            try { FileOperationWorker.requirePath(to); }
            catch (IOException invalid) { destination.setError("Enter an absolute folder path."); return; }
            try { ArchiveExtraction.validateLeaf(leaf); }
            catch (IOException invalid) { folder.setError("Enter a safe folder name."); return; }
            sheet.dismiss();
            extractArchive(to,leaf);
        });
        sheet.show();
    }

    private void chooseExtractionFolder(android.widget.EditText destination){
        if(extractFolderPicker!=null)return;
        FileIndex index=FileIndex.get(this);
        String internal=android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
        java.util.function.UnaryOperator<String> normalize=path->{
            if(path==null||!path.startsWith("/"))return null;
            String direct=new File(path).toPath().normalize().toString();
            if(direct.equals("/sdcard")||direct.startsWith("/sdcard/"))direct=internal+direct.substring(7);
            try{return FileOperationWorker.requirePath(direct);}catch(IOException invalid){return null;}
        };
        java.util.Map<String,String> locations=new java.util.LinkedHashMap<>();
        locations.put(internal,"Internal storage");
        for(String root:index.roots()){
            String path=normalize.apply(root);
            if(path!=null&&path.startsWith("/storage/")&&!path.equals("/storage/emulated"))
                locations.putIfAbsent(path,new File(path).getName());
        }
        java.util.function.Predicate<String> allowed=path->{
            if(path==null)return false;
            for(String volume:locations.keySet())if(path.equals(volume)||path.startsWith(volume+"/")){
                String child=path.substring(volume.length());
                return !child.equals("/Android/data")&&!child.startsWith("/Android/data/")
                        &&!child.equals("/Android/obb")&&!child.startsWith("/Android/obb/");
            }
            return false;
        };
        extractFolderPicker=new FolderPathDialog(this,index,"Choose extraction folder",destination.getText().toString().trim(),normalize,
                destination::setText,()->extractFolderPicker=null,locations,allowed,false,true,false);
        extractFolderPicker.show();
    }

    private void extractArchive(String destination,String leaf) {
        if (!canExtract() || extracting) return;
        extracting = true;
        Context context = getApplicationContext();
        String sourcePath = path, identity = shown.identity, sourceName = name;
        StreamArchiveExtraction.Format stream = StreamArchiveExtraction.format(sourceName);
        boolean seven = kind == Kind.SEVEN_Z;
        Uri source = uri;
        boolean root = session != null && session.root;
        note("Extracting into " + leaf + "…");
        try {
            actions.execute(()->{
                String error = null;
                java.nio.file.Path temporary = null;
                try {
                    temporary = java.nio.file.Files.createTempDirectory(context.getCacheDir().getCanonicalFile().toPath(),"archive-stage-");
                    java.nio.file.Path staged = java.nio.file.Files.createDirectory(temporary.resolve(leaf));
                    try (ParcelFileDescriptor descriptor = source == null ? SafeFileAccess.open(context,sourcePath,root)
                            : context.getContentResolver().openFileDescriptor(source,"r")) {
                        if (descriptor == null || !identity.equals(RootFileProvider.identity(Os.fstat(descriptor.getFileDescriptor()))))
                            throw new IOException("The archive changed. Reopen it before extracting.");
                        try (ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(
                                ParcelFileDescriptor.dup(descriptor.getFileDescriptor()))) {
                            if (seven) SevenZipArchive.extract(input.getChannel(),staged);
                            else if (stream == null) ArchiveExtraction.extract(input.getChannel(),staged);
                            else StreamArchiveExtraction.extract(input.getChannel(),sourceName,stream,staged);
                        }
                        if (!identity.equals(RootFileProvider.identity(Os.fstat(descriptor.getFileDescriptor()))))
                            throw new IOException("The archive changed during extraction. Nothing was extracted.");
                    }
                    FileClipboard.Snapshot snapshot = FileClipboard.capture(context,java.util.Collections.singletonList(
                            new FileEntry(staged.toString(),true,0,-1)),false,false);
                    try (FileOperationProcess copy = new FileOperationProcess(context,snapshot.request(destination),false,null)) {
                        org.json.JSONObject result = copy.run();
                        if (!result.optBoolean("ok")) throw new IOException(result.optString("error","Could not write to this folder."));
                    }
                    try {
                        FileIndex.get(context).reconcilePaths(java.util.Collections.singletonList(destination+"/"+leaf));
                    } catch (Exception ignored) { /* The completed output is still available in Files. */ }
                } catch (Exception failure) {
                    error = failure instanceof IOException && failure.getMessage() != null
                            ? failure.getMessage() : "Could not extract to this folder. Check file access and free space.";
                } finally {
                    if (temporary != null) ArchiveExtraction.removeStaging(temporary);
                }
                final String problem = error;
                ui.post(()->{
                    extracting = false;
                    if (destroyed || isFinishing()) return;
                    String message = problem == null ? "Extracted to " + destination + "/" + leaf : problem;
                    if (sourcePath.equals(path)) note(message);
                    else Toast.makeText(this,message,Toast.LENGTH_LONG).show();
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            extracting = false; note("File access is busy. Try again.");
        }
    }

    private void showSevenZip(SevenZipArchive.Listing listing) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        TextView intro = text(listing.count == 0 ? "This archive is empty."
                : listing.count == 1 ? "1 archive entry" : listing.count + " archive entries",15,INK);
        intro.setPadding(dp(18),dp(12),dp(18),dp(10));
        column.addView(intro,new LinearLayout.LayoutParams(-1,-2));
        ScrollView scroll = new ScrollView(this);
        LinearLayout rows = new LinearLayout(this);
        rows.setPadding(dp(18),0,dp(18),dp(10));
        rows.setOrientation(LinearLayout.VERTICAL);
        for (SevenZipArchive.Entry entry : listing.entries) {
            TextView line = text(entry.name,14,INK);
            line.setMaxLines(2);
            line.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            line.setPadding(0,dp(9),0,0);
            if(!entry.directory){
                line.setMinHeight(dp(48));
                line.setOnClickListener(v->{
                    String leaf=entry.name.substring(entry.name.lastIndexOf('/')+1);
                    long length=Math.max(0,entry.size);
                    showArchiveMember(leaf,length,(app,channel)->ArchiveEntryFile.write(app,length,
                            output->SevenZipArchive.copyEntry(channel,entry,output)));
                });
            }
            rows.addView(line,new LinearLayout.LayoutParams(-1,-2));
            TextView metadata = text(entry.directory ? "Folder" : bytes(Math.max(0,entry.size)),12,MUTED);
            metadata.setPadding(0,0,0,dp(8));
            rows.addView(metadata,new LinearLayout.LayoutParams(-1,-2));
            rows.addView(viewerRule(),new LinearLayout.LayoutParams(-1,dp(1)));
        }
        if (listing.entries.size() < listing.count) {
            TextView more = text("Only the first " + listing.entries.size() + " entries are shown. Extract all includes the rest.",13,MUTED);
            more.setPadding(0,dp(12),0,dp(6));
            rows.addView(more,new LinearLayout.LayoutParams(-1,-2));
        }
        scroll.addView(rows,new ScrollView.LayoutParams(-1,-2));
        column.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if (canExtract()) showExtractDock(column);
        previewContent.addView(column,new FrameLayout.LayoutParams(-1,-1));
    }

    private void showStreamArchive(StreamArchiveExtraction.Format format,TarArchiveListing.Listing listing) {
        if (format == null) { failure("This archive format could not be identified."); return; }
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(22),dp(20),dp(22),dp(20));
        body.addView(text(format.label,18,INK));
        if(listing==null){
            body.addView(text("Extracts one file: " + StreamArchiveExtraction.decompressedName(name),14,MUTED));
        }else{
            String caption;
            switch(listing.stop){
                case COMPLETE: caption=listing.entries.isEmpty()?"This archive is empty."
                        :listing.entries.size()==1?"1 entry":"Contents · "+listing.entries.size()+" entries";break;
                case ENTRY_LIMIT:caption="Showing the first "+listing.entries.size()+" entries. More entries may follow.";break;
                case READ_LIMIT:caption="Partial listing. The preview reached its scan limit.";break;
                default:caption="Partial listing. An unsupported record needs a dedicated archive app.";
            }
            TextView description=text(caption,13,MUTED);
            description.setPadding(0,dp(4),0,dp(8));
            body.addView(description);
            for(TarArchiveListing.Member member:listing.entries){
                TextView label=text(member.name,14,INK);
                label.setMaxLines(2);
                label.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                label.setPadding(0,dp(8),0,0);
                if(TarArchiveListing.canPreview(member,format.gzip)
                        &&(!format.gzip||shown!=null&&shown.size<=TarArchiveListing.MAX_COMPRESSED)){
                    label.setMinHeight(dp(48));label.setTextColor(theme.accent);
                    label.setOnClickListener(v->{
                        String leaf=member.name.substring(member.name.lastIndexOf('/')+1);
                        showArchiveMember(leaf,member.size,(app,channel)->ArchiveEntryFile.write(app,member.size,
                                output->TarArchiveListing.copyEntry(channel,format.gzip,member,output)));
                    });
                }
                body.addView(label);
                TextView details=text(member.special?"Special file · review only"
                        :member.directory?"Folder":bytes(member.size),12,MUTED);
                details.setPadding(0,0,0,dp(8));
                body.addView(details);
                body.addView(viewerRule(),new LinearLayout.LayoutParams(-1,dp(1)));
            }
            String note=listing.specialEntries?"This archive contains links or special files. Use a dedicated archive app."
                    :shown!=null&&shown.size>ArchiveExtraction.MAX_BYTES?"Archives over 1 GB need a dedicated app to extract."
                     :"Tap an eligible file to preview it without extracting the archive.";
            TextView help=text(note,13,MUTED);help.setPadding(0,dp(12),0,0);
            body.addView(help);
        }
        scroll.addView(body,new ScrollView.LayoutParams(-1,-2));
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if(canExtract())showExtractDock(column);
        previewContent.addView(column,new FrameLayout.LayoutParams(-1,-1));
    }

    private void showArchive(ZipDirectory.Listing result) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        previewContent.addView(column,new FrameLayout.LayoutParams(-1,-1));
        if (result.entries.isEmpty() && !result.truncated) {
            TextView empty = text("This archive is empty.", 14, MUTED); empty.setGravity(Gravity.CENTER); column.addView(empty,new LinearLayout.LayoutParams(-1,0,1));
        } else {
            EdgeFastScroll.List entries=new EdgeFastScroll.List(this);
            entries.labels=p->result.entries.get(p).name;entries.enableReleasePick();archiveList=entries; archiveList.setPadding(dp(12),dp(8),dp(12),dp(8));
            archiveList.setDivider(new android.graphics.drawable.ColorDrawable(theme.line)); archiveList.setDividerHeight(dp(1));
            archiveList.setAdapter(new ArchiveAdapter(result.entries));
            archiveList.setOnItemClickListener((parent, view, position, id) -> showArchiveEntry(result.entries.get(position)));
            archiveList.setSelectionFromTop(Math.min(archivePosition, Math.max(0, result.entries.size() - 1)), archiveTop);
            column.addView(archiveList,new LinearLayout.LayoutParams(-1,0,1));
        }
        details.setText(metadata() + " · " + NumberFormat.getIntegerInstance().format(result.totalEntries) + (result.totalEntries == 1 ? " entry" : " entries"));
        if (canExtract()) showExtractDock(column);
        if (result.truncated) note("Showing " + result.entries.size() + " entries within the preview limit. Use Open for the full archive.");
    }

    private void showExtractDock(LinearLayout column) {
        // Keep the primary control aligned with the sheet's right-hand confirmation.
        column.addView(viewerRule(),new LinearLayout.LayoutParams(-1,dp(1)));
        LinearLayout dock = row();
        dock.setPadding(dp(20),dp(8),dp(20),dp(12));
        int width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(24),dp(560));
        LinearLayout.LayoutParams dockLayout = new LinearLayout.LayoutParams(Math.max(dp(48),width),-2);
        dockLayout.gravity = Gravity.CENTER_HORIZONTAL;
        column.addView(dock,dockLayout);
        dock.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
        Button extract = button("Extract all…");
        extract.setTextColor(theme.dark ? theme.surface : Color.WHITE);
        extract.setBackground(controlBackground(theme.accent,14));
        extract.setOnClickListener(v->chooseExtraction());
        LinearLayout.LayoutParams action = new LinearLayout.LayoutParams(0,dp(48),1);
        action.setMarginStart(dp(10));
        dock.addView(extract,action);
    }

    private void showArchiveEntry(ZipDirectory.Entry entry) {
        if (shown == null) return;
        if (activeSheet != null) activeSheet.dismiss();
        String entryName = entry.name.substring(entry.name.lastIndexOf('/') + 1);
        Kind format = kindFor(entryName, mimeFor(entryName));
        if (format == Kind.AUDIO || format == Kind.VIDEO || format == Kind.OFFICE
                || format == Kind.ZIP || format == Kind.SEVEN_Z) {
            openArchivePreview(entry,entryName);
            return;
        }
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
                            if (result != null) handOffFile(result, entryName, true, true);
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

    private void openArchivePreview(ZipDirectory.Entry entry,String entryName){
        showArchiveMember(entryName,entry.size,(app,channel)->{
            if(entry.special||entry.encrypted||entry.crc<0)throw new IOException("This archive entry cannot be safely previewed.");
            try(java.util.zip.CheckedInputStream input=new java.util.zip.CheckedInputStream(
                    ZipDirectory.open(channel,entry),new java.util.zip.CRC32())){
                ParcelFileDescriptor copy=ArchiveEntryFile.copy(app,input,entry.size);
                if(input.getChecksum().getValue()!=entry.crc){
                    copy.close();throw new IOException("This archive entry is damaged: checksum does not match.");
                }
                return copy;
            }
        });
    }

    private interface ArchiveMemberReader {
        ParcelFileDescriptor decode(Context app,java.nio.channels.FileChannel channel)throws Exception;
    }

    /** Reuse the bounded ordinary viewer after reading a verified private source. */
    private void showArchiveMember(String entryName,long entrySize,ArchiveMemberReader member){
        if(shown==null)return;
        AppSheet progress=new AppSheet(this,entryName);
        activeSheet=progress;
        TextView status=progress.text("Checking archive entry…",14,MUTED);
        progress.content.addView(status);
        progress.action("Close",false,false,null);
        String sourcePath=path,identity=shown.identity;
        Uri sourceUri=uri;
        boolean root=session!=null&&session.root;
        Context app=getApplicationContext();
        CancellationSignal canceled=new CancellationSignal();
        java.util.concurrent.atomic.AtomicReference<Future<?>> pending=new java.util.concurrent.atomic.AtomicReference<>();
        progress.setOnDismissListener(ignored->{
            if(activeSheet==progress)activeSheet=null;
            canceled.cancel();
            Future<?> task=pending.getAndSet(null);
            if(task!=null)task.cancel(true);
        });
        progress.show();
        try{
            pending.set(PREVIEWS.submit(()->{
                Uri uri=null;
                String error=null;
                try{
                    int depth=ArchivePreviewSources.depth(sourceUri)+1;
                    if(depth>ArchivePreviewSources.MAX_DEPTH)throw new IOException("Nested archive previews are limited to four levels.");
                    try(ParcelFileDescriptor descriptor=sourceUri==null?SafeFileAccess.open(app,sourcePath,root)
                                :app.getContentResolver().openFileDescriptor(sourceUri,"r",canceled)){
                        if(descriptor==null||!identity.equals(RootFileProvider.identity(Os.fstat(descriptor.getFileDescriptor()))))
                            throw new IOException("The archive changed. Reopen it.");
                        try(ParcelFileDescriptor.AutoCloseInputStream archive=new ParcelFileDescriptor.AutoCloseInputStream(
                                ParcelFileDescriptor.dup(descriptor.getFileDescriptor()))){
                            ParcelFileDescriptor snapshot=member.decode(app,archive.getChannel());
                            try{
                                if(!identity.equals(RootFileProvider.identity(Os.fstat(descriptor.getFileDescriptor()))))
                                    throw new IOException("The archive changed while reading its entry.");
                                if(canceled.isCanceled()||Thread.currentThread().isInterrupted())
                                    throw new java.io.InterruptedIOException();
                                uri=ArchivePreviewSources.publish(snapshot,entrySize,entryName,depth);
                                snapshot=null;
                            }finally{if(snapshot!=null)snapshot.close();}
                        }
                    }
                }catch(Exception|OutOfMemoryError failure){error=archivePreviewError(failure);}
                Uri prepared=uri;String message=error;
                if(canceled.isCanceled()||destroyed||!MAIN.post(()->{
                    if(!isCurrentSheet(progress)){ArchivePreviewSources.release(prepared);return;}
                    if(prepared==null){status.setText(message==null?"Could not open this archive entry.":message);return;}
                    pending.set(null);
                    progress.dismiss();activeSheet=null;
                    try{startActivity(intent(this,prepared,entryName,null,entrySize));}
                    catch(RuntimeException unable){
                        ArchivePreviewSources.release(prepared);
                        Toast.makeText(this,"Could not open this archive entry.",Toast.LENGTH_LONG).show();
                    }
                }))ArchivePreviewSources.release(prepared);
            }));
        }catch(java.util.concurrent.RejectedExecutionException busy){
            status.setText("Preview is busy. Close this message and try again.");
        }
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
                throw new IOException("This entry is not supported by the in-archive reader.");
        }
    }

    private final class ArchiveAdapter extends BaseAdapter {
        private final List<ZipDirectory.Entry> entries;
        private final boolean packageContents;
        ArchiveAdapter(List<ZipDirectory.Entry> entries) { this(entries,false); }
        ArchiveAdapter(List<ZipDirectory.Entry> entries,boolean packageContents) {
            this.entries=entries; this.packageContents=packageContents;
        }
        @Override public int getCount() { return entries.size(); }
        @Override public Object getItem(int position) { return entries.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public boolean areAllItemsEnabled() { return false; }
        @Override public boolean isEnabled(int position) {
            ZipDirectory.Entry entry = entries.get(position);
            return packageContents || !entry.directory && !entry.encrypted;
        }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            LinearLayout item;
            if (recycled instanceof LinearLayout) item = (LinearLayout) recycled;
            else {
                item = row(); item.setMinimumHeight(dp(58)); item.setPadding(dp(6), dp(8), dp(6), dp(8));
                android.graphics.drawable.StateListDrawable press=new android.graphics.drawable.StateListDrawable();
                press.addState(new int[]{android.R.attr.state_pressed},AppSheet.shape(theme.selected,dp(8)));
                press.addState(new int[0],new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
                item.setBackground(press);
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
            FileEntry iconEntry=new FileEntry(entry.name,entry.directory,entry.size,0);
            icon.setImageResource(UiTheme.fileIcon(FileViewerActivity.this,iconEntry));
            icon.setImageTintList(UiTheme.fileTint(FileViewerActivity.this,FileTypes.category(iconEntry)));
            LinearLayout labels = (LinearLayout) item.getChildAt(1);
            ((TextView) labels.getChildAt(0)).setText(entry.name);
            ((TextView) labels.getChildAt(1)).setText(packageContents
                    ? ApkPackageContents.type(entry)+(entry.directory?"":" · "+bytes(entry.size))
                    : (entry.directory ? "Folder" : bytes(entry.size)) + (entry.encrypted ? " · encrypted" : ""));
            item.getChildAt(2).setVisibility(!packageContents&&(entry.directory||entry.encrypted)?View.INVISIBLE:View.VISIBLE);
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

    private View addLoadingIndicator(FrameLayout frame,String label){
        LinearLayout pill=row();pill.setPadding(dp(12),0,dp(12),0);
        pill.setBackground(panel(theme.surface,14));pill.setGravity(Gravity.CENTER);
        pill.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        ProgressBar progress=new ProgressBar(this,null,android.R.attr.progressBarStyleSmall);
        progress.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
        progress.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        pill.addView(progress,new LinearLayout.LayoutParams(dp(20),dp(20)));
        TextView status=text(label,12,INK);status.setPadding(dp(10),0,0,0);
        pill.addView(status,new LinearLayout.LayoutParams(-2,dp(44)));
        frame.addView(pill,new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER));
        return pill;
    }

    private void failure(String message){failure(message,false);}
    private void failure(String message,boolean retry){
        loadingPlaceholder=null;formatGuideTarget=null;previewContent.removeAllViews();
        ScrollView body=new ScrollView(this);body.setFillViewport(false);
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setGravity(Gravity.START);
        content.setPadding(dp(24),dp(16),dp(24),dp(16));
        ImageView file=new ImageView(this);file.setImageResource(R.drawable.ic_file);file.setImageTintList(ColorStateList.valueOf(MUTED));
        file.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        content.addView(file,new LinearLayout.LayoutParams(dp(22),dp(22)));
        TextView heading=text("Preview unavailable",16,INK);heading.setPadding(0,dp(12),0,0);
        heading.setTypeface(getResources().getFont(R.font.inter_semibold));if(Build.VERSION.SDK_INT>=28)heading.setAccessibilityHeading(true);
        content.addView(heading);
        TextView error=text(message,13,MUTED);error.setPadding(0,dp(6),0,dp(10));error.setLineSpacing(dp(2),1f);content.addView(error);
        if(retry)addPreviewAction(content,"Try again",this::load);
        if(canReadAsText())addPreviewAction(content,"Read as text",()->{
            plainTextOverride=true;kind=Kind.TEXT;load();
        });
        if(uri!=null||session!=null)addPreviewAction(content,"Open in another app",this::openExternal);
        if(canReturnFile())addPreviewAction(content,"Show in Anything Files",this::revealFile);
        body.addView(content,new ScrollView.LayoutParams(-1,-2));
        previewContent.addView(body,new FrameLayout.LayoutParams(-1,-2,Gravity.CENTER));
    }
    private void addPreviewAction(LinearLayout column,String label,Runnable action){
        Button button=button(label);button.setTextColor(ACCENT);button.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
        button.setPadding(0,0,dp(8),0);button.setMinHeight(dp(48));button.setOnClickListener(v->action.run());
        column.addView(button,new LinearLayout.LayoutParams(-2,-2));
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
        handOffFile(uri, name, share, false);
    }

    private void handOffFile(Uri file, String label, boolean share, boolean archiveEntry) {
        sharingArchiveEntry = archiveEntry;
        String failure = ExternalFileActions.launch(this, file, label, share);
        if (failure != null) {
            sharingArchiveEntry = false;
            if (archiveEntry) Toast.makeText(this,failure,Toast.LENGTH_LONG).show();
            else note(failure);
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
        text.setTypeface(getResources().getFont(R.font.inter));return text;
    }
    private LinearLayout row() { LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL); return row; }
    private Button button(String label) {
        Button button = new Button(this, null, 0); button.setText(label); button.setAllCaps(false); button.setTextSize(13);
        button.setGravity(Gravity.CENTER);
        button.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled},{}},new int[]{MUTED,ACCENT}));
        button.setTypeface(getResources().getFont(R.font.inter_medium));
        button.setMinHeight(0); button.setMinimumHeight(0); button.setMinWidth(0); button.setMinimumWidth(0);
        button.setBackground(controlBackground(Color.TRANSPARENT,24)); return button;
    }

    static String mimeFor(String name) { return ExternalFileActions.mimeFor(name); }
    private boolean canReadAsText() {
        return kind==Kind.OTHER && !isExternalArchive(extension(name)) && !isSplitPackage(extension(name))
                && (uri!=null || session!=null && !path.isEmpty());
    }

    private static boolean isExternalArchive(String ext) {
        return has("rar xz bz2 zst tbz2 txz",ext);
    }

    private static boolean isSplitPackage(String ext) {
        return has("apks apkm xapk",ext);
    }

    private static String otherPreviewMessage(String ext) {
        if (isSplitPackage(ext)) return "This is an APK bundle, not a standalone APK. It needs a compatible bundle installer.";
        if (isExternalArchive(ext)) return "This archive format needs a dedicated archive app. Extract ZIP, JAR, CBZ, TAR, GZIP, and supported 7z files directly in Anything.";
        return "Preview is unavailable for this file type.";
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.'); return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
    private static Kind kindFor(String name, String mime) {
        String ext = extension(name);
        if ("pdf".equals(ext)) return Kind.PDF;
        if (OfficePreview.format(name)!=null) return Kind.OFFICE;
        if(has("svg svgz",ext))return Kind.SVG;
        if(has("md markdown",ext))return Kind.MARKDOWN;
        if(has("html htm",ext))return Kind.HTML;
        if ("apk".equals(ext)) return Kind.APK;
        if ("dex".equals(ext)) return Kind.DEX;
        if (ArchiveExtraction.supports(ext)) return Kind.ZIP;
        if ("7z".equals(ext)) return Kind.SEVEN_Z;
        if (StreamArchiveExtraction.format(name) != null) return Kind.STREAM_ARCHIVE;
        // .ts can be TypeScript or MPEG transport stream; the worker checks the file header before opening.
        if ("ts".equals(ext)) return Kind.TEXT;
        if (has("jpg jpeg png gif webp bmp heic heif avif ico", ext) || (mime.startsWith("image/") && !"svg".equals(ext) && !"svgz".equals(ext))) return Kind.IMAGE;
        if (mime.startsWith("video/") || has("mp4 m4v mkv webm 3gp 3gpp mov avi mpeg mpg ts mts", ext)) return Kind.VIDEO;
        if (mime.startsWith("audio/") || has("mp3 m4a aac wav ogg oga opus flac amr mid midi aif aiff", ext)) return Kind.AUDIO;
        if (mime.startsWith("text/") || CodeSyntax.known(name)) return Kind.TEXT;
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
        mediaLoading=addLoadingIndicator(mediaAnchor,kind==Kind.VIDEO?"Preparing video":"Preparing audio");
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
        failure("This device can’t play this media format or codec.");
    }
    private void releaseMedia() {
        ui.removeCallbacks(mediaTimeout);
        if (controller != null) { controller.hide(); controller.setEnabled(false); controller = null; }
        mediaLoading=null;
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
            activity.prepared=true;activity.ui.removeCallbacks(activity.mediaTimeout);activity.controller.setEnabled(true);
            if(activity.mediaLoading!=null){activity.mediaAnchor.removeView(activity.mediaLoading);activity.mediaLoading=null;}
            if(activity.kind==Kind.VIDEO&&!activity.playWhenReady&&activity.previewContent!=null)activity.previewContent.reveal();
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
        final CodeSyntax.Model code;
        final OfficePreview.Result office;
        final ZipDirectory.Listing archive;
        final TarArchiveListing.Listing tar;
        final int orientation,width,height,page,pageCount,bytes;
        PreviewData(PreviewResult result){bitmap=result.bitmap;picture=result.picture;formatted=result.formatted;text=result.text;code=result.code;office=result.office;archive=result.archive;tar=result.tar;orientation=result.orientation;
            width=result.width;height=result.height;page=result.page;pageCount=result.pageCount;
            int count=256+result.vectorBytes+(bitmap==null?0:bitmap.getAllocationByteCount())+(text==null?0:text.text.length()*2)+(formatted==null?0:formatted.length()*4)+(code==null?0:code.memoryBytes())+(office==null?0:office.memoryBytes());
            if(archive!=null)for(ZipDirectory.Entry entry:archive.entries)count+=80+entry.name.length()*2+(entry.rawName==null?0:entry.rawName.length()*2);
            if(tar!=null)for(TarArchiveListing.Member member:tar.entries)count+=64+member.name.length()*2;
            bytes=count;result.sharedBitmap=true;}
        void copyTo(PreviewResult result){result.bitmap=bitmap;result.picture=picture;result.formatted=formatted;result.text=text;result.code=code;result.office=office;result.archive=archive;result.tar=tar;result.orientation=orientation;
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
        CodeSyntax.Model code;
        OfficePreview.Result office;
        PresentationSlide slide;
        boolean transportStream;
        ZipDirectory.Listing archive;
        TarArchiveListing.Listing tar;
        SevenZipArchive.Listing sevenZip;
        ApkInstallAction.Details apk;
        int orientation = 1, width, height, page, pageCount;
        long size = -1;
        long cpuNanos;
        String error,key,identity;
        boolean sharedBitmap,cached;
        ViewState state;
        @Override public void close() {
            if (bitmap != null) { if (!sharedBitmap&&!bitmap.isRecycled()) bitmap.recycle(); bitmap = null; }
            if (media != null) { try { media.close(); } catch (IOException ignored) { } media = null; }
            if (apk != null) { apk.close(); apk = null; }
            picture=null;formatted=null;text = null;code=null;office=null;slide=null; archive = null; tar = null; sevenZip = null;
        }
    }

    private static final class PreviewJob implements Runnable {
        final WeakReference<FileViewerActivity> owner;
        final Context context;
        Uri uri;
        FileEntry entry;
        boolean allowRoot,restoreState;
        final String sourcePath;
        String sourceName;
        final CodeSyntax.Language language;
        final Kind kind;
        final int requestedPage, generation;
        final AtomicBoolean cancelled = new AtomicBoolean();
        final CancellationSignal signal = new CancellationSignal();
        PreviewJob(FileViewerActivity owner, Context context, Uri uri, Kind kind, int page, int generation) {
            this.owner = new WeakReference<>(owner); this.context = context; this.uri = uri; this.kind = kind; requestedPage = page; this.generation = generation;
            sourcePath=owner==null?String.valueOf(uri):owner.path;sourceName=owner==null?"":owner.name;language=owner==null?null:owner.readerLanguage;allowRoot=owner!=null&&owner.session!=null&&owner.session.root;
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
                if(kind==Kind.OTHER){
                    String ext = extension(sourceName);
                    result.error = otherPreviewMessage(ext);
                    return result;
                }
                file = uri==null&&entry!=null?SafeFileAccess.open(context,entry.path(),allowRoot):context.getContentResolver().openFileDescriptor(uri, "r", signal);
                if (file == null) throw new IOException("File access is unavailable.");
                checkCancelled(); result.size = file.getStatSize();
                if (result.size < 0) throw new IOException("This file does not support a seekable preview.");
                result.identity=RootFileProvider.identity(Os.fstat(file.getFileDescriptor()));
                result.key=sourcePath+'\0'+allowRoot+':'+result.identity;
                if(kind==Kind.TEXT&&"ts".equals(extension(sourceName))&&looksLikeTransportStream(file,result.size)){
                    result.transportStream=true;result.media=file;file=null;return result;
                }
                boolean preciseMetadata=Build.VERSION.SDK_INT>=27;
                if(restoreState&&preciseMetadata)synchronized(VIEW_STATES){result.state=VIEW_STATES.get(result.key);}
                int wantedPage=result.state==null?requestedPage:result.state.page;
                String cacheKey=result.key+':'+kind+':'+(kind==Kind.PDF?wantedPage:0)+':'+sourceName+':'+language;
                // API 26 exposes only whole-second metadata. Slides are read lazily; don't retain pages and their images in the general cache.
                boolean isPresentation=kind==Kind.OFFICE&&OfficePreview.format(sourceName)==OfficePreview.Format.SLIDES;
                boolean cacheable=preciseMetadata&&kind!=Kind.APK&&kind!=Kind.SEVEN_Z&&!isPresentation;
                PreviewData existing=cacheable?CACHE.get(cacheKey):null;
                if(existing!=null){existing.copyTo(result);return result;}
                if (kind == Kind.IMAGE || kind == Kind.PDF) {
                    if (result.size > MAX_DOCUMENT_BYTES) throw new PreviewLimit("Files larger than 128 MiB need another app.");
                    if (kind == Kind.IMAGE) decodeImage(file, result); else renderPdf(file, result,wantedPage);
                } else if (kind == Kind.SVG) {
                    try(ParcelFileDescriptor.AutoCloseInputStream input=new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(file.getFileDescriptor()))){
                        SvgPreview.Result vector=SvgPreview.read(input);result.picture=vector.picture;result.vectorBytes=vector.source.length*32;
                        result.width=vector.picture.getWidth();result.height=vector.picture.getHeight();result.text=PreviewText.read(new java.io.ByteArrayInputStream(vector.source));}
                } else if (kind == Kind.TEXT || kind==Kind.MARKDOWN || kind==Kind.HTML) {
                    try (ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(file.getFileDescriptor()))) { result.text = PreviewText.read(input); }
                    checkCancelled();if(kind==Kind.MARKDOWN)result.formatted=RichText.markdown(result.text.text);else if(kind==Kind.HTML)result.formatted=RichText.html(result.text.text);
                } else if (kind == Kind.OFFICE) {
                    try (ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(file.getFileDescriptor()))) {
                        if(isPresentation){
                            try{result.slide=PresentationSlide.render(OfficePreview.readSlide(input.getChannel(),wantedPage),false);}
                            catch(IOException unavailable){
                                checkCancelled();
                                // The same bounded text-only reader stays available when visual drawing is unsupported.
                                input.getChannel().position(0);
                                try{result.office=OfficePreview.read(input.getChannel(),OfficePreview.Format.SLIDES);}
                                catch(IOException noOutline){throw unavailable;}
                            }
                        }else result.office=OfficePreview.read(input.getChannel(),OfficePreview.format(sourceName));
                    }
                } else if (kind == Kind.ZIP) {
                    try (ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(file.getFileDescriptor()))) { result.archive = ZipDirectory.read(input.getChannel()); }
                } else if (kind == Kind.SEVEN_Z) {
                    try (ParcelFileDescriptor.AutoCloseInputStream input =
                                 new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(file.getFileDescriptor()))) {
                        result.sevenZip = SevenZipArchive.inspect(input.getChannel());
                    }
                } else if (kind == Kind.STREAM_ARCHIVE) {
                    try (ParcelFileDescriptor.AutoCloseInputStream input =
                                 new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(file.getFileDescriptor()))) {
                        StreamArchiveExtraction.Format format=StreamArchiveExtraction.format(sourceName);
                        if(format==null)throw new IOException("Unrecognized archive format.");
                        if(format.tar)result.tar=TarArchiveListing.read(input.getChannel(),format.gzip);
                        else StreamArchiveExtraction.inspect(input.getChannel(),format);
                    }
                } else if (kind == Kind.APK) {
                    result.apk = ApkInstallAction.inspect(context,file);
                } else if (kind != Kind.DEX) { result.media = file; file = null; }
                if(result.text!=null)result.code=CodeSyntax.model(sourceName,result.text.text,language);
                checkCancelled();
                if(file!=null){
                    if(!result.identity.equals(RootFileProvider.identity(Os.fstat(file.getFileDescriptor())))){throw new IOException("File changed while loading.");}
                    if(cacheable)CACHE.put(cacheKey,new PreviewData(result));
                }
            } catch (StackOverflowError complex) {
                result.close();result.error="This file is too complex to preview.";
            } catch (OutOfMemoryError exhausted) {
                result.close(); result.error = "This file needs too much memory to preview.";
            } catch (SecurityException denied) {
                result.close(); result.error = kind == Kind.PDF ? "This PDF is protected or cannot be rendered."
                        : "File access expired or was denied. Return to results and open it again.";
            } catch (Exception failure) {
                result.close();
                if (failure instanceof PreviewLimit || (failure instanceof IOException && (kind == Kind.TEXT || kind == Kind.OFFICE || kind == Kind.ZIP || kind == Kind.STREAM_ARCHIVE || kind == Kind.SEVEN_Z || kind == Kind.APK || kind==Kind.SVG || kind==Kind.MARKDOWN || kind==Kind.HTML)))
                    result.error = failure.getMessage();
                else result.error = "Could not preview this file. It may have changed, or its format may be unsupported.";
                if (result.error == null || result.error.length() > 240) result.error = "Could not read this file.";
            } finally { if (file != null) try { file.close(); } catch (IOException ignored) { }result.cpuNanos=android.os.Debug.threadCpuTimeNanos()-cpuStart; }
            return result;
        }

        private static boolean looksLikeTransportStream(ParcelFileDescriptor file,long size) throws android.system.ErrnoException, IOException {
            if(size<4L*188)return false;
            byte[] header=new byte[(int)Math.min(size,5L*204)];
            int read=Os.pread(file.getFileDescriptor(),header,0,header.length,0);
            for(int packet:new int[]{188,192,204}){
                int offset=packet==192?4:0;
                if(read<=offset+3*packet)continue;
                boolean sync=true;
                for(int i=0;i<4;i++)if(header[offset+i*packet]!=0x47){sync=false;break;}
                if(sync)return true;
            }
            return false;
        }

        private void decodeImage(ParcelFileDescriptor file, PreviewResult result) throws Exception {
            decodeImage(file,result,MAX_BITMAP_EDGE);
        }

        private void decodeImage(ParcelFileDescriptor file, PreviewResult result,int maxEdge) throws Exception {
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFileDescriptor(file.getFileDescriptor(), null, bounds);
            checkCancelled();
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new PreviewLimit("This image format is unsupported on this device.");
            result.width = bounds.outWidth; result.height = bounds.outHeight;
            result.orientation = ImageOrientation.read(file);
            checkCancelled(); Os.lseek(file.getFileDescriptor(), 0, OsConstants.SEEK_SET);
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inPreferredConfig = Bitmap.Config.ARGB_8888; options.inScaled = false;
            options.inSampleSize = 1;
            while ((long) Math.ceil(bounds.outWidth / (double) options.inSampleSize) * (long) Math.ceil(bounds.outHeight / (double) options.inSampleSize) > MAX_BITMAP_PIXELS
                    || Math.ceil(bounds.outWidth / (double) options.inSampleSize) > maxEdge || Math.ceil(bounds.outHeight / (double) options.inSampleSize) > maxEdge) {
                if (options.inSampleSize > (1 << 28)) throw new PreviewLimit("This image is too large to preview.");
                options.inSampleSize *= 2;
            }
            result.bitmap = BitmapFactory.decodeFileDescriptor(file.getFileDescriptor(), null, options);
            checkCancelled();
            if (result.bitmap == null) throw new PreviewLimit("This image format is unsupported on this device.");
            if ((long) result.bitmap.getWidth() * result.bitmap.getHeight() > MAX_BITMAP_PIXELS
                    || result.bitmap.getWidth() > maxEdge || result.bitmap.getHeight() > maxEdge
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
        renderPdfPage(file,result,wantedPage,MAX_PDF_EDGE);
    }

    private static void renderPdfPage(ParcelFileDescriptor file, PreviewResult result, int wantedPage,int maxEdge) throws Exception {
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
                float scale = Math.min(maxEdge / (float) Math.max(width, height),
                        (float) Math.sqrt(MAX_BITMAP_PIXELS / ((double) width * height)));
                int bitmapWidth = Math.max(1, (int) (width * scale)), bitmapHeight = Math.max(1, (int) (height * scale));
                result.bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888);
                result.bitmap.eraseColor(Color.WHITE);
                pdf.render(result.bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                PreviewText.cancelled();
            }
        }
    }

    // Inline previews use the same no-follow source and decoders, with smaller rendered images.
    enum QuickKind { IMAGE, PDF, TEXT, AUDIO, VIDEO, UNSUPPORTED }
    static QuickKind quickKind(String name) {
        switch (kindFor(name,mimeFor(name))) {
            case IMAGE: return QuickKind.IMAGE;
            case PDF: return QuickKind.PDF;
            case TEXT: case MARKDOWN: case HTML: return QuickKind.TEXT;
            case AUDIO: return QuickKind.AUDIO;
            case VIDEO: return QuickKind.VIDEO;
            default: return QuickKind.UNSUPPORTED;
        }
    }
    static final class InlineImage implements AutoCloseable {
        final Bitmap bitmap;
        final int orientation;
        InlineImage(Bitmap bitmap,int orientation){this.bitmap=bitmap;this.orientation=orientation;}
        @Override public void close(){if(!bitmap.isRecycled())bitmap.recycle();}
    }
    static Object readInline(Context context,FileEntry entry,boolean root,QuickKind format)throws Exception {
        if(format==QuickKind.UNSUPPORTED)throw new IOException("Quick preview isn’t available for this file type.");
        try(ParcelFileDescriptor file=SafeFileAccess.open(context,entry.path(),root)) {
            long bytes=file.getStatSize();
            if(bytes<0)throw new IOException("This file cannot be read here.");
            if(format!=QuickKind.TEXT&&bytes>MAX_DOCUMENT_BYTES)
                throw new IOException("This file is too large for quick preview.");
            if(format==QuickKind.TEXT) {
                try(ParcelFileDescriptor.AutoCloseInputStream input=
                            new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(file.getFileDescriptor()))) {
                    PreviewText.Result text=PreviewText.read(input);
                    String value=text.text;
                    // The quick viewport scrolls. Keep an independent UI-layout budget.
                    int end=Math.min(value.length(),12_000),lines=0;
                    for(int i=0;i<end;i++) {
                        char c=value.charAt(i);
                        if((c=='\n'||c=='\r'&&(i+1>=end||value.charAt(i+1)!='\n'))&&++lines>=180){
                            end=i;break;
                        }
                    }
                    if(end<value.length()&&end>0&&Character.isHighSurrogate(value.charAt(end-1)))end--;
                    boolean partial=text.truncated||end<value.length();
                    return (end==value.length()?value:value.substring(0,end))
                            +(partial?"\n\nPreview shortened. Open to read more.":"");
                }
            }
            String identity=RootFileProvider.identity(Os.fstat(file.getFileDescriptor()));
            try(PreviewResult result=new PreviewResult()) {
                if(format==QuickKind.PDF) renderPdfPage(file,result,0,960);
                else if(format==QuickKind.IMAGE) {
                    PreviewJob worker=new PreviewJob(null,context,null,Kind.IMAGE,0,0);
                    worker.decodeImage(file,result,960);
                } else throw new IOException("Select Play to preview audio or video.");
                PreviewText.cancelled();
                if(!identity.equals(RootFileProvider.identity(Os.fstat(file.getFileDescriptor()))))
                    throw new IOException("The file changed. Reopen it to preview.");
                InlineImage image=new InlineImage(result.bitmap,result.orientation);
                result.bitmap=null;
                return image;
            }
        }
    }

    /** Codec/descriptor checks without launching an Activity or touching a user's files. */
    static PreviewCheck checkForProbe(Context context, Uri uri, String name, int page) {
        PreviewJob task = new PreviewJob(null, context.getApplicationContext(), uri, kindFor(name, mimeFor(name)), page, 0);
        task.sourceName = name;
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
