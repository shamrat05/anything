package dev.fastsearch.android;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import android.os.Build;
import android.os.Environment;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import dev.fastsearch.core.FileEntry;
import dev.fastsearch.core.FileEntryTable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Persistent filename/metadata index. All traversal and database work runs on one worker.
 * Completed immutable snapshots remain searchable during scans, cancellation and IO errors.
 */
public final class FileIndex {
    public interface Listener { void onChanged(State state); }

    public static final class State {
        public final List<FileEntry> entries;
        public final boolean scanning;
        public final boolean loaded;
        public final int scannedDirectories;
        public final int deniedDirectories;
        /** Requested FileObservers, since Android does not confirm successful registration. */
        public final int watchCount;
        public final int totalDirectories;
        public final long lastScanMillis;
        public final long scanDurationMillis;
        public final String status;
        public final String error;
        /** Discovered rows in the current scan, separate from the committed index. */
        public final long discoveredItems;

        private State(List<FileEntry> entries, boolean scanning, boolean loaded,
                      int scannedDirectories, int deniedDirectories, int watchCount,
                      int totalDirectories, long lastScanMillis, long scanDurationMillis,
                      String status, String error, long discoveredItems) {
            this.entries = entries;
            this.scanning = scanning;
            this.loaded = loaded;
            this.scannedDirectories = scannedDirectories;
            this.deniedDirectories = deniedDirectories;
            this.watchCount = watchCount;
            this.totalDirectories = totalDirectories;
            this.lastScanMillis = lastScanMillis;
            this.scanDurationMillis = scanDurationMillis;
            this.status = status;
            this.error = error == null ? "" : error;
            this.discoveredItems = discoveredItems;
        }
    }

    private static final int WATCH_LIMIT = 2048;
    private static final int EVENT_LIMIT = 2048;
    private static final long RECONCILE_INTERVAL = 30_000;
    private static final Comparator<FileEntry> ORDER = (a, b) -> {
        int order = a.name.compareToIgnoreCase(b.name);
        return order != 0 ? order : a.comparePath(b);
    };
    private static volatile FileIndex instance;

    public static FileIndex get(Context context) {
        FileIndex value = instance;
        if (value == null) synchronized (FileIndex.class) {
            value = instance;
            if (value == null) instance = value = new FileIndex(context);
        }
        return value;
    }

    /** Shared-storage permission only; root access is tested by the root scan itself. */
    public static boolean hasAccess(Context context) {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private final Context context;
    private final String boundedProbeRoot;
    private final String storageAlias=Environment.getExternalStorageDirectory().getAbsolutePath();
    private final String storageBacking=storageAlias.startsWith("/storage/emulated/")?"/data/media/"+new File(storageAlias).getName():storageAlias;
    private final String preferenceName;
    private final String databasePrefix;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "filename-index");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private final AtomicBoolean scanQueued = new AtomicBoolean();
    private final AtomicBoolean loadQueued = new AtomicBoolean();
    private final AtomicBoolean catchUpQueued = new AtomicBoolean();
    private final Object catchUpLock = new Object();
    private boolean automaticCatchUpRunning, resumeAutomaticCatchUp;
    private boolean initialObserversPending, initialCatchUpDeferred;
    private volatile boolean interruptAutomaticCatchUp;
    private int pendingPathUpdates;
    private volatile int pendingOperationUpdates;
    private List<String> foregroundPaths = Collections.emptyList();
    private long[] foregroundStamps = new long[0];
    private final AtomicBoolean discoveryQueued = new AtomicBoolean();
    private long lastDiscoveryElapsed;
    private final AtomicBoolean reconcileAfterScan=new AtomicBoolean();
    private final AtomicBoolean cancelRequested = new AtomicBoolean();
    private final AtomicBoolean scopeScanPending = new AtomicBoolean();
    private final AtomicInteger scopeVersion = new AtomicInteger();
    private final Object eventsLock = new Object();
    private final Map<String, Integer> pendingEvents = new LinkedHashMap<>();
    private final Map<String, DirectoryObserver> observers = new HashMap<>();
    private volatile boolean rootEnabled;
    private volatile boolean closed;
    private volatile IndexRootScanner rootScanner;
    private volatile FileOperationProcess updateProcess;
    private volatile Runnable transactionProbe;
    private volatile boolean probeLiveWatching=true;
    private volatile List<String> rootPaths;
    private volatile State state = new State(Collections.emptyList(), false, false,
            0, 0, 0, 0, 0, 0, "Index has not been loaded.", "", 0);
    private long discoveredItems;
    // The following fields belong exclusively to the worker.
    private Boolean activeRootMode;
    private Database database;
    private FileEntryTable entries = new FileEntryTable();
    private Set<String> readableDirectories = new HashSet<>();
    private Set<String> deniedDirectories = new HashSet<>();
    private List<FileEntry> snapshot = Collections.emptyList();
    private boolean loaded;
    private volatile boolean scannedThisProcess;
    private volatile boolean reconcileNeeded;
    private boolean eventTaskQueued;
    private boolean eventGap;
    private int rootAccessErrors;
    private int rootExcludedPaths;
    private long lastScanMillis;
    private long scanDurationMillis;
    private long lastProgress;
    private long lastReconcileElapsed;
    private int fullScanStarts;
    private int wholeReconcileStarts;
    private boolean probeFolderSnapshots;
    private String scanWarning = "";

    private FileIndex(Context context) {
        this(context, null, "");
    }

