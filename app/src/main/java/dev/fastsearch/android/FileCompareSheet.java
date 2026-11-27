package dev.fastsearch.android;

import android.app.Activity;
import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import dev.fastsearch.core.FileEntry;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/** Read-only comparison of the two files explicitly selected by the user. */
final class FileCompareSheet {
    private FileCompareSheet(){}

    static AppSheet open(Activity activity,FileEntry before,FileEntry after,boolean root,
                         FileComparison.Mode mode,Consumer<AppSheet> closed){
        AppSheet sheet=new AppSheet(activity,mode==FileComparison.Mode.APK?"Compare APKs":
                mode==FileComparison.Mode.DEX?"Compare DEX files":"Compare text files",true);
        LinearLayout header=new LinearLayout(activity);
        header.setOrientation(LinearLayout.VERTICAL);
        LinearLayout files=new LinearLayout(activity);
        files.setOrientation(LinearLayout.VERTICAL);
        files.setBackground(AppSheet.shape(sheet.theme.chrome,sheet.dp(16)));
        files.setPadding(sheet.dp(12),sheet.dp(8),sheet.dp(12),sheet.dp(8));
        header.addView(files,new LinearLayout.LayoutParams(-1,-2));

        TextView summary=sheet.text("Reading comparison…",14,sheet.theme.ink);
        summary.setPadding(sheet.dp(4),sheet.dp(12),sheet.dp(4),sheet.dp(4));
        header.addView(summary,new LinearLayout.LayoutParams(-1,-2));
        TextView scope=sheet.text(scope(mode),12,sheet.theme.muted);
        scope.setPadding(sheet.dp(4),0,sheet.dp(4),sheet.dp(8));
        header.addView(scope,new LinearLayout.LayoutParams(-1,-2));

        ProgressBar progress=new ProgressBar(activity);progress.setIndeterminate(true);
        LinearLayout.LayoutParams spinner=new LinearLayout.LayoutParams(sheet.dp(40),sheet.dp(40));
        spinner.gravity=android.view.Gravity.CENTER_HORIZONTAL;
        header.addView(progress,spinner);
        ListView changes=new ListView(activity);
        changes.setDivider(null);changes.addHeaderView(header,null,false);changes.setAdapter(null);
        changes.setClipToPadding(false);changes.setPadding(0,0,0,sheet.dp(12));
        sheet.content.addView(changes,new LinearLayout.LayoutParams(-1,0,1));
        Button swap=sheet.action("Swap sides",false,false,null);
        // AppSheet actions normally dismiss. Comparisons swap the already read result in place.
        swap.setOnClickListener(null);
        swap.setEnabled(false);
        sheet.action("Done",true,false,null);

        Context context=activity.getApplicationContext();
        ExecutorService worker=Executors.newSingleThreadExecutor(task->new Thread(task,"file-compare"));
        Future<?> reading=worker.submit(()->{
            try{
                FileComparison.Result result=read(context,before,after,root,mode);
                activity.runOnUiThread(()->{
                    if(!sheet.isShowing())return;
                    progress.setVisibility(View.GONE);
                    render(sheet,files,summary,changes,before,after,result,false);
                    boolean[] reversed={false};
                    swap.setEnabled(true);
                    swap.setOnClickListener(v->{
                        reversed[0]=!reversed[0];
                        render(sheet,files,summary,changes,before,after,result,reversed[0]);
                    });
                });
            }catch(Exception failure){
                if(!Thread.currentThread().isInterrupted())activity.runOnUiThread(()->{
                    if(!sheet.isShowing())return;
                    progress.setVisibility(View.GONE);
                    summary.setText(failure instanceof IOException&&failure.getMessage()!=null
                            ?failure.getMessage():"One of the selected files could not be opened.");
                });
            }
        });
        sheet.setOnDismissListener(dialog->{
            reading.cancel(true);worker.shutdownNow();closed.accept(sheet);
        });
        names(sheet,files,before,after,null,false);
        sheet.show();
        return sheet;
    }

