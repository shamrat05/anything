package dev.fastsearch.android;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.MediaStore;

import dev.fastsearch.core.FileEntry;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Local-only viewport lookup. No directory reads, file reads, root calls or added permissions. */
final class AppFileOwners implements AutoCloseable {
    enum Source { APP_FOLDER, MEDIA_CONTRIBUTOR }

    static final class Badge {
        final Bitmap bitmap;
        final String label,packageName;
        final Source source;
        Badge(Icon icon,String packageName,Source source){
            bitmap=icon.bitmap;label=icon.label;this.packageName=packageName;this.source=source;
        }
        String accessibilityLabel(){return (source==Source.APP_FOLDER?"Associated app: ":"Media contributor: ")+label;}
    }

    static final int MAX_ROWS=64;
    private static final int MAX_QUERY_ROWS=MAX_ROWS*4;
    private static final long FRESH_MS=15_000;
    private static final long ICON_MS=60_000;
    private static final String[] MEDIA_COLUMNS={MediaStore.MediaColumns.DATA,MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
            MediaStore.MediaColumns.SIZE,MediaStore.MediaColumns.DATE_MODIFIED};
    private static final Handler MAIN=new Handler(Looper.getMainLooper());

    private final Context context;
    private final Runnable onPackagesChanged;
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,15,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(2),r->{
        Thread thread=new Thread(r,"file-app-badges");thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);return thread;
    });
    private final Set<Job> jobs=new HashSet<>(); // Main thread only.
    private final LinkedHashMap<String,Icon> icons=lru(96); // Worker only.
    private final LinkedHashMap<String,Media> media=lru(384); // Worker only.
    private AppFileAttribution paths;
    private long refreshPaths;
    private volatile int revision;
    private int cacheRevision=-1;
    private volatile boolean closed;
    private boolean registered;
    private final BroadcastReceiver packages=new BroadcastReceiver(){
        @Override public void onReceive(Context ignored,Intent event){
            if(closed)return;
            revision++;
            cancelAll();
            if(onPackagesChanged!=null)onPackagesChanged.run();
        }
    };

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    AppFileOwners(Context context,Runnable onPackagesChanged){
        this.context=context.getApplicationContext();
        this.onPackagesChanged=onPackagesChanged;
        worker.allowCoreThreadTimeOut(true);
        IntentFilter filter=new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        try{
            // These package lifecycle actions are protected system broadcasts.
            if(Build.VERSION.SDK_INT>=33)this.context.registerReceiver(packages,filter,Context.RECEIVER_EXPORTED);
            else this.context.registerReceiver(packages,filter);
            registered=true;
        }catch(RuntimeException unavailable){ /* TTL still expires any stale cached icon. */ }
    }

    /** Call on main with a coalesced visible batch, and cancel it on recycle/navigation. Unknown paths are omitted. */
    Runnable request(List<FileEntry> visible,boolean authorizedRoot,int iconEdgePx,
                     Consumer<Map<String,Badge>> callback){
        if(closed||visible.isEmpty()){callback.accept(Collections.emptyMap());return ()->{};}
        int size=Math.min(MAX_ROWS,visible.size());
        List<FileEntry> selected=new ArrayList<>(visible.subList(0,size));
        int edge=iconEdgePx<=28?28:iconEdgePx<=48?48:64;
        Job job=new Job(selected,authorizedRoot,edge,revision,callback);
        // Only one running batch plus the latest pending batch should be retained.
        for(Job older:new ArrayList<>(jobs))if(worker.remove(older))older.cancel();
        jobs.add(job);
        try{worker.execute(job);}catch(RejectedExecutionException busy){
            jobs.remove(job);callback.accept(Collections.emptyMap());return ()->{};
        }
        return job::cancel;
    }

    private void cancelAll(){for(Job job:new ArrayList<>(jobs))job.cancel();}

    @Override public void close(){
        if(closed)return;
        closed=true;revision++;cancelAll();worker.shutdownNow();
        if(registered){context.unregisterReceiver(packages);registered=false;}
    }

    private final class Job implements Runnable {
        final List<FileEntry> entries;
        final boolean root;
        final int edge,requestRevision;
        final CancellationSignal cancellation=new CancellationSignal();
        Consumer<Map<String,Badge>> callback;
        Job(List<FileEntry> entries,boolean root,int edge,int requestRevision,Consumer<Map<String,Badge>> callback){
            this.entries=entries;this.root=root;this.edge=edge;this.requestRevision=requestRevision;this.callback=callback;
        }
        boolean stopped(){return closed||requestRevision!=revision||cancellation.isCanceled()||Thread.currentThread().isInterrupted();}
        void cancel(){jobs.remove(this);callback=null;cancellation.cancel();worker.remove(this);}
        @Override public void run(){
            Map<String,Badge> results;
            try{results=resolve(this);}catch(RuntimeException|OutOfMemoryError unavailable){results=Collections.emptyMap();}
            if(stopped())return;
            Map<String,Badge> result=results;
            MAIN.post(()->{
                jobs.remove(this);
                if(!stopped()&&callback!=null)callback.accept(result);
                callback=null;
            });
        }
    }

    private Map<String,Badge> resolve(Job job){
        if(job.stopped())return Collections.emptyMap();
        if(cacheRevision!=job.requestRevision){icons.clear();media.clear();cacheRevision=job.requestRevision;}
        long now=SystemClock.elapsedRealtime();
        if(paths==null||now>=refreshPaths){paths=knownPaths(context);refreshPaths=now+FRESH_MS;}
        Map<String,Badge> result=new LinkedHashMap<>();
        Map<String,List<FileEntry>> unknown=new LinkedHashMap<>();
        Map<String,FileEntry> pending=new LinkedHashMap<>();
        for(FileEntry row:job.entries){
            if(job.stopped())return Collections.emptyMap();
            if(row==null)continue;
            String path=row.path(),candidate=paths.pathPackage(path,row.directory,job.root);
            if(candidate!=null){
                Icon icon=installedIcon(candidate,job.edge,now);
                if(icon!=null)result.put(path,new Badge(icon,candidate,Source.APP_FOLDER));
                continue; // A missing app or an invisible package does not turn into a guess.
            }
            if(Build.VERSION.SDK_INT<29||row.directory||row.size<0||row.modified<=0)continue;
            int kind=FileTypes.category(row);
            if(kind!=3&&kind!=6&&kind!=7)continue;
            String providerPath=paths.mediaPath(path,job.root);
            if(providerPath==null)continue;
            unknown.computeIfAbsent(providerPath,unused->new ArrayList<>()).add(row);
            Media cached=media.get(providerPath);
            if(cached==null||!cached.matches(row,now))pending.put(providerPath,row);
        }
        if(!pending.isEmpty()&&!job.stopped())fetchMedia(pending,job.cancellation,now);
        for(Map.Entry<String,List<FileEntry>> item:unknown.entrySet()){
            if(job.stopped())return Collections.emptyMap();
            Media contribution=media.get(item.getKey());
            if(contribution==null||contribution.packageName==null)continue;
            for(FileEntry row:item.getValue())if(contribution.matches(row,now)){
                Icon icon=installedIcon(contribution.packageName,job.edge,now);
                if(icon!=null)result.put(row.path(),new Badge(icon,contribution.packageName,Source.MEDIA_CONTRIBUTOR));
            }
        }
        return Collections.unmodifiableMap(result);
    }

    @SuppressWarnings("deprecation") // Query known exact paths only; DATA is never used to open files.
    private void fetchMedia(Map<String,FileEntry> requested,CancellationSignal signal,long now){
        Map<String,Media> found=new HashMap<>();
        Set<String> ambiguous=new HashSet<>();
        String[] args=requested.keySet().toArray(new String[0]);
        String selection=MediaStore.MediaColumns.DATA+" IN ("+String.join(",",Collections.nCopies(args.length,"?"))+")";
        try(Cursor rows=context.getContentResolver().query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                MEDIA_COLUMNS,selection,args,null,signal)){
            if(rows!=null){
                int inspected=0;
                while(rows.moveToNext()){
                    signal.throwIfCanceled();
                    if(++inspected>MAX_QUERY_ROWS){found.clear();break;}
                    String path=rows.getString(0);
                    FileEntry expected=requested.get(path);
                    if(expected==null)continue;
                    String owner=rows.getString(1);
                    // A stale media row at a reused path is not enough for attribution.
                    if(!AppFileAttribution.validPackage(owner)||rows.isNull(2)||rows.isNull(3)
                            ||rows.getLong(2)!=expected.size||rows.getLong(3)!=expected.modified/1000L){
                        ambiguous.add(path);continue;
                    }
                    Media previous=found.get(path);
                    if(previous!=null&&!previous.packageName.equals(owner))ambiguous.add(path);
                    found.put(path,new Media(owner,expected,now));
                }
            }
        }catch(RuntimeException noVisibleOwner){found.clear();}
        if(signal.isCanceled())return;
        for(Map.Entry<String,FileEntry> item:requested.entrySet()){
            String path=item.getKey();
            Media value=ambiguous.contains(path)?null:found.get(path);
            media.put(path,value==null?new Media(null,item.getValue(),now):value);
        }
    }

    private Icon installedIcon(String packageName,int edge,long now){
        String key=edge+":"+packageName;
        Icon cached=icons.get(key);
        if(cached!=null&&cached.until>now)return cached.bitmap==null?null:cached;
        Icon loaded=null;
        try{
            PackageManager packages=context.getPackageManager();
            ApplicationInfo app=packages.getApplicationInfo(packageName,0);
            if((app.flags&ApplicationInfo.FLAG_INSTALLED)!=0&&app.icon!=0){
                Drawable drawable=packages.getApplicationIcon(app);
                Bitmap bitmap=ArchiveAppIcon.bitmap(drawable,edge);
                if(bitmap!=null){
                    CharSequence label=app.loadLabel(packages);
                    loaded=new Icon(bitmap,label==null||label.length()==0?packageName:label.toString(),now+ICON_MS);
                }
            }
        }catch(PackageManager.NameNotFoundException|RuntimeException unknown){ /* Hidden or uninstalled. */ }
        icons.put(key,loaded==null?new Icon(null,"",now+FRESH_MS):loaded);
        return loaded;
    }

    private static AppFileAttribution knownPaths(Context context){
        Set<String> roots=new LinkedHashSet<>();
        File internal=Environment.getExternalStorageDirectory();
        String primary=knownRoot(roots,internal);
        StorageManager storage=context.getSystemService(StorageManager.class);
        if(storage!=null)for(StorageVolume volume:storage.getStorageVolumes()){
            if(Build.VERSION.SDK_INT>=30)knownRoot(roots,volume.getDirectory());
            else if(volume.isRemovable()&&(Environment.MEDIA_MOUNTED.equals(volume.getState())
                    ||Environment.MEDIA_MOUNTED_READ_ONLY.equals(volume.getState()))){
                String uuid=volume.getUuid();
                // Portable SD convention, based only on the current OS-reported volume.
                if(uuid!=null&&uuid.matches("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}"))knownRoot(roots,new File("/storage",uuid));
            }
        }
        String profile=AppFileAttribution.currentProfile(context.getDataDir().getAbsolutePath());
        return new AppFileAttribution(new ArrayList<>(roots),primary,profile);
    }

    private static String knownRoot(Set<String> roots,File directory){
        if(directory==null||!directory.isAbsolute()||"/".equals(directory.getPath()))return null;
        String absolute=directory.getAbsolutePath();
        roots.add(absolute);
        try{
            String canonical=directory.getCanonicalPath();
            if(!"/".equals(canonical)){roots.add(canonical);return canonical;}
        }catch(IOException|SecurityException inaccessible){ /* Known absolute Android volume alias remains valid. */ }
        return absolute;
    }

    private static <T> LinkedHashMap<String,T> lru(int capacity){
        return new LinkedHashMap<String,T>(capacity,.75f,true){
            @Override protected boolean removeEldestEntry(Map.Entry<String,T> entry){return size()>capacity;}
        };
    }
    private static final class Icon {
        final Bitmap bitmap;final String label;final long until;
        Icon(Bitmap bitmap,String label,long until){this.bitmap=bitmap;this.label=label;this.until=until;}
    }
    private static final class Media {
        final String packageName;
        final long bytes,modified,until;
        Media(String packageName,FileEntry row,long now){
            this.packageName=packageName;bytes=row.size;modified=row.modified;until=now+FRESH_MS;
        }
        boolean matches(FileEntry row,long now){return bytes==row.size&&modified==row.modified&&now<until;}
    }
}
