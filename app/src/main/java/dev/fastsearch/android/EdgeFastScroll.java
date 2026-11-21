package dev.fastsearch.android;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.AbsListView;
import android.widget.GridView;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.function.Consumer;
import java.util.function.IntFunction;

/** An edge drag selects a fraction of the full range, independently of current thumb position. */
final class EdgeFastScroll {
    interface Range {
        int count();
        String label(int position);
        void seek(int position);
    }

    private final View view;
    private final Range range;
    private final boolean commitOnRelease;
    private final int slop, width;
    private boolean armed, dragging;
    private float startY;
    private int last = -1;
    private PopupWindow popup;
    private TextView label;
    private final Runnable hideThumb = () -> thumb(false);

    private void thumb(boolean visible) {
        if (view instanceof AbsListView) {
            AbsListView list = (AbsListView)view;
            list.setFastScrollEnabled(visible);
            list.setFastScrollAlwaysVisible(visible);
        }
    }

    EdgeFastScroll(View view, Range range, boolean commitOnRelease) {
        this.view = view;
        this.range = range;
        this.commitOnRelease = commitOnRelease;
        width = Math.round(24 * view.getResources().getDisplayMetrics().density);
        slop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
    }

    boolean handle(MotionEvent event, Consumer<MotionEvent> cancelChild) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            clear();
            armed = event.getX() >= view.getWidth() - width && range.count() > 1;
            if (armed) thumb(true);
            startY = event.getY();
            return false;
        }
        if (!armed) return false;
        if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_CANCEL) {
            boolean consumed = dragging;
            clear();
            return consumed;
        }
        if (action == MotionEvent.ACTION_MOVE && !dragging && Math.abs(event.getY() - startY) > slop) {
            dragging = true;
            MotionEvent cancel = MotionEvent.obtain(event);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            cancelChild.accept(cancel);
            cancel.recycle();
            view.getParent().requestDisallowInterceptTouchEvent(true);
        }
        if (!dragging) {
            if (action == MotionEvent.ACTION_UP) finishTouch();
            return false;
        }
        int count = range.count();
        if (count > 0) {
            float fraction = (event.getY() - view.getPaddingTop()) /
                    Math.max(1, view.getHeight() - view.getPaddingTop() - view.getPaddingBottom() - 1);
            int pos = Math.round(Math.max(0, Math.min(1, fraction)) * (count - 1));
            if (last != pos) {
                last = pos;
                showLabel(range.label(pos));
                if (!commitOnRelease) range.seek(pos);
            }
            if (action == MotionEvent.ACTION_UP && commitOnRelease) range.seek(pos);
        }
        if (action == MotionEvent.ACTION_UP) finishTouch();
        return true;
    }

    private void showLabel(String value) {
        if (popup == null) {
            label = new TextView(view.getContext());
            label.setSingleLine();
            label.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            label.setMaxWidth(Math.max(width, view.getWidth() - 2 * width));
            label.setTextColor(Color.WHITE);
            label.setPadding(width / 2, width / 4, width / 2, width / 4);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xef243449);
            bg.setCornerRadius(width / 2f);
            label.setBackground(bg);
            popup = new PopupWindow(label, -2, -2, false);
            popup.setTouchable(false);
        }
        label.setText(value);
        if (!popup.isShowing()) {
            int[] location = new int[2];
            view.getLocationInWindow(location);
            popup.showAtLocation(view, Gravity.TOP | Gravity.RIGHT, width, location[1] + width / 2);
        } else popup.update(-2, -2);
    }

    private void finishTouch() {
        boolean reveal = armed && view instanceof AbsListView;
        clear();
        if (reveal) {
            thumb(true);
            view.postDelayed(hideThumb, 900);
        }
    }

    void clear() {
        view.removeCallbacks(hideThumb);
        thumb(false);
        if (dragging && view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(false);
        dragging = armed = false;
        last = -1;
        if (popup != null) { popup.dismiss(); popup = null; label = null; }
    }

    static EdgeFastScroll rows(AbsListView list, IntFunction<String> labels) {
        list.setVerticalScrollBarEnabled(false);
        return new EdgeFastScroll(list, new Range() {
            public int count() { return list.canScrollVertically(1) || list.canScrollVertically(-1) ? list.getCount() : 0; }
            public String label(int p) { return labels.apply(p); }
            public void seek(int p) { list.setSelection(p); }
        }, false);
    }

    static class List extends ListView {
        IntFunction<String> labels = p -> String.valueOf(p + 1);
        private final EdgeFastScroll edge = rows(this, p -> labels.apply(p));
        List(Context context) { super(context); }
        @Override public boolean dispatchTouchEvent(MotionEvent e) {
            return edge.handle(e, super::dispatchTouchEvent) || super.dispatchTouchEvent(e);
        }
        @Override protected void onDetachedFromWindow() { edge.clear(); super.onDetachedFromWindow(); }
    }

    static final class Grid extends GridView {
        IntFunction<String> labels = p -> String.valueOf(p + 1);
        private final EdgeFastScroll edge = rows(this, p -> labels.apply(p));
        Grid(Context context) { super(context); }
        @Override public boolean dispatchTouchEvent(MotionEvent e) {
            return edge.handle(e, super::dispatchTouchEvent) || super.dispatchTouchEvent(e);
        }
        @Override protected void onDetachedFromWindow() { edge.clear(); super.onDetachedFromWindow(); }
    }

    static class Document extends ScrollView {
        private final EdgeFastScroll edge = new EdgeFastScroll(this, new Range() {
            public int count() { return getChildCount() == 0 ? 0 : Math.max(0, getChildAt(0).getHeight() + getPaddingTop() + getPaddingBottom() - getHeight()) + 1; }
            public String label(int p) { return Math.round(100f * p / Math.max(1, count() - 1)) + "%"; }
            public void seek(int p) { scrollTo(0, p); }
        }, false);
        Document(Context context) { super(context); }
        @Override public boolean dispatchTouchEvent(MotionEvent e) {
            return edge.handle(e, super::dispatchTouchEvent) || super.dispatchTouchEvent(e);
        }
        @Override protected void onDetachedFromWindow() { edge.clear(); super.onDetachedFromWindow(); }
    }
}
