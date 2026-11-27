package dev.fastsearch.android;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.text.TextUtils;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** App-owned compact panels. No platform alert layout or retained Activity references. */
final class AppSheet extends Dialog {
    final UiTheme theme;
    final LinearLayout content, actions;
    private final LinearLayout heading;
    private final boolean reader;
    FileHoldActions fileHoldActions;
    private java.util.function.BooleanSupplier navigateBack;
    private android.window.OnBackInvokedCallback backCallback;
    AppSheet(Activity activity,String title){this(activity,title,false);}
    AppSheet(Activity activity,String title,boolean reader){
        super(activity);this.reader=reader;requestWindowFeature(Window.FEATURE_NO_TITLE);theme=UiTheme.resolve(activity);
        LinearLayout panel=new LinearLayout(activity);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(20),dp(8),dp(20),dp(16));
        panel.setBackground(shape(theme.chrome,dp(24)));
        heading=new LinearLayout(activity);heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView label=text(title,18,theme.ink);label.setTypeface(activity.getResources().getFont(R.font.inter_medium));
        label.setMaxLines(2);label.setEllipsize(TextUtils.TruncateAt.END);
        heading.addView(label,new LinearLayout.LayoutParams(0,dp(52),1));
        ImageButton close=new ImageButton(activity);close.setImageResource(R.drawable.ic_close);close.setContentDescription("Close");
        close.setImageTintList(ColorStateList.valueOf(theme.muted));close.setBackground(new RippleDrawable(ColorStateList.valueOf(theme.line),null,shape(Color.WHITE,dp(24))));close.setPadding(dp(14),dp(14),dp(14),dp(14));close.setOnClickListener(v->dismiss());
        heading.addView(close,new LinearLayout.LayoutParams(dp(48),dp(48)));panel.addView(heading);
        content=new LinearLayout(activity);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(0,dp(4),0,dp(16));
        if(reader){
            panel.setBackgroundColor(theme.surface);
            panel.setPadding(dp(12),dp(4),dp(12),dp(8));
            panel.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        }else{
            ScrollView scroll=new EdgeFastScroll.Document(activity){@Override protected void onMeasure(int width,int height){int maximum=(int)(getResources().getDisplayMetrics().heightPixels*.55f);if(View.MeasureSpec.getMode(height)!=View.MeasureSpec.UNSPECIFIED)maximum=Math.min(maximum,View.MeasureSpec.getSize(height));super.onMeasure(width,View.MeasureSpec.makeMeasureSpec(maximum,View.MeasureSpec.AT_MOST));}};
            scroll.setFillViewport(false);scroll.addView(content);panel.addView(scroll,new LinearLayout.LayoutParams(-1,-2));
        }
        actions=new LinearLayout(activity);actions.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);panel.addView(actions);
        setContentView(panel);setCanceledOnTouchOutside(true);
        Window window=getWindow();if(window!=null){window.setBackgroundDrawableResource(android.R.color.transparent);window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);window.setDimAmount(.35f);window.setGravity(Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);}
    }
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event){
        return fileHoldActions!=null&&fileHoldActions.dispatch(event,super::dispatchTouchEvent)||super.dispatchTouchEvent(event);
    }
    void setBackNavigation(java.util.function.BooleanSupplier action){navigateBack=action;}
    private void handleBack(){
        if(navigateBack==null||!navigateBack.getAsBoolean())super.onBackPressed();
    }
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed(){handleBack();}
    @Override protected void onStart(){
        super.onStart();
        if(android.os.Build.VERSION.SDK_INT>=33){
            backCallback=this::handleBack;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,backCallback);
        }
    }
    @Override protected void onStop(){
        if(android.os.Build.VERSION.SDK_INT>=33&&backCallback!=null){
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
            backCallback=null;
        }
        super.onStop();
    }

    void headerAction(View action){heading.addView(action,heading.getChildCount()-1);}
    TextView text(String value,int size,int color){TextView text=new TextView(getContext());text.setText(value);text.setTextSize(size);text.setTextColor(color);text.setGravity(Gravity.CENTER_VERTICAL);text.setTypeface(getContext().getResources().getFont(R.font.inter));return text;}
    Button action(String label,boolean primary,boolean destructive,Runnable action){Button button=new Button(getContext());button.setText(label);button.setAllCaps(false);button.setTextSize(14);button.setTypeface(getContext().getResources().getFont(R.font.inter_medium));
        int fill=destructive?0xFFBD3046:theme.accent;button.setTextColor(primary?(destructive||!theme.dark?Color.WHITE:theme.surface):theme.ink);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(theme.line),(primary&&!destructive?theme.primaryAction(dp(14)):shape(primary?fill:Color.TRANSPARENT,dp(14))),null));
        LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(0,dp(48),1);if(actions.getChildCount()>0)layout.leftMargin=dp(10);actions.addView(button,layout);
        button.setOnClickListener(v->{dismiss();if(action!=null)action.run();});return button;}
    @Override public void show(){super.show();Window window=getWindow();if(window!=null){
        if(reader){window.setLayout(-1,-1);return;}
        int width=Math.min(getContext().getResources().getDisplayMetrics().widthPixels-dp(24),dp(560));window.setLayout(width,-2);WindowManager.LayoutParams layout=window.getAttributes();layout.y=dp(12);window.setAttributes(layout);}}
    int dp(float value){return Math.round(value*getContext().getResources().getDisplayMetrics().density);}
    static GradientDrawable shape(int color,float radius){GradientDrawable shape=new GradientDrawable();shape.setColor(color);shape.setCornerRadius(radius);return shape;}
}
