package dev.fastsearch.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** Owns the user-started scan; queries never start this service. */
public final class IndexService extends Service implements FileIndex.Listener {
    public static final String CANCEL = "dev.fastsearch.android.CANCEL";
    private FileIndex index;
    private boolean sawScan;
    private long notified;

    @Override public void onCreate() {
        super.onCreate();
        index = FileIndex.get(this);
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("index", "File indexing", NotificationManager.IMPORTANCE_LOW));
        startForeground(1, notification("Preparing file index"));
        index.addListener(this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent != null && CANCEL.equals(intent.getAction())) {
            index.cancelScan();
            stopSelf();
            return START_NOT_STICKY;
        }
        if(intent!=null&&intent.getBooleanExtra("rebuild",false))index.startScan();
        else if(intent!=null&&intent.getBooleanExtra("updates",false))index.reconcile(intent.getBooleanExtra("force",false));
        else index.refresh();
        return START_NOT_STICKY;
    }

    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent cancel = PendingIntent.getService(this, 1, new Intent(this, IndexService.class).setAction(CANCEL), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, "index").setSmallIcon(R.drawable.ic_search)
            .setContentTitle(getString(R.string.app_name)).setContentText(text).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).addAction(new Notification.Action.Builder(null, "Stop indexing", cancel).build()).build();
    }

    @Override public void onChanged(FileIndex.State state) {
        // A queued completion can belong to the previous storage scope.
        if (!state.scanning && index.getState().scanning) return;
        if (state.scanning) {
            sawScan = true;
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - notified > 1000) {
                notified = now;
                getSystemService(NotificationManager.class).notify(1, notification(state.status));
            }
        } else if (sawScan || !index.hasPendingWork()) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    @Override public void onTimeout(int startId, int foregroundServiceType) { index.cancelScan(); stopSelf(); }
    @Override public void onDestroy() { index.removeListener(this); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
