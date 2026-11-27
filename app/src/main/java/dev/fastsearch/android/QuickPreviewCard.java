package dev.fastsearch.android;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.RippleDrawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import dev.fastsearch.core.FileEntry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/** Opt-in popup content. Selected files are never exported to render or play here. */
final class QuickPreviewCard extends LinearLayout implements AutoCloseable {
    static final String PREFERENCE = "quickPreviewFileMenu";
    static final String SETTING = "Quick preview file menu";
    private final UiTheme theme;
    private final FileEntry entry;
    private final boolean root;
    private final Context app;
    private final ExecutorService worker;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final FrameLayout area;
    private final TextView status,title;
    private final ScrollView previewScroll,overflowScroll;
    private final ImageButton back,more;
    private final LinearLayout fallbacks;
    private final int previewHeight;
    private boolean overflow;
    private final FileViewerActivity.QuickKind format;
    private final Runnable prepareTimeout=this::mediaError;
    private Future<?> task;
    private ZoomPreviewView image;
    private FileViewerActivity.InlineImage pixels;
    private TextureView texture;
    private Surface surface;
    private MediaPlayer player;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private int focusEpoch;
    private ImageButton play;
    private boolean ready, started, preparing;
    private volatile boolean closed;
    private Consumer<ClipData> probeClipboard;