    private static FileComparison.Result read(Context context,FileEntry before,FileEntry after,
                                              boolean root,FileComparison.Mode mode)throws Exception{
        try(ParcelFileDescriptor left=SafeFileAccess.open(context,before.path(),root);
            ParcelFileDescriptor right=SafeFileAccess.open(context,after.path(),root)){
            String a=RootFileProvider.identity(Os.fstat(left.getFileDescriptor()));
            String b=RootFileProvider.identity(Os.fstat(right.getFileDescriptor()));
            try(ParcelFileDescriptor.AutoCloseInputStream leftData=new ParcelFileDescriptor.AutoCloseInputStream(
                        ParcelFileDescriptor.dup(left.getFileDescriptor()));
                ParcelFileDescriptor.AutoCloseInputStream rightData=new ParcelFileDescriptor.AutoCloseInputStream(
                        ParcelFileDescriptor.dup(right.getFileDescriptor()))){
                FileComparison.Result result=FileComparison.compare(mode,leftData.getChannel(),rightData.getChannel());
                if(!a.equals(RootFileProvider.identity(Os.fstat(left.getFileDescriptor())))
                        ||!b.equals(RootFileProvider.identity(Os.fstat(right.getFileDescriptor()))))
                    throw new IOException("One of these files changed. Refresh your results and compare again.");
                return result;
            }
        }
    }

    private static String scope(FileComparison.Mode mode){
        switch(mode){
            case APK:return "ZIP entry names, sizes, CRC32, compression and flags only. Matching metadata does not prove identical contents or signatures.";
            case DEX:return "File size, SHA-256 and declared class count. This does not compare individual classes or methods.";
            default:return "UTF-8 or BOM-marked UTF-16 text. Line endings are normalized. Code is never run.";
        }
    }

    private static void names(AppSheet sheet,LinearLayout files,FileEntry before,FileEntry after,
                              FileComparison.Result result,boolean reverse){
        files.removeAllViews();
        name(sheet,files,"A",reverse?after:before,result==null?-1:reverse?result.afterBytes:result.beforeBytes);
        name(sheet,files,"B",reverse?before:after,result==null?-1:reverse?result.beforeBytes:result.afterBytes);
    }

    private static void name(AppSheet sheet,LinearLayout all,String side,FileEntry entry,long actualSize){
        TextView label=sheet.text(side+"  "+FileComparison.safe(entry.name),14,sheet.theme.ink);
        label.setTypeface(sheet.getContext().getResources().getFont(R.font.inter_medium));
        label.setSingleLine(true);label.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        label.setTextIsSelectable(true);
        label.setPadding(0,sheet.dp(3),0,0);
        all.addView(label,new LinearLayout.LayoutParams(-1,-2));
        String suffix=actualSize<0?"":" · "+Formatter.formatShortFileSize(sheet.getContext(),actualSize);
        TextView path=sheet.text(FileComparison.safe(entry.parent)+suffix,12,sheet.theme.muted);
        path.setSingleLine(true);path.setEllipsize(TextUtils.TruncateAt.MIDDLE);path.setTextIsSelectable(true);
        path.setPadding(0,0,0,sheet.dp(4));
        all.addView(path,new LinearLayout.LayoutParams(-1,-2));
    }

