package dev.fastsearch.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.graphics.pdf.PdfRenderer;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import android.util.LruCache;
import android.widget.ImageView;
import dev.fastsearch.core.FileEntry;
import java.lang.ref.WeakReference;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Visible-only requests; reusable bitmaps are owned by ThumbnailStore, never by an Activity. */
final class ThumbnailLoader implements AutoCloseable {
    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,15,TimeUnit.SECONDS,new ArrayBlockingQueue<>(48),r->{Thread t=new Thread(r,"file-thumbnail");t.setPriority(Thread.MIN_PRIORITY);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final java.util.WeakHashMap<ImageView,Request> bindings=new java.util.WeakHashMap<>();
    private final java.util.Map<String,Job> jobs=new java.util.HashMap<>();
    private final java.util.LinkedHashMap<String,Long> unavailable=new java.util.LinkedHashMap<String,Long>(128,.75f,true){@Override protected boolean removeEldestEntry(java.util.Map.Entry<String,Long> entry){return size()>256;}};
    private final Runnable dispatch=this::dispatchVisible;
    private volatile boolean closed;
    private boolean paused, scheduled, flinging;
    private final android.graphics.Rect visibleBounds = new android.graphics.Rect();
    private final java.util.concurrent.atomic.AtomicInteger sourceDecodes=new java.util.concurrent.atomic.AtomicInteger(),diskHits=new java.util.concurrent.atomic.AtomicInteger();
    ThumbnailLoader(Context context){this.context=context.getApplicationContext();ThumbnailStore.register(this.context);workers.allowCoreThreadTimeOut(true);}
    private static final class Request {
        final String key,packageName;final FileEntry entry;final int edge;final boolean root,pdf,fitted;
        Request(String key,FileEntry entry,int edge,boolean root,boolean pdf,boolean fitted,String packageName){this.key=key;this.entry=entry;this.edge=edge;this.root=root;this.pdf=pdf;this.fitted=fitted;this.packageName=packageName;}
    }
    void bind(ImageView view,FileEntry entry,int edge,boolean root){bind(view,entry,edge,root,false);}
    void bindPreview(ImageView view,FileEntry entry,int edge,boolean root){bind(view,entry,edge,root,true);}
    private void bind(ImageView view,FileEntry entry,int edge,boolean root,boolean fitted){
        int category=FileTypes.category(entry),bucket=edge<=96?96:edge<=192?192:384;
        float density=context.getResources().getDisplayMetrics().density;
        String extension=entry.directory?"":FileSubtypes.extension(entry);
        boolean pdf=(fitted||edge>=80*density)&&extension.equals("pdf"),svg=extension.equals("svg")||extension.equals("svgz");
        if(!closed&&(category==6||category==7||pdf||svg)){
            watch(view);boolean fit=fitted||pdf||svg;
            String key=entry.path()+'\0'+entry.modified+':'+entry.size+':'+root+':'+bucket;
            boolean same=key.equals(view.getTag());view.setTag(key);
            Request previous = bindings.get(view);
            if (previous == null || !previous.key.equals(key) || previous.fitted != fit)
                bindings.put(view,new Request(key,entry,bucket,root,pdf,fit,null));
            if(same&&view.getDrawable() instanceof android.graphics.drawable.BitmapDrawable){view.setScaleType(fit?ImageView.ScaleType.FIT_CENTER:ImageView.ScaleType.CENTER_CROP);viewportChanged();return;}
            Bitmap cached=ThumbnailStore.get(key);if(cached!=null){apply(view,cached,fit);viewportChanged();return;}
            viewportChanged();
        }else {view.setTag(null);if(bindings.remove(view)!=null)viewportChanged();}
        int iconEdge=Math.max(Math.round(24*density),Math.min(Math.round(64*density),Math.round(edge*.6f)));
        int placeholder=category*4096+iconEdge;
        if(Integer.valueOf(placeholder).equals(view.getTag(R.id.thumbnail_placeholder)))return;
        view.setTag(R.id.thumbnail_placeholder,placeholder);
        view.setImageTintList(android.content.res.ColorStateList.valueOf(UiTheme.fileColor(context,category)));
        Drawable icon=context.getDrawable(FileTypes.ICONS[category]);android.graphics.drawable.LayerDrawable sized=new android.graphics.drawable.LayerDrawable(new Drawable[]{icon});
        sized.setLayerSize(0,iconEdge,iconEdge);
        view.setImageDrawable(sized);view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
    }
    void bindApp(ImageView view,String packageName){if(closed)return;watch(view);String key="app:"+packageName;boolean same=key.equals(view.getTag());view.setTag(key);
        bindings.put(view,new Request(key,null,128,false,false,false,packageName));Bitmap cached=ThumbnailStore.get(key);
        if(cached!=null)apply(view,cached,false);else if(!same)view.setImageResource(R.drawable.ic_app);viewportChanged();}
    private static final class Watch implements android.view.View.OnAttachStateChangeListener,android.view.View.OnLayoutChangeListener {
        final WeakReference<ThumbnailLoader> owner;Watch(ThumbnailLoader loader){owner=new WeakReference<>(loader);}
        void changed(){ThumbnailLoader loader=owner.get();if(loader!=null)loader.viewportChanged();}
        public void onViewAttachedToWindow(android.view.View view){changed();}public void onViewDetachedFromWindow(android.view.View view){changed();}
        public void onLayoutChange(android.view.View view,int l,int t,int r,int b,int oldL,int oldT,int oldR,int oldB){changed();}
    }
    private void watch(ImageView view){Object previous=view.getTag(R.id.thumbnail_watch);if(previous instanceof Watch){Watch watch=(Watch)previous;if(watch.owner.get()==this)return;view.removeOnAttachStateChangeListener(watch);view.removeOnLayoutChangeListener(watch);}
        Watch watch=new Watch(this);view.setTag(R.id.thumbnail_watch,watch);view.addOnAttachStateChangeListener(watch);view.addOnLayoutChangeListener(watch);
    }
    void setFlinging(boolean flinging) {
        if (this.flinging == flinging) return;
        this.flinging = flinging;
        if (!flinging) viewportChanged();
    }
    void viewportChanged(){if(closed||paused||flinging||scheduled)return;scheduled=true;main.post(dispatch);}
    private boolean visible(ImageView view,Request request){return view!=null&&request.key.equals(view.getTag())&&view.isAttachedToWindow()&&view.isShown()&&view.getGlobalVisibleRect(visibleBounds);}
    private void dispatchVisible(){scheduled=false;if(closed||paused||flinging)return;java.util.LinkedHashMap<String,Request> wanted=new java.util.LinkedHashMap<>();long now=android.os.SystemClock.uptimeMillis();
        for(java.util.Map.Entry<ImageView,Request> item:bindings.entrySet()){ImageView view=item.getKey();Request request=item.getValue();if(!visible(view,request))continue;
            if(view.getDrawable() instanceof android.graphics.drawable.BitmapDrawable)continue;
            Bitmap cached=ThumbnailStore.get(request.key);if(cached!=null){apply(view,cached,request.fitted);continue;}
            Long failed=unavailable.get(request.key);if(failed==null||now-failed>3000)wanted.put(request.key,request);
        }
        java.util.Iterator<java.util.Map.Entry<String,Job>> iterator=jobs.entrySet().iterator();while(iterator.hasNext()){java.util.Map.Entry<String,Job> item=iterator.next();if(!wanted.containsKey(item.getKey())){item.getValue().cancel();workers.remove(item.getValue());iterator.remove();}}
        for(Request request:wanted.values()){if(jobs.containsKey(request.key))continue;Job job=new Job(request);jobs.put(request.key,job);try{workers.execute(job);}catch(java.util.concurrent.RejectedExecutionException full){jobs.remove(request.key);}}
    }
    private final class Job implements Runnable {
        final Request request;volatile boolean cancelled;private Thread running;
        final android.os.CancellationSignal signal=new android.os.CancellationSignal();
        Job(Request request){this.request=request;}
        synchronized void cancel(){cancelled=true;if(running!=null)running.interrupt();signal.cancel();}
        private boolean stopped(){return cancelled||closed||Thread.currentThread().isInterrupted();}
        @Override public void run(){synchronized(this){if(cancelled||closed)return;running=Thread.currentThread();}
            Bitmap bitmap=null;boolean decoded=false;
            try{if(stopped())return;bitmap=ThumbnailStore.get(request.key);
                if(bitmap==null&&request.entry!=null){bitmap=ThumbnailStore.read(context,request.key);if(bitmap!=null)diskHits.incrementAndGet();}
                if(bitmap==null&&!stopped()){
                    sourceDecodes.incrementAndGet();decoded=true;
                    if(request.entry!=null)bitmap=decode(request.entry,request.edge,request.root,request.pdf,signal);
                    else{Drawable icon=context.getPackageManager().getApplicationIcon(request.packageName);bitmap=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888);icon.setBounds(0,0,128,128);icon.draw(new Canvas(bitmap));}
                }
                // A native decoder may finish after its cell leaves the viewport. Reuse that
                // completed work in bounded memory; cancellation still skips disk compression.
                if(bitmap!=null)ThumbnailStore.put(request.key,bitmap);
                if(stopped())return;Bitmap result=bitmap;
                main.post(()->{if(closed||cancelled)return;if(jobs.get(request.key)==this)jobs.remove(request.key);
                    if(flinging)return;
                    if(result==null){unavailable.put(request.key,android.os.SystemClock.uptimeMillis());viewportChanged();return;}
                    for(java.util.Map.Entry<ImageView,Request> item:bindings.entrySet())if(request.key.equals(item.getValue().key)&&visible(item.getKey(),item.getValue()))apply(item.getKey(),result,item.getValue().fitted);
                    viewportChanged();
                });
                if(decoded&&bitmap!=null&&request.entry!=null&&!stopped())ThumbnailStore.write(context,request.key,bitmap);
            }catch(Exception|OutOfMemoryError failure){main.post(()->{if(jobs.get(request.key)==this)jobs.remove(request.key);if(!closed&&!cancelled){unavailable.put(request.key,android.os.SystemClock.uptimeMillis());viewportChanged();}});}
            finally{synchronized(this){running=null;}}
        }
    }
    private Bitmap decode(FileEntry entry, int edge, boolean allowRoot, boolean pdf) {
        return decode(entry,edge,allowRoot,pdf,new android.os.CancellationSignal());
    }
    private Bitmap decode(FileEntry entry,int edge,boolean allowRoot,boolean pdf,android.os.CancellationSignal signal){
        Bitmap provider=MediaThumbnails.load(context,entry,edge,allowRoot,signal);
        if(provider!=null)return provider;
        if(signal.isCanceled()||Thread.currentThread().isInterrupted())return null;
        try (ParcelFileDescriptor file = SafeFileAccess.openThumbnail(context, entry.path(), allowRoot)) {
            if (closed || Thread.currentThread().isInterrupted()) return null;
            String lower=entry.name.toLowerCase(java.util.Locale.ROOT);
            if(lower.endsWith(".svg")||lower.endsWith(".svgz")){
                android.graphics.Picture picture;
                try(ParcelFileDescriptor.AutoCloseInputStream input=new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(file.getFileDescriptor()))){picture=SvgPreview.read(input).picture;}
                float scale=edge/(float)Math.max(picture.getWidth(),picture.getHeight());
                Bitmap bitmap=Bitmap.createBitmap(Math.max(1,Math.round(picture.getWidth()*scale)),Math.max(1,Math.round(picture.getHeight()*scale)),Bitmap.Config.ARGB_8888);
                android.graphics.Canvas canvas=new android.graphics.Canvas(bitmap);canvas.scale(scale,scale);canvas.drawPicture(picture);return bitmap;
            }
            if (pdf) {
                if (file.getStatSize() > FileViewerActivity.MAX_DOCUMENT_BYTES) return null;
                try (PdfRenderer renderer = new PdfRenderer(file)) {
                    if (renderer.getPageCount() == 0) return null;
                    try (PdfRenderer.Page page = renderer.openPage(0)) {
                        float scale = edge / (float) Math.max(page.getWidth(), page.getHeight());
                        Bitmap bitmap = Bitmap.createBitmap(Math.max(1, Math.round(page.getWidth() * scale)),
                                Math.max(1, Math.round(page.getHeight() * scale)), Bitmap.Config.ARGB_8888);
                        bitmap.eraseColor(android.graphics.Color.WHITE);bitmap.setHasAlpha(false);
                        try { page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); return bitmap; }
                        catch (RuntimeException failure) { bitmap.recycle(); throw failure; }
                    }
                }
            }
            if (FileTypes.category(entry) == 7) {
                // API 26 cannot request a bounded-size video frame. Keep its video icon.
                if (Build.VERSION.SDK_INT < 27) return null;
                MediaMetadataRetriever video = new MediaMetadataRetriever();
                try {
                    video.setDataSource(file.getFileDescriptor());
                    Bitmap frame=video.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, edge, edge);
                    if(frame!=null)frame.setHasAlpha(false);return frame;
                } finally { video.release(); }
            }
            if(Build.VERSION.SDK_INT>=28){
                // Decode directly at the cell's size. The procfs path stays pinned by file.
                android.graphics.ImageDecoder.Source source=android.graphics.ImageDecoder.createSource(new java.io.File("/proc/self/fd/"+file.getFd()));
                return android.graphics.ImageDecoder.decodeBitmap(source,(decoder,info,ignored)->{
                    android.util.Size size=info.getSize();float scale=Math.min(1,edge/(float)Math.max(size.getWidth(),size.getHeight()));
                    decoder.setTargetSize(Math.max(1,Math.round(size.getWidth()*scale)),Math.max(1,Math.round(size.getHeight()*scale)));
                    decoder.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);
                    decoder.setMemorySizePolicy(android.graphics.ImageDecoder.MEMORY_POLICY_LOW_RAM);
                });
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFileDescriptor(file.getFileDescriptor(), null, options);
            if (options.outWidth <= 0 || options.outHeight <= 0) return null;
            int sample = 1;
            while (options.outWidth / sample > edge * 2 || options.outHeight / sample > edge * 2) sample *= 2;
            options.inSampleSize = sample;
            options.inJustDecodeBounds = false;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            int orientation = 1;
            try {
                Os.lseek(file.getFileDescriptor(), 0, OsConstants.SEEK_SET);
                orientation = new ExifInterface(file.getFileDescriptor()).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
            } catch (java.io.IOException | RuntimeException unsupportedExif) { /* Some formats carry no EXIF. */ }
            Os.lseek(file.getFileDescriptor(), 0, OsConstants.SEEK_SET);
            Bitmap bitmap = BitmapFactory.decodeFileDescriptor(file.getFileDescriptor(), null, options);
            if(bitmap!=null&&Math.max(bitmap.getWidth(),bitmap.getHeight())>edge){float scale=edge/(float)Math.max(bitmap.getWidth(),bitmap.getHeight());
                Bitmap fitted=Bitmap.createScaledBitmap(bitmap,Math.max(1,Math.round(bitmap.getWidth()*scale)),Math.max(1,Math.round(bitmap.getHeight()*scale)),true);
                if(fitted!=bitmap)bitmap.recycle();bitmap=fitted;}
            if (bitmap == null || orientation <= 1 || orientation > 8) return bitmap;
            Matrix transform = ZoomPreviewView.orientationMatrix(orientation);
            try {
                Bitmap oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), transform, true);
                if (oriented != bitmap) bitmap.recycle();
                return oriented;
            } catch (RuntimeException | OutOfMemoryError failure) { bitmap.recycle(); throw failure; }
        } catch (Exception | OutOfMemoryError | StackOverflowError unavailable) { return null; }
    }

    private static void apply(ImageView view,Bitmap bitmap,boolean fitted){view.setTag(R.id.thumbnail_placeholder,null);view.setImageTintList(null);view.setScaleType(fitted?ImageView.ScaleType.FIT_CENTER:ImageView.ScaleType.CENTER_CROP);view.setImageBitmap(bitmap);}
    void pause(){paused=true;cancelJobs();}
    void resume(){paused=false;viewportChanged();}
    private void cancelJobs(){main.removeCallbacksAndMessages(null);scheduled=false;for(Job job:jobs.values())job.cancel();jobs.clear();workers.getQueue().clear();}
    void trim(){cancelJobs();ThumbnailStore.clearMemory();unavailable.clear();if(paused)for(ImageView view:bindings.keySet())if(view!=null)view.setImageDrawable(null);}
    int cachedBytes(){return closed?0:ThumbnailStore.bytes();}int cacheLimitBytes(){return ThumbnailStore.limit();}
    int queuedJobs(){return workers.getQueue().size();}int activeJobs(){return workers.getActiveCount();}
    int sourceDecodesForProbe(){return sourceDecodes.get();}int diskHitsForProbe(){return diskHits.get();}
    boolean isStopped(){return closed&&workers.isShutdown();}
    boolean awaitStopped(long millis)throws InterruptedException{return workers.awaitTermination(millis,TimeUnit.MILLISECONDS);}
    @Override public void close(){closed=true;cancelJobs();workers.shutdownNow();for(ImageView view:bindings.keySet())if(view!=null){Object tag=view.getTag(R.id.thumbnail_watch);if(tag instanceof Watch&&((Watch)tag).owner.get()==this){view.removeOnAttachStateChangeListener((Watch)tag);view.removeOnLayoutChangeListener((Watch)tag);view.setTag(R.id.thumbnail_watch,null);}}bindings.clear();unavailable.clear();}
}
