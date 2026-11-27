package dev.fastsearch.android;

import android.content.Context;
import android.view.MotionEvent;
import android.widget.GridView;

final class MenuDragGrid extends GridView {
    private final MenuPressGesture gesture;
    MenuDragGrid(Context context,int columns) {
        super(context);
        setNumColumns(columns);
        setStretchMode(STRETCH_COLUMN_WIDTH);
        gesture=MenuPressGesture.forList(this,super::onTouchEvent);
    }
    @Override public boolean onTouchEvent(MotionEvent event){return gesture.touch(event);}
    void clearPick(){if(gesture!=null)gesture.clear();}
    @Override public void onWindowFocusChanged(boolean focused){
        if(!focused)clearPick();
        super.onWindowFocusChanged(focused);
    }
}