    private FileIndex(Context context, String boundedProbeRoot, String databasePrefix) {
        Context application = context.getApplicationContext();
        this.context = application == null ? context : application;
        this.boundedProbeRoot = boundedProbeRoot;
        this.databasePrefix = databasePrefix;
        this.preferenceName = databasePrefix + "filename-index";
        rootEnabled = this.context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
                .getBoolean("root_enabled", false);
        rootPaths = selectedRoots(rootEnabled);
        worker.setRemoveOnCancelPolicy(true);
        worker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    public State getState() { return state; }
    /** One catch-up per loaded scope/process; returning from a preview never repeats it. */
    public boolean needsCatchUp() { return !closed && (!scannedThisProcess || reconcileNeeded); }
    boolean hasPendingWork() { return discoveryQueued.get() || scanQueued.get() || loadQueued.get() || catchUpQueued.get() || scopeScanPending.get(); }
    void setProbeFolderSnapshots(boolean enabled){if(boundedProbeRoot==null)throw new IllegalStateException("Isolated index only.");probeFolderSnapshots=enabled;}
    public boolean isRootEnabled() { return rootEnabled; }
    public List<String> roots() { return rootPaths; }

    List<FileEntry> listFolder(String path, android.os.CancellationSignal cancellation) throws IOException {
        ArrayList<FileEntry> entries = new ArrayList<>();
        if (rootEnabled) {
            readRootFolder(path, cancellation, entries::add);
        } else {
            File[] children = new File(path).listFiles();
            if (children == null) throw new IOException("Could not read this folder.");
            for (File child : children) {
                cancellation.throwIfCanceled();
                FileEntry entry = metadata(child);
                if (entry != null) entries.add(entry);
            }
        }
        return entries;
    }

    int scanMap(String path, boolean showHidden, android.os.CancellationSignal cancellation,
                StorageMapData data) throws IOException {
        if(rootEnabled){
            IndexRootScanner scanner=new IndexRootScanner(context.getCacheDir(),
                    new File(context.getApplicationInfo().nativeLibraryDir,"librootindex.so").getPath());
            cancellation.setOnCancelListener(scanner::cancel);
            try{
                IndexRootScanner.Result result=scanner.scanMap((name,directory,bytes,modified)->{
                    cancellation.throwIfCanceled();
                    if(!path.equals(name))data.addTile(new FileEntry(name,directory,directory?0:bytes,modified),bytes);
                },path,showHidden);
                return result.accessErrors+result.otherErrors;
            }finally{cancellation.setOnCancelListener(null);}
        }
        ArrayDeque<File> directories=new ArrayDeque<>();
        directories.add(new File(path));int unreadable=0;
        while(!directories.isEmpty()){
            cancellation.throwIfCanceled();
            File directory=directories.removeLast();
            File[] children=directory.listFiles();
            if(children==null){
                if(directory.getPath().equals(path))throw new IOException("Could not read this folder.");
                unreadable++;continue;
            }
            for(File child:children){
                cancellation.throwIfCanceled();
                FileEntry entry=metadata(child);
                if(entry==null)continue;
                if(!showHidden&&entry.hidden)continue;
                data.add(entry);
                if(entry.directory)directories.add(child);
            }
        }
        return unreadable;
    }

    private void readRootFolder(String path,android.os.CancellationSignal cancellation,
                               java.util.function.Consumer<FileEntry> sink)throws IOException{
        IndexRootScanner scanner=new IndexRootScanner(context.getCacheDir(),
                new File(context.getApplicationInfo().nativeLibraryDir,"librootindex.so").getPath());
        cancellation.setOnCancelListener(scanner::cancel);
        try{
            IndexRootScanner.Sink records=(name,directory,size,modified)->{
                cancellation.throwIfCanceled();
                if(!path.equals(name))sink.accept(new FileEntry(name,directory,size,modified));
            };
            scanner.listChildren(records,path);
        }finally{cancellation.setOnCancelListener(null);}
    }

    /** Instrumentation only: isolated caches and traversal restricted to this app's cache. */
    static FileIndex forProbe(Context context, File fixture, String id) throws IOException {
        String path = fixture.getCanonicalPath();
        String cache = context.getCacheDir().getCanonicalPath() + "/";
        if (!path.startsWith(cache) || !fixture.getName().startsWith("IndexProbe-")
                || !id.matches("IndexProbe[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("Probe fixture must be a named child of the app cache.");
        }
        return new FileIndex(context, path, id + "-");
    }

    /** Pauses a real SQLite transaction so the probe can cancel and verify rollback. */
    void setTransactionProbe(Runnable action) {
        if (boundedProbeRoot == null) throw new IllegalStateException("Not a bounded probe instance.");
        transactionProbe = action;
    }
    void setProbeLiveWatching(boolean enabled){if(boundedProbeRoot==null)throw new IllegalStateException("Not a bounded probe instance.");probeLiveWatching=enabled;}
    int fullScanStartsForProbe(){if(boundedProbeRoot==null)throw new IllegalStateException("Not a bounded probe instance.");return fullScanStarts;}
    int wholeReconcileStartsForProbe(){if(boundedProbeRoot==null)throw new IllegalStateException("Not a bounded probe instance.");return wholeReconcileStarts;}
    static synchronized void installForProbe(FileIndex value){
        if(!BuildConfig.DEBUG||value.boundedProbeRoot==null||!value.context.getPackageName().endsWith(".fresh"))throw new IllegalStateException("Not an isolated debug probe.");
        if(instance!=null&&instance!=value)throw new IllegalStateException("An index is already installed.");instance=value;
    }

    boolean awaitShutdown(long timeoutMillis) throws InterruptedException {
        return worker.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    private List<String> selectedRoots(boolean root) {
        if (boundedProbeRoot != null) return Collections.singletonList(boundedProbeRoot);
        return root ? Collections.singletonList("/") : sharedRoots(context);
    }

    private boolean sharedAccess() {
        // The probe can inspect its own cache without broad shared-storage permission.
        return boundedProbeRoot != null || hasAccess(context);
    }

    /** Switches independent caches; it never silently substitutes shared-only for root. */
    public void setRootEnabled(boolean enabled) {
        if (closed || rootEnabled == enabled) return;
        boolean restartScan = scanQueued.get() || scopeScanPending.get();
        if (restartScan) scopeScanPending.set(true);
        rootEnabled = enabled;
        scopeVersion.incrementAndGet();
        context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE).edit()
                .putBoolean("root_enabled", enabled).apply();
        requestCancellation();
        execute(() -> {
            try {
                ensureLoaded();
                if (restartScan && !closed && scopeScanPending.get()) refresh();
            } finally {
                scopeScanPending.set(false);
                if (!scanQueued.get() && !closed) {
                    State completed = state;
                    publish(false, completed.scannedDirectories, completed.deniedDirectories,
                            completed.totalDirectories, completed.status, completed.error);
                }
            }
        });
    }

    public void addListener(Listener listener) {
        if (listener == null || closed) return;
        listeners.add(listener);
        main.post(() -> {
            if (listeners.contains(listener)) listener.onChanged(state);
        });
    }

    public void removeListener(Listener listener) { listeners.remove(listener); }

    public void load() {
        if (closed || !loadQueued.compareAndSet(false, true)) return;
        execute(() -> {
            try { ensureLoaded(); } finally { loadQueued.set(false); }
        });
    }

    /** Explicit complete rebuild. Routine refresh and resume must use refresh/reconcile instead. */
    public void startScan() {
        if (closed || !scanQueued.compareAndSet(false, true)) return;
        cancelRequested.set(false);
        execute(() -> {
            try {
                ensureLoaded();
                scan();
            } finally {
                rootScanner = null;
                scanQueued.set(false);
                cancelRequested.set(false);
                if (!closed) {
                    State completed = state;
                    publish(false, completed.scannedDirectories, completed.deniedDirectories,
                            completed.totalDirectories, completed.status, completed.error);
                    if(reconcileAfterScan.getAndSet(false))reconcile(true);
                }
            }
        });
    }

    public void cancelScan() {
        scopeScanPending.set(false);
        requestCancellation();
    }

    private void requestCancellation() {
        cancelRequested.set(true);
        IndexRootScanner scanner = rootScanner;
        if (scanner != null) scanner.cancel();
        FileOperationProcess update=updateProcess;if(update!=null)update.cancel();
    }

    /** Resume reconciliation is throttled so service/activity callbacks cannot rescan in a loop. */
    public void reconcile() {
        reconcile(false);
    }
    public void refresh(){reconcile(true);}
    /** Cached results stay usable during the one automatic folder catch-up. */
    public void checkChanges(){checkChanges(false);}

    boolean checkChangesNow(){return checkChanges(true);}

    private boolean checkChanges(boolean requested){
        if(closed||scanQueued.get()||(!requested&&!needsCatchUp())||!catchUpQueued.compareAndSet(false,true))return false;
        try{worker.execute(()->{try{
            if(scanQueued.get())return;
            cancelRequested.set(false);
            ensureLoaded();
            if(lastScanMillis==0)return;
            if (!requested && initialObserversPending) {
                initialCatchUpDeferred = true;
                return;
            }
            synchronized(catchUpLock){
                if(pendingPathUpdates>0){resumeAutomaticCatchUp=true;return;}
                automaticCatchUpRunning=true;
            }
            reconcileCache(false,null,false,requested);
        }finally{
            synchronized(catchUpLock){
                if(interruptAutomaticCatchUp)lastReconcileElapsed=0;
                interruptAutomaticCatchUp=false;
                automaticCatchUpRunning=false;
                catchUpQueued.set(false);
            }
            if(!scanQueued.get())cancelRequested.set(false);
        }});}
        catch(RejectedExecutionException stopped){catchUpQueued.set(false);return false;}
        return true;
    }
    /** Small foreground-only check, ahead of offline catch-up. No recurring timer or service. */
    public void discoverOnOpen(String folder) { scheduleDiscovery(folder); }

    /** Shared-storage FUSE mounts can miss watcher events from another mount namespace. */
    synchronized void probeForegroundChanges(String folder) {
        if(closed||scanQueued.get()||!state.loaded||state.lastScanMillis==0||!sharedAccess())return;
        List<String> paths=new ArrayList<>();
        for(String path:commonSharedFolders(folder)) paths.add(ioPath(path));
        long[] stamps=NativeDiscovery.modified(paths);
        if(paths.equals(foregroundPaths)&&java.util.Arrays.equals(stamps,foregroundStamps))return;
        if(scheduleDiscovery(folder)){
            foregroundPaths=paths;
            foregroundStamps=stamps;
        }
    }

    private LinkedHashSet<String> commonSharedFolders(String folder){
        LinkedHashSet<String> chosen=new LinkedHashSet<>();
        if(folder!=null&&watchable(folder))chosen.add(folder);
        List<String> bases=boundedProbeRoot!=null?Collections.singletonList(boundedProbeRoot):sharedRoots(context);
        for(String base:bases){
            String path=indexPath(base);chosen.add(path);
            for(String name:new String[]{"Download","DCIM","DCIM/Camera","Pictures","Pictures/Screenshots","Movies","Music","Documents"})
                chosen.add(path+"/"+name);
        }
        return chosen;
    }

    private boolean scheduleDiscovery(String folder) {
        if(closed||!discoveryQueued.compareAndSet(false,true))return false;
        synchronized(catchUpLock){
        pendingPathUpdates++;
        try{worker.execute(()->{try{
            if(scanQueued.get())return;
            checkDiscovery(scopeVersion.get());
            ensureLoaded();
            if(lastScanMillis==0||!sharedAccess())return;
            long now=SystemClock.elapsedRealtime();if(now-lastDiscoveryElapsed<500)return;
            lastDiscoveryElapsed=now;int version=scopeVersion.get();
            LinkedHashSet<String> chosen=commonSharedFolders(folder);
            // Prioritize shallow covered folders, including app-specific media folders.
            List<String> candidates=new ArrayList<>(observers.keySet());candidates.sort(Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder()));
            for(String path:candidates){if(chosen.size()>=128)break;chosen.add(path);}
            List<String> logical=new ArrayList<>(),physical=new ArrayList<>();
            for(String path:chosen){if(logical.size()>=128)break;if(underRoots(path)&&watchable(path)){logical.add(path);physical.add(ioPath(path));}}
            long[] stamps=NativeDiscovery.modified(physical);ScanData additions=new ScanData();
            Map<String,Set<String>> listings=new HashMap<>();List<String> removed=new ArrayList<>();
            for(int i=0;i<logical.size();i++){
                checkDiscovery(version);if(stamps[i]<0)continue;
                String path=logical.get(i);FileEntry old=entries.get(path);
                if(old!=null&&old.directory&&old.modified==stamps[i])continue;
                File directory=new File(physical.get(i));ensureObserver(path);
                File[] children=directory.listFiles();if(children==null)continue;
                Set<String> seen=new HashSet<>();listings.put(path,seen);
                // Only new subtrees are walked. Existing descendants are never recursively revisited.
                for(File child:children){checkDiscovery(version);seen.add(indexPath(child.getAbsolutePath()));FileEntry fresh=indexedMetadata(child);if(fresh==null)continue;
                    FileEntry previous=entries.get(fresh.path());
                    if(previous!=null&&previous.directory!=fresh.directory)removed.add(fresh.path());
                    if(fresh.directory&&(previous==null||!previous.directory))walk(child, additions, false, () -> checkDiscovery(version));
                    else if(!fresh.directory&&(previous==null||previous.directory||previous.modified!=fresh.modified||previous.size!=fresh.size))additions.entries.put(fresh);
                    // Keep existing child-directory stamps until their own listing completes.
                }
                additions.entries.put(new FileEntry(path,true,0,stamps[i]));additions.readable.add(path);
            }
            if(!listings.isEmpty())for(FileEntry entry:snapshot){checkDiscovery(version);Set<String> seen=listings.get(entry.parent);
                if(seen!=null&&!seen.contains(entry.path()))removed.add(entry.path());}
            checkDiscovery(version);
            if(!additions.entries.isEmpty()||!removed.isEmpty()){
                persistDelta(removed,additions,version);for(String path:removed)removeTree(path);entries.putAll(additions.entries);
                readableDirectories.addAll(additions.readable);snapshot=mergeSnapshot(snapshot,removed,additions.entries);
                syncObservers();publishStable("");
            }
        } catch (CancellationException stopped) {
            synchronized (catchUpLock) {
                if (!closed && pendingOperationUpdates > 0) {
                    reconcileNeeded = true;
                    lastReconcileElapsed = 0;
                    resumeAutomaticCatchUp = true;
                }
            }
        } catch (RuntimeException | OutOfMemoryError failed) {
            reconcileNeeded = true;
        } finally { discoveryQueued.set(false); finishPathUpdate(true); }});
        interruptCatchUpForPathUpdate();
        }catch(RejectedExecutionException stopped){discoveryQueued.set(false);pendingPathUpdates--;return false;}
        }
        return true;
    }

    private void checkDiscovery(int version) {
        checkWork(version, false);
        if (pendingOperationUpdates > 0) throw new CancellationException("File operation update is waiting.");
    }

    /** Watch the accessible storage alias while retaining the root index's canonical keys. */
    private String sharedAlias(){return storageAlias;}
    private String backingPrefix(){return storageBacking;}
    private String ioPath(String path){
        if(Boolean.TRUE.equals(activeRootMode)&&boundedProbeRoot==null){String prefix=backingPrefix();
            if(path.equals(prefix)||path.startsWith(prefix+"/"))return sharedAlias()+path.substring(prefix.length());}
        return path;
    }
    private String indexPath(String path){
        if(Boolean.TRUE.equals(activeRootMode)&&boundedProbeRoot==null){String alias=sharedAlias();
            if(path.equals(alias)||path.startsWith(alias+"/"))return backingPrefix()+path.substring(alias.length());}
        return path;
    }
    private boolean watchable(String path){return !Boolean.TRUE.equals(activeRootMode)||boundedProbeRoot!=null||!path.equals(ioPath(path));}
    private FileEntry indexedMetadata(File file){FileEntry entry=metadata(file);if(entry==null)return null;
        String path=indexPath(entry.path());return path.equals(entry.path())?entry:new FileEntry(path,entry.directory,entry.size,entry.modified);}

    /** Only paths affected by an acknowledged or interrupted file operation, never a whole-cache check. */
    CompletableFuture<org.json.JSONObject> reconcilePaths(List<String> paths){
        CompletableFuture<org.json.JSONObject> done=new CompletableFuture<>();List<String> requested=new ArrayList<>(paths);
        if(closed){done.completeExceptionally(new IOException("Index is closed."));return done;}
        synchronized(catchUpLock){
        pendingPathUpdates++;
        pendingOperationUpdates++;
        try{worker.execute(()->{try{ensureLoaded();List<String> chosen=new ArrayList<>();
            if(requested.size()>FileClipboard.MAX_ITEMS*2)throw new IOException("Too many changed paths.");
            for (String path : requested) {
                FileOperationWorker.requirePath(path);
                String indexed = indexPath(path);
                if (underRoots(indexed)) chosen.add(indexed);
            }
            Collections.sort(chosen);List<String> compact=new ArrayList<>();String previous=null;
            for(String path:chosen)if(previous==null||(!path.equals(previous)&&!path.startsWith(previous+"/"))){compact.add(path);previous=path;}
            if(compact.isEmpty()){done.complete(new org.json.JSONObject().put("checkedRows",0).put("snapshotRows",0));return;}
            cancelRequested.set(false);org.json.JSONObject result=reconcileCache(true,compact,false);
            if(result==null)throw new IOException(state.error.isEmpty()?"Changed-file update stopped.":state.error);
            done.complete(result);
        }catch(Exception|OutOfMemoryError failure){done.completeExceptionally(failure);}
        finally{synchronized(catchUpLock){pendingOperationUpdates--;}finishPathUpdate(false);}});
        interruptCatchUpForPathUpdate();}
        catch(RejectedExecutionException stopped){pendingPathUpdates--;pendingOperationUpdates--;done.completeExceptionally(stopped);}
        }
        return done;
    }

    // Called under catchUpLock. Explicit rebuilds and verification are separate.
    private void interruptCatchUpForPathUpdate(){
        if(!automaticCatchUpRunning||interruptAutomaticCatchUp)return;
        resumeAutomaticCatchUp=true;
        interruptAutomaticCatchUp=true;
        FileOperationProcess update=updateProcess;
        if(update!=null)update.cancel();
    }

    private void finishPathUpdate(boolean watcher){
        boolean resume;
        synchronized(catchUpLock){
            pendingPathUpdates--;
            resume=pendingPathUpdates==0&&resumeAutomaticCatchUp;
            if(resume)resumeAutomaticCatchUp=false;
        }
        if(resume){
            if(watcher)try{worker.schedule(()->{checkChanges();},750,TimeUnit.MILLISECONDS);}
            catch(RejectedExecutionException ignored){}
            else checkChanges();
        }
    }
    public void reconcile(boolean force) {
        if(closed)return;
        if(!force&&!needsCatchUp()){execute(()->publishStable(""));return;}
        if(!scanQueued.compareAndSet(false,true)){if(force){reconcileAfterScan.set(true);if(!scanQueued.get()&&reconcileAfterScan.getAndSet(false))reconcile(true);}return;}
        cancelRequested.set(false);
        execute(()->{try{ensureLoaded();if(lastScanMillis==0)scan();else reconcileCache(force);}
            finally{updateProcess=null;scanQueued.set(false);cancelRequested.set(false);if(!closed){State done=state;publish(false,done.scannedDirectories,done.deniedDirectories,done.totalDirectories,done.status,done.error);if(reconcileAfterScan.getAndSet(false))reconcile(true);}}});
    }

    private org.json.JSONObject reconcileCache(boolean force){return reconcileCache(force,null,true);}
    private org.json.JSONObject reconcileCache(boolean force,List<String> targets,boolean visible){
        return reconcileCache(force,targets,visible,false);
    }
    private org.json.JSONObject reconcileCache(boolean force,List<String> targets,boolean visible,boolean requested){
        int version=scopeVersion.get();long started=SystemClock.elapsedRealtime();
        if(!requested&&!force&&scannedThisProcess&&!reconcileNeeded){return null;}
        if(!requested&&!force&&lastReconcileElapsed>0&&started-lastReconcileElapsed<RECONCILE_INTERVAL){return null;}
        if(targets==null){lastReconcileElapsed=started;wholeReconcileStarts++;}
        File snapshotFile=null;ScanData additions=new ScanData();Set<String> removed=new HashSet<>();
        Map<String,String> changedParents=new HashMap<>();
        boolean foldersOnly=(rootEnabled||probeFolderSnapshots)&&!force&&targets==null;
        Map<String,Set<String>> listings=new HashMap<>();Set<String> completeListings=new HashSet<>();
        try{
            rootPaths=selectedRoots(rootEnabled);checkCancelled(version);discoveredItems=0;
            if(visible)publish(true,0,0,0,"Updating saved index","");
            List<FileEntry> source=snapshot;Set<String> parents=new HashSet<>(),targetSet=targets==null?null:new HashSet<>(targets);
            List<String> denied=new ArrayList<>();
            if(targets!=null){
                for(String path:targets){String parent=new File(path).getParent();if(parent!=null&&underRoots(parent)&&!within(parent,targetSet))parents.add(parent);}
                Map<String,FileEntry> selected=new LinkedHashMap<>();Set<String> trees=new HashSet<>();
                for(String path:targets){FileEntry entry=entries.get(path);if(entry!=null){selected.put(path,entry);if(entry.directory)trees.add(path);}}
                for(String path:parents){FileEntry entry=entries.get(path);if(entry!=null)selected.put(path,entry);}
                if(!trees.isEmpty()){int count=0;for(FileEntry entry:snapshot){if((count++&255)==0)checkCancelled(version);
                    if(within(entry.parent,trees))selected.put(entry.path(),entry);}}
                source=new ArrayList<>(selected.values());
                for(String path:deniedDirectories)if(within(path,targetSet))denied.add(path);
            }else denied.addAll(deniedDirectories);
            if(foldersOnly){List<FileEntry> folders=new ArrayList<>();for(FileEntry entry:source)if(entry.directory)folders.add(entry);source=folders;}
            if(rootEnabled){snapshotFile=File.createTempFile("index-snapshot-",".bin",context.getCacheDir());
            try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(snapshotFile),64*1024))){
                out.writeInt(1);out.writeInt(source.size());int count=0;
                for(FileEntry entry:source){if((count++&255)==0)checkCancelled(version);out.writeUTF(entry.path());out.writeBoolean(entry.directory);out.writeLong(entry.size);out.writeLong(entry.modified);}
            }}
            org.json.JSONObject request=new org.json.JSONObject().put("mode","reconcile").put("root",rootEnabled)
                .put("snapshot",snapshotFile==null?"":snapshotFile.getPath()).put("roots",new org.json.JSONArray(rootPaths)).put("denied",new org.json.JSONArray(denied))
                .put("verifyFiles",force).put("foldersOnly",foldersOnly);
            if(targets!=null)request.put("targets",new org.json.JSONArray(targets)).put("parents",new org.json.JSONArray(parents));
            FileOperationProcess.Progress callback=new FileOperationProcess.Progress(){
                @Override public void onProgress(int completed,int total,org.json.JSONArray ignored){checkCancelled(version);discoveredItems=completed;
                    if(visible)publish(true,completed,0,total,"Updating "+String.format(java.util.Locale.getDefault(),"%,d / %,d",completed,total),"");}
                @Override public void onDelta(org.json.JSONObject delta)throws Exception{checkCancelled(version);
                    if(delta.has("listing")){String path=delta.getString("listing");Set<String> seen=listings.computeIfAbsent(path,ignored->new HashSet<>());org.json.JSONArray names=delta.getJSONArray("seen");
                        for(int i=0;i<names.length();i++)seen.add(names.getString(i));if(delta.optBoolean("complete"))completeListings.add(path);return;}
                    org.json.JSONArray updates=delta.getJSONArray("entries"),deletions=delta.getJSONArray("removed");
                    for(int i=0;i<updates.length();i++){org.json.JSONObject item=updates.getJSONObject(i);
                        String path=item.getString("path");boolean directory=item.getBoolean("directory");
                        if(directory)changedParents.putIfAbsent(path,path);
                        additions.entries.put(new FileEntry(path,directory,item.getLong("size"),item.getLong("modified"),changedParents));
                        if(directory)additions.readable.add(path);}
                    for(int i=0;i<deletions.length();i++)removed.add(deletions.getString(i));}
            };
            org.json.JSONObject result;
            if(rootEnabled){try(FileOperationProcess process=new FileOperationProcess(context,request,true,callback)){updateProcess=process;if(cancelRequested.get()||interruptAutomaticCatchUp)process.cancel();try { result=process.run(); }
                catch(IOException failure){checkCancelled(version);throw failure;}
            }}
            else result=IndexReconciler.run(request,source,()->checkCancelled(version),message->{
                if("delta".equals(message.optString("event")))callback.onDelta(message);
                else if("progress".equals(message.optString("event")))callback.onProgress(message.optInt("completed"),message.optInt("total"),null);
            }).put("ok",true);
            result.put("snapshotRows",source.size());
            checkCancelled(version);if(!result.optBoolean("ok"))throw new IOException(result.optString("error","Could not check file changes."));
            if(foldersOnly&&!completeListings.isEmpty()){int checked=0;for(FileEntry entry:snapshot){if((checked++&255)==0)checkCancelled(version);
                if(completeListings.contains(entry.parent)&&!listings.get(entry.parent).contains(entry.name))removed.add(entry.path());}}
            List<String> deletions=new ArrayList<>(removed);Collections.sort(deletions);List<String> collapsed=new ArrayList<>();String parent=null;
            for(String path:deletions){if(parent!=null&&path.startsWith(parent+"/"))continue;collapsed.add(path);parent=path;}
            if(foldersOnly){Set<String> deletedTrees=new HashSet<>(collapsed);java.util.Iterator<FileEntry> unchanged=additions.entries.values().iterator();
                while(unchanged.hasNext()){FileEntry fresh=unchanged.next(),old=entries.get(fresh.path());
                    if(old!=null&&old.directory==fresh.directory&&old.size==fresh.size&&old.modified==fresh.modified&&!within(fresh.path(),deletedTrees))unchanged.remove();}}
            long completed=targets==null?System.currentTimeMillis():0,duration=SystemClock.elapsedRealtime()-started;
            persistDelta(collapsed,additions,version,completed,duration,targets!=null);
            boolean watchChanges=targets==null;
            for(String path:collapsed){FileEntry old=entries.get(path);watchChanges|=old!=null&&old.directory;removeTree(path);}
            for(String path:additions.readable)if(!readableDirectories.contains(path))watchChanges=true;
            entries.putAll(additions.entries);readableDirectories.addAll(additions.readable);
            deniedDirectories.removeAll(additions.readable);snapshot=mergeSnapshot(snapshot,collapsed,additions.entries);
            if(targets==null){lastScanMillis=completed;scanDurationMillis=duration;scannedThisProcess=true;reconcileNeeded=false;}
            if(watchChanges)syncObservers();
            publishStable("","Updated "+(additions.entries.size()+collapsed.size())+" entries. Checked "+result.optInt("checkedRows")+(targets!=null?" affected paths; listed ":force?" cached rows; listed ":" cached folders; listed ")+result.optInt("listedDirectories")+" changed/new folders."
                +(result.optInt("accessErrors")>0?" Inaccessible entries retained.":""));
            return result;
        }catch(CancellationException stopped){reconcileNeeded=true;publishStable("","Update stopped. Saved index retained.");}
        catch(Exception|OutOfMemoryError failure){reconcileNeeded=true;publishStable("Could not check file changes. Saved index retained. "+failureMessage(failure));}
        finally{updateProcess=null;if(snapshotFile!=null)snapshotFile.delete();}
        return null;
    }
    private static boolean within(String path,Set<String> roots){if(roots.contains(path))return true;
        for(int slash=path.lastIndexOf('/');slash>0;slash=path.lastIndexOf('/',slash-1))if(roots.contains(path.substring(0,slash)))return true;
        return false;}

    public void shutdown() {
        if (closed) return;
        closed = true;
        cancelScan();
        worker.execute(() -> {
            stopObservers();
            if (database != null) database.close();
            listeners.clear();
        });
        worker.shutdown();
        synchronized (FileIndex.class) { if (instance == this) instance = null; }
    }

    private void execute(Runnable operation) {
        if (closed) return;
        try { worker.execute(operation); } catch (RejectedExecutionException ignored) { }
    }

    private void ensureLoaded() {
        boolean requestedRoot = rootEnabled;
        if (activeRootMode != null && activeRootMode == requestedRoot && loaded) return;
        stopObservers();
        synchronized (eventsLock) { pendingEvents.clear(); eventGap = false; }
        if (database != null) database.close();
        activeRootMode = requestedRoot;
        rootPaths = selectedRoots(requestedRoot);
        database = new Database(context, requestedRoot, databasePrefix);
        entries = new FileEntryTable();
        readableDirectories = new HashSet<>();
        deniedDirectories = new HashSet<>();
        snapshot = Collections.emptyList();
        lastScanMillis = 0;
        scanDurationMillis = 0;
        rootAccessErrors = 0;
        rootExcludedPaths = 0;
        scanWarning = "";
        scannedThisProcess = false;
        reconcileNeeded = false;
        initialObserversPending = false;
        initialCatchUpDeferred = false;
        loaded = false;
        publish(false, 0, 0, 0, "Loading saved filenames…", "");
        try {
            SQLiteDatabase db = database.getWritableDatabase();
            loadCachedEntries(db);
            try (Cursor cursor = db.rawQuery("SELECT path,readable FROM directories", null)) {
                while (cursor.moveToNext()) {
                    (cursor.getInt(1) != 0 ? readableDirectories : deniedDirectories).add(cursor.getString(0));
                }
            }
            lastScanMillis = readLong(db, "last_scan");
            scanDurationMillis = readLong(db, "duration");
            rootAccessErrors = (int) readLong(db, "root_access_errors");
            rootExcludedPaths = (int) readLong(db, "root_excluded");
            snapshot = sortedCache(entries);
            loaded = true;
            publishStable("");
            // Give foreground work the first turn after publishing the saved snapshot.
            scheduleInitialObservers(scopeVersion.get());
        } catch (RuntimeException | OutOfMemoryError failure) {
            entries.clear();
            readableDirectories.clear();
            deniedDirectories.clear();
            snapshot = Collections.emptyList();
            loaded = true;
            SupportLog.record(SupportLog.Event.CACHE_LOAD_FAILED, Boolean.TRUE.equals(activeRootMode), 0, 0);
            publishStable("Saved index could not be loaded. Refresh to rebuild. " + failureMessage(failure));
        }
    }

    private void scheduleInitialObservers(int version) {
        if (!probeLiveWatching || closed) return;
        initialObserversPending = true;
        try {
            worker.schedule(() -> {
                if (closed || version != scopeVersion.get()) return;
                if (pendingOperationUpdates > 0) {
                    scheduleInitialObservers(version);
                    return;
                }
                State current = state;
                boolean stable = current.status.equals(stableStatus());
                try {
                    syncObservers();
                    publish(current.scanning, current.scannedDirectories, current.deniedDirectories,
                            current.totalDirectories, stable ? stableStatus() : current.status, current.error);
                } finally {
                    initialObserversPending = false;
                    if (initialCatchUpDeferred) {
                        initialCatchUpDeferred = false;
                        checkChanges();
                    }
                }
            }, 500, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException stopped) {
            initialObserversPending = false;
        }
    }

    private void loadCachedEntries(SQLiteDatabase db) {
        Map<String,String> parents = new HashMap<>();
        String after = null;
        int pageSize = 4096;
        int rows;
        do {
            rows = 0;
            // Seek to the next row directly instead of replaying a growing prefix as the cursor window refills.
            String where = after == null ? "" : " WHERE rowid>?";
            try (Cursor cursor = db.rawQuery("SELECT rowid,path,directory,size,modified FROM entries"
                    + where + " ORDER BY rowid LIMIT " + pageSize, after == null ? null : new String[]{after})) {
                long lastRow = 0;
                while (cursor.moveToNext()) {
                    lastRow = cursor.getLong(0);
                    String path = cursor.getString(1);
                    boolean directory = cursor.getInt(2) != 0;
                    if (directory) parents.putIfAbsent(path, path);
                    entries.put(new FileEntry(path, directory, cursor.getLong(3), cursor.getLong(4), parents));
                    rows++;
                }
                if (rows == pageSize) after = Long.toString(lastRow);
            }
        } while (rows == pageSize);
    }

    private void scan() {
        fullScanStarts++;
        final int version = scopeVersion.get();
        final boolean root = Boolean.TRUE.equals(activeRootMode);
        SupportLog.record(SupportLog.Event.INDEX_STARTED, root, 0, 0);
        ScanData found = new ScanData();
        long started = SystemClock.elapsedRealtime();
        lastProgress = 0;
        discoveredItems = 0;
        publish(true, 0, 0, 0, root ? "Indexing root filenames…" : "Indexing shared-storage filenames…", "");
        boolean committed = false;
        try {
            checkCancelled(version);
            if (root) {
                IndexRootScanner scanner = new IndexRootScanner(context.getCacheDir(),
                        new File(context.getApplicationInfo().nativeLibraryDir, "librootindex.so").getPath());
                rootScanner = scanner;
                if (closed || cancelRequested.get() || version != scopeVersion.get()) scanner.cancel();
                Map<String,String> parents = new HashMap<>();
                IndexRootScanner.Sink sink = (path, directory, size, modified) -> {
                    checkCancelled(version);
                    if (directory) parents.putIfAbsent(path, path);
                    found.entries.put(new FileEntry(path, directory, size, modified, parents));
                    if (directory) found.readable.add(path);
                    progress(found);
                };
                IndexRootScanner.Result result = boundedProbeRoot == null ? scanner.scan(sink)
                        : scanner.scan(sink, boundedProbeRoot);
                found.rootAccessErrors = result.accessErrors;
                found.rootExcluded = result.excludedPaths;
                found.warning = result.warning;
            } else {
                if (!sharedAccess()) throw new IOException("Shared-storage access is not granted. Allow all files access, or enable root indexing.");
                rootPaths = selectedRoots(false);
                for (String path : rootPaths) walk(new File(path), found, version, true);
            }
            checkCancelled(version);
            long walkFinished=SystemClock.elapsedRealtime();
            List<FileEntry> completed = sorted(found.entries);
            long completedAt = System.currentTimeMillis();
            long duration = SystemClock.elapsedRealtime() - started;
            persistAll(found, completed, completedAt, duration, version);
            android.util.Log.i("AnythingIndex","Rebuild scope="+(root?"root":"shared")+" entries="+completed.size()
                    +" walkMs="+(walkFinished-started)+" sortMs="+(started+duration-walkFinished)
                    +" commitMs="+(SystemClock.elapsedRealtime()-started-duration));
            // No partially scanned state is published or saved outside that transaction.
            entries = found.entries;
            readableDirectories = found.readable;
            deniedDirectories = found.denied;
            snapshot = completed;
            lastScanMillis = completedAt;
            scanDurationMillis = duration;
            rootAccessErrors = found.rootAccessErrors;
            rootExcludedPaths = found.rootExcluded;
            scanWarning = found.warning;
            scannedThisProcess = true;
            reconcileNeeded = false;
            committed = true;
            SupportLog.record(SupportLog.Event.INDEX_SAVED, root, completed.size(), duration);
            syncObservers();
            publishStable("");
        } catch (CancellationException cancelled) {
            SupportLog.record(SupportLog.Event.INDEX_STOPPED, root, 0, 0);
            found.clear();
            syncObservers();
            if (version == scopeVersion.get()) publishStable("", "Indexing stopped. Previous completed index retained.");
        } catch (OutOfMemoryError memory) {
            if (!committed) found.clear();
            SupportLog.record(SupportLog.Event.INDEX_MEMORY_LIMIT, root, 0, 0);
            syncObservers();
            publishStable("Index exceeded available memory. " + (committed
                    ? "Completed cache was saved." : "Previous completed index retained."));
        } catch (IOException | RuntimeException failure) {
            SupportLog.record(SupportLog.Event.INDEX_FAILED, root, 0, 0);
            if (!committed) found.clear();
            syncObservers();
            publishStable(failureMessage(failure));
        } finally {
            rootScanner = null;
        }
    }

    private void progress(ScanData found) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastProgress < 350) return;
        lastProgress = now;
        discoveredItems = found.entries.size();
        int denied = found.denied.size() + found.rootAccessErrors;
        publish(true, found.readable.size(), denied, found.readable.size() + found.denied.size(),
                "Indexing: " + found.entries.size() + " items, " + found.readable.size() + " folders. Previous index remains searchable.", "");
    }

    private void walk(File root, ScanData found, int version, boolean cancellable) {
        walk(root, found, cancellable, () -> checkWork(version, cancellable));
    }

    private void walk(File root, ScanData found, boolean cancellable, Runnable check) {
        ArrayDeque<File> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            check.run();
            File directory = pending.removeFirst();
            FileEntry directoryEntry = indexedMetadata(directory);
            if (directoryEntry == null) {
                if (isDenied(directory)) found.denied.add(indexPath(directory.getAbsolutePath()));
                continue;
            }
            found.entries.put(directoryEntry);
            if (!directoryEntry.directory) continue;
            // Register before listing to capture changes after this directory was visited.
            ensureObserver(directoryEntry.path());
            File[] children;
            try { children = directory.listFiles(); } catch (SecurityException denied) { children = null; }
            if (children == null) {
                found.denied.add(directoryEntry.path());
                continue;
            }
            found.readable.add(directoryEntry.path());
            for (File child : children) {
                check.run();
                FileEntry entry = indexedMetadata(child);
                if (entry == null) continue;
                found.entries.put(entry);
                if (entry.directory) pending.addLast(child);
            }
            if (cancellable) progress(found);
        }
    }

    /** lstat excludes symlinks, sockets, pipes and devices, so traversal never follows loops. */
    private static FileEntry metadata(File file) {
        try {
            android.system.StructStat stat=Os.lstat(file.getAbsolutePath());
            boolean directory=OsConstants.S_ISDIR(stat.st_mode);
            if(!directory&&!OsConstants.S_ISREG(stat.st_mode))return null;
            return new FileEntry(file.getAbsolutePath(),directory,directory?0:stat.st_size,NativeDiscovery.modified(stat));
        } catch (ErrnoException | SecurityException failure) {
            return null;
        }
    }

    private static boolean isDenied(File file) {
        try {
            Os.lstat(file.getAbsolutePath());
            return false;
        } catch (ErrnoException failure) {
            return failure.errno == OsConstants.EACCES || failure.errno == OsConstants.EPERM;
        } catch (SecurityException denied) { return true; }
    }

    private void checkWork(int version, boolean cancellable) {
        if (closed || version != scopeVersion.get() || (cancellable && (cancelRequested.get() || interruptAutomaticCatchUp))) {
            throw new CancellationException("Indexing cancelled.");
        }
    }

    private void checkCancelled(int version) { checkWork(version, true); }

    private void persistAll(ScanData found, List<FileEntry> orderedEntries, long completed, long duration, int version) {
        List<FileEntry> databaseOrder = orderedEntries;
        if (Boolean.TRUE.equals(activeRootMode)) {
            // The SQLite primary key is the path. Keep the UI's filename order independent.
            databaseOrder = new ArrayList<>(orderedEntries);
            databaseOrder.sort(FileEntry::comparePath);
            checkCancelled(version);
        }
        SQLiteDatabase db = database.getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("entries", null, null);
            db.delete("directories", null, null);
            try (SQLiteStatement insert = db.compileStatement("INSERT INTO entries(path,directory,size,modified) VALUES(?,?,?,?)");
                 SQLiteStatement dirs = db.compileStatement("INSERT OR REPLACE INTO directories(path,readable) VALUES(?,?)")) {
                int checked = 0;
                for (FileEntry entry : databaseOrder) {
                    if ((checked++ & 255) == 0) checkCancelled(version);
                    insertEntry(insert, entry);
                }
                for (String path : found.readable) insertDirectory(dirs, path, true);
                for (String path : found.denied) insertDirectory(dirs, path, false);
            }
            writeLong(db, "last_scan", completed);
            writeLong(db, "duration", duration);
            writeLong(db, "root_access_errors", found.rootAccessErrors);
            writeLong(db, "root_excluded", found.rootExcluded);
            Runnable probe = transactionProbe;
            if (probe != null) probe.run();
            checkCancelled(version);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    private void syncObservers() {
        if (!sharedAccess()) {
            stopObservers();
            return;
        }
        List<String> paths = new ArrayList<>();
        for(String path:readableDirectories)if(watchable(path))paths.add(path);
        paths.sort((a, b) -> {
            int order = Integer.compare(a.length(), b.length());
            return order == 0 ? a.compareTo(b) : order;
        });
        Set<String> desired = new HashSet<>(paths.subList(0, Math.min(paths.size(), WATCH_LIMIT)));
        Iterator<Map.Entry<String, DirectoryObserver>> iterator = observers.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, DirectoryObserver> observer = iterator.next();
            if (!desired.contains(observer.getKey())) { observer.getValue().stop(); iterator.remove(); }
        }
        for (String path : desired) ensureObserver(path);
    }

    private void ensureObserver(String path) {
        if (closed || !probeLiveWatching || !watchable(path) || observers.size() >= WATCH_LIMIT || observers.containsKey(path)) return;
        DirectoryObserver observer = new DirectoryObserver(path);
        try {
            observer.startWatching();
            observers.put(path, observer);
        } catch (RuntimeException failure) { reconcileNeeded = true; }
    }

    private void stopObservers() {
        for (DirectoryObserver observer : observers.values()) observer.stop();
        observers.clear();
    }

    private final class DirectoryObserver extends FileObserver {
        private final String path;
        private volatile boolean active = true;
        DirectoryObserver(String path) {
            super(ioPath(path), CREATE | DELETE | MOVED_FROM | MOVED_TO | CLOSE_WRITE | ATTRIB | DELETE_SELF | MOVE_SELF);
            this.path = path;
        }
        void stop() { active = false; stopWatching(); }
        @Override public void onEvent(int event, String relativePath) {
            if (!active || closed) return;
            String changed = relativePath == null ? path : new File(path, relativePath).getAbsolutePath();
            synchronized (eventsLock) {
                if ((event & 0x00004000) != 0 || pendingEvents.size() >= EVENT_LIMIT) eventGap = true;
                else pendingEvents.put(changed, pendingEvents.containsKey(changed) ? pendingEvents.get(changed) | event : event);
                if (eventTaskQueued) return;
                eventTaskQueued = true;
            }
            synchronized(catchUpLock){
                pendingPathUpdates++;
                try{
                    worker.schedule(()->{try{drainEvents();}finally{finishPathUpdate(true);}},160,TimeUnit.MILLISECONDS);
                    interruptCatchUpForPathUpdate();
                }catch(RejectedExecutionException stopped){pendingPathUpdates--;}
            }
        }
    }

    private void drainEvents() {
        Map<String, Integer> changes;
        boolean gap;
        synchronized (eventsLock) {
            changes = new LinkedHashMap<>(pendingEvents);
            pendingEvents.clear();
            gap = eventGap;
            eventGap = false;
            eventTaskQueued = false;
        }
        if (closed || !loaded) return;
        reconcileNeeded |= gap;
        if (changes.isEmpty()) { publishStable(""); return; }
        int version = scopeVersion.get();
        ScanData additions = new ScanData();
        List<String> removals = new ArrayList<>();
        try {
            // Collapse descendants when a newly moved/copied directory already covers them.
            List<String> paths = new ArrayList<>(changes.keySet());
            Collections.sort(paths);
            String previousTree = null;
            for (String path : paths) {
                checkWork(version, false);
                if (!underRoots(path) || (previousTree != null && path.startsWith(previousTree + "/"))) continue;
                FileEntry entry = indexedMetadata(new File(ioPath(path)));
                FileEntry previous=entries.get(path);
                if(entry!=null&&entry.directory&&previous!=null&&previous.directory
                        &&(changes.get(path)&(FileObserver.CREATE|FileObserver.MOVED_TO))==0){
                    // ATTRIB on an existing folder is not a request to walk its descendants.
                    if(entry.modified!=previous.modified)additions.entries.put(entry);
                    continue;
                }
                if(entry==null&&isDenied(new File(ioPath(path)))){reconcileNeeded=true;continue;}
                if (entry == null || entry.directory || (entries.get(path) != null && entries.get(path).directory)) {
                    removals.add(path);
                    previousTree = path;
                    if (entry != null) walk(new File(ioPath(path)), additions, version, false);
                    else if (isDenied(new File(ioPath(path))) && entries.get(path) != null && entries.get(path).directory) additions.denied.add(path);
                } else {
                    FileEntry old=entries.get(path);
                    if(old==null||old.size!=entry.size||old.modified!=entry.modified||old.directory!=entry.directory)
                        additions.entries.put(entry);
                }
            }
            if(removals.isEmpty()&&additions.entries.isEmpty()){publishStable("");return;}
            int directoryCount=readableDirectories.size()+deniedDirectories.size();
            persistDelta(removals, additions, version);
            for (String path : removals) removeTree(path);
            entries.putAll(additions.entries);
            readableDirectories.addAll(additions.readable);
            deniedDirectories.addAll(additions.denied);
            snapshot = mergeSnapshot(snapshot,removals,additions.entries);
            if(!additions.readable.isEmpty()||!additions.denied.isEmpty()
                    ||directoryCount!=readableDirectories.size()+deniedDirectories.size())syncObservers();
            publishStable("");
        } catch (CancellationException ignored) {
            reconcileNeeded = true;
        } catch (RuntimeException | OutOfMemoryError failure) {
            additions.clear();
            reconcileNeeded = true;
            SupportLog.record(SupportLog.Event.UPDATE_FAILED, Boolean.TRUE.equals(activeRootMode), 0, 0);
            publishStable("Live update could not be saved. Refresh to reconcile. " + failureMessage(failure));
        }
    }

    private void persistDelta(List<String> removals, ScanData additions, int version) {
        persistDelta(removals,additions,version,0,0);
    }
    private void persistDelta(List<String> removals, ScanData additions, int version,long completed,long duration) {
        persistDelta(removals,additions,version,completed,duration,false);
    }
    private void persistDelta(List<String> removals,ScanData additions,int version,long completed,long duration,boolean operation) {
        SQLiteDatabase db = database.getWritableDatabase();
        db.beginTransaction();
        try {
            for (String path : removals) {
                String prefix = path.endsWith("/") ? path : path + "/";
                String upper = prefix.substring(0, prefix.length() - 1) + "0";
                String[] arguments = {path, prefix, upper};
                // Binary prefix range preserves case and literal %, _ and backslash names.
                db.delete("entries", "path=? OR (path>=? AND path<?)", arguments);
                db.delete("directories", "path=? OR (path>=? AND path<?)", arguments);
            }
            try (SQLiteStatement insert = db.compileStatement("INSERT OR REPLACE INTO entries(path,directory,size,modified) VALUES(?,?,?,?)");
                 SQLiteStatement dirs = db.compileStatement("INSERT OR REPLACE INTO directories(path,readable) VALUES(?,?)")) {
                for (FileEntry entry : additions.entries.values()) insertEntry(insert, entry);
                for (String path : additions.readable) insertDirectory(dirs, path, true);
                for (String path : additions.denied) insertDirectory(dirs, path, false);
            }
            checkWork(version, false);
            if(completed>0){checkCancelled(version);writeLong(db,"last_scan",completed);writeLong(db,"duration",duration);}
            if(completed>0||operation){Runnable probe=transactionProbe;if(probe!=null)probe.run();checkCancelled(version);}
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    private void removeTree(String path) {
        String prefix = path.endsWith("/") ? path : path + "/";
        FileEntry existing = entries.remove(path);
        readableDirectories.remove(path);
        deniedDirectories.remove(path);
        if (existing != null && !existing.directory) return;
        entries.removeDescendants(path);
        readableDirectories.removeIf(candidate -> candidate.startsWith(prefix));
        deniedDirectories.removeIf(candidate -> candidate.startsWith(prefix));
    }

    private boolean underRoots(String path) {
        for (String root : rootPaths) if (root.equals("/") || path.equals(root) || path.startsWith(root + "/")) return true;
        return false;
    }

    private void publishStable(String error) { publishStable(error, stableStatus()); }

    private void publishStable(String error, String status) {
        publish(false, readableDirectories.size(), deniedDirectories.size() + rootAccessErrors,
                readableDirectories.size() + deniedDirectories.size(), status, error);
    }

    private String stableStatus() {
        if (lastScanMillis == 0) return rootEnabled ? "Root index is empty. Refresh to index /data, /system and other folders."
                : "Shared-storage index is empty. Refresh to index accessible folders.";
        if (Boolean.TRUE.equals(activeRootMode)) {
            return snapshot.size() + " items. Shared-storage watch requests: "+observers.size()+". Private root updates require refresh. "
                    + rootAccessErrors + " access errors. Virtual folders, symlinks and "
                    + rootExcludedPaths + " excluded paths/aliases skipped."
                    + (scanWarning.isEmpty() ? "" : " " + scanWarning);
        }
        int gaps = Math.max(0, readableDirectories.size() - observers.size());
        return snapshot.size() + " items. Watch requests: " + observers.size() + "/" + readableDirectories.size()
                + ". " + deniedDirectories.size() + " folders denied. "
                + (gaps > 0 ? gaps + " folders outside watch coverage. " : "")
                + (reconcileNeeded ? "Change gap detected. Refresh required. " : "")
                + "Android does not confirm watch registration; refresh after app restart.";
    }

    private void publish(boolean scanning, int scanned, int denied, int total, String status, String error) {
        // A foreground service must remain alive through cache preparation and scan commit.
        State next = new State(snapshot, scanning || scanQueued.get() || scopeScanPending.get(), loaded, scanned, denied, observers.size(), total,
                lastScanMillis, scanDurationMillis, status, error, scanning ? discoveredItems : snapshot.size());
        state = next;
        main.post(() -> {
            for (Listener listener : listeners) listener.onChanged(next);
        });
    }

    private static List<FileEntry> sorted(FileEntryTable values) {
        List<FileEntry> result = new ArrayList<>(values.values());
        result.sort(ORDER);
        return Collections.unmodifiableList(result);
    }

    private static List<FileEntry> sortedCache(FileEntryTable entries) {
        Runtime memory = Runtime.getRuntime();
        long available = memory.maxMemory() - memory.totalMemory() + memory.freeMemory();
        if (entries.size() < 65_536 || available < 24L * entries.size()
                || memory.availableProcessors() < 4) return sorted(entries);
        FileEntry[] ordered = entries.values().toArray(new FileEntry[0]);
        Arrays.parallelSort(ordered, ORDER);
        return Collections.unmodifiableList(Arrays.asList(ordered));
    }

    /** Keep existing order and sort only the delta; published snapshots remain immutable. */
    private static List<FileEntry> mergeSnapshot(List<FileEntry> previous,List<String> removals,FileEntryTable additions){
        if(removals.isEmpty()&&additions.isEmpty())return previous;
        Set<String> removed = new HashSet<>(removals);
        Map<String, Set<String>> removedNames = new HashMap<>();
        for (String path : removed) {
            int slash = path.lastIndexOf('/');
            if (slash >= 0) removedNames.computeIfAbsent(slash == 0 ? "/" : path.substring(0, slash),
                    key -> new HashSet<>()).add(path.substring(slash + 1));
        }
        boolean removeAll = removed.contains("/");
        String lastParent = null;
        boolean removedParent = false;
        Set<String> names = null;
        ArrayList<FileEntry> changed=new ArrayList<>(additions.values());changed.sort(ORDER);
        ArrayList<FileEntry> merged=new ArrayList<>(previous.size()+changed.size());int next=0;
        for(FileEntry entry:previous){
            if(additions.contains(entry))continue;
            if (!removed.isEmpty() && !entry.parent.equals(lastParent)) {
                lastParent = entry.parent;
                removedParent = removeAll || within(lastParent, removed);
                names = removedNames.get(lastParent);
            }
            if (removedParent || (names != null && names.contains(entry.name))) continue;
            while(next<changed.size()&&ORDER.compare(changed.get(next),entry)<0)merged.add(changed.get(next++));
            merged.add(entry);
        }
        while(next<changed.size())merged.add(changed.get(next++));
        return Collections.unmodifiableList(merged);
    }

    private static String failureMessage(Throwable failure) {
        String text = failure.getMessage();
        return text == null || text.trim().isEmpty() ? failure.getClass().getSimpleName() : text;
    }

    private static List<String> sharedRoots(Context context) {
        Set<String> paths = new LinkedHashSet<>();
        addRoot(paths, Environment.getExternalStorageDirectory());
        if (Build.VERSION.SDK_INT >= 30) {
            StorageManager manager = context.getSystemService(StorageManager.class);
            if (manager != null) for (StorageVolume volume : manager.getStorageVolumes()) addRoot(paths, volume.getDirectory());
        }
        return Collections.unmodifiableList(new ArrayList<>(paths));
    }

    private static void addRoot(Set<String> paths, File root) {
        if (root == null) return;
        try { paths.add(root.getCanonicalPath()); } catch (IOException failure) { paths.add(root.getAbsolutePath()); }
    }

    private static void insertEntry(SQLiteStatement insert, FileEntry entry) {
        insert.clearBindings();
        insert.bindString(1, entry.path());
        insert.bindLong(2, entry.directory ? 1 : 0);
        insert.bindLong(3, entry.size);
        insert.bindLong(4, entry.modified);
        insert.executeInsert();
    }

    private static void insertDirectory(SQLiteStatement insert, String path, boolean readable) {
        insert.clearBindings();
        insert.bindString(1, path);
        insert.bindLong(2, readable ? 1 : 0);
        insert.executeInsert();
    }

    private static long readLong(SQLiteDatabase db, String name) {
        try (Cursor cursor = db.rawQuery("SELECT value FROM metadata WHERE name=?", new String[]{name})) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0;
        }
    }

    private static void writeLong(SQLiteDatabase db, String name, long value) {
        db.execSQL("INSERT OR REPLACE INTO metadata(name,value) VALUES(?,?)", new Object[]{name, value});
    }

    private static final class ScanData {
        FileEntryTable entries = new FileEntryTable();
        Set<String> readable = new HashSet<>();
        Set<String> denied = new HashSet<>();
        int rootAccessErrors;
        int rootExcluded;
        String warning = "";
        // Release table capacity as well as records when an oversized scan is abandoned.
        void clear() { entries.clear(); readable = Collections.emptySet(); denied = Collections.emptySet(); }
    }

    private static final class Database extends SQLiteOpenHelper {
        Database(Context context, boolean root, String prefix) { super(context, prefix + (root ? "root-filenames.db" : "shared-filenames.db"), null, 1); }
        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE entries(path TEXT PRIMARY KEY COLLATE BINARY,directory INTEGER NOT NULL,size INTEGER NOT NULL,modified INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE directories(path TEXT PRIMARY KEY COLLATE BINARY,readable INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE metadata(name TEXT PRIMARY KEY,value INTEGER NOT NULL)");
        }
        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            throw new IllegalStateException("Unsupported index database version " + oldVersion + " to " + newVersion);
        }
    }
}
