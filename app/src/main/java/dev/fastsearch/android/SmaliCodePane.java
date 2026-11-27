package dev.fastsearch.android;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.UnderlineSpan;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ViewTreeObserver;
import java.util.List;
import java.util.function.Consumer;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

final class SmaliCodePane extends LinearLayout {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScrollView vertical;
    private final HorizontalScrollView horizontal;
    private final TextView body, count;
    private final EditText find;
    private final Button previous, next;
    private final LinearLayout findPanel;
    private final BackgroundColorSpan highlight, memberHighlight;
    private int[] hits = new int[0];
    private int current = -1, length;
    private final Runnable update = () -> applyQuery(null, 0);

    SmaliCodePane(Context context, UiTheme theme, CharSequence code,List<SmaliReferences.Ref> refs,
                  Consumer<SmaliReferences.Ref> follow) {
        super(context);
        setOrientation(VERTICAL);
        setBackgroundColor(theme.surface);

        findPanel = new LinearLayout(context);
        findPanel.setOrientation(VERTICAL);
        findPanel.setBackgroundColor(theme.chrome);
        findPanel.setPadding(dp(12),0,dp(8),dp(6));
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        find = new EditText(context);
        find.setSingleLine(true);
        find.setTextSize(16);
        find.setTextColor(theme.ink);
        find.setHintTextColor(theme.muted);
        find.setHint("Find in Smali");
        find.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        find.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        find.setFilters(new InputFilter[] { new InputFilter.LengthFilter(SmaliFind.MAX_QUERY) });
        row.addView(find, new LinearLayout.LayoutParams(0,dp(49),1));
        previous = arrow(context, "↑", "Previous match", theme.accent, () -> step(-1));
        next = arrow(context, "↓", "Next match", theme.accent, () -> step(1));
        row.addView(previous, new LinearLayout.LayoutParams(dp(48),dp(48)));
        row.addView(next, new LinearLayout.LayoutParams(dp(48),dp(48)));
        findPanel.addView(row, new LinearLayout.LayoutParams(-1,-2));
        count = new TextView(context);
        count.setTextColor(theme.muted);
        count.setTextSize(12);
        count.setPadding(dp(4),0,0,0);
        count.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        findPanel.addView(count, new LinearLayout.LayoutParams(-1,-2));
        findPanel.setVisibility(GONE);
        addView(findPanel,new LinearLayout.LayoutParams(-1,-2));
        find.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count) {
                main.removeCallbacks(update);
                main.postDelayed(update,100);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        find.setOnEditorActionListener((v,action,event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            step(1);
            return true;
        });

        vertical = new ScrollView(context);
        horizontal = new HorizontalScrollView(context);
        body = new ReferenceText(context,refs,follow);
        body.setTextColor(theme.ink);
        body.setTextSize(14);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextDirection(View.TEXT_DIRECTION_LTR);
        FileViewerActivity.simpleTextLayout(body);
        body.setHorizontallyScrolling(true);
        body.setPadding(dp(16),dp(12),dp(16),dp(24));
        body.setTextIsSelectable(true);
        SpannableString styled=new SpannableString(code);
        for(SmaliReferences.Ref ref:refs)
            styled.setSpan(new UnderlineSpan(),ref.start,ref.end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        body.setText(styled,TextView.BufferType.SPANNABLE);
        horizontal.addView(body,new HorizontalScrollView.LayoutParams(-2,-2));
        vertical.addView(horizontal,new ScrollView.LayoutParams(-1,-2));
        addView(vertical,new LinearLayout.LayoutParams(-1,0,1));
        highlight = new BackgroundColorSpan(theme.dark ? 0xFF334E70 : 0xFFD4E7FF);
        memberHighlight=new BackgroundColorSpan(theme.dark?0xFF493C23:0xFFFFE6A7);
    }

    boolean isFinding() { return findPanel.getVisibility() == VISIBLE; }

    void openFind(boolean keyboard) {
        findPanel.setVisibility(VISIBLE);
        if (keyboard) {
            find.requestFocus();
            InputMethodManager imm = getContext().getSystemService(InputMethodManager.class);
            if (imm != null) imm.showSoftInput(find,InputMethodManager.SHOW_IMPLICIT);
        }
        applyQuery(null,0);
    }

    void closeFind() {
        main.removeCallbacks(update);
        InputMethodManager imm = getContext().getSystemService(InputMethodManager.class);
        if (imm != null && find.hasFocus()) imm.hideSoftInputFromWindow(find.getWindowToken(),0);
        find.clearFocus();
        findPanel.setVisibility(GONE);
        ((Spannable)body.getText()).removeSpan(highlight);
    }

    Bundle saveFind() {
        Bundle result = new Bundle();
        result.putBoolean("visible",isFinding());
        result.putString("query",find.getText().toString());
        result.putInt("match",current);
        return result;
    }

    void restoreFind(Bundle saved) {
        if (saved == null) return;
        find.setText(saved.getString("query",""));
        if (saved.getBoolean("visible")) {
            openFind(false);
            applyQuery(saved.getInt("match",0),0);
        }
    }

    Bundle saveLocation() {
        Bundle result=saveFind();
        result.putInt("x",horizontal.getScrollX()); result.putInt("y",vertical.getScrollY());
        Spanned text=(Spanned)body.getText();
        result.putInt("memberStart",text.getSpanStart(memberHighlight));
        result.putInt("memberEnd",text.getSpanEnd(memberHighlight));
        return result;
    }

    void restoreLocation(Bundle saved) {
        restoreFind(saved);
        int start=saved.getInt("memberStart",-1), end=saved.getInt("memberEnd",-1);
        if(start>=0 && end>start && end<=body.length())
            ((Spannable)body.getText()).setSpan(memberHighlight,start,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        afterLayout(()->{
            horizontal.scrollTo(saved.getInt("x"),0);
            vertical.scrollTo(0,saved.getInt("y"));
        });
    }

    void showMember(SmaliReferences.Anchor anchor) {
        ((Spannable)body.getText()).setSpan(memberHighlight,anchor.start,anchor.end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        afterLayout(()->{
            Layout layout=body.getLayout();
            if(layout==null)return;
            int line=layout.getLineForOffset(anchor.start);
            int y=body.getTotalPaddingTop()+layout.getLineTop(line);
            int x=body.getTotalPaddingLeft()+(int)layout.getPrimaryHorizontal(anchor.start);
            vertical.scrollTo(0,Math.max(0,y-vertical.getHeight()/4));
            horizontal.scrollTo(Math.max(0,x-horizontal.getWidth()/4),0);
        });
    }

    private void afterLayout(Runnable action) {
        body.post(()->{
            if(!isAttachedToWindow())return;
            if(body.getLayout()!=null && !isLayoutRequested()){action.run();return;}
            ViewTreeObserver observer=body.getViewTreeObserver();
            observer.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener(){
                @Override public boolean onPreDraw(){
                    if(observer.isAlive())observer.removeOnPreDrawListener(this);
                    if(isAttachedToWindow())action.run();
                    return true;
                }
            });
        });
    }

    private static final class ReferenceText extends TextView {
        private final GestureDetector clicks;
        ReferenceText(Context context,List<SmaliReferences.Ref> refs,Consumer<SmaliReferences.Ref> follow) {
            super(context);
            clicks=new GestureDetector(context,new GestureDetector.SimpleOnGestureListener(){
                @Override public boolean onDown(MotionEvent event){return true;}
                @Override public boolean onSingleTapConfirmed(MotionEvent event){
                    Layout layout=getLayout();
                    if(layout==null || !isAttachedToWindow() || hasSelection())return false;
                    float y=event.getY()-getTotalPaddingTop()+getScrollY();
                    if(y<0 || y>=layout.getHeight())return false;
                    int line=layout.getLineForVertical((int)y);
                    float x=event.getX()-getTotalPaddingLeft()+getScrollX();
                    if(x<layout.getLineLeft(line) || x>=layout.getLineRight(line))return false;
                    SmaliReferences.Ref ref=SmaliReferences.at(refs,layout.getOffsetForHorizontal(line,x));
                    if(ref==null)return false;
                    performClick();
                    follow.accept(ref);
                    return true;
                }
            });
        }
        @Override public boolean onTouchEvent(MotionEvent event){
            boolean handled=super.onTouchEvent(event);
            clicks.onTouchEvent(event);
            return handled;
        }
        @Override public boolean performClick(){return super.performClick();}
    }

    private String lastQuery = "";
    private void step(int direction) {
        String query = find.getText().toString();
        if (!query.equals(lastQuery)) {
            applyQuery(direction < 0 ? Integer.MAX_VALUE : 0,0);
            return;
        }
        applyQuery(null,direction);
    }

    private void applyQuery(Integer wanted,int direction) {
        main.removeCallbacks(update);
        if (!isFinding()) return;
        String query = find.getText().toString();
        if (!query.equals(lastQuery)) {
            hits = SmaliFind.matches(body.getText(),query);
            length = query.length();
            current = hits.length == 0 ? -1 : 0;
            lastQuery = query;
        }
        Spannable text = (Spannable)body.getText();
        text.removeSpan(highlight);
        boolean has = hits.length > 0;
        next.setEnabled(has); previous.setEnabled(has);
        if (!has) {
            count.setText(query.isEmpty() ? "Type text to find a match" : "No matches");
            return;
        }
        if (wanted != null) current = Math.max(0,Math.min(wanted,hits.length-1));
        else if (direction != 0) current = SmaliFind.navigate(current,direction,hits.length);
        int start = hits[current];
        text.setSpan(highlight,start,start+length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        count.setText("Match " + (current+1) + " of " + hits.length);
        body.post(() -> {
            if (!isAttachedToWindow() || !isFinding() || text.getSpanStart(highlight) != start) return;
            Layout layout = body.getLayout();
            if (layout == null) return;
            int line = layout.getLineForOffset(start);
            int y = body.getTotalPaddingTop()+layout.getLineTop(line);
            int x = body.getTotalPaddingLeft()+(int)layout.getPrimaryHorizontal(start);
            vertical.scrollTo(0,Math.max(0,y-vertical.getHeight()/4));
            horizontal.scrollTo(Math.max(0,x-horizontal.getWidth()/4),0);
        });
    }

    @Override protected void onDetachedFromWindow() {
        main.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }
    private Button arrow(Context context,String glyph,String label,int color,Runnable tap) {
        Button button = new Button(context);
        button.setText(glyph); button.setTextSize(20); button.setTextColor(color);
        button.setContentDescription(label); button.setBackground(null); button.setPadding(0,0,0,0);
        button.setOnClickListener(v -> tap.run());
        return button;
    }
    private int dp(int v) { return Math.round(v*getResources().getDisplayMetrics().density); }
}
