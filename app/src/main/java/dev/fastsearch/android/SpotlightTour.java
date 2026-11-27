package dev.fastsearch.android;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** An opt-in coach mark on the actual control. The app, not the user, owns the overlay and its lifetime. */
final class SpotlightTour {
    static final class Step {
        final String title, description;
        final Supplier<View> target;
        Step(String title,String description,Supplier<View> target){
            this.title=title;this.description=description;this.target=target;
        }
    }

    static final class Topic {
        final String title, summary;
        final Step[] steps;
        Topic(String title,String summary,Step... steps){
            this.title=title;this.summary=summary;this.steps=Arrays.copyOf(steps,steps.length);
        }
    }

    private final Activity activity;
    private final UiTheme theme;
    private final Step[] quickSteps;
    private final List<Topic> topics;
    private final Runnable onClose,onShowTopics;
    private Step[] steps;
    private Topic topic;
    private int index;
    private Layer layer;
    private View anchor;
    private ViewTreeObserver.OnPreDrawListener observer;
    private boolean closed,anchorRevealRequested;
    private final Map<View,Integer> accessibility = new LinkedHashMap<>();
    private final int[] anchorAt=new int[2],layerAt=new int[2];
    private final Rect visible=new Rect();
    private final RectF position=new RectF();

    SpotlightTour(Activity activity,Runnable onClose,List<Topic> topics,Runnable onShowTopics,Step... steps){
        this.activity=activity;this.theme=UiTheme.resolve(activity);this.onClose=onClose;
        this.quickSteps=Arrays.copyOf(steps,steps.length);this.steps=this.quickSteps;
        this.topics=new java.util.ArrayList<>(topics);this.onShowTopics=onShowTopics;
    }

    void start(){if(steps.length==0)close();else show();}
    void close(){
        if(closed)return;
        closed=true;detach();onClose.run();
    }
    private int dp(float n){return Math.round(n*activity.getResources().getDisplayMetrics().density);}
    private void detach(){
        Layer current=layer;layer=null;
        if(current!=null){
            if(observer!=null&&current.getViewTreeObserver().isAlive())current.getViewTreeObserver().removeOnPreDrawListener(observer);
            if(current.card!=null)current.card.animate().cancel();
            if(current.getParent() instanceof ViewGroup)((ViewGroup)current.getParent()).removeView(current);
        }
        observer=null;anchor=null;
        for(Map.Entry<View,Integer> restored:accessibility.entrySet())restored.getKey().setImportantForAccessibility(restored.getValue());
        accessibility.clear();
    }
    private boolean available(){
        if(closed||activity.isDestroyed()||activity.isFinishing()){close();return false;}
        return true;
    }
    private boolean attach(View root){
        if(!(root instanceof FrameLayout))root=activity.getWindow().getDecorView();
        if(!(root instanceof FrameLayout)){close();return false;}
        FrameLayout host=(FrameLayout)root;
        layer=new Layer();
        for(int i=0;i<host.getChildCount();i++){
            View child=host.getChildAt(i);accessibility.put(child,child.getImportantForAccessibility());
            child.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }
        host.addView(layer,new FrameLayout.LayoutParams(-1,-1));layer.bringToFront();
        layer.setElevation(dp(36));layer.setFocusableInTouchMode(true);
        Layer current=layer;
        observer=()->{
            if(layer==current&&!closed)updateHole(current);
            return true;
        };
        layer.getViewTreeObserver().addOnPreDrawListener(observer);
        return true;
    }
    private void show(){
        detach();if(!available())return;
        Step step=steps[index];
        View next=step.target==null?null:step.target.get();
        // A just-opened sidebar can have its DecorView before Android dispatches attachment.
        anchor=next!=null&&(next.isAttachedToWindow()||next.getParent()!=null)?next:null;
        anchorRevealRequested=false;
        if(!attach(anchor==null?null:anchor.getRootView()))return;
        layer.makeCard(step);
        layer.requestFocus();layer.announceForAccessibility("Step "+(index+1)+" of "+steps.length+". "+step.title);
    }
    private void showTopics(){
        detach();if(!available())return;
        if(onShowTopics!=null)onShowTopics.run();
        if(!attach(null))return;
        layer.makeTopics();
        layer.requestFocus();layer.announceForAccessibility("Guide topics");
    }
    private void openTopic(Topic next){
        topic=next;steps=next==null?quickSteps:next.steps;
        index=0;if(steps.length==0)showTopics();else show();
    }
    private void advance(){
        if(index+1<steps.length){index++;show();}
        else if(topic!=null)showTopics();
        else close();
    }

    private void updateHole(Layer current){
        if(current.getWidth()==0||current.getHeight()==0)return;
        current.updateSafeArea();
        if(anchor==null||!anchor.isShown()||!anchor.isAttachedToWindow()||anchor.getWidth()==0){
            current.center();return;
        }
        if(!anchor.getLocalVisibleRect(visible)){
            if(!anchorRevealRequested){
                anchorRevealRequested=true;
                visible.set(0,0,anchor.getWidth(),anchor.getHeight());
                anchor.requestRectangleOnScreen(visible,true);
            }
            current.center();return;
        }
        anchor.getLocationOnScreen(anchorAt);current.getLocationOnScreen(layerAt);
        int left=anchorAt[0]-layerAt[0],top=anchorAt[1]-layerAt[1];
        position.set(left+visible.left,top+visible.top,left+visible.right,top+visible.bottom);
        current.move(position);
    }

