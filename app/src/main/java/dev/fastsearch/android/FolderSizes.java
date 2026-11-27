package dev.fastsearch.android;

import android.content.Context;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.OperationCanceledException;
import android.os.SystemClock;
import android.text.format.Formatter;

import dev.fastsearch.core.FileEntry;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.text.NumberFormat;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Short-lived per-index metadata cache and coalesced, cancellable folder measurements. */
final class FolderSizes {
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final ThreadPoolExecutor WORK=new ThreadPoolExecutor(2,2,15,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(12),task->{
        Thread thread=new Thread(task,"folder-size");thread.setDaemon(true);return thread;
    });
    private static final ScheduledThreadPoolExecutor DEADLINES=new ScheduledThreadPoolExecutor(1,task->{
        Thread thread=new Thread(task,"folder-size-deadline");thread.setDaemon(true);return thread;
    });
    static {
        WORK.allowCoreThreadTimeOut(true);
        DEADLINES.setRemoveOnCancelPolicy(true);
        DEADLINES.setKeepAliveTime(15,TimeUnit.SECONDS);
        DEADLINES.allowCoreThreadTimeOut(true);
    }
    private static final long CACHE_MS=4_000;
    private static final int MAX_CACHE=24;
    private static final int MAX_INDEXED_ROWS=350_000;
    private static final long SCAN_MS=6_000;

    private static final int EXACT=0,PARTIAL=1,INDEXED=2,UNAVAILABLE=3;
    static final class Result {
        final long bytes;
        private final int quality;
        private Result(long bytes,int quality){this.bytes=bytes;this.quality=quality;}
        boolean isExact(){return quality==EXACT;}
        String label(Context context){
            if(quality==UNAVAILABLE)return "Size unavailable";
            String amount=Formatter.formatFileSize(context,bytes);
            if(quality==INDEXED)return amount+" indexed · not verified";
            if(quality==PARTIAL)return bytes==0?"Incomplete · some entries not checked":amount+" counted · incomplete";
            return amount;
        }
        String details(Context context){
            String text=label(context);
            if(!isExact()||bytes<1000)return text;
            return text+"\n"+NumberFormat.getIntegerInstance().format(bytes)+" bytes";
        }
    }
    static Result exact(long bytes){return new Result(bytes,EXACT);}
    static Result partial(long bytes){return new Result(bytes,PARTIAL);}
    static Result unavailable(){return new Result(0,UNAVAILABLE);}

    private final FileIndex index;
    // State below is main-thread only. In-flight jobs don't hold Activity or View references.
    private final Map<String,Job> jobs=new HashMap<>();
    private final LinkedHashMap<String,Cache> cache=new LinkedHashMap<>(16,.75f,true);

    FolderSizes(FileIndex index){this.index=index;}

    /** Subscribe on main. allowPrivilegedWalk is true only for explicit Properties. */
    Runnable observe(String path,boolean allowPrivilegedWalk,Consumer<Result> callback){
        if(path==null||!path.startsWith("/")){callback.accept(unavailable());return ()->{};}
        final boolean root=index.isRootEnabled();
        final boolean measure=!root||allowPrivilegedWalk;
        FileIndex.State snapshot=index.getState();
        int generation=index.folderSizeGeneration();
        Cache saved=cache.get(path);
        long now=SystemClock.elapsedRealtime();
        boolean fresh=saved!=null&&saved.root==root&&saved.snapshot.get()==snapshot.entries
                &&saved.generation==generation&&now<saved.expiry;
        if(fresh&&(saved.measured||!measure)){
            callback.accept(saved.result);return ()->{};
        }
        String key=(root?"root:":"files:")+path+(measure?"":"|index-only");
        if(!measure){
            Job measured=jobs.get("root:"+path);
            if(measured!=null&&measured.snapshot.entries==snapshot.entries&&measured.valid())key=measured.key;
        }
        Job job=jobs.get(key);
        if(job!=null&&(job.snapshot.entries!=snapshot.entries||!job.valid())){
            jobs.remove(key);
            job.cancel();
            job=null;
        }
        if(job==null){
            job=new Job(key,path,snapshot,root,measure,generation,fresh?saved.result:null);
            jobs.put(key,job);
            try{WORK.execute(job);}catch(RejectedExecutionException busy){
                jobs.remove(key);callback.accept(unavailable());return ()->{};
            }
        }
        Job active=job;
        active.listeners.add(callback);
        if(active.latest!=null)callback.accept(active.latest);
        return ()->{
            if(active.listeners.remove(callback)&&active.listeners.isEmpty()&&jobs.get(active.key)==active){
                // A card followed immediately by Properties should share its active scan.
                MAIN.postDelayed(()->{
                    if(active.listeners.isEmpty()&&jobs.get(active.key)==active){
                        jobs.remove(active.key);
                        active.cancel();
                    }
                },260);
            }
        };
    }

