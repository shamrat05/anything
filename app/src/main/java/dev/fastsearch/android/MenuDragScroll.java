package dev.fastsearch.android;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ScrollView;

/** Native scrolling plus release-to-pick for the file action card and navigation drawer. */
final class MenuDragScroll extends ScrollView {
    private final MenuPressGesture gesture;
    private final int[] location=new int[2];
    interface Hit { View at(float x,float y); }
    MenuDragScroll(Context context, Hit resolver) {
        super(context);
        gesture=new MenuPressGesture(context,new MenuPressGesture.Host() {
            public boolean inside(MotionEvent e) {return e.getX()>=0&&e.getX()<getWidth()&&e.getY()>=0&&e.getY()<getHeight();}
            public boolean scrollable(){return canScrollVertically(-1)||canScrollVertically(1);}
            public boolean nativeTouch(MotionEvent e){return MenuDragScroll.super.dispatchTouchEvent(e);}
            public View hit(MotionEvent e){
                if(!inside(e))return null;
                getLocationOnScreen(location);
                return resolver.at(location[0]+e.getX(),location[1]+e.getY());
            }
            public void pick(View target){target.performClick();}
        });
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event){return gesture.touch(event);}
    @Override public void onWindowFocusChanged(boolean focused){
        if(!focused&&gesture!=null)gesture.clear();
        super.onWindowFocusChanged(focused);
    }
    @Override protected void onDetachedFromWindow(){
        if(gesture!=null)gesture.clear();
        super.onDetachedFromWindow();
    }
}