    private final class Layer extends FrameLayout {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF cutout=new RectF();
        private final RectF previous=new RectF();
        private final Path outline=new Path();
        private View card;
        private int cardLimit,safeLeft,safeTop,safeRight,safeBottom;
        private final Rect visibleFrame=new Rect();
        Layer(){super(activity);setWillNotDraw(false);setClickable(true);}
        void makeCard(Step step){
            LinearLayout wrapper=wrapper();
            LinearLayout header=new LinearLayout(activity);header.setGravity(Gravity.CENTER_VERTICAL);
            String progress="STEP "+(index+1)+" / "+steps.length;
            TextView lead=text(topic==null?progress:topic.title+" · "+progress,10,theme.accent,R.font.inter_semibold);
            lead.setLetterSpacing(.06f);header.addView(lead,new LinearLayout.LayoutParams(0,-2,1));
            if(!topics.isEmpty())header.addView(button("Topics",false,SpotlightTour.this::showTopics),
                    new LinearLayout.LayoutParams(-2,dp(48)));
            wrapper.addView(header,new LinearLayout.LayoutParams(-1,-2));
            TextView title=text(step.title,18,theme.ink,R.font.inter_semibold);
            if(android.os.Build.VERSION.SDK_INT>=28)title.setAccessibilityHeading(true);
            title.setPadding(0,dp(4),0,0);wrapper.addView(title,new LayoutParams(-1,-2));
            TextView description=text(step.description,13,theme.muted,R.font.inter);
            description.setPadding(0,dp(5),0,dp(7));description.setLineSpacing(dp(2),1);
            wrapper.addView(description,new LayoutParams(-1,-2));
            LinearLayout actions=new LinearLayout(activity);actions.setGravity(Gravity.END);
            Button back=button("Back",false,()->{if(index>0){index--;show();}});
            back.setEnabled(index>0);
            Button skip=button("Skip",false,SpotlightTour.this::close);
            Button next=button(index==steps.length-1?"Done":"Next",true,SpotlightTour.this::advance);
            for(Button button:new Button[]{back,skip,next})actions.addView(button,new LinearLayout.LayoutParams(0,dp(48),1));
            wrapper.addView(actions,new LayoutParams(-1,-2));
            mount(wrapper);
        }
        private LinearLayout wrapper(){
            LinearLayout wrapper=new LinearLayout(activity);wrapper.setOrientation(LinearLayout.VERTICAL);
            wrapper.setPadding(dp(13),dp(10),dp(13),dp(8));wrapper.setBackground(AppSheet.shape(theme.chrome,dp(15)));
            return wrapper;
        }
        void makeTopics(){
            LinearLayout wrapper=wrapper();
            TextView title=text("Guide topics",20,theme.ink,R.font.inter_semibold);
            title.setPadding(dp(5),dp(6),dp(5),dp(4));
            if(android.os.Build.VERSION.SDK_INT>=28)title.setAccessibilityHeading(true);
            wrapper.addView(title,new LinearLayout.LayoutParams(-1,-2));
            TextView hint=text("Choose what to learn. You can replay any topic.",13,theme.muted,R.font.inter);
            hint.setPadding(dp(5),0,dp(5),dp(5));wrapper.addView(hint,new LinearLayout.LayoutParams(-1,-2));
            topicRow(wrapper,"Quick tour","Start with the essentials.",()->openTopic(null));
            for(Topic next:topics)topicRow(wrapper,next.title,next.summary,()->openTopic(next));
            Button close=button("Close",false,SpotlightTour.this::close);
            wrapper.addView(close,new LinearLayout.LayoutParams(-1,dp(48)));
            mount(wrapper);
        }
        private void topicRow(LinearLayout wrapper,String label,String summary,Runnable open){
            Button row=button(label,false,open);
            row.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
            row.setTextSize(14);
            row.setPaddingRelative(dp(8),dp(8),dp(8),dp(8));
            row.setContentDescription(label+". "+summary);
            LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(-1,-2);layout.topMargin=dp(2);
            wrapper.addView(row,layout);
        }
        private void mount(LinearLayout wrapper){
            ScrollView scroll=new ScrollView(activity){@Override protected void onMeasure(int width,int height){
                int max=cardLimit>0?cardLimit:Math.max(dp(72),Layer.this.getHeight()-dp(24));
                if(MeasureSpec.getMode(height)!=MeasureSpec.UNSPECIFIED)max=Math.min(max,MeasureSpec.getSize(height));
                super.onMeasure(width,MeasureSpec.makeMeasureSpec(max,MeasureSpec.AT_MOST));}};
            scroll.setVerticalScrollBarEnabled(false);
            scroll.setFillViewport(false);scroll.setClipToOutline(true);scroll.setBackground(AppSheet.shape(theme.chrome,dp(15)));
            scroll.addView(wrapper,new ScrollView.LayoutParams(-1,-2));card=scroll;
            int initial=Math.max(dp(160),Math.min(dp(310),activity.getResources().getDisplayMetrics().widthPixels-dp(28)));
            LayoutParams box=new LayoutParams(initial,-2);box.topMargin=dp(24);box.leftMargin=dp(14);
            addView(card,box);
            if(android.animation.ValueAnimator.areAnimatorsEnabled()){
                card.setAlpha(.65f);card.animate().alpha(1f).setDuration(135).start();
            }
        }
        private TextView text(String value,int sp,int color,int font){
            TextView v=new TextView(activity);v.setText(value);v.setTextSize(sp);v.setTextColor(color);
            v.setTypeface(activity.getResources().getFont(font));return v;
        }
        private Button button(String text,boolean primary,Runnable action){
            Button button=new Button(activity);button.setAllCaps(false);button.setText(text);
            button.setTextSize(12);button.setPadding(dp(8),0,dp(8),0);button.setMinimumWidth(0);button.setMinWidth(0);
            button.setMinHeight(dp(48));button.setTypeface(activity.getResources().getFont(R.font.inter_semibold));
            button.setTextColor(primary?(theme.dark?theme.surface:Color.WHITE):theme.ink);
            button.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(theme.line),
                    AppSheet.shape(primary?theme.accent:Color.TRANSPARENT,dp(10)),AppSheet.shape(Color.WHITE,dp(10))));
            button.setOnClickListener(v->action.run());return button;
        }
        void updateSafeArea(){
            getWindowVisibleDisplayFrame(visibleFrame);getLocationOnScreen(layerAt);
            int left=Math.max(0,visibleFrame.left-layerAt[0]),top=Math.max(0,visibleFrame.top-layerAt[1]);
            int right=Math.min(getWidth(),visibleFrame.right-layerAt[0]),bottom=Math.min(getHeight(),visibleFrame.bottom-layerAt[1]);
            safeLeft=right>left?left:0;safeRight=right>left?right:getWidth();
            safeTop=bottom>top?top:0;safeBottom=bottom>top?bottom:getHeight();
        }
        void center(){
            if(card==null||getHeight()==0)return;
            int margin=dp(14),width=Math.min(dp(310),Math.max(1,safeRight-safeLeft-2*margin));
            int limit=Math.max(1,safeBottom-safeTop-2*margin);
            int height=Math.min(limit,card.getMeasuredHeight());
            place(width,safeLeft+(safeRight-safeLeft-width)/2,
                    Math.max(safeTop+margin,safeTop+(safeBottom-safeTop-height)/2),limit);
            cutout.setEmpty();
            if(!previous.isEmpty()){previous.setEmpty();invalidate();}
        }
        private void place(int width,int x,int y,int limit){
            LayoutParams lp=(LayoutParams)card.getLayoutParams();
            if(lp.width!=width||lp.leftMargin!=x||lp.topMargin!=y||cardLimit!=limit){
                cardLimit=limit;lp.width=width;lp.leftMargin=x;lp.topMargin=y;card.setLayoutParams(lp);
            }
        }
        void move(RectF position){
            if(card==null||getHeight()==0)return;
            cutout.set(position);cutout.inset(-dp(4),-dp(4));
            int margin=dp(14),gap=dp(12);
            int width=Math.min(dp(310),Math.max(1,safeRight-safeLeft-2*margin));
            int x=Math.round(cutout.centerX()-width*.5f);
            x=Math.max(safeLeft+margin,Math.min(x,safeRight-width-margin));
            int below=Math.round(cutout.bottom)+gap,above=Math.round(cutout.top)-gap;
            int lowerSpace=safeBottom-below-margin,upperSpace=above-safeTop-margin;
            boolean placeBelow=lowerSpace>=dp(205)||lowerSpace>=upperSpace;
            int space=placeBelow?lowerSpace:upperSpace;
            if(space<dp(150)){center();return;}
            int limit=Math.min(safeBottom-safeTop-margin,space);
            int height=Math.min(limit,Math.max(dp(52),card.getMeasuredHeight()));
            int y=placeBelow?below:above-height;
            y=Math.max(safeTop+margin,Math.min(y,safeBottom-height-margin));
            place(width,x,y,limit);
            if(!previous.equals(cutout)){previous.set(cutout);invalidate();}
        }
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);
            outline.reset();outline.setFillType(Path.FillType.EVEN_ODD);
            outline.addRect(0,0,getWidth(),getHeight(),Path.Direction.CW);
            if(!cutout.isEmpty())outline.addRoundRect(cutout,dp(12),dp(12),Path.Direction.CW);
            paint.setStyle(Paint.Style.FILL);paint.setColor(0xb5000000);canvas.drawPath(outline,paint);
            if(!cutout.isEmpty()){
                paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1.5f));paint.setColor(theme.accent);
                canvas.drawRoundRect(cutout,dp(12),dp(12),paint);
            }
        }
    }
}
