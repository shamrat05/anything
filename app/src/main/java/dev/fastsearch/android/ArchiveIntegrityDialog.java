package dev.fastsearch.android;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/** Modal, cancellable tool opened only by the viewer's explicit More action. */
final class ArchiveIntegrityDialog {
    static AppSheet open(Activity owner,ExecutorService worker,Uri uri,String path,boolean root,
                         String expected,String name,Consumer<AppSheet> dismissed){
        Context context=owner.getApplicationContext();
        AppSheet sheet=new AppSheet(owner,"Test archive integrity");
        CancellationSignal cancel=new CancellationSignal();
        Future<?>[] pending=new Future<?>[1];
        ProgressBar progress=new ProgressBar(owner);
        progress.setIndeterminate(true);
        TextView status=sheet.text("Checking "+ZipDirectory.safeLabel(name)+"…",14,sheet.theme.ink);
        status.setAccessibilityLiveRegion(TextView.ACCESSIBILITY_LIVE_REGION_POLITE);
        TextView scope=sheet.text("Checks ZIP data and CRCs only. It does not verify signatures, detect malware, or say whether an APK is safe to install.",13,sheet.theme.muted);
        scope.setPadding(0,sheet.dp(10),0,0);
        sheet.content.addView(status);
        sheet.content.addView(progress);
        sheet.content.addView(scope);
        Button done=sheet.action("Cancel",true,false,null);
        sheet.setOnDismissListener(dialog->{
            if(pending[0]!=null&&!pending[0].isDone())pending[0].cancel(true);
            cancel.cancel();
            dismissed.accept(sheet);
        });
        sheet.show();
        Handler ui=new Handler(Looper.getMainLooper());
        WeakReference<AppSheet> visible=new WeakReference<>(sheet);
        // The UI task is short-lived even if a document provider is slow to cancel.
        WeakReference<TextView> currentStatus=new WeakReference<>(status);
        WeakReference<ProgressBar> currentProgress=new WeakReference<>(progress);
        WeakReference<Button> currentDone=new WeakReference<>(done);
        try{
            pending[0]=worker.submit(()->{
                String result;
                try(ParcelFileDescriptor descriptor=uri==null?SafeFileAccess.open(context,path,root)
                        :context.getContentResolver().openFileDescriptor(uri,"r",cancel)){
                    if(descriptor==null)throw new IOException("File access unavailable.");
                    String before=RootFileProvider.identity(Os.fstat(descriptor.getFileDescriptor()));
                    if(expected!=null&&!expected.equals(before))result="File changed. Reopen it before testing.";
                    else {
                        try(ParcelFileDescriptor.AutoCloseInputStream file=new ParcelFileDescriptor.AutoCloseInputStream(
                                ParcelFileDescriptor.dup(descriptor.getFileDescriptor()))){
                            ArchiveIntegrity.Result checked=ArchiveIntegrity.check(file.getChannel(),cancel::isCanceled);
                            result=before.equals(RootFileProvider.identity(Os.fstat(descriptor.getFileDescriptor())))
                                    ? summary(checked):"File changed while testing. The result was discarded.";
                        }
                    }
                }catch(Exception problem){
                    if(cancel.isCanceled()||Thread.currentThread().isInterrupted())return;
                    result="Could not test this archive. Its ZIP directory may be damaged, split, or inaccessible.";
                }
                final String outcome=result;
                ui.post(()->{
                    AppSheet popup=visible.get();
                    TextView label=currentStatus.get();
                    ProgressBar bar=currentProgress.get();
                    Button close=currentDone.get();
                    if(popup==null||!popup.isShowing()||cancel.isCanceled()||label==null||bar==null||close==null)return;
                    label.setText(outcome);
                    popup.content.removeView(bar);
                    close.setText("Done");
                });
            });
        }catch(RejectedExecutionException busy){
            status.setText("File access is busy. Try again.");
            sheet.content.removeView(progress);
            done.setText("Done");
        }
        return sheet;
    }

    static String summary(ArchiveIntegrity.Result result){
        if(result.passed()){
            if(result.entries==0)return "Empty ZIP archive. No file contents to test.";
            return "Passed: "+result.verified+" of "+result.entries+" entries verified.";
        }
        StringBuilder text=new StringBuilder(result.damaged>0?"Archive data failed the check.":"Partial check. This archive has not passed.");
        text.append("\n\nVerified: ").append(result.verified).append(" of ").append(result.entries);
        if(result.damaged>0)text.append("\nFailed: ").append(result.damaged);
        if(result.unsupported>0)text.append("\nEncrypted or unsupported: ").append(result.unsupported);
        if(result.overBudget>0)text.append("\nOver the check limit: ").append(result.overBudget);
        if(result.unlisted>0)text.append("\nBeyond the ZIP directory limit: ").append(result.unlisted);
        if(result.firstDamagedEntry!=null)text.append("\n\nFirst failure: ").append(result.firstDamagedEntry);
        return text.toString();
    }

    private ArchiveIntegrityDialog(){}
}