    QuickPreviewCard(Context context,UiTheme theme,FileEntry entry,boolean root,ExecutorService worker,
                     Menu model,Consumer<String> action,FileActionCard standard,String pasteDestination,
                     Runnable confirmPaste,int width,int height,Runnable close,Runnable previous,Runnable next) {
        super(context);
        this.theme=theme;this.entry=entry;this.root=root;this.worker=worker;
        app=context.getApplicationContext();
        format=FileViewerActivity.quickKind(entry.name);
        setOrientation(VERTICAL);
        setBackground(AppSheet.shape(theme.chrome,dp(24)));
        setClipToOutline(true);
        setPaddingRelative(dp(12),dp(4),dp(12),dp(4));
        previewHeight=Math.max(dp(76),Math.min(dp(format==FileViewerActivity.QuickKind.AUDIO?120:422),height-dp(174)));

        LinearLayout heading=row();
        back=icon(R.drawable.ic_back,"Back to quick preview",this::returnToPreview);
        back.setVisibility(GONE);
        heading.addView(back);
        title=label(entry.name,16,theme.ink);
        title.setMaxLines(2);title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        title.setTypeface(getResources().getFont(R.font.inter_semibold));
        if(Build.VERSION.SDK_INT>=28)title.setAccessibilityHeading(true);
        heading.addView(title,new LayoutParams(0,-2,1));
        heading.addView(icon(R.drawable.ic_close,"Close quick preview",close));
        addView(heading,new LayoutParams(-1,dp(48)));

        FrameLayout pages=new FrameLayout(context);
        previewScroll=new ScrollView(context);
        previewScroll.setFillViewport(true);
        LinearLayout document=new LinearLayout(context);
        document.setOrientation(VERTICAL);
        document.setGravity(Gravity.BOTTOM);
        previewScroll.addView(document,new ScrollView.LayoutParams(-1,-2));


        area=new FrameLayout(context);
        area.setBackground(AppSheet.shape(theme.surface,dp(12)));
        area.setClipToOutline(true);
        boolean text=format==FileViewerActivity.QuickKind.TEXT;
        document.addView(area,new LayoutParams(-1,text||format==FileViewerActivity.QuickKind.UNSUPPORTED?-2:previewHeight));
        status=label("",14,theme.muted);
        status.setTextIsSelectable(text);
        status.setPadding(dp(12),dp(12),dp(12),dp(12));
        status.setGravity(text?Gravity.TOP|Gravity.START:Gravity.CENTER);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        area.addView(status,new FrameLayout.LayoutParams(-1,text||format==FileViewerActivity.QuickKind.UNSUPPORTED?-2:-1));

        fallbacks=row();
        addTextAction(fallbacks,model,"Open",action);
        addTextAction(fallbacks,model,"Open with",action);
        fallbacks.setVisibility(GONE);
        document.addView(fallbacks,new LayoutParams(-1,-2));
        TextView meta=label(StorageMapView.typeLabel(entry)+"  ·  "+(entry.size<0?"Size unavailable"
                :android.text.format.Formatter.formatShortFileSize(context,entry.size)),12,theme.muted);
        meta.setPaddingRelative(dp(6),dp(12),dp(6),dp(2));
        document.addView(meta,new LayoutParams(-1,-2));
        if(!entry.parent.isEmpty()){
            TextView location=label(entry.path(),12,theme.muted);
            location.setPaddingRelative(dp(6),dp(1),dp(6),dp(8));
            location.setTextIsSelectable(true);FileViewerActivity.simpleTextLayout(location);
            document.addView(location,new LayoutParams(-1,-2));
        }

        QuickPreviewSwipe swipe=new QuickPreviewSwipe(context,previous,next,()->!closed&&!overflow
                &&(image==null||!image.isMagnified()),
                ()->!closed&&!overflow&&!status.hasSelection());
        swipe.addView(previewScroll,new FrameLayout.LayoutParams(-1,-1));
        pages.addView(swipe,new FrameLayout.LayoutParams(-1,-1));

        overflowScroll=new MenuDragScroll(context,standard::actionAt);
        overflowScroll.setBackgroundColor(theme.chrome);
        overflowScroll.setClickable(true); // Absorb empty-area taps rather than selecting the hidden preview.
        overflowScroll.addView(standard,new ScrollView.LayoutParams(-1,-2));
        overflowScroll.setVisibility(GONE);
        pages.addView(overflowScroll,new FrameLayout.LayoutParams(-1,-1));
        addView(pages,new LayoutParams(-1,0,1));

        LinearLayout commands=row();
        commands.setGravity(Gravity.CENTER_VERTICAL|Gravity.END);
        if(width>=dp(216))addAction(commands,model,R.drawable.ic_document,"Open",action);
        addAction(commands,model,R.drawable.ic_copy,"Copy",action);
        addAction(commands,model,R.drawable.ic_copy,"Copy to other pane",action);
        if(width>=dp(320)){
            addAction(commands,model,R.drawable.ic_cut,"Move",action);
            addAction(commands,model,R.drawable.ic_cut,"Move to other pane",action);
        }
        if(width>=dp(376))addAction(commands,model,R.drawable.ic_share,"Share",action);
        if(confirmPaste!=null)commands.addView(icon(R.drawable.ic_paste,"Paste to "+pasteDestination,confirmPaste));
        more=icon(R.drawable.ic_more,"Show all file actions",this::toggleActions);
        commands.addView(more);
        View separator=new View(context);separator.setBackgroundColor(theme.line);
        separator.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(separator,new LayoutParams(-1,dp(1)));
        addView(commands,new LayoutParams(-1,dp(48)));

        LinearLayout foot=row();
        Button filename=copyButton("Copy filename",()->copy("File name",entry.name));
        Button folder=copyButton("Copy containing folder",()->copy("Containing folder",entry.parent));
        folder.setEnabled(!entry.parent.isEmpty());
        foot.addView(filename,new LayoutParams(0,-1,1));
        LayoutParams folderSize=new LayoutParams(0,-1,1);folderSize.setMarginStart(dp(4));
        foot.addView(folder,folderSize);
        addView(foot,new LayoutParams(-1,dp(48)));
    }

    boolean hasOverflow(){return overflow;}

    boolean returnToPreview(){
        if(!overflow)return false;
        toggleActions();
        return true;
    }

    private void toggleActions(){
        overflow=!overflow;
        // Leave the preview mounted so text scroll, zoom, and TextureView/player state survive.
        ((View)previewScroll.getParent()).setImportantForAccessibility(overflow?IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                :IMPORTANT_FOR_ACCESSIBILITY_YES);
        previewScroll.setDescendantFocusability(overflow?FOCUS_BLOCK_DESCENDANTS:FOCUS_AFTER_DESCENDANTS);
        overflowScroll.setVisibility(overflow?VISIBLE:GONE);
        back.setVisibility(overflow?VISIBLE:GONE);
        title.setText(overflow?"File actions":entry.name);
        String label=overflow?"Back to quick preview":"Show all file actions";
        more.setContentDescription(label);more.setTooltipText(label);
        if(overflow){back.requestFocus();title.announceForAccessibility("File actions");}
        else more.requestFocus();
    }