    private static void render(AppSheet sheet,LinearLayout files,TextView summary,ListView changes,
                               FileEntry before,FileEntry after,FileComparison.Result result,boolean reverse){
        names(sheet,files,before,after,result,reverse);
        int a=reverse?result.afterItems:result.beforeItems,b=reverse?result.beforeItems:result.afterItems;
        int added=reverse?result.removed:result.added,removed=reverse?result.added:result.removed;
        String heading;
        switch(result.mode){
            case APK: heading="A: "+a+" entries  ·  B: "+b+" entries";break;
            case DEX: heading="A: "+a+" classes declared  ·  B: "+b+" classes declared";break;
            default: heading="A: "+a+" lines  ·  B: "+b+" lines";break;
        }
        String details;
        if(result.mode==FileComparison.Mode.DEX){
            details=(result.totalChanges()==0?"Size and SHA-256 match.":"DEX byte fingerprints differ.")
                    +"\n\nA · "+(reverse?result.afterDetail:result.beforeDetail)
                    +"\n\nB · "+(reverse?result.beforeDetail:result.afterDetail);
        }else if(result.totalChanges()==0){
            details=result.mode==FileComparison.Mode.APK?"No differences in the compared ZIP entry metadata.":
                    "No differences after normalizing line endings.";
        }else{
            details=added+" added  ·  "+removed+" removed";
            if(result.mode==FileComparison.Mode.APK)details+="  ·  "+result.changed+" changed";
        }
        if(result.totalChanges()>result.rows.size())details+="\nShowing "+result.rows.size()+" of "+result.totalChanges()+" changes.";
        summary.setText(heading+"\n"+details);
        summary.setTextIsSelectable(true);
        changes.setAdapter(result.rows.isEmpty()||result.mode==FileComparison.Mode.DEX?null:
                new ChangeAdapter(sheet,result,reverse));
    }

    private static final class ChangeAdapter extends BaseAdapter {
        final AppSheet sheet; final FileComparison.Result result; final boolean reverse;
        ChangeAdapter(AppSheet sheet,FileComparison.Result result,boolean reverse){
            this.sheet=sheet;this.result=result;this.reverse=reverse;
        }
        @Override public int getCount(){return result.rows.size();}
        @Override public FileComparison.Row getItem(int position){return result.rows.get(position);}
        @Override public long getItemId(int position){return position;}
        @Override public View getView(int position,View recycled,ViewGroup parent){
            ChangeView row=recycled instanceof ChangeView?(ChangeView)recycled:new ChangeView(sheet);
            row.bind(getItem(position),result.mode,reverse);
            return row;
        }
    }
    private static final class ChangeView extends LinearLayout {
        private final AppSheet sheet;
        private final TextView title,left,right;
        ChangeView(AppSheet sheet){
            super(sheet.getContext());this.sheet=sheet;setOrientation(VERTICAL);
            setPadding(sheet.dp(10),sheet.dp(7),sheet.dp(10),sheet.dp(8));
            title=sheet.text("",13,sheet.theme.accent);
            title.setTypeface(sheet.getContext().getResources().getFont(R.font.inter_medium));
            title.setTextIsSelectable(true);
            left=detail();right=detail();
            addView(title,new LayoutParams(-1,-2));addView(left,new LayoutParams(-1,-2));
            addView(right,new LayoutParams(-1,-2));
        }
        private TextView detail(){
            TextView detail=sheet.text("",12,sheet.theme.ink);
            detail.setTypeface(android.graphics.Typeface.MONOSPACE);detail.setTextIsSelectable(true);
            detail.setPadding(0,sheet.dp(3),0,0);
            return detail;
        }
        void bind(FileComparison.Row row,FileComparison.Mode mode,boolean reverse){
            FileComparison.Kind kind=row.kind;
            if(reverse&&kind!=FileComparison.Kind.CHANGED)
                kind=kind==FileComparison.Kind.ADDED?FileComparison.Kind.REMOVED:FileComparison.Kind.ADDED;
            String label=row.label;
            if(reverse&&mode==FileComparison.Mode.TEXT)label=(label.startsWith("A")?"B":"A")+label.substring(1);
            title.setText((kind==FileComparison.Kind.CHANGED?"Changed":
                    kind==FileComparison.Kind.ADDED?"Added":"Removed")+" · "+label);
            detail(left,"A",reverse?row.after:row.before);
            detail(right,"B",reverse?row.before:row.after);
        }
        private void detail(TextView view,String side,String value){
            view.setVisibility(value==null?View.GONE:View.VISIBLE);
            view.setText(value==null?"":side+"  "+value);
        }
    }
}
