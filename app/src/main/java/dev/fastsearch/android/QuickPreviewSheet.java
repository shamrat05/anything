package dev.fastsearch.android;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import android.widget.FrameLayout;

/** A responsive bottom sheet. Preview and overflow share one window and one Back stack. */
final class QuickPreviewSheet extends Dialog {
    private QuickPreviewCard preview;
    private final FrameLayout container;
    final int width,height;
    private android.window.OnBackInvokedCallback backCallback;

    QuickPreviewSheet(View anchor,Rect visible) {
        super(anchor.getContext());
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        int edge=dp(8);
        width=Math.max(1,Math.min(dp(580),visible.width()-edge*2));
        int heightAvailable=Math.max(1,visible.height()-edge*2);
        height=Math.max(1,Math.min(Math.min(dp(660),heightAvailable),
                Math.max(dp(252),Math.round(heightAvailable*.76f))));
        container=new FrameLayout(getContext());
        setContentView(container);
        setCanceledOnTouchOutside(true);
        Window window=getWindow();
        if(window!=null){
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND
                    |WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
            window.setDimAmount(.24f);
            window.setGravity(Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);
            WindowManager.LayoutParams params=window.getAttributes();
            params.y=edge;
            params.width=width;
            params.height=height;
            window.setAttributes(params);
        }
    }

    void showPage(QuickPreviewCard next){
        container.removeAllViews();
        preview=next;
        container.addView(next,new FrameLayout.LayoutParams(-1,-1));
    }

    @Override public void show(){
        super.show();
        if(getWindow()!=null)getWindow().setLayout(width,height);
    }

    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed(){if(preview==null||!preview.returnToPreview())super.onBackPressed();}
    @Override protected void onStart(){
        super.onStart();
        if(Build.VERSION.SDK_INT>=33){
            backCallback=this::onBackPressed;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,backCallback);
        }
    }
    @Override protected void onStop(){
        if(Build.VERSION.SDK_INT>=33&&backCallback!=null){
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
            backCallback=null;
        }
        super.onStop();
    }
    private int dp(int value){return Math.round(value*getContext().getResources().getDisplayMetrics().density);}
}
