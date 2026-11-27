package dev.fastsearch.android;

import android.content.Context;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import java.util.function.BooleanSupplier;

/** Claims only a clearly horizontal single-finger gesture inside the preview body. */
final class QuickPreviewSwipe extends FrameLayout {
    private static final int PREVIOUS=AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
    private static final int NEXT=AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
    private final Runnable previous,next;
    private final BooleanSupplier allowSwipe,allowPaging;
    private final float slop,threshold;
    private float startX,startY;
    private boolean armed,dragging;

    QuickPreviewSwipe(Context context,Runnable previous,Runnable next,
                      BooleanSupplier allowSwipe,BooleanSupplier allowPaging){
        super(context);
        this.previous=previous;this.next=next;this.allowSwipe=allowSwipe;this.allowPaging=allowPaging;
        slop=ViewConfiguration.get(context).getScaledTouchSlop();
        threshold=Math.max(slop*3,56*getResources().getDisplayMetrics().density);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setClickable(previous!=null||next!=null);
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event){
        switch(event.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                startX=event.getX();startY=event.getY();dragging=false;
                armed=(previous!=null||next!=null)&&allowSwipe.getAsBoolean()&&allowPaging.getAsBoolean();
                break;
            case MotionEvent.ACTION_MOVE:
                if(!armed||!allowPaging.getAsBoolean()||!allowSwipe.getAsBoolean())break;
                float dx=event.getX()-startX,dy=event.getY()-startY;
                if(Math.abs(dy)>slop&&Math.abs(dy)>=Math.abs(dx)){armed=false;break;}
                if(Math.abs(dx)>slop*2&&Math.abs(dx)>Math.abs(dy)*1.5f){
                    if((dx<0?next:previous)==null){armed=false;break;}
                    dragging=true;return true;
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN: case MotionEvent.ACTION_CANCEL: case MotionEvent.ACTION_UP:
                armed=false;break;
        }
        return false;
    }

    @Override public boolean onTouchEvent(MotionEvent event){
        if(dragging){
            switch(event.getActionMasked()){
                case MotionEvent.ACTION_POINTER_DOWN: case MotionEvent.ACTION_CANCEL:
                    armed=false;dragging=false;return true;
                case MotionEvent.ACTION_UP:
                    float dx=event.getX()-startX,dy=event.getY()-startY;
                    Runnable go=dx<0?next:previous;
                    if(armed&&allowPaging.getAsBoolean()&&go!=null&&Math.abs(dx)>=threshold
                            &&Math.abs(dx)>Math.abs(dy)*1.5f)post(go);
                    armed=false;dragging=false;return true;
            }
            return true;
        }
        return super.onTouchEvent(event);
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){
        super.onInitializeAccessibilityNodeInfo(info);
        if(previous!=null)info.addAction(new AccessibilityNodeInfo.AccessibilityAction(PREVIOUS,"Previous file"));
        if(next!=null)info.addAction(new AccessibilityNodeInfo.AccessibilityAction(NEXT,"Next file"));
    }

    @Override public boolean performAccessibilityAction(int action,Bundle args){
        Runnable go=action==PREVIOUS?previous:action==NEXT?next:null;
        if(go!=null&&allowPaging.getAsBoolean()){post(go);return true;}
        return super.performAccessibilityAction(action,args);
    }
}
