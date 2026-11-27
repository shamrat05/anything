package dev.fastsearch.android;

import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AbsListView;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import dev.fastsearch.core.FileEntry;
import java.util.concurrent.ExecutorService;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Native long-click starts a passive window; a fresh touch after release can pick an action. */
final class FileHoldActions {
    static final class Target {
        final View anchor;
        final FileEntry entry;
        final boolean pane, transfer;
        final Menu menu;
        final Consumer<String> action;
        String pasteDestination;
        Runnable confirmPaste;
        int orderSize;
        java.util.function.IntFunction<FileEntry> inOrder;
        java.util.function.Function<FileEntry,Target> forEntry;
        Target withPreviewOrder(int count,java.util.function.IntFunction<FileEntry> entries,
                                java.util.function.Function<FileEntry,Target> factory){
            orderSize=count;inOrder=entries;forEntry=factory;return this;
        }
        Target withQuickPaste(String destination,Runnable confirmation) {
            if(destination!=null&&destination.startsWith("/")&&confirmation!=null){
                pasteDestination=destination;confirmPaste=confirmation;
            }
            return this;
        }
        Target(View anchor, FileEntry entry, boolean pane, boolean transfer, Consumer<String> action) {
            this(anchor,entry,pane,transfer,null,action);
        }
        Target(View anchor, FileEntry entry, boolean pane, boolean transfer, Menu menu, Consumer<String> action) {
            this.anchor=anchor;this.entry=entry;this.pane=pane;this.transfer=transfer;this.menu=menu;this.action=action;
        }
    }
    interface Resolve {Target at(float x,float y);}
    private final UiTheme theme;
    private final Resolve resolver;
    static final class QuickMode {
        final BooleanSupplier enabled,root;
        final ExecutorService worker;
        QuickMode(BooleanSupplier enabled,BooleanSupplier root,ExecutorService worker) {
            this.enabled=enabled;this.root=root;this.worker=worker;
        }
    }
    private final QuickMode quickMode;
    private FolderSizes folderSizes;
    private Runnable cancelFolderSize;
    void setFolderSizes(FolderSizes source){folderSizes=source;}
    private PopupWindow popup;
    private ScrollView frame;
    private QuickPreviewCard quickCard;
    private QuickPreviewSheet quickSheet;
    private Target previewOrder;
    private int previewPosition=-1;
    private FileActionCard card;
    private Target target;
    private Menu model;
    private GradientDrawable marker;
    private boolean finger, tracking, drain, sourceCancelled;
    private float y;
    private long shownAt;
    private final int[] screen=new int[2];
    private final Rect visible=new Rect();

    FileHoldActions(UiTheme theme,Resolve resolver){this(theme,resolver,null);}

    FileHoldActions(UiTheme theme,Resolve resolver,QuickMode quickMode){
        this.theme=theme;this.resolver=resolver;this.quickMode=quickMode;
    }

    private boolean wantsQuick(Target target) {
        return quickMode!=null&&target!=null&&target.menu==null&&!target.entry.directory
                &&quickMode.enabled.getAsBoolean();
    }

