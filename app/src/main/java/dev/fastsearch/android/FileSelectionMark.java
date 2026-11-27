package dev.fastsearch.android;

import android.content.Context;
import android.graphics.Color;
import android.graphics.RectF;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;

/** Native selection artwork, kept separate from the file icon or thumbnail. */
final class FileSelectionMark {
    private FileSelectionMark() { }

    static Drawable create(Context context, UiTheme theme) {
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(theme.accent);
        Drawable check = context.getDrawable(R.drawable.ic_check).mutate();
        check.setTint(theme.dark ? 0xFF0D1722 : Color.WHITE);
        return new LayerDrawable(new Drawable[]{circle, check});
    }

    /** Badge follows the rendered artwork, including fitted thumbnails that don't fill the cell. */
    static final class Picture extends FrameLayout {
        private final RectF artwork = new RectF();
        private ImageView icon, badge, owner;

        Picture(Context context) { super(context); }

        void marks(ImageView icon, ImageView badge) { this.icon=icon; this.badge=badge; }
        void ownerMark(ImageView mark) { this.owner=mark; }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed,l,t,r,b);
            if(icon==null || badge==null) return;
            Drawable drawable=icon.getDrawable();
            artwork.set(0,0,icon.getWidth(),icon.getHeight());
            if(drawable!=null && !drawable.getBounds().isEmpty()) {
                artwork.set(drawable.getBounds());
                icon.getImageMatrix().mapRect(artwork);
                if(!artwork.intersect(0,0,icon.getWidth(),icon.getHeight()))
                    artwork.set(0,0,icon.getWidth(),icon.getHeight());
            }
            int width=badge.getMeasuredWidth(), height=badge.getMeasuredHeight();
            int left=Math.max(0,Math.min(getWidth()-width,icon.getLeft()+Math.round(artwork.right)-width/3));
            int top=Math.max(0,Math.min(getHeight()-height,icon.getTop()+Math.round(artwork.top)-height/2));
            badge.layout(left,top,left+width,top+height);
            if(owner!=null){
                width=owner.getMeasuredWidth();height=owner.getMeasuredHeight();
                left=Math.max(0,Math.min(getWidth()-width,icon.getLeft()+Math.round(artwork.right)-width/3));
                top=Math.max(0,Math.min(getHeight()-height,icon.getTop()+Math.round(artwork.bottom)-height/3));
                owner.layout(left,top,left+width,top+height);
            }
        }
    }

    static GradientDrawable outline(UiTheme theme, int width, int radius) {
        GradientDrawable border = new GradientDrawable();
        border.setColor(Color.TRANSPARENT);
        border.setStroke(width, theme.selectionLine());
        border.setCornerRadius(radius);
        return border;
    }
}