    private void fallback(String message){
        status.setText(message);
        fallbacks.setVisibility(VISIBLE);
    }

    void start() {
        if(started||closed)return;
        started=true;
        switch(format) {
            case UNSUPPORTED:
                fallback("No quick preview for this file type.");
                return;
            case AUDIO: case VIDEO:
                showPlay();
                return;
            default:
                status.setText("Opening quick preview…");
        }
        try {
            task=worker.submit(()->{
                Object value=null;
                try { value=FileViewerActivity.readInline(app,entry,root,format); }
                catch(Exception|OutOfMemoryError|StackOverflowError unavailable) { /* Show a clean fallback. */ }
                Object result=value;
                if(closed||Thread.currentThread().isInterrupted()||!main.post(()->showResult(result)))discard(result);
            });
        }catch(RejectedExecutionException busy) {
            fallback("Quick preview is busy. Use Open or try again.");
        }
    }

    private void showResult(Object result) {
        if(closed){discard(result);return;}
        if(result instanceof FileViewerActivity.InlineImage) {
            pixels=(FileViewerActivity.InlineImage)result;
            area.removeView(status);
            image=new ZoomPreviewView(getContext());
            image.setContentDescription(format==FileViewerActivity.QuickKind.PDF?"First page":"Image preview");
            area.addView(image,new FrameLayout.LayoutParams(-1,-1));
            image.setImage(pixels.bitmap,pixels.orientation);
        } else if(result instanceof String) {
            String text=(String)result;
            status.setGravity(Gravity.TOP|Gravity.START);
            FileViewerActivity.simpleTextLayout(status);
            status.setText(text.isEmpty()?"This file is empty.":text);
        } else {
            fallback("Couldn’t show this file here.");
        }
    }

