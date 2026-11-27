package dev.fastsearch.android;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.Locale;

/** Embedded playback UI. Timers run only while visible and playing. */
final class PlaybackControls extends android.widget.MediaController {
    private final UiTheme theme;
    private final LinearLayout column,buttons;
    private final SeekBar seek;
    private final TextView elapsed,total;
    private final ImageButton play;
    private final Button speed;
    private MediaPlayerControl media;
    private boolean dragging;
    private final Runnable back;
    private final Runnable tick=this::refresh;
    private final Runnable disappear=this::hide;
    PlaybackControls(Activity context,boolean video,Runnable back,Runnable volume,Runnable brightness,Runnable changeSpeed){
        super(context);this.back=back;theme=UiTheme.resolve(context);removeAllViews();
        column=new LinearLayout(context);column.setOrientation(LinearLayout.VERTICAL);column.setPadding(dp(6),0,dp(6),dp(2));
        GradientDrawable panel=AppSheet.shape(theme.surface,dp(16));panel.setStroke(dp(1),theme.line);column.setBackground(panel);
        LinearLayout timeline=new LinearLayout(context);timeline.setGravity(Gravity.CENTER_VERTICAL);timeline.setPadding(dp(4),0,dp(4),0);
        elapsed=label("0:00");total=label("0:00");timeline.addView(elapsed,new LinearLayout.LayoutParams(-2,dp(48)));
        seek=new SeekBar(context);seek.setId(R.id.media_seek);seek.setContentDescription("Seek playback");seek.setProgressTintList(ColorStateList.valueOf(theme.accent));seek.setThumbTintList(ColorStateList.valueOf(theme.accent));
        seek.setProgressBackgroundTintList(ColorStateList.valueOf(theme.line));seek.setSplitTrack(false);
        timeline.addView(seek,new LinearLayout.LayoutParams(0,dp(48),1));total.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);timeline.addView(total,new LinearLayout.LayoutParams(-2,dp(48)));column.addView(timeline);
        buttons=new LinearLayout(context);buttons.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton rewind=icon(R.drawable.ic_viewer_replay10,"Back 10 seconds",View.NO_ID);
        rewind.setOnClickListener(v->skip(-10_000));buttons.addView(rewind);
        play=icon(R.drawable.ic_play,"Play",R.id.media_play);play.setImageTintList(ColorStateList.valueOf(theme.accent));
        play.setBackground(new RippleDrawable(ColorStateList.valueOf(theme.line),
            new android.graphics.drawable.InsetDrawable(AppSheet.shape(theme.selected,dp(13)),dp(4)),null));
        play.setOnClickListener(v->toggle());buttons.addView(play);
        ImageButton forward=icon(R.drawable.ic_viewer_forward10,"Forward 10 seconds",View.NO_ID);
        forward.setOnClickListener(v->skip(10_000));buttons.addView(forward);
        buttons.addView(new View(context),new LinearLayout.LayoutParams(0,1,1));
        speed=word("1×","Playback speed");speed.setId(R.id.media_speed);speed.setOnClickListener(v->changeSpeed.run());buttons.addView(speed);
        ImageButton sound=icon(R.drawable.ic_volume,"Volume",R.id.media_volume);sound.setOnClickListener(v->volume.run());buttons.addView(sound);
        if(video){ImageButton light=icon(R.drawable.ic_brightness,"Brightness",R.id.media_brightness);light.setOnClickListener(v->brightness.run());buttons.addView(light);}
        android.widget.HorizontalScrollView actions=new android.widget.HorizontalScrollView(context);actions.setFillViewport(true);actions.setHorizontalScrollBarEnabled(false);actions.addView(buttons,new FrameLayout.LayoutParams(-2,-2));column.addView(actions);addView(column,new FrameLayout.LayoutParams(-1,-2));setVisibility(INVISIBLE);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar bar,int value,boolean user){if(user)elapsed.setText(time(value));}
            public void onStartTrackingTouch(SeekBar bar){dragging=true;removeCallbacks(disappear);removeCallbacks(tick);}
            public void onStopTrackingTouch(SeekBar bar){dragging=false;if(media!=null)media.seekTo(bar.getProgress());show(4000);}
        });
    }
    @Override public void setAnchorView(View anchor){if(getParent() instanceof ViewGroup)((ViewGroup)getParent()).removeView(this);
        if(anchor instanceof FrameLayout){FrameLayout.LayoutParams layout=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);layout.setMargins(dp(8),0,dp(8),dp(8));((FrameLayout)anchor).addView(this,layout);}}
    @Override public void setMediaPlayer(MediaPlayerControl player){media=player;}
    View playTarget(){return play;}
    @Override public void setEnabled(boolean enabled){super.setEnabled(enabled);if(seek!=null)seek.setEnabled(enabled);if(buttons!=null)for(int i=0;i<buttons.getChildCount();i++)buttons.getChildAt(i).setEnabled(enabled);}
    @Override public void show(){show(4000);}
    @Override public void show(int timeout){setVisibility(VISIBLE);removeCallbacks(disappear);refresh();if(timeout>0&&media!=null&&media.isPlaying()&&!dragging)postDelayed(disappear,timeout);}
    @Override public void hide(){removeCallbacks(tick);removeCallbacks(disappear);setVisibility(INVISIBLE);}
    @Override public boolean isShowing(){return getVisibility()==VISIBLE;}
    void speed(float value){
        String label=String.format(Locale.ROOT,"%s×",value==(int)value?Integer.toString((int)value):Float.toString(value));
        speed.setText(label);speed.setTooltipText("Playback speed: "+label);
    }
    private void refresh(){removeCallbacks(tick);if(!isShowing()||media==null)return;
        if(!dragging){int duration=media.getDuration(),position=media.getCurrentPosition();seek.setMax(Math.max(1,duration));seek.setProgress(position);elapsed.setText(time(position));total.setText(time(duration));}
        boolean playing=media.isPlaying();play.setImageResource(playing?R.drawable.ic_pause:R.drawable.ic_play);play.setContentDescription(playing?"Pause":"Play");play.setTooltipText(playing?"Pause":"Play");
        if(playing&&!dragging)postDelayed(tick,1000);}
    private void toggle(){if(media==null||!isEnabled())return;if(media.isPlaying())media.pause();else media.start();show(4000);}
    private void skip(int delta){if(media==null)return;media.seekTo(Math.max(0,Math.min(media.getDuration(),media.getCurrentPosition()+delta)));show(4000);}
    @Override public boolean dispatchKeyEvent(KeyEvent event){int key=event.getKeyCode();
        if(key==KeyEvent.KEYCODE_BACK){if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0)back.run();return true;}
        if(key==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE||key==KeyEvent.KEYCODE_HEADSETHOOK||key==KeyEvent.KEYCODE_SPACE){if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0)toggle();return true;}
        if(media!=null&&(key==KeyEvent.KEYCODE_MEDIA_PLAY||key==KeyEvent.KEYCODE_MEDIA_PAUSE||key==KeyEvent.KEYCODE_MEDIA_STOP)){
            if(event.getAction()==KeyEvent.ACTION_DOWN){if(key==KeyEvent.KEYCODE_MEDIA_PLAY)media.start();else media.pause();show(4000);}return true;}
        return super.dispatchKeyEvent(event);}
    @Override protected void onDetachedFromWindow(){hide();super.onDetachedFromWindow();}
    private TextView label(String value){TextView label=new TextView(getContext());label.setText(value);label.setTextSize(11);label.setTextColor(theme.muted);label.setGravity(Gravity.CENTER_VERTICAL);label.setMinWidth(dp(36));label.setSingleLine(true);label.setTypeface(getResources().getFont(R.font.inter));return label;}
    private Button word(String value,String description){Button button=new Button(getContext());button.setText(value);button.setTextSize(12);button.setAllCaps(false);button.setTextColor(theme.ink);button.setTypeface(getResources().getFont(R.font.inter_medium));button.setContentDescription(description);button.setTooltipText(description);button.setMinWidth(0);button.setMinimumWidth(0);button.setPadding(0,0,0,0);button.setBackground(ripple());button.setLayoutParams(new LinearLayout.LayoutParams(dp(48),dp(48)));return button;}
    private ImageButton icon(int resource,String description,int id){ImageButton button=new ImageButton(getContext());button.setId(id);button.setImageResource(resource);button.setImageTintList(ColorStateList.valueOf(theme.ink));button.setContentDescription(description);button.setTooltipText(description);button.setPadding(dp(14),dp(14),dp(14),dp(14));button.setBackground(ripple());button.setLayoutParams(new LinearLayout.LayoutParams(dp(48),dp(48)));return button;}
    private RippleDrawable ripple(){return new RippleDrawable(ColorStateList.valueOf(theme.line),null,AppSheet.shape(Color.WHITE,dp(12)));}
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private static String time(int millis){int seconds=Math.max(0,millis/1000);return seconds>=3600?String.format(Locale.ROOT,"%d:%02d:%02d",seconds/3600,seconds/60%60,seconds%60):String.format(Locale.ROOT,"%d:%02d",seconds/60,seconds%60);}
}
