package dev.fastsearch.android;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.widget.Toast;

/** A captured selection is never deleted until this explicit confirmation is accepted. */
final class FileDeletion {
    static AppSheet confirm(Activity activity,java.util.List<dev.fastsearch.core.FileEntry> entries,boolean root,java.util.concurrent.ExecutorService executor,Runnable accepted){
        if (activity.isFinishing() || activity.isDestroyed()) return null;
        if (!root && Build.VERSION.SDK_INT <= 29
                && activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 33);
            Toast.makeText(activity, "Allow storage changes, then tap Delete again. If no prompt appears, check the app’s storage permission in Settings.", Toast.LENGTH_LONG).show();
            return null;
        }
        int count=entries.size();StringBuilder names=new StringBuilder();
        for(int i=0;i<Math.min(count,3);i++){if(i>0)names.append('\n');names.append(entries.get(i).name);}
        if(count>3)names.append("\n+").append(count-3);
        String title=count==1?"Delete this item?":"Delete "+count+" items?";
        AppSheet sheet=new AppSheet(activity,title);
        android.widget.TextView files=sheet.text(names.toString(),15,sheet.theme.ink);files.setPadding(0,0,0,sheet.dp(16));sheet.content.addView(files);
        sheet.content.addView(sheet.text("Permanently delete these items and any folder contents?",13,sheet.theme.muted));
        SelectionPreparation preparation=new SelectionPreparation();
        android.widget.ProgressBar preparing=new android.widget.ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);preparing.setIndeterminate(true);preparing.setContentDescription("Preparing deletion");sheet.content.addView(preparing,new android.widget.LinearLayout.LayoutParams(-1,sheet.dp(2)));
        sheet.action("Cancel",false,false,null);android.widget.Button confirm=sheet.action("Checking files…",true,true,null);
        confirm.setOnClickListener(view->{try{FileOperationService.delete(activity,preparation.snapshot);sheet.dismiss();accepted.run();}
            catch(Exception failure){Toast.makeText(activity,failure.getMessage(),Toast.LENGTH_LONG).show();}});
        confirm.setEnabled(false);confirm.setAlpha(.4f);sheet.show();preparation.start(sheet,entries,root,executor,()->{
            preparing.setVisibility(android.view.View.GONE);confirm.setText("Delete");confirm.setEnabled(preparation.snapshot!=null);confirm.setAlpha(preparation.snapshot!=null?1:.4f);
            if(preparation.error!=null)sheet.content.addView(sheet.text(preparation.error,13,sheet.theme.muted));
        });return sheet;
    }
    private FileDeletion(){}
}