    private void showPlay() {
        status.setText("");
        play=icon(R.drawable.ic_play, "Play quick preview", this::toggle);
        play.setImageTintList(ColorStateList.valueOf(theme.accent));
        play.setBackground(new RippleDrawable(ColorStateList.valueOf(theme.line),
                AppSheet.shape(theme.selected,dp(16)),null));
        area.addView(play,new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.CENTER));
    }

    private void toggle() {
        if(closed||preparing)return;
        if(player!=null&&ready) {
            try {
                if(player.isPlaying()){player.pause();abandonFocus();playState(false);}
                else beginPlayback();
            }catch(RuntimeException unavailable){mediaError();}
            return;
        }
        preparing=true;
        play.setVisibility(View.INVISIBLE);
        status.setText("Preparing playback…");
        main.postDelayed(prepareTimeout,15000);
        if(format==FileViewerActivity.QuickKind.VIDEO) {
            texture=new TextureView(getContext());
            area.addView(texture,0,new FrameLayout.LayoutParams(-1,-1));
            texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                public void onSurfaceTextureAvailable(SurfaceTexture value,int width,int height) {
                    if(closed||!preparing)return;
                    surface=new Surface(value);
                    prepareMedia();
                }
                public void onSurfaceTextureSizeChanged(SurfaceTexture value,int width,int height){fitVideo(width,height);}
                public void onSurfaceTextureUpdated(SurfaceTexture value){}
                public boolean onSurfaceTextureDestroyed(SurfaceTexture value){
                    if(!closed&&(preparing||ready))mediaError();
                    return true;
                }
            });
        } else prepareMedia();
    }

    private void prepareMedia() {
        try{
            task=worker.submit(()->{
                ParcelFileDescriptor source=null;
                try{source=SafeFileAccess.open(app,entry.path(),root);}
                catch(Exception failure){ /* handled on the main thread */ }
                ParcelFileDescriptor opened=source;
                if(closed||Thread.currentThread().isInterrupted()||!main.post(()->attachMedia(opened)))closeFd(opened);
            });
        }catch(RejectedExecutionException busy){mediaError();}
    }

    private void attachMedia(ParcelFileDescriptor source) {
        try(ParcelFileDescriptor media=source) {
            if(closed||!preparing)return;
            if(media==null||media.getStatSize()<=0)throw new java.io.IOException("File no longer readable");
            MediaPlayer value=new MediaPlayer();
            player=value;
            value.setAudioAttributes(attributes());
            if(surface!=null)value.setSurface(surface);
            value.setDataSource(media.getFileDescriptor());
            value.setOnErrorListener((owner,what,extra)->{if(owner==player)mediaError();return true;});
            value.setOnVideoSizeChangedListener((owner,width,height)->{
                if(owner==player&&texture!=null)fitVideo(texture.getWidth(),texture.getHeight());
            });
            value.setOnCompletionListener(owner->{
                if(owner!=player)return;
                abandonFocus();
                playState(false);
            });
            value.setOnPreparedListener(owner->{
                if(closed||owner!=player)return;
                ready=true;preparing=false;main.removeCallbacks(prepareTimeout);
                status.setVisibility(GONE);
                play.setVisibility(VISIBLE);
                if(texture!=null)fitVideo(texture.getWidth(),texture.getHeight());
                beginPlayback();
            });
            value.prepareAsync();
        }catch(Exception failure){if(!closed)mediaError();}
    }

    private AudioAttributes attributes() {
        return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(format==FileViewerActivity.QuickKind.VIDEO
                        ?AudioAttributes.CONTENT_TYPE_MOVIE:AudioAttributes.CONTENT_TYPE_MUSIC).build();
    }

    private void beginPlayback() {
        if(closed||!ready||player==null)return;
        audio=app.getSystemService(AudioManager.class);
        if(audio==null)return;
        if(focus==null) {
            int wanted=++focusEpoch;
            focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attributes()).setOnAudioFocusChangeListener(change->{
                        if(change==AudioManager.AUDIOFOCUS_GAIN||closed||player==null||!ready||wanted!=focusEpoch)return;
                        try{player.pause();}catch(RuntimeException ignored){}
                        abandonFocus();playState(false);
                    },main).build();
        }
        if(audio.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            abandonFocus();
            status.setText("Audio focus unavailable. Tap Play to try again.");
            status.setVisibility(VISIBLE);
            playState(false);
            return;
        }
        try{player.start();status.setVisibility(GONE);playState(true);}
        catch(RuntimeException failure){mediaError();}
    }

    private void fitVideo(int width,int height) {
        if(texture==null||player==null||!ready||width<=0||height<=0)return;
        int videoWidth=player.getVideoWidth(),videoHeight=player.getVideoHeight();
        if(videoWidth<=0||videoHeight<=0)return;
        float scale=Math.min((float)width/videoWidth,(float)height/videoHeight);
        android.graphics.Matrix matrix=new android.graphics.Matrix();
        matrix.setScale(videoWidth*scale/width,videoHeight*scale/height,width*.5f,height*.5f);
        texture.setTransform(matrix);
    }

    private void playState(boolean playing) {
        if(play==null)return;
        String label=playing?"Pause quick preview":"Play quick preview";
        play.setContentDescription(label);play.setTooltipText(label);
        play.setImageResource(playing?R.drawable.ic_pause:R.drawable.ic_play);
        FrameLayout.LayoutParams place=new FrameLayout.LayoutParams(dp(48),dp(48),
                texture!=null?Gravity.BOTTOM|Gravity.END:Gravity.CENTER);
        play.setLayoutParams(place);
        play.setVisibility(VISIBLE);
    }

    private void mediaError() {
        if(closed)return;
        if(task!=null)task.cancel(true);
        releaseMedia();
        if(play!=null)play.setVisibility(GONE);
        fallback("Can't play this file here.");
        status.setVisibility(VISIBLE);
    }

    private void releaseMedia() {
        main.removeCallbacks(prepareTimeout);
        ready=false;preparing=false;
        MediaPlayer value=player;player=null;
        if(value!=null){try{value.release();}catch(RuntimeException ignored){}}
        if(texture!=null){texture.setSurfaceTextureListener(null);area.removeView(texture);texture=null;}
        if(surface!=null){surface.release();surface=null;}
        abandonFocus();
    }

    private void abandonFocus() {
        AudioFocusRequest request=focus;focus=null;focusEpoch++;
        if(audio!=null&&request!=null)audio.abandonAudioFocusRequest(request);
    }

    @Override public void close() {
        if(closed)return;
        closed=true;
        if(task!=null){task.cancel(true);task=null;}
        releaseMedia();
        // A detached draw may still use these pixels. Drop the references rather
        // than recycling a published bitmap while the renderer retires the frame.
        if(image!=null){image.setImage(null,1);image=null;}
        pixels=null;
    }

    void clipboardForProbe(Consumer<ClipData> clipboard) {
        if(BuildConfig.DEBUG)probeClipboard=clipboard;
    }

    private void copy(String label,String content) {
        if(closed||content.isEmpty())return;
        ClipData clip=ClipData.newPlainText(label,content);
        if(probeClipboard!=null){probeClipboard.accept(clip);return;}
        ClipboardManager clipboard=app.getSystemService(ClipboardManager.class);
        if(clipboard!=null){
            clipboard.setPrimaryClip(clip);
            if(Build.VERSION.SDK_INT<33)android.widget.Toast.makeText(getContext(),label+" copied",
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void addTextAction(LinearLayout row,Menu menu,String label,Consumer<String> run) {
        for(int i=0;i<menu.size();i++){
            MenuItem item=menu.getItem(i);
            if(label.contentEquals(item.getTitle())&&item.isVisible()&&item.isEnabled())
                row.addView(copyButton(label,()->run.accept(label)),new LayoutParams(0,-2,1));
        }
    }

    private void addAction(LinearLayout row,Menu menu,int resource,String label,Consumer<String> run) {
        for(int i=0;i<menu.size();i++) {
            MenuItem item=menu.getItem(i);
            if(!label.contentEquals(item.getTitle())||!item.isVisible())continue;
            ImageButton button=icon(resource,label,()->run.accept(label));
            button.setEnabled(item.isEnabled());button.setAlpha(item.isEnabled()?1:.45f);
            row.addView(button);return;
        }
    }

    private LinearLayout row() {
        LinearLayout result=new LinearLayout(getContext());result.setGravity(Gravity.CENTER_VERTICAL);return result;
    }
    private TextView label(String value,int size,int color) {
        TextView result=new TextView(getContext());result.setText(value);result.setTextSize(size);result.setTextColor(color);
        result.setTypeface(getResources().getFont(R.font.inter));
        return result;
    }
    private ImageButton icon(int resource,String label,Runnable click) {
        ImageButton button=new ImageButton(getContext());
        button.setImageResource(resource);button.setImageTintList(ColorStateList.valueOf(theme.ink));
        button.setContentDescription(label);button.setTooltipText(label);button.setPadding(dp(13),dp(13),dp(13),dp(13));
        button.setLayoutParams(new LayoutParams(dp(48),dp(48)));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(theme.line),null,
                AppSheet.shape(Color.WHITE,dp(12))));
        button.setOnClickListener(v->{if(!closed)click.run();});return button;
    }
    private Button copyButton(String title,Runnable click) {
        Button button=new Button(getContext());
        button.setText(title);button.setAllCaps(false);button.setTextSize(12);button.setTextColor(theme.accent);
        button.setMinHeight(dp(48));button.setPadding(dp(3),dp(5),dp(3),dp(5));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(theme.line),null,
                AppSheet.shape(Color.WHITE,dp(10))));
        button.setOnClickListener(v->click.run());return button;
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private static void discard(Object result) {
        if(result instanceof FileViewerActivity.InlineImage)((FileViewerActivity.InlineImage)result).close();
    }
    private static void closeFd(ParcelFileDescriptor source) {
        if(source!=null)try{source.close();}catch(java.io.IOException ignored){}
    }
}
