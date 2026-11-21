package dev.fastsearch.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** One user-requested clipboard operation, independent of any Activity lifetime. */
public final class FileOperationService extends Service {
    private static final String CANCEL = "dev.fastsearch.android.CANCEL_FILE_OPERATION";
    private static final String DESTINATION = "destination";
    private static final int NOTIFICATION = 2;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<WeakReference<Listener>> LISTENERS = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean CANCEL_REQUESTED = new AtomicBoolean();
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static volatile State state = new State(false, false, 0, 0, "", "");
    private static volatile FileOperationProcess active;
    private static final java.util.concurrent.atomic.AtomicReference<FileClipboard.Snapshot> PENDING_DELETE=new java.util.concurrent.atomic.AtomicReference<>();
    private static final java.util.concurrent.atomic.AtomicReference<RenameRequest> PENDING_RENAME=new java.util.concurrent.atomic.AtomicReference<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private boolean submitted;
    private volatile boolean taskFinished;
    private volatile boolean destroyed;
    private long notified;

    public interface Listener { void onChanged(State state); }

    public static final class State {
        public final boolean running;
        public final boolean cancelled;
        public final int completed;
        public final int total;
        public final String status;
        public final String error;
        State(boolean running, boolean cancelled, int completed, int total, String status, String error) {
            this.running = running; this.cancelled = cancelled; this.completed = completed; this.total = total;
            this.status = status; this.error = error;
        }
    }

    public static State getState() { return state; }

    public static void addListener(Listener listener) {
        if (listener == null) return;
        for (WeakReference<Listener> ref : LISTENERS) if (ref.get() == listener) return;
        WeakReference<Listener> ref = new WeakReference<>(listener);
        LISTENERS.add(ref);
        MAIN.post(() -> { Listener current = ref.get(); if (current != null && LISTENERS.contains(ref)) current.onChanged(state); });
    }

    public static void removeListener(Listener listener) {
        for (WeakReference<Listener> ref : LISTENERS) if (ref.get() == null || ref.get() == listener) LISTENERS.remove(ref);
    }

    public static synchronized void start(Context context, String destinationDirectory) throws IOException {
        FileOperationWorker.requirePath(destinationDirectory);
        if (state.running || BUSY.get()) throw new IOException("A file operation is already running.");
        int count = FileClipboard.count(context);
        if (count == 0) throw new IOException("Copy or cut files or folders first.");
        BUSY.set(true);
        CANCEL_REQUESTED.set(false);
        publish(new State(true, false, 0, count, "Preparing file operation", ""));
        try {
            context.getApplicationContext().startForegroundService(new Intent(context, FileOperationService.class).putExtra(DESTINATION, destinationDirectory));
        } catch (RuntimeException error) {
            BUSY.set(false);
            publish(new State(false, false, 0, count, "File operation did not start", "Android could not start file operations."));
            throw new IOException("Android could not start file operations.", error);
        }
    }

    static synchronized void delete(Context context,FileClipboard.Snapshot snapshot)throws IOException{
        if(state.running||!BUSY.compareAndSet(false,true))throw new IOException("A file operation is already running.");
        CANCEL_REQUESTED.set(false);PENDING_DELETE.set(snapshot);
        publish(new State(true,false,0,snapshot.entries.length(),"Preparing deletion",""));
        try{context.getApplicationContext().startForegroundService(new Intent(context,FileOperationService.class).putExtra("delete",true));}
        catch(RuntimeException failure){PENDING_DELETE.set(null);BUSY.set(false);publish(new State(false,false,0,snapshot.entries.length(),"Deletion did not start","Android could not start deletion."));throw new IOException("Android could not start deletion.",failure);}
    }

