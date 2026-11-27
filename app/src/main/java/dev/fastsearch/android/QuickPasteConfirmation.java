package dev.fastsearch.android;

import android.app.Activity;
import android.widget.TextView;
import java.io.File;
import java.io.IOException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Hand-off to the ordinary file-operation worker only after the exact destination is reviewed. */
final class QuickPasteConfirmation {
    private QuickPasteConfirmation(){}

    static AppSheet show(Activity activity,String directory,BooleanSupplier rootAllowed,
                         Runnable accepted,Consumer<String> error){
        FileClipboard.Snapshot snapshot=FileClipboard.read(activity);
        if(snapshot==null){error.accept("Clipboard is empty.");return null;}
        try{FileOperationWorker.requirePath(directory);}
        catch(IOException invalid){error.accept(invalid.getMessage());return null;}
        if("/".equals(directory)||snapshot.root&&!rootAllowed.getAsBoolean()){
            error.accept("This destination or its required access is unavailable.");
            return null;
        }
        AppSheet sheet=new AppSheet(activity,snapshot.move?"Move here?":"Paste here?");
        TextView path=sheet.text(directory,14,sheet.theme.ink);
        path.setTextIsSelectable(true);
        sheet.content.addView(sheet.text("Destination",13,sheet.theme.muted));
        sheet.content.addView(path);
        int count=snapshot.entries.length();
        StringBuilder names=new StringBuilder();
        for(int i=0;i<Math.min(5,count);i++){
            org.json.JSONObject item=snapshot.entries.optJSONObject(i);
            if(item==null)continue;
            if(names.length()>0)names.append('\n');
            names.append(new File(item.optString("path")).getName());
        }
        if(count>5)names.append("\n+").append(count-5).append(" more");
        TextView files=sheet.text(count+(count==1?" item\n":" items\n")+names,14,sheet.theme.ink);
        files.setPadding(0,sheet.dp(12),0,0);sheet.content.addView(files);
        sheet.content.addView(sheet.text("Existing files are never replaced.",13,sheet.theme.muted));
        sheet.action("Cancel",false,false,null);
        sheet.action(snapshot.move?"Move here":"Paste here",true,false,()->{
            FileClipboard.Snapshot latest=FileClipboard.read(activity);
            if(latest==null||!snapshot.id.equals(latest.id)||!snapshot.entries.toString().equals(latest.entries.toString())
                    ||snapshot.root&&!rootAllowed.getAsBoolean()){
                error.accept("The clipboard or storage access changed. Open Paste again.");
                return;
            }
            try{FileOperationService.transfer(activity,directory,snapshot);accepted.run();}
            catch(IOException failure){error.accept(failure.getMessage());}
        });
        sheet.show();
        return sheet;
    }
}
