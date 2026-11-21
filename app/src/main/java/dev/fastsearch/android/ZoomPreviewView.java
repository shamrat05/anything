package dev.fastsearch.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

/** A single bounded bitmap with fit, pinch zoom, double tap and constrained pan. */
final class ZoomPreviewView extends View {
    private EdgeFastScroll pageEdge;
    void enablePageEdge(java.util.function.IntSupplier pages, java.util.function.IntConsumer go) {
        pageEdge = new EdgeFastScroll(this, new EdgeFastScroll.Range() {
            public int count() { return pages.getAsInt(); }
            public String label(int p) { return "Page " + (p + 1) + " / " + count(); }
            public void seek(int p) { go.accept(p); }
        }, true);
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        return (pageEdge != null && pageEdge.handle(event, super::dispatchTouchEvent)) || super.dispatchTouchEvent(event);
    }
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix orientation = new Matrix();
    private final ScaleGestureDetector pinch;
    private final GestureDetector gestures;
    private Bitmap bitmap;
    private android.graphics.Picture picture;
    private float imageWidth, imageHeight, fit = 1, zoom = 1, left, top;
    private float centerX = .5f, centerY = .5f;
    private int viewportWidth, viewportHeight;

    ZoomPreviewView(Context context) {
        super(context);
        setBackgroundColor(UiTheme.resolve(context).chrome);
        setFocusable(true);
        setClickable(true);
        pinch = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                zoomAt(zoom * detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
                return true;
            }
        });
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public boolean onScroll(MotionEvent first, MotionEvent current, float x, float y) {
                if (pinch.isInProgress() || current.getPointerCount() != 1) return true;
                left -= x; top -= y; constrain(); invalidate(); return true;
            }
            @Override public boolean onDoubleTap(MotionEvent event) {
                zoomAt(zoom > 1.1f ? 1 : 2.5f, event.getX(), event.getY()); return true;
            }
            @Override public boolean onSingleTapConfirmed(MotionEvent event) { return performClick(); }
            @Override public void onLongPress(MotionEvent event) { performLongClick(); }
        });
    }

    void setImage(Bitmap value, int exifOrientation) {
        picture=null;bitmap = value;
        orientation.set(orientationMatrix(exifOrientation));
        if (bitmap == null) { imageWidth = imageHeight = 0; invalidate(); return; }
        RectF bounds = new RectF(0, 0, bitmap.getWidth(), bitmap.getHeight());
        orientation.mapRect(bounds);
        orientation.postTranslate(-bounds.left, -bounds.top);
        imageWidth = bounds.width(); imageHeight = bounds.height();
        layoutImage();
    }

    void setPicture(android.graphics.Picture value){bitmap=null;picture=value;orientation.reset();
        imageWidth=value==null?0:value.getWidth();imageHeight=value==null?0:value.getHeight();layoutImage();}
    void zoomBy(float factor){zoomAt(zoom*factor,getWidth()/2f,getHeight()/2f);}
    void fit(){restore(1,.5f,.5f);}
    private boolean hasImage(){return picture!=null||bitmap!=null&&!bitmap.isRecycled();}

    static Matrix orientationMatrix(int exifOrientation) {
        Matrix orientation = new Matrix();
        switch (exifOrientation) {
            case 2: orientation.setScale(-1, 1); break;
            case 3: orientation.setRotate(180); break;
            case 4: orientation.setScale(1, -1); break;
            case 5: orientation.setRotate(90); orientation.postScale(-1, 1); break;
            case 6: orientation.setRotate(90); break;
            case 7: orientation.setRotate(270); orientation.postScale(-1, 1); break;
            case 8: orientation.setRotate(270); break;
            default: break;
        }
        return orientation;
    }

    void restore(float savedZoom, float savedX, float savedY) {
        zoom = sane(savedZoom, 1, 8, 1);
        centerX = sane(savedX, 0, 1, .5f); centerY = sane(savedY, 0, 1, .5f);
        layoutImage();
    }

    float[] state() {
        rememberCenter();
        return new float[]{zoom, centerX, centerY};
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        rememberCenter();
        viewportWidth = width; viewportHeight = height;
        layoutImage();
    }

    private void rememberCenter() {
        if (hasImage() && fit > 0 && imageWidth > 0 && imageHeight > 0 && viewportWidth > 0) {
            centerX = (viewportWidth / 2f - left) / (fit * zoom * imageWidth);
            centerY = (viewportHeight / 2f - top) / (fit * zoom * imageHeight);
        }
    }

    private void layoutImage() {
        if (!hasImage() || viewportWidth <= 0 || viewportHeight <= 0) return;
        fit = Math.min(viewportWidth / imageWidth, viewportHeight / imageHeight);
        left = viewportWidth / 2f - centerX * imageWidth * fit * zoom;
        top = viewportHeight / 2f - centerY * imageHeight * fit * zoom;
        constrain(); invalidate();
    }

    private void zoomAt(float value, float x, float y) {
        if (!hasImage()) return;
        float next = sane(value, 1, 8, 1), ratio = next / zoom;
        left = x - (x - left) * ratio; top = y - (y - top) * ratio;
        zoom = next; constrain(); invalidate();
    }

    private void constrain() {
        float width = imageWidth * fit * zoom, height = imageHeight * fit * zoom;
        left = width <= viewportWidth ? (viewportWidth - width) / 2f : Math.max(viewportWidth - width, Math.min(0, left));
        top = height <= viewportHeight ? (viewportHeight - height) / 2f : Math.max(viewportHeight - height, Math.min(0, top));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!hasImage()) return;
        int saved = canvas.save();
        canvas.translate(left, top); canvas.scale(fit * zoom, fit * zoom); canvas.concat(orientation);
        if(picture!=null)canvas.drawPicture(picture);else canvas.drawBitmap(bitmap, 0, 0, paint);
        canvas.restoreToCount(saved);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!hasImage()) return false;
        pinch.onTouchEvent(event); gestures.onTouchEvent(event);
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent((zoom>1.05f||pinch.isInProgress())&&event.getActionMasked() != MotionEvent.ACTION_UP
                && event.getActionMasked() != MotionEvent.ACTION_CANCEL);
        return true;
    }

    @Override public boolean performClick() { super.performClick(); return true; }

    @Override protected void onDetachedFromWindow() {
        if(pageEdge!=null)pageEdge.clear();
        // Cancel GestureDetector's delayed tap/long-press messages with the view.
        long now = SystemClock.uptimeMillis(); MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
        try { pinch.onTouchEvent(cancel); gestures.onTouchEvent(cancel); }
        finally { cancel.recycle(); super.onDetachedFromWindow(); }
    }

    @Override public boolean onKeyDown(int code, KeyEvent event) {
        switch (code) {
            case KeyEvent.KEYCODE_PLUS: case KeyEvent.KEYCODE_EQUALS: zoomAt(zoom * 1.5f, getWidth() / 2f, getHeight() / 2f); return true;
            case KeyEvent.KEYCODE_MINUS: zoomAt(zoom / 1.5f, getWidth() / 2f, getHeight() / 2f); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: left += getWidth() / 5f; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: left -= getWidth() / 5f; break;
            case KeyEvent.KEYCODE_DPAD_UP: top += getHeight() / 5f; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: top -= getHeight() / 5f; break;
            default: return super.onKeyDown(code, event);
        }
        constrain(); invalidate(); return true;
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
    }

    @Override public boolean performAccessibilityAction(int action, Bundle args) {
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
            zoomAt(action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? zoom * 1.5f : zoom / 1.5f, getWidth() / 2f, getHeight() / 2f);
            return true;
        }
        return super.performAccessibilityAction(action, args);
    }

    private static float sane(float value, float min, float max, float fallback) {
        return Float.isNaN(value) || Float.isInfinite(value) ? fallback : Math.max(min, Math.min(max, value));
    }
}