    private static final class RenameRequest {
        final FileClipboard.Snapshot snapshot;final JSONObject request;
        RenameRequest(FileClipboard.Snapshot snapshot,java.util.Map<String,String> names)throws Exception{this.snapshot=snapshot;JSONArray entries=new JSONArray(snapshot.entries.toString());
            for(int i=0;i<entries.length();i++){JSONObject item=entries.getJSONObject(i);String name=names.get(item.getString("path"));if(name==null)throw new IOException("The rename selection changed.");dev.fastsearch.core.RenameRules.validate(name);item.put("newName",name);}
            request=new JSONObject().put("mode","rename").put("entries",entries);
        }
    }
    static synchronized void rename(Context context,FileClipboard.Snapshot snapshot,java.util.Map<String,String> names)throws Exception{
        RenameRequest request=new RenameRequest(snapshot,names);
        if(state.running||!BUSY.compareAndSet(false,true))throw new IOException("A file operation is already running.");
        CANCEL_REQUESTED.set(false);PENDING_RENAME.set(request);publish(new State(true,false,0,snapshot.entries.length(),"Preparing rename",""));
        try{context.getApplicationContext().startForegroundService(new Intent(context,FileOperationService.class).putExtra("rename",true));}
        catch(RuntimeException failure){PENDING_RENAME.set(null);BUSY.set(false);publish(new State(false,false,0,snapshot.entries.length(),"Rename did not start","Android could not start renaming."));throw new IOException("Android could not start renaming.",failure);}
    }

    /** Context is retained only for API symmetry; cancellation works without an Activity. */
    public static void cancel(Context context) {
        CANCEL_REQUESTED.set(true);
        FileOperationProcess process = active;
        if (process != null) process.cancel();
    }

