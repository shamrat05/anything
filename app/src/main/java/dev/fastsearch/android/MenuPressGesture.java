package dev.fastsearch.android;

import android.content.Context;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.AbsListView;
import android.widget.AdapterView;
import java.util.function.Function;
import android.view.accessibility.AccessibilityManager;

/** The same single-finger pick behavior for both list menus and two-column action cards. */
final class MenuPressGesture {
    interface Host {
        View hit(MotionEvent event);
        boolean inside(MotionEvent event);
        boolean scrollable();
        boolean nativeTouch(MotionEvent event);
        void pick(View target);
    }

    private static final int IDLE=0, WAIT=1, TRACK=2, SCROLL=3, CANCELLED=4;
    private static final long PICK_HOLD_MS=170;
    private final int slop;
    private final AccessibilityManager accessibility;
    private final Host host;
    private int mode=IDLE;
    private float startX,startY;
    private View highlighted;

    MenuPressGesture(Context context, Host host) {
        this.host=host;
        slop=ViewConfiguration.get(context).getScaledTouchSlop();
        accessibility=context.getSystemService(AccessibilityManager.class);
    }

    static MenuPressGesture forList(AbsListView list, Function<MotionEvent,Boolean> nativeTouch){
        return new MenuPressGesture(list.getContext(),new Host(){
            public boolean inside(MotionEvent e) {
                return e.getX()>=0&&e.getX()<list.getWidth()&&e.getY()>=0&&e.getY()<list.getHeight();
            }
            public boolean scrollable(){return list.canScrollVertically(-1)||list.canScrollVertically(1);}
            public boolean nativeTouch(MotionEvent e){return nativeTouch.apply(e);}
            public View hit(MotionEvent e){
                int pos=inside(e)?list.pointToPosition((int)e.getX(),(int)e.getY()):AdapterView.INVALID_POSITION;
                return pos==AdapterView.INVALID_POSITION||list.getAdapter()==null||!list.getAdapter().isEnabled(pos)?null:
                        list.getChildAt(pos-list.getFirstVisiblePosition());
            }
            public void pick(View target){
                int pos=list.getPositionForView(target);
                if(pos!=AdapterView.INVALID_POSITION&&list.getAdapter().isEnabled(pos))
                    list.performItemClick(target,pos,list.getAdapter().getItemId(pos));
            }
        });
    }

    boolean touch(MotionEvent event) {
        if (!event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)
                || accessibility!=null&&accessibility.isTouchExplorationEnabled()) return host.nativeTouch(event);
        int action=event.getActionMasked();
        if (action==MotionEvent.ACTION_DOWN) {
            clear();
            View target=host.hit(event);
            if (!enabled(target)) return host.nativeTouch(event);
            startX=event.getX();startY=event.getY();
            if (host.scrollable()) {
                mode=WAIT;
                return host.nativeTouch(event);
            }
            mode=TRACK;
            highlight(target);
            return true;
        }
        if (mode==IDLE) return host.nativeTouch(event);
        if (action==MotionEvent.ACTION_CANCEL || action==MotionEvent.ACTION_POINTER_DOWN) {
            if (mode==WAIT||mode==SCROLL) cancelNative(event);
            clear();
            mode=action==MotionEvent.ACTION_CANCEL?IDLE:CANCELLED;
            return true;
        }
        if (mode==CANCELLED) {
            if (action==MotionEvent.ACTION_UP) clear();
            return true;
        }
        if ((mode==WAIT||mode==TRACK) && (action==MotionEvent.ACTION_MOVE||action==MotionEvent.ACTION_UP)
                && !host.inside(event)) {
            if(mode==WAIT)cancelNative(event);
            clear();
            if(action==MotionEvent.ACTION_MOVE)mode=CANCELLED;
            return true;
        }
        if (mode==WAIT && action==MotionEvent.ACTION_MOVE) {
            float x=Math.abs(event.getX()-startX), y=Math.abs(event.getY()-startY);
            if (Math.max(x,y)>slop) {
                if (x<=y&&event.getEventTime()-event.getDownTime()<PICK_HOLD_MS) {
                    mode=SCROLL;
                } else {
                    cancelNative(event);
                    mode=TRACK;
                }
            }
        }
        if (mode==WAIT||mode==SCROLL) {
            if (action==MotionEvent.ACTION_UP) clear();
            return host.nativeTouch(event);
        }
        if (mode!=TRACK) return true;
        if (action==MotionEvent.ACTION_MOVE) {
            highlight(host.hit(event));
        } else if (action==MotionEvent.ACTION_UP) {
            View target=host.inside(event)?host.hit(event):null;
            clear();
            if (enabled(target)) host.pick(target);
        }
        return true;
    }

    void clear() {
        highlight(null);
        mode=IDLE;
    }

    private void cancelNative(MotionEvent event) {
        MotionEvent cancel=MotionEvent.obtain(event);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        try { host.nativeTouch(cancel); }
        finally { cancel.recycle(); }
    }

    private static boolean enabled(View view) {
        return view!=null&&view.isEnabled()&&view.getVisibility()==View.VISIBLE;
    }

    private void highlight(View view) {
        View next=enabled(view)?view:null;
        if (next==highlighted) return;
        if (highlighted!=null) highlighted.setPressed(false);
        highlighted=next;
        if (highlighted!=null) highlighted.setPressed(true);
    }
}