    boolean dispatch(MotionEvent event,Consumer<MotionEvent> cancelSource){
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN){
            finger=event.getPointerCount()==1;tracking=false;drain=false;sourceCancelled=false;
            y=event.getRawY();
            return false;
        }
        if(!tracking&&!drain){
            if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL||action==MotionEvent.ACTION_POINTER_DOWN)
                finger=false;
            else if(action==MotionEvent.ACTION_MOVE)y=event.getRawY();
            return false;
        }
        if(!sourceCancelled){
            sourceCancelled=true;
            MotionEvent cancel=MotionEvent.obtain(event);
            try{cancel.setAction(MotionEvent.ACTION_CANCEL);cancelSource.accept(cancel);}finally{cancel.recycle();}
        }
        if(tracking&&action==MotionEvent.ACTION_MOVE&&event.getPointerCount()==1){
            retarget(event.getRawX(),event.getRawY());return true;
        }
        if(tracking&&action==MotionEvent.ACTION_UP&&event.getPointerCount()==1){
            retarget(event.getRawX(),event.getRawY());finger=tracking=false;
            if(popup!=null&&target!=null){
                shownAt=event.getEventTime();
                popup.setOutsideTouchable(true);
                popup.setTouchable(true);popup.setFocusable(true);
                if(wantsQuick(target)) showQuick();
                else {card.bind(target.entry,model,false);popup.update();measureFolder();}
            }
            return true;
        }
        if(action==MotionEvent.ACTION_CANCEL||action==MotionEvent.ACTION_POINTER_DOWN||action==MotionEvent.ACTION_POINTER_UP){
            boolean more=action!=MotionEvent.ACTION_CANCEL;
            stop();drain=more;return true;
        }
        if(action==MotionEvent.ACTION_UP)drain=false;
        return true;
    }

    void begin(Target next){
        if(!next.anchor.isAttachedToWindow())return;
        closePopup();
        tracking=finger;drain=false;sourceCancelled=false;
        shownAt=tracking?Long.MAX_VALUE:0;
        View anchor=next.anchor;
        anchor.getWindowVisibleDisplayFrame(visible);
        if(visible.isEmpty()){tracking=false;return;}
        if(!tracking&&wantsQuick(next)) {
            card=new FileActionCard(anchor.getContext(),theme,this::activate,this::stop);
            bind(next);showQuick();return;
        }
        int edge=dp(anchor,8),width=Math.min(dp(anchor,340),visible.width()-2*edge);
        boolean viewer=next.menu!=null;
        int maxHeight=Math.min(dp(anchor,viewer?548:460),Math.round(visible.height()*(viewer?.72f:.57f)));
        if(width<=0||maxHeight<=0){tracking=false;return;}
        card=new FileActionCard(anchor.getContext(),theme,this::activate,this::stop);
        frame=new MenuDragScroll(anchor.getContext(),(x,y)->quickCard==null&&card!=null?card.actionAt(x,y):null);
        frame.setBackground(AppSheet.shape(theme.chrome,dp(anchor,20)));
        frame.setClipToOutline(true);
        frame.setFillViewport(false);
        frame.addView(card,new ScrollView.LayoutParams(-1,-2));
        bind(next);
        frame.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(maxHeight,View.MeasureSpec.AT_MOST));
        int height=Math.max(1,frame.getMeasuredHeight());
        int x=visible.centerX()-width/2;
        boolean pinBottom=tracking&&y<visible.centerY();
        int top=pinBottom?visible.bottom-height-edge:visible.top+edge;
        if(!tracking){anchor.getLocationOnScreen(screen);
            top=Math.max(visible.top+edge,Math.min(screen[1]+anchor.getHeight(),visible.bottom-height-edge));
            if(viewer)x=Math.max(visible.left+edge,
                    Math.min(screen[0]+anchor.getWidth()-width,visible.right-width-edge));
        }
        PopupWindow panel=new PopupWindow(frame,width,height,!tracking);
        popup=panel;
        panel.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        panel.setElevation(dp(anchor,14));
        panel.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        panel.setClippingEnabled(true);
        panel.setTouchable(!tracking);
        panel.setOutsideTouchable(!tracking);
        panel.setOnDismissListener(()->{
            if(popup!=panel)return;
            stopFolderSize();disposeQuick();popup=null;frame=null;card=null;model=null;clearMarker();target=null;
            if(tracking){tracking=false;drain=finger;finger=false;}
        });
        try{
            panel.showAtLocation(anchor,Gravity.TOP|Gravity.LEFT,x,top);
            if(!tracking)measureFolder();
        }catch(android.view.WindowManager.BadTokenException unavailable){stop();}
    }

    private void measureFolder() {
        stopFolderSize();
        if(folderSizes==null||target==null||card==null||!target.entry.directory||target.menu!=null)return;
        FileEntry entry=target.entry;
        FileActionCard active=card;
        cancelFolderSize=folderSizes.observe(entry.path(),false,
                result->{if(card==active&&target!=null&&target.entry==entry)active.setFolderSize(entry,result.label(active.getContext()));});
    }
    private void stopFolderSize(){
        if(cancelFolderSize!=null){cancelFolderSize.run();cancelFolderSize=null;}
    }

    private void showQuick() {
        if(target==null||tracking||quickSheet!=null)return;
        View anchor=target.anchor;
        anchor.getWindowVisibleDisplayFrame(visible);
        if(visible.isEmpty()){stop();return;}
        if(popup!=null){
            PopupWindow old=popup;
            popup=null; // The passive window no longer owns the highlighted target.
            old.dismiss();
        }
        frame=null;
        card.bind(target.entry,model,false);
        card.hideStandaloneClose();
        // Release the standard card from its passive ScrollView before adding it to the new sheet.
        if(card.getParent() instanceof android.view.ViewGroup)
            ((android.view.ViewGroup)card.getParent()).removeView(card);
        previewOrder=target;
        if(previewOrder.inOrder!=null)for(int i=0;i<previewOrder.orderSize;i++){
            if(previewOrder.inOrder.apply(i).pathEquals(target.entry.path())){previewPosition=i;break;}
        }
        quickSheet=new QuickPreviewSheet(anchor,visible);
        QuickPreviewSheet panel=quickSheet;
        installQuickPage(panel);
        panel.setOnDismissListener(ignored->{
            if(quickSheet==panel)stop();
        });
        try{
            panel.show();
            quickCard.start();
        }catch(android.view.WindowManager.BadTokenException unavailable){stop();}
    }

    private void installQuickPage(QuickPreviewSheet panel){
        Target current=target;
        int previous=adjacent(-1),next=adjacent(1),origin=previewPosition;
        quickCard=new QuickPreviewCard(panel.getContext(),theme,current.entry,quickMode.root.getAsBoolean(),
                quickMode.worker,model,this::activate,card,current.pasteDestination,
                current.confirmPaste==null?null:()->{stop();current.confirmPaste.run();},panel.width,panel.height,
                this::stop,previous<0?null:()->stepQuick(panel,origin,previous),next<0?null:()->stepQuick(panel,origin,next));
        panel.showPage(quickCard);
    }

    private int adjacent(int direction){
        if(previewOrder==null||previewPosition<0)return -1;
        for(int i=previewPosition+direction;i>=0&&i<previewOrder.orderSize;i+=direction){
            FileEntry entry=previewOrder.inOrder.apply(i);
            if(!entry.directory&&!entry.pathEquals(target.entry.path()))return i;
        }
        return -1;
    }

    private void stepQuick(QuickPreviewSheet panel,int origin,int position){
        if(quickSheet!=panel||quickCard==null||quickCard.hasOverflow()||!panel.isShowing()
                ||previewOrder==null||origin!=previewPosition||position==previewPosition)return;
        Target next=previewOrder.forEntry.apply(previewOrder.inOrder.apply(position));
        if(!wantsQuick(next))return;
        quickCard.close();clearMarker();
        previewPosition=position;
        // The source row might no longer be visible. Rebuild actions for the new file,
        // but never reapply the old row's highlight to a different file.
        target=next;
        model=FileItemMenu.model(next.anchor,theme,next.entry,next.pane,next.transfer);
        card=new FileActionCard(panel.getContext(),theme,this::activate,this::stop);
        card.bind(next.entry,model,false);card.hideStandaloneClose();
        installQuickPage(panel);
        quickCard.start();
    }

    private void disposeQuick() {
        QuickPreviewSheet panel=quickSheet;quickSheet=null;previewOrder=null;previewPosition=-1;
        QuickPreviewCard preview=quickCard;quickCard=null;
        if(preview!=null)preview.close();
        if(panel!=null)panel.dismiss();
    }

    private void retarget(float x,float y){
        Target next=resolver.at(x,y);
        if(next!=null&&(target==null||target.anchor!=next.anchor||!next.entry.pathEquals(target.entry.path())))bind(next);
    }

    private void bind(Target next){
        clearMarker();
        target=next;
        model=next.menu==null?FileItemMenu.model(next.anchor,theme,next.entry,next.pane,next.transfer):next.menu;
        card.bind(next.entry,model,tracking);
        if(next.menu!=null)return;
        marker=new GradientDrawable();
        marker.setCornerRadius(dp(next.anchor,9));
        marker.setColor((theme.accent&0x00ffffff)|0x28000000);
        marker.setStroke(dp(next.anchor,2),theme.accent);
        marker.setBounds(1,1,Math.max(1,next.anchor.getWidth()-1),Math.max(1,next.anchor.getHeight()-1));
        next.anchor.getOverlay().add(marker);
    }

    private void activate(String name){
        if(tracking||target==null||!isOpen()||android.os.SystemClock.uptimeMillis()<=shownAt)return;
        for(int i=0;i<model.size();i++){
            android.view.MenuItem item=model.getItem(i);
            if(item.isEnabled()&&item.isVisible()&&name.contentEquals(item.getTitle())){
                Target chosen=target;stop();chosen.action.accept(name);return;
            }
        }
    }

    boolean isOpen(){return popup!=null&&popup.isShowing()||quickSheet!=null&&quickSheet.isShowing();}
    String targetPath(){return target==null?null:target.entry.path();}
    boolean isTracking(){return tracking;}

    void stop(){tracking=finger=drain=sourceCancelled=false;closePopup();}
    private void closePopup(){stopFolderSize();disposeQuick();if(popup!=null)popup.dismiss();clearMarker();target=null;model=null;card=null;popup=null;frame=null;}
    private void clearMarker(){if(marker!=null&&target!=null)target.anchor.getOverlay().remove(marker);marker=null;}
    private static int dp(View view,int value){return Math.round(value*view.getResources().getDisplayMetrics().density);}

    static boolean inside(View view,float x,float y,int[] screen,Rect local){
        if(!view.isShown()||!view.getLocalVisibleRect(local))return false;
        view.getLocationOnScreen(screen);
        return local.contains((int)(x-screen[0]),(int)(y-screen[1]));
    }
    static int position(AbsListView list,float x,float y,int[] screen,Rect local){
        if(!inside(list,x,y,screen,local))return AbsListView.INVALID_POSITION;
        return list.pointToPosition((int)(x-screen[0]),(int)(y-screen[1]));
    }
}
