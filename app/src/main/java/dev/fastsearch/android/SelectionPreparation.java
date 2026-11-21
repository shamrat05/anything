package dev.fastsearch.android;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import dev.fastsearch.core.FileEntry;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/** Identity checks never delay displaying a panel; dismissal cancels its helper. */
final class SelectionPreparation {
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    FileClipboard.Snapshot snapshot;
    String error;
    private Future<?> pending;
    private Runnable changed;
    private boolean closed;
    void start(AppSheet sheet,List<FileEntry> entries,boolean root,ExecutorService executor,Runnable changed){
        this.changed=changed;Context context=sheet.getContext().getApplicationContext();List<FileEntry> selection=new ArrayList<>(entries);
        WeakReference<SelectionPreparation> owner=new WeakReference<>(this);
        sheet.setOnDismissListener(dialog->{closed=true;if(pending!=null)pending.cancel(true);this.changed=null;snapshot=null;});
        try{pending=executor.submit(()->{
            FileClipboard.Snapshot captured=null;String problem=null;
            try{captured=FileClipboard.capture(context,selection,true,root);}catch(Exception failure){problem=failure.getMessage();}
            FileClipboard.Snapshot result=captured;String failure=problem;
            MAIN.post(()->{SelectionPreparation state=owner.get();if(state==null||state.closed)return;state.snapshot=result;state.error=failure;if(state.changed!=null)state.changed.run();});
        });}catch(java.util.concurrent.RejectedExecutionException busy){error="File access is busy. Try again.";changed.run();}
    }
}
