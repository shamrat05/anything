package dev.fastsearch.android;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import dev.fastsearch.core.FileEntry;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/** Shown only after explicitly choosing Hex view for one ordinary local file. */
final class HexInspectionSheet {
    static final String LABEL="Hex view";
    private final AppSheet sheet;
    private final Activity owner;
    private final ExecutorService worker;
    private final FileEntry entry;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final EditText offset,pattern;
    private final Button go,find,previous,next;
    private final TextView total,message,dump;
    private final EdgeFastScroll.Document codeScroll;
    private final int columns;
    private Future<?> job;
    private volatile boolean closed;
    private String identity;
    private long length=-1, page, cursor;
    private long nextFind=-1;
    private byte[] lastPattern;

    HexInspectionSheet(Activity activity,FileEntry entry,ExecutorService worker,Consumer<AppSheet> onClose) {
        this.owner=activity;this.entry=entry;this.worker=worker;
        sheet=new AppSheet(activity,LABEL,true);
        columns=activity.getResources().getConfiguration().screenWidthDp>=660?16:8;
        LinearLayout content=sheet.content;
        content.setPaddingRelative(0,0,0,sheet.dp(4));
        total=sheet.text(entry.name+"  ·  Read only",13,sheet.theme.muted);
        total.setSingleLine();total.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        content.addView(total,new LinearLayout.LayoutParams(-1,sheet.dp(24)));

        LinearLayout jump=row();
        offset=input("Byte offset","Offset (decimal or 0x…)",22);
        go=button("Go",()->goTo());
        jump.addView(offset,new LinearLayout.LayoutParams(0,sheet.dp(48),1));
        jump.addView(go,new LinearLayout.LayoutParams(sheet.dp(68),sheet.dp(48)));
        content.addView(jump);
        offset.setOnEditorActionListener((v,action,event)->{
            if(action!=EditorInfo.IME_ACTION_GO)return false;
            if(go.isEnabled())goTo();return true;
        });

        LinearLayout search=row();
        pattern=input("Hex bytes to find","Hex bytes, e.g. 50 4B",3*HexInspection.MAX_PATTERN+16);
        find=button("Find",()->findNext());
        search.addView(pattern,new LinearLayout.LayoutParams(0,sheet.dp(48),1));
        search.addView(find,new LinearLayout.LayoutParams(sheet.dp(106),sheet.dp(48)));
        content.addView(search);
        pattern.setOnEditorActionListener((v,action,event)->{
            if(action!=EditorInfo.IME_ACTION_GO)return false;
            if(find.isEnabled())findNext();return true;
        });

        message=sheet.text("",12,sheet.theme.muted);
        message.setMinHeight(sheet.dp(28));message.setMaxLines(3);
        message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(message);

        codeScroll=new EdgeFastScroll.Document(activity);codeScroll.setFillViewport(true);
        HorizontalScrollView across=new HorizontalScrollView(activity);
        across.setHorizontalScrollBarEnabled(true);
        dump=sheet.text("",12,sheet.theme.ink);
        dump.setTypeface(Typeface.MONOSPACE);
        dump.setTextIsSelectable(true);FileViewerActivity.simpleTextLayout(dump);
        dump.setGravity(Gravity.TOP|Gravity.START);
        dump.setPaddingRelative(sheet.dp(8),sheet.dp(8),sheet.dp(8),sheet.dp(8));
        across.addView(dump,new HorizontalScrollView.LayoutParams(-2,-2));
        codeScroll.addView(across,new android.widget.ScrollView.LayoutParams(-1,-2));
        codeScroll.setBackground(AppSheet.shape(sheet.theme.surface,sheet.dp(12)));
        content.addView(codeScroll,new LinearLayout.LayoutParams(-1,0,1));

        previous=button("Previous page",()->load(Math.max(0,page-HexInspection.PAGE_BYTES),null));
        next=button("Next page",()->load(page+HexInspection.PAGE_BYTES,null));
        sheet.actions.addView(previous,new LinearLayout.LayoutParams(0,sheet.dp(48),1));
        sheet.actions.addView(next,new LinearLayout.LayoutParams(0,sheet.dp(48),1));
        content.setFocusableInTouchMode(true);content.requestFocus();
        if(sheet.getWindow()!=null)sheet.getWindow().setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                |android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        sheet.setOnDismissListener(ignored->{
            closed=true;
            if(job!=null){job.cancel(true);job=null;}
            ui.removeCallbacksAndMessages(null);
            onClose.accept(sheet);
        });
        update(false);
    }

