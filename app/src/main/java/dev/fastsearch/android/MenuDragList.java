package dev.fastsearch.android;

import android.content.Context;
import android.view.MotionEvent;
import android.widget.ListView;

/** Preserves ListView keyboard and accessibility activation. */
final class MenuDragList extends ListView {
    private final MenuPressGesture gesture;
    MenuDragList(Context context) {
        super(context);
        gesture=MenuPressGesture.forList(this,super::onTouchEvent);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {return gesture.touch(event);}
    void clearPick(){if(gesture!=null)gesture.clear();}
    @Override public void onWindowFocusChanged(boolean focused) {
        if (!focused) clearPick();
        super.onWindowFocusChanged(focused);
    }
}
