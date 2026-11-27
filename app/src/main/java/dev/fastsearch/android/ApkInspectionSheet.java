package dev.fastsearch.android;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/** Explicit opt-in deeper inspection, canceled when its sheet closes. */
final class ApkInspectionSheet {
    private ApkInspectionSheet() {}

    static AppSheet create(Activity host,ApkInstallAction.Details apk,ExecutorService worker,Consumer<AppSheet> closed){
        AppSheet sheet=new AppSheet(host,"APK inspection",true);
        ScrollView scroll=new ScrollView(host);
        LinearLayout report=new LinearLayout(host);
        report.setOrientation(LinearLayout.VERTICAL);
        report.setPadding(sheet.dp(12),sheet.dp(8),sheet.dp(12),sheet.dp(18));
        sheet.content.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        scroll.addView(report,new ScrollView.LayoutParams(-1,-2));
        report.addView(sheet.text("Read-only facts from this APK. This does not say whether it’s safe to install.",14,sheet.theme.muted));
        section(sheet,report,"APK file SHA-256",apk.fileSha256);
        TextView status=sheet.text("Reading package details…",13,sheet.theme.muted);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setPadding(0,sheet.dp(12),0,0);report.addView(status);
        Future<?>[] task=new Future<?>[1];
        sheet.action("Done",true,false,null);
        sheet.setOnDismissListener(dialog->{
            if(task[0]!=null)task[0].cancel(true);
            if(closed!=null)closed.accept(sheet);
        });
        sheet.setOnShowListener(dialog->{
            try{
                task[0]=worker.submit(()->{
                    ApkInspection.Report inspected=null;
                    try{inspected=apk.inspectMore(host.getApplicationContext());}
                    catch(Exception|OutOfMemoryError ignored){ /* Keep the verified checksum visible. */ }
                    ApkInspection.Report result=inspected;
                    host.runOnUiThread(()->{
                        if(!sheet.isShowing()||host.isDestroyed())return;
                        if(result==null){status.setText("More details could not be read. Reopen the APK to try again.");return;}
                        status.setVisibility(View.GONE);
                        section(sheet,report,"Requested permissions",result.permissions);
                        section(sheet,report,"Signer certificate SHA-256",result.certificates);
                        section(sheet,report,"Native library ABIs",result.nativeABIs);
                        section(sheet,report,"Split APK declarations",result.splits);
                    });
                });
            }catch(RejectedExecutionException busy){status.setText("Inspection is busy. Reopen this panel to retry.");}
        });
        return sheet;
    }

    private static void section(AppSheet sheet,LinearLayout parent,String title,String value){
        TextView heading=sheet.text(title,15,sheet.theme.ink);
        heading.setTypeface(sheet.getContext().getResources().getFont(R.font.inter_semibold));
        heading.setPadding(0,sheet.dp(16),0,sheet.dp(5));
        if(Build.VERSION.SDK_INT>=28)heading.setAccessibilityHeading(true);
        parent.addView(heading,new LinearLayout.LayoutParams(-1,-2));
        TextView contents=sheet.text(value,13,sheet.theme.muted);
        contents.setTextIsSelectable(true);
        parent.addView(contents,new LinearLayout.LayoutParams(-1,-2));
    }
}