    AppSheet dialog(){return sheet;}
    void show(){sheet.show();load(0,null);}

    private void goTo(){
        try{
            long requested=HexInspection.parseOffset(offset.getText().toString());
            if(length>=0&&(length==0&&requested!=0||length>0&&requested>=length))
                throw new IllegalArgumentException("Offset is outside this file.");
            load(requested,null);
        }catch(IllegalArgumentException invalid){message.setText(invalid.getMessage());}
    }

    private void findNext(){
        try{load(cursor,HexInspection.parsePattern(pattern.getText().toString()));}
        catch(IllegalArgumentException invalid){message.setText(invalid.getMessage());}
    }

    private void load(long requested,byte[] needle){
        if(job!=null||closed)return;
        InputMethodManager keyboard=owner.getSystemService(InputMethodManager.class);
        if(keyboard!=null)keyboard.hideSoftInputFromWindow(offset.getWindowToken(),0);
        long start=needle!=null&&Arrays.equals(needle,lastPattern)&&nextFind>=0?nextFind:requested;
        String expected=identity;
        update(true);message.setText(needle==null?"Reading bytes…":"Finding exact bytes…");
        try{
            job=worker.submit(()->{
                Outcome outcome;
                try{outcome=read(requested,start,needle,expected);}
                catch(Exception failure){outcome=new Outcome(failure instanceof IOException
                        ?failure.getMessage():"The file is no longer readable with ordinary storage access.");}
                Outcome result=outcome;
                if(!closed&&!Thread.currentThread().isInterrupted())ui.post(()->apply(result,needle));
            });
        }catch(RejectedExecutionException unavailable){
            update(false);message.setText("The viewer is busy. Try again.");
        }
    }

    private Outcome read(long requested,long start,byte[] needle,String expected)throws Exception {
        // This deliberately does not use the root helper, content providers or sniffing.
        ParcelFileDescriptor descriptor;
        try{descriptor=SafeFileAccess.openLocal(entry.path());}
        catch(Exception unavailable){throw new IOException("Hex view requires an ordinary readable local file.");}
        try(ParcelFileDescriptor file=descriptor){
            StructStat before=Os.fstat(file.getFileDescriptor());
            String stamp=RootFileProvider.identity(before);
            if(!OsConstants.S_ISREG(before.st_mode)||before.st_size<0)throw new IOException("Hex view requires a regular file.");
            if(expected!=null&&!expected.equals(stamp))throw changed();
            HexInspection.Source source=(position,bytes,count)->{
                int at=0;
                try{
                    while(at<count){
                        if(closed||Thread.currentThread().isInterrupted())
                            throw new java.io.InterruptedIOException("Byte inspection stopped.");
                        int n=Os.pread(file.getFileDescriptor(),bytes,at,count-at,position+at);
                        if(n<=0)break;
                        at+=n;
                    }
                }catch(android.system.ErrnoException unavailable){throw new IOException("File could not be read.");}
                return at;
            };
            long size=before.st_size,shownPage=HexInspection.pageStart(requested);
            long focus=requested;
            HexInspection.Match match=null;
            if(needle!=null){
                match=HexInspection.find(source,size,Math.min(start,size),needle,()->closed);
                if(match.offset>=0){focus=match.offset;shownPage=HexInspection.pageStart(focus);}
            }
            byte[] bytes=HexInspection.page(source,size,shownPage,()->closed);
            if(!stamp.equals(RootFileProvider.identity(Os.fstat(file.getFileDescriptor()))))throw changed();
            return new Outcome(stamp,size,shownPage,focus,bytes,match);
        }
    }

    private static IOException changed(){return new IOException("The file changed. Close and reopen Hex view.");}

