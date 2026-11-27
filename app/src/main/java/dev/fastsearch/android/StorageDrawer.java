package dev.fastsearch.android;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

/** Compact start-edge navigation; storage values are provided asynchronously by the caller. */
final class StorageDrawer extends Dialog {
    private final Activity owner;
    private final UiTheme theme;
    private final LinearLayout items;
    private float touchX,touchY;
    private boolean swipe;
    private final java.util.ArrayList<View> pickable=new java.util.ArrayList<>();
    private final int[] hitLocation=new int[2];
    private final android.graphics.Rect hitBounds=new android.graphics.Rect();
    private final java.util.Map<String,View> actions=new java.util.LinkedHashMap<>();

    StorageDrawer(Activity activity){
        super(activity);owner=activity;theme=UiTheme.resolve(activity);requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel=new LinearLayout(activity);panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(theme.chrome);panel.setPadding(dp(10),dp(4),dp(10),0);
        LinearLayout heading=new LinearLayout(activity);heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=label(activity.getString(R.string.app_name),17,theme.ink);
        title.setTypeface(activity.getResources().getFont(R.font.inter_semibold));
        title.setPaddingRelative(dp(8),0,0,0);title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if(Build.VERSION.SDK_INT>=28)title.setAccessibilityHeading(true);
        heading.addView(title,new LinearLayout.LayoutParams(0,dp(48),1));
        ImageButton close=new ImageButton(activity);close.setImageResource(R.drawable.ic_close);
        close.setImageTintList(ColorStateList.valueOf(theme.muted));close.setContentDescription("Close sidebar");
        close.setPadding(dp(13),dp(13),dp(13),dp(13));close.setBackground(ripple(Color.TRANSPARENT));
        close.setOnClickListener(view->dismiss());heading.addView(close,new LinearLayout.LayoutParams(dp(48),dp(48)));
        panel.addView(heading,new LinearLayout.LayoutParams(-1,-2));
        ScrollView scroll=new MenuDragScroll(activity,this::actionAt);scroll.setVerticalScrollBarEnabled(false);
        panel.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        items=new LinearLayout(activity);items.setOrientation(LinearLayout.VERTICAL);items.setPadding(0,0,0,dp(14));
        scroll.addView(items,new ScrollView.LayoutParams(-1,-2));
        setContentView(panel);setCanceledOnTouchOutside(true);
        Window window=getWindow();
        if(window!=null){
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(.34f);window.setGravity(Gravity.START|Gravity.TOP);
            boolean rtl=activity.getResources().getConfiguration().getLayoutDirection()==View.LAYOUT_DIRECTION_RTL;
            window.setWindowAnimations(rtl?R.style.StorageDrawerMotionRtl:R.style.StorageDrawerMotion);
        }
    }