    void modeChanged(){
        if(Looper.myLooper()!=MAIN.getLooper()){MAIN.post(this::modeChanged);return;}
        for(Job job:jobs.values())job.cancel();
        jobs.clear();
        cache.clear();
    }

    private final class Job implements Runnable {
        final String key,path;
        final FileIndex.State snapshot;
        final boolean root,measure;
        final int generation;
        final CancellationSignal cancellation=new CancellationSignal();
        final List<Consumer<Result>> listeners=new ArrayList<>();
        final Result seed;
        Result latest;
        Job(String key,String path,FileIndex.State snapshot,boolean root,boolean measure,int generation,Result seed){
            this.key=key;this.path=path;this.snapshot=snapshot;this.root=root;this.measure=measure;this.generation=generation;this.seed=seed;
        }
        boolean currentMode(){return index.isRootEnabled()==root&&index.folderSizeGeneration()==generation;}
        boolean valid(){return !cancellation.isCanceled()&&currentMode();}
        void cancel(){listeners.clear();cancellation.cancel();WORK.remove(this);}
        @Override public void run(){
            if(!valid())return;
            Result known=seed!=null?seed:index.safeFolderSizeSnapshot(snapshot,root)?indexed(snapshot,path,cancellation):null;
            if(!valid())return;
            if(known!=null&&measure)MAIN.post(()->publish(known,false));
            Result result=known==null?unavailable():known;
            if(measure){
                long deadline=SystemClock.elapsedRealtime()+SCAN_MS;
                // The privileged helper may block on IPC or su; the watchdog is independent of UI.
                ScheduledFuture<?> expiry=root?DEADLINES.schedule(cancellation::cancel,SCAN_MS,TimeUnit.MILLISECONDS):null;
                try{
                    Result actual=index.measureFolder(path,root,cancellation,deadline);
                    if(actual.quality!=UNAVAILABLE||known==null)result=actual;
                }catch(IOException|OperationCanceledException|java.util.concurrent.CancellationException|SecurityException ignored){
                    // If no direct measurement was proved, keep only the labeled index total.
                }finally{if(expiry!=null)expiry.cancel(false);}
            }
            Result completed=result;
            MAIN.post(()->publish(completed,true));
        }
        void publish(Result value,boolean done){
            if(jobs.get(key)!=this)return;
            if(done)jobs.remove(key);
            if(!currentMode()){listeners.clear();return;}
            latest=value;
            if(done&&snapshot.entries==index.getState().entries&&!cancellation.isCanceled()){
                Cache previous=cache.get(path);
                if(measure||previous==null||!previous.measured||SystemClock.elapsedRealtime()>=previous.expiry
                        ||previous.snapshot.get()!=snapshot.entries||previous.root!=root||previous.generation!=generation){
                    cache.put(path,new Cache(snapshot.entries,value,root,measure,generation,SystemClock.elapsedRealtime()+CACHE_MS));
                    while(cache.size()>MAX_CACHE){
                        Iterator<String> keys=cache.keySet().iterator();
                        keys.next();keys.remove();
                    }
                }
            }
            for(Consumer<Result> callback:new ArrayList<>(listeners))callback.accept(value);
            if(done)listeners.clear();
        }
    }

    private static Result indexed(FileIndex.State snapshot,String path,CancellationSignal cancellation){
        if(!snapshot.loaded)return null;
        String prefix=path.endsWith("/")?path:path+"/";
        long bytes=0;
        int size=Math.min(snapshot.entries.size(),MAX_INDEXED_ROWS);
        boolean found=false;
        for(int i=0;i<size;i++){
            if((i&511)==0&&cancellation.isCanceled())return null;
            FileEntry entry=snapshot.entries.get(i);
            if(entry.directory||entry.size<0||(!entry.parent.equals(path)&&!entry.parent.startsWith(prefix)))continue;
            found=true;
            if(bytes>Long.MAX_VALUE-entry.size){bytes=Long.MAX_VALUE;break;}
            bytes+=entry.size;
        }
        return found?new Result(bytes,INDEXED):null;
    }

    private static final class Cache {
        final WeakReference<List<FileEntry>> snapshot;
        final Result result;
        final boolean root,measured;
        final long expiry;
        final int generation;
        Cache(List<FileEntry> snapshot,Result result,boolean root,boolean measured,int generation,long expiry){
            this.snapshot=new WeakReference<>(snapshot);this.result=result;this.root=root;this.measured=measured;this.generation=generation;this.expiry=expiry;
        }
    }
}