    private static void publish(State next) {
        state = next;
        MAIN.post(() -> {
            for (WeakReference<Listener> ref : LISTENERS) {
                Listener listener = ref.get();
                if (listener == null) LISTENERS.remove(ref);
                else listener.onChanged(next);
            }
        });
    }

    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel("file-operations", "File operations", NotificationManager.IMPORTANCE_LOW));
        startForeground(NOTIFICATION, notification("Preparing file operation"));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || CANCEL.equals(intent.getAction())) {
            cancel(this);
            if (!submitted) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); }
            return START_NOT_STICKY;
        }
        if (submitted) return START_NOT_STICKY;
        submitted = true;
        String destination = intent.getStringExtra(DESTINATION);
        FileClipboard.Snapshot deletion=intent.getBooleanExtra("delete",false)?PENDING_DELETE.getAndSet(null):null;
        RenameRequest renaming=intent.getBooleanExtra("rename",false)?PENDING_RENAME.getAndSet(null):null;
        worker.execute(() -> perform(destination,deletion,renaming));
        return START_NOT_STICKY;
    }

    private void perform(String destination,FileClipboard.Snapshot deletion,RenameRequest renaming) {
        boolean deleting=deletion!=null;
        FileClipboard.Snapshot clipboard = deleting?deletion:renaming!=null?renaming.snapshot:FileClipboard.read(this);
        JSONArray committed = new JSONArray();
        boolean changed = false;
        int total = clipboard == null ? 0 : clipboard.entries.length();
        boolean move = clipboard != null && clipboard.move;
        State outcome = null;
        try {
            if (clipboard == null) throw new IOException("The file clipboard is empty or expired.");
            if (CANCEL_REQUESTED.get()) throw new IOException("File operation stopped.");
            if(!deleting&&renaming==null)FileOperationWorker.requirePath(destination);
            final JSONArray[] progressIndices = {committed};
            JSONObject request = deleting ? clipboard.deleteRequest()
                    : renaming != null ? renaming.request : clipboard.request(destination);
            FileOperationProcess.Progress progress = (done, count, indices) -> {
                if (indices != null) progressIndices[0] = indices;
                update(true, CANCEL_REQUESTED.get(), done, count, (deleting?"Deleting ":renaming!=null?"Renaming ":move ? "Moving " : "Copying ") + done + " of " + count + " items", "");
            };
            try {
                changed = true; // Reconcile conservatively even if an operation stops before its final record.
                JSONObject result;
                if (deleting && !clipboard.root) {
                    result = FileOperationWorker.runLocal(this, request, CANCEL_REQUESTED::get, progress);
                } else {
                    try (FileOperationProcess process = new FileOperationProcess(this, request, clipboard.root, progress)) {
                        active = process;
                        if (CANCEL_REQUESTED.get()) process.cancel();
                        result = process.run();
                    }
                }
                committed = result.optJSONArray("completedIndices");
                if (committed == null) committed = progressIndices[0];
                changed |= result.optBoolean("changed");
                int done = committed.length();
                boolean stopped = result.optBoolean("cancelled") || CANCEL_REQUESTED.get();
                String status = result.optBoolean("ok") ? (deleting?"Deleted ":renaming!=null?"Renamed ":move ? "Moved " : "Copied ") + done + " items"
                        : (stopped ? "Stopped after " : "Finished ") + done + " of " + total + " items";
                outcome = new State(false, stopped, done, total, status, result.optBoolean("ok") ? "" : result.optString("error", "File operation failed."));
            } finally {
                // Progress acknowledgements remain useful if a helper exits without a final record.
                if (committed.length() == 0 && progressIndices[0].length() != 0) committed = progressIndices[0];
            }
        } catch (Exception failure) {
            String error = failure instanceof IOException ? failure.getMessage() : "File operation failed. Refresh and try again.";
            outcome = new State(false, CANCEL_REQUESTED.get(), committed.length(), total,
                    CANCEL_REQUESTED.get() ? "File operation stopped" : "File operation failed", error == null ? "File operation failed." : error);
        } finally {
            active = null;
            if (clipboard != null){if(deleting||renaming!=null)FileClipboard.deleted(this,clipboard,committed);else FileClipboard.completed(this, clipboard, committed);}
            if (changed || committed.length() != 0) {
                update(true, CANCEL_REQUESTED.get(), committed.length(), total, "Updating file index", "");
                try { java.util.List<String> paths;
                    if(renaming!=null){paths=new java.util.ArrayList<>();JSONArray entries=renaming.request.getJSONArray("entries");for(int i=0;i<entries.length();i++){
                        JSONObject item=entries.getJSONObject(i);String source=item.getString("path");paths.add(source);paths.add(new java.io.File(new java.io.File(source).getParent(),item.getString("newName")).getPath());}}
                    else paths=affectedPaths(clipboard,destination,deleting);
                    ThumbnailStore.invalidate(this,paths);
                    FileIndex.get(this).reconcilePaths(paths).get(); }
                catch (Exception failure) {
                    if(failure instanceof InterruptedException)Thread.currentThread().interrupt();
                    State previous=outcome;
                    outcome=new State(false,CANCEL_REQUESTED.get(),committed.length(),total,
                            previous==null?"File operation finished":previous.status,"File changes could not be saved. Refresh to retry.");
                }
            }
            // Final listeners may read the clipboard, so publish only after its
            // acknowledged move entries have been removed.
            publish(outcome != null ? outcome : new State(false, true, committed.length(), total,
                    "File operation stopped", "File operation stopped."));
            taskFinished = true;
            if (destroyed) BUSY.set(false);
            MAIN.post(() -> { if (!destroyed) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); } });
        }
    }

    private static java.util.List<String> affectedPaths(FileClipboard.Snapshot clipboard,String destination,boolean deleting)throws Exception{
        java.util.List<String> paths=new java.util.ArrayList<>();
        if(clipboard!=null)for(int i=0;i<clipboard.entries.length();i++){
            String source=clipboard.entries.getJSONObject(i).getString("path");
            if(deleting||clipboard.move)paths.add(source);
            if(!deleting)paths.add(new java.io.File(destination,new java.io.File(source).getName()).getPath());
        }
        return paths;
    }

    private void update(boolean running, boolean cancelled, int completed, int total, String text, String error) {
        publish(new State(running, cancelled, completed, total, text, error));
        long now = android.os.SystemClock.elapsedRealtime();
        if (running && now - notified >= 1000) {
            notified = now;
            getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(text));
        }
    }

    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 2, new Intent(this, FileOperationService.class).setAction(CANCEL), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, "file-operations").setSmallIcon(R.drawable.ic_search).setContentTitle("File operations")
                .setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build()).build();
    }

    @Override public void onTimeout(int startId, int foregroundServiceType) { cancel(this); stopSelf(); }
    @Override public void onDestroy() {
        destroyed = true;
        if (!taskFinished) cancel(this);
        // Explicit helper cancellation permits rollback. Let even an already
        // queued task run its finally block instead of abandoning BUSY forever.
        worker.shutdown();
        if (!submitted && state.running) publish(new State(false, true, state.completed, state.total,
                "File operation stopped", ""));
        if (!submitted || taskFinished) BUSY.set(false);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