    private void apply(Outcome result,byte[] needle){
        if(closed||!sheet.isShowing())return;
        job=null;
        if(result.error!=null){update(false);message.setText(result.error);return;}
        identity=result.identity;length=result.length;page=result.page;
        total.setText(entry.name+"  ·  "+java.text.NumberFormat.getIntegerInstance().format(length)+" bytes  ·  Read only");
        String range=length==0?"No bytes in this file.":HexInspection.offset(page)+" to "
                +HexInspection.offset(page+result.bytes.length-1)+"  ·  Hex and ASCII";
        String data=HexInspection.render(result.bytes,page,columns);
        if(length==0)dump.setText("Empty file");
        else{
            android.text.SpannableString styled=new android.text.SpannableString(data);
            if(result.match==null||result.match.offset>=0){
                for(int i=0,n=result.match==null?1:needle.length;i<n;i++){
                    long relative=result.focus-page+i;
                    if(relative<0||relative>=result.bytes.length)break;
                    int row=(int)relative/columns,col=(int)relative%columns,index=0;
                    for(int line=0;line<row;line++)index=data.indexOf('\n',index)+1;
                    int width=page+result.bytes.length-1>0xffffffffL?16:8;
                    int pos=index+width+2+col*3;
                    styled.setSpan(new android.text.style.BackgroundColorSpan(sheet.theme.selected),
                            pos,pos+2,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            dump.setText(styled);
        }
        if(result.match==null){lastPattern=null;nextFind=-1;message.setText(range);}
        else{
            lastPattern=needle;
            nextFind=result.match.scannedUntil;
            if(result.match.offset>=0)message.setText("Match at "+HexInspection.offset(result.match.offset)+"  ·  "+range);
            else if(nextFind<length)message.setText("No match in this scan. Continue from "+HexInspection.offset(nextFind)+".");
            else{message.setText("No further match before the end of the file.");nextFind=-1;}
        }
        if(result.match==null||result.match.offset>=0){
            cursor=result.focus;
            offset.setText(HexInspection.offset(cursor));
            int row=(int)Math.max(0,result.focus-page)/columns;
            codeScroll.post(()->{if(!closed)codeScroll.scrollTo(0,row*dump.getLineHeight());});
        }
        update(false);
    }

    private void update(boolean busy){
        go.setEnabled(!busy&&length>=0);find.setEnabled(!busy&&length>0);
        previous.setEnabled(!busy&&page>0);
        next.setEnabled(!busy&&length>=0&&length-page>HexInspection.PAGE_BYTES);
        find.setText(nextFind>=0&&(length-nextFind)>0?"Find next":"Find");
    }

    private LinearLayout row(){
        LinearLayout row=new LinearLayout(owner);row.setGravity(Gravity.CENTER_VERTICAL);return row;
    }
    private EditText input(String description,String hint,int cap){
        EditText field=new EditText(owner);field.setContentDescription(description);field.setHint(hint);
        field.setTextSize(14);field.setSingleLine(true);field.setSelectAllOnFocus(true);
        field.setTextColor(sheet.theme.ink);field.setHintTextColor(sheet.theme.muted);
        field.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setImeOptions(EditorInfo.IME_ACTION_GO);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(cap)});
        return field;
    }
    private Button button(String label,Runnable action){
        Button button=new Button(owner);button.setAllCaps(false);button.setText(label);
        button.setMinWidth(0);button.setMinimumWidth(0);button.setPaddingRelative(sheet.dp(5),0,sheet.dp(5),0);
        button.setTextColor(android.content.res.ColorStateList.valueOf(sheet.theme.accent));
        button.setOnClickListener(v->action.run());return button;
    }

    private static final class Outcome {
        final String identity,error;
        final long length,page,focus;
        final byte[] bytes;
        final HexInspection.Match match;
        Outcome(String identity,long length,long page,long focus,byte[] bytes,HexInspection.Match match){
            this.identity=identity;this.length=length;this.page=page;this.focus=focus;this.bytes=bytes;this.match=match;this.error=null;
        }
        Outcome(String error){this.identity=null;this.length=this.page=this.focus=0;this.bytes=null;this.match=null;
            this.error=error==null?"The file could not be read.":error;}
    }
}
