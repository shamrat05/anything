package dev.fastsearch.android;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** Draws the upcoming content geometry without allocating placeholder bitmaps. */
final class LoadingSkeleton extends View {
    static final int LIST = 0, GRID = 1, PREVIEW = 2, BRAND = 3;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int style;
    private ValueAnimator sweep;
    private float phase;
    private boolean visible;
    private final UiTheme theme;
    private final Drawable brandMark;
    private final Typeface brandTitle, brandCaption;

    LoadingSkeleton(Context context, int style) {
        super(context); this.style = style;
        theme=UiTheme.resolve(context);setBackgroundColor(theme.surface); setContentDescription("Loading");
        brandMark=context.getDrawable(R.drawable.ic_launcher_foreground);
        brandTitle=getResources().getFont(R.font.aeonik_medium);
        brandCaption=getResources().getFont(R.font.aeonik_regular);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }
    void setStyle(int next){if(style!=next){style=next;invalidate();}}
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); updateMotion(); }
    @Override protected void onDetachedFromWindow() { stop(); super.onDetachedFromWindow(); }
    @Override public void onVisibilityAggregated(boolean shown) { super.onVisibilityAggregated(shown); visible = shown; updateMotion(); }
    private void updateMotion() {
        if (!isAttachedToWindow() || !visible || !ValueAnimator.areAnimatorsEnabled()) { stop(); return; }
        if (sweep != null) return;
        sweep = ValueAnimator.ofFloat(0, 1); sweep.setDuration(1500); sweep.setRepeatCount(ValueAnimator.INFINITE);
        sweep.setInterpolator(new LinearInterpolator());
        sweep.addUpdateListener(value -> { phase = (float) value.getAnimatedValue(); invalidate(); }); sweep.start();
    }
    private void stop() { if (sweep != null) { sweep.removeAllUpdateListeners(); sweep.cancel(); sweep = null; } }
    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
    private void block(Canvas canvas, float left, float top, float right, float bottom, float radius) {
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); float width = getWidth(), height = getHeight(), x = phase * (width * 2) - width;
        if (style == BRAND) {
            drawBrand(canvas, width / 2, height * .42f);
            return;
        }
        paint.setShader(new LinearGradient(x, 0, x + width, height * .2f,
                new int[]{theme.skeleton,theme.highlight,theme.skeleton}, new float[]{0, .5f, 1}, Shader.TileMode.CLAMP));
        if (style == PREVIEW) {
            float edge = Math.min(width - dp(32), height * .64f);
            block(canvas, (width - edge) / 2, (height - edge) / 2, (width + edge) / 2, (height + edge) / 2, dp(8));
        } else if (style == GRID) {
            int columns = Math.max(2, (int) (width / dp(116))); float cell = (width - dp(16)) / columns;
            for (float top = dp(8); top < height; top += cell + dp(34)) for (int column = 0; column < columns; column++) {
                float left = dp(8) + column * cell;
                block(canvas, left, top, left + cell - dp(6), top + cell - dp(12), dp(5));
                block(canvas, left, top + cell - dp(4), left + cell * .72f, top + cell + dp(5), dp(3));
            }
        } else for (int row = 0; row < Math.ceil(height / dp(56)); row++) {
            float top = dp(10 + row * 56);
            block(canvas, dp(14), top, dp(50), top + dp(36), dp(7));
            block(canvas, dp(64), top + dp(3), Math.max(dp(90), width * (row % 3 == 0 ? .65f : .79f)), top + dp(14), dp(3));
            block(canvas, dp(64), top + dp(23), Math.max(dp(80), width * .5f), top + dp(31), dp(3));
        }
        paint.setShader(null);
    }

    private void drawBrand(Canvas canvas, float x, float y) {
        float radius=dp(27);
        paint.setColor(theme.highlight);
        canvas.drawCircle(x,y,radius,paint);
        if(brandMark!=null){brandMark.setBounds((int)(x-radius),(int)(y-radius),(int)(x+radius),(int)(y+radius));brandMark.draw(canvas);}
        paint.setColor(theme.accent);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        canvas.drawArc(x-radius-dp(4),y-radius-dp(4),x+radius+dp(4),y+radius+dp(4),phase*360-90,85,false,paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(theme.ink);
        paint.setTypeface(brandTitle);
        paint.setTextSize(dp(23));
        canvas.drawText(getContext().getString(R.string.app_name),x,y+dp(64),paint);
        paint.setTypeface(brandCaption);
        paint.setTextSize(dp(14));
        canvas.drawText("Find files by name, across folders.",x,y+dp(88),paint);
    }
}