    @Override public void show(){
        super.show();
        Window window=getWindow();
        int width=owner.getResources().getDisplayMetrics().widthPixels;
        if(window!=null)window.setLayout(Math.max(1,Math.min(dp(256),Math.round(width*.66f))),-1);
    }
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event){
        switch(event.getActionMasked()){
            case android.view.MotionEvent.ACTION_DOWN:
                touchX=event.getX();touchY=event.getY();swipe=true;break;
            case android.view.MotionEvent.ACTION_MOVE:
                if(swipe){
                    boolean rtl=owner.getResources().getConfiguration().getLayoutDirection()==View.LAYOUT_DIRECTION_RTL;
                    float outward=(event.getX()-touchX)*(rtl?1:-1),dy=Math.abs(event.getY()-touchY);
                    if(dy>Math.max(dp(28),outward))swipe=false;
                    if(swipe&&outward>=dp(64)&&outward>dy*1.6f){
                        android.view.MotionEvent cancel=android.view.MotionEvent.obtain(event);
                        cancel.setAction(android.view.MotionEvent.ACTION_CANCEL);
                        try{super.dispatchTouchEvent(cancel);}finally{cancel.recycle();}
                        swipe=false;dismiss();return true;
                    }
                }
                break;
            case android.view.MotionEvent.ACTION_CANCEL:case android.view.MotionEvent.ACTION_UP:
            case android.view.MotionEvent.ACTION_POINTER_DOWN:
                swipe=false;break;
            default:break;
        }
        return super.dispatchTouchEvent(event);
    }
    int dp(float value){return Math.round(owner.getResources().getDisplayMetrics().density*value);}
    private TextView label(String text,float size,int color){
        TextView label=new TextView(owner);label.setText(text);label.setTextSize(size);label.setTextColor(color);
        label.setGravity(Gravity.CENTER_VERTICAL);label.setIncludeFontPadding(false);
        label.setTypeface(owner.getResources().getFont(R.font.inter));return label;
    }
    private RippleDrawable ripple(int color){
        return new RippleDrawable(ColorStateList.valueOf(theme.line),AppSheet.shape(color,dp(10)),AppSheet.shape(Color.WHITE,dp(10)));
    }
    void section(String text){
        TextView header=label(text,11,theme.muted);header.setTypeface(owner.getResources().getFont(R.font.inter_semibold));
        header.setPaddingRelative(dp(9),dp(12),dp(6),0);
        if(Build.VERSION.SDK_INT>=28)header.setAccessibilityHeading(true);
        items.addView(header,new LinearLayout.LayoutParams(-1,dp(34)));
    }
    private TextView row(String text,int icon,boolean selected,Runnable action){
        TextView row=label(text,14,theme.ink);
        row.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);row.setPaddingRelative(dp(10),dp(3),dp(8),dp(3));
        row.setSelected(selected);row.setSingleLine(true);row.setMinHeight(dp(48));
        row.setEllipsize(android.text.TextUtils.TruncateAt.END);
        boolean folder=icon==R.drawable.ic_folder;
        android.graphics.drawable.Drawable mark=owner.getDrawable(folder?UiTheme.fileIcon(owner,2):icon).mutate();
        if(!folder)mark.setTint(selected?theme.accent:theme.muted);
        mark.setBounds(0,0,dp(18),dp(18));
        row.setCompoundDrawablesRelative(mark,null,null,null);row.setCompoundDrawablePadding(dp(9));
        row.setBackground(ripple(selected?theme.selected:Color.TRANSPARENT));
        row.setFocusable(true);row.setOnClickListener(view->{dismiss();action.run();});pickable.add(row);
        return row;
    }
    void action(String text,int icon,boolean selected,Runnable callback){
        View entry=row(text,icon,selected,callback);
        actions.put(text,entry);items.addView(entry,new LinearLayout.LayoutParams(-1,-2));
    }
    private View actionAt(float x,float y){
        for(View entry:pickable)if(entry.isEnabled()&&FileHoldActions.inside(entry,x,y,hitLocation,hitBounds))return entry;
        return null;
    }
    View findAction(String text){return actions.get(text);}
    Volume volume(String text,boolean selected,Runnable callback){
        LinearLayout volume=new LinearLayout(owner);volume.setOrientation(LinearLayout.VERTICAL);
        volume.setBackground(AppSheet.shape(theme.surface,dp(11)));
        volume.addView(row(text,R.drawable.ic_locations,selected,callback),new LinearLayout.LayoutParams(-1,-2));
        TextView detail=label("Checking space…",11,theme.muted);
        detail.setPaddingRelative(dp(10),0,dp(10),0);
        volume.addView(detail,new LinearLayout.LayoutParams(-1,dp(23)));
        ProgressBar bar=new ProgressBar(owner,null,android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(false);bar.setMax(100);bar.setVisibility(View.INVISIBLE);
        bar.setProgressTintList(ColorStateList.valueOf(theme.accent));bar.setProgressBackgroundTintList(ColorStateList.valueOf(theme.line));
        bar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams track=new LinearLayout.LayoutParams(-1,dp(3));track.setMargins(dp(10),dp(2),dp(10),dp(9));
        volume.addView(bar,track);LinearLayout.LayoutParams panel=new LinearLayout.LayoutParams(-1,-2);panel.bottomMargin=dp(5);
        items.addView(volume,panel);return new Volume(detail,bar);
    }
    static final class Volume{
        private final TextView detail;
        private final ProgressBar bar;
        Volume(TextView detail,ProgressBar bar){this.detail=detail;this.bar=bar;}
        void update(String text,int used){
            detail.setText(text);
            bar.setVisibility(used<0?View.INVISIBLE:View.VISIBLE);
            if(used>=0)bar.setProgress(Math.max(0,Math.min(100,used)));
        }
    }
}
