package dev.fastsearch.android;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.view.View;
import android.view.animation.PathInterpolator;

/** A transient acknowledgement for an accepted pull, not an estimated progress meter. */
final class PullRefreshIndicator extends View {
    private final UiTheme theme;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF track=new RectF();
    private final Runnable hideStill=()->setVisibility(GONE);
    private SweepGradient palette;
    private ValueAnimator motion;
    private float rotation;

    PullRefreshIndicator(Context context,UiTheme theme){
        super(context);
        this.theme=theme;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setClickable(false);setFocusable(false);
        setVisibility(GONE);
    }

    void showPulse(){
        reset();
        rotation=0;
        setVisibility(VISIBLE);
        if(!ValueAnimator.areAnimatorsEnabled()){
            postDelayed(hideStill,280);
            return;
        }
        ValueAnimator current=ValueAnimator.ofFloat(0f,1f);
        motion=current;
        current.setDuration(620);
        current.setInterpolator(new PathInterpolator(.2f,.6f,.3f,1f));
        current.addUpdateListener(animator->{
            float frame=(Float)animator.getAnimatedValue();
            rotation=270f*frame;
            setTranslationY(dp(8f)-dp(25f)*frame);
            setAlpha(frame<.7f?1f:Math.max(0f,(1f-frame)/.3f));
            invalidate();
        });
        current.addListener(new AnimatorListenerAdapter(){
            @Override public void onAnimationEnd(Animator animator){
                if(motion==current){motion=null;setVisibility(GONE);}
            }
        });
        current.start();
    }

    private void reset(){
        removeCallbacks(hideStill);
        ValueAnimator previous=motion;motion=null;
        if(previous!=null)previous.cancel();
        setAlpha(1f);setTranslationY(0);
    }

    @Override protected void onSizeChanged(int w,int h,int oldW,int oldH){
        super.onSizeChanged(w,h,oldW,oldH);
        float x=w/2f,y=h/2f,r=dp(10f);
        track.set(x-r,y-r,x+r,y+r);
        palette=new SweepGradient(x,y,new int[]{0xff493a9e,0xff0082ff,0xff65d9f1,0xff493a9e},
                new float[]{0f,.38f,.77f,1f});
    }

    @Override protected void onDraw(Canvas canvas){
        float x=track.centerX(),y=track.centerY();
        paint.setShader(null);paint.setStyle(Paint.Style.FILL);paint.setColor(theme.surface);
        canvas.drawCircle(x,y,dp(16f),paint);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1f));paint.setColor(theme.line);
        canvas.drawCircle(x,y,dp(16f),paint);
        canvas.save();canvas.rotate(rotation-90f,x,y);
        paint.setShader(palette);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeWidth(dp(2.5f));
        canvas.drawArc(track,0f,265f,false,paint);
        canvas.restore();
        paint.setShader(null);
    }

    @Override protected void onDetachedFromWindow(){reset();setVisibility(GONE);super.onDetachedFromWindow();}
    private float dp(float value){return value*getResources().getDisplayMetrics().density;}
}
