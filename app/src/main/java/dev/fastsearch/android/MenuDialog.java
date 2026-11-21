package dev.fastsearch.android;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.function.Consumer;

/** One bounded native menu window. Back pops a level before dismissing the root. */
final class MenuDialog extends Dialog {
    private final UiTheme theme;
    private final View anchor;
    private final Consumer<String> action;
    private final ArrayList<Menu> levels=new ArrayList<>();
    private final ArrayList<String> titles=new ArrayList<>();
    private final ArrayList<MenuItem> items=new ArrayList<>();
    private LinearLayout header;
    private ImageButton back;
    private TextView title;
    private ListView list;
    private int maxHeight;
    private android.window.OnBackInvokedCallback backCallback;
    private final BaseAdapter adapter=new BaseAdapter(){
        @Override public int getCount(){return items.size();}
        @Override public MenuItem getItem(int position){return items.get(position);}
        @Override public long getItemId(int position){return position;}
        @Override public boolean areAllItemsEnabled(){return false;}
        @Override public boolean isEnabled(int position){return getItem(position).isEnabled();}
        @Override public View getView(int position,View recycled,ViewGroup parent){
            MenuRow row=recycled==null?new MenuRow():(MenuRow)recycled;MenuItem item=getItem(position);
            if(row.item!=item)row.setPressed(false);
            row.item=item;
            row.label.setText(item.getTitle());row.label.setEnabled(item.isEnabled());
            row.mark.setImageResource(item.hasSubMenu()?R.drawable.ic_chevron_right:R.drawable.ic_check);
            row.mark.setImageTintList(ColorStateList.valueOf(item.hasSubMenu()?theme.muted:theme.accent));
            row.mark.setVisibility(item.hasSubMenu()||item.isChecked()?View.VISIBLE:View.INVISIBLE);
            row.setEnabled(item.isEnabled());row.setAlpha(item.isEnabled()?1f:.38f);
            return row;
        }
    };
    // Keep ListView's own accessibility delegate, which routes ACTION_CLICK to onItemClick.
    private final class MenuRow extends LinearLayout {
        final TextView label;
        final ImageView mark;
        MenuItem item;
        MenuRow(){super(MenuDialog.this.getContext());setGravity(Gravity.CENTER_VERTICAL);
            setBackground(rowFeedback());
            setPaddingRelative(dp(16),0,dp(12),0);setMinimumHeight(dp(48));setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            label=label(16);label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);addView(label,new LinearLayout.LayoutParams(0,dp(48),1));
            mark=new ImageView(getContext());mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);addView(mark,new LinearLayout.LayoutParams(dp(24),dp(24)));}
        @Override public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);
            if(item==null)return;info.setText(item.getTitle());info.setEnabled(item.isEnabled());info.setCheckable(item.isCheckable());info.setChecked(item.isChecked());
            if(item.hasSubMenu())info.setContentDescription(item.getTitle()+", submenu");}
    }

    MenuDialog(Context context,UiTheme theme,View anchor,Menu root,String rootTitle,Consumer<String> action){
        super(context,theme.dark?R.style.DarkMenuDialog:R.style.LightMenuDialog);
        this.theme=theme;this.anchor=anchor;this.action=action;levels.add(root);titles.add(rootTitle);
        setCanceledOnTouchOutside(true);
    }
    @Override protected void onCreate(Bundle saved){super.onCreate(saved);
        Window window=getWindow();window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setWindowAnimations(0);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        GradientDrawable surface=new GradientDrawable();surface.setColor(theme.chrome);surface.setCornerRadius(dp(12));
        window.setBackgroundDrawableResource(android.R.color.transparent);
        LinearLayout content=new LinearLayout(getContext());content.setOrientation(LinearLayout.VERTICAL);content.setPadding(0,dp(4),0,dp(4));
        content.setBackground(surface);content.setElevation(dp(8));
        header=new LinearLayout(getContext());header.setGravity(Gravity.CENTER_VERTICAL);
        back=new ImageButton(getContext());back.setId(R.id.menu_back);back.setImageResource(R.drawable.ic_back);
        back.setImageTintList(ColorStateList.valueOf(theme.ink));back.setPadding(dp(12),dp(12),dp(12),dp(12));
        TypedValue selectable=new TypedValue();getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground,selectable,true);
        back.setBackgroundResource(selectable.resourceId);back.setOnClickListener(v->goBack());
        header.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));title=label(16);title.setId(R.id.menu_title);
        title.setTypeface(getContext().getResources().getFont(R.font.aeonik_medium));if(Build.VERSION.SDK_INT>=28)title.setAccessibilityHeading(true);
        header.addView(title,new LinearLayout.LayoutParams(0,dp(48),1));content.addView(header);
        list=new ListView(getContext());list.setDivider(null);
        // A ListView selector keeps drawing at the old position after the submenu replaces its items.
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));list.setAdapter(adapter);content.addView(list);
        list.setOnItemClickListener((parent,view,position,id)->{MenuItem item=items.get(position);if(!item.isEnabled())return;
            if(item.hasSubMenu()){levels.add(item.getSubMenu());titles.add(item.getTitle().toString());render();}
            else{String value=item.getTitle().toString();dismiss();action.accept(value);}});
        setContentView(content);position();render();
        if(android.animation.ValueAnimator.areAnimatorsEnabled())animateFromAnchor(content);
    }
    private void animateFromAnchor(View content){
        content.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener(){
            @Override public boolean onPreDraw(){
                content.getViewTreeObserver().removeOnPreDrawListener(this);
                int[] origin=new int[2],button=new int[2];
                content.getLocationOnScreen(origin);anchor.getLocationOnScreen(button);
                content.setPivotX(Math.max(0,Math.min(content.getWidth(),button[0]+anchor.getWidth()/2f-origin[0])));
                content.setPivotY(Math.max(0,Math.min(content.getHeight(),button[1]+anchor.getHeight()/2f-origin[1])));
                content.setAlpha(0);content.setScaleX(.94f);content.setScaleY(.94f);
                content.animate().alpha(1).scaleX(1).scaleY(1).setDuration(130).start();
                return true;
            }
        });
    }
    private StateListDrawable rowFeedback(){
        int tint=Color.argb(32,Color.red(theme.accent),Color.green(theme.accent),Color.blue(theme.accent));
        StateListDrawable feedback=new StateListDrawable();
        feedback.addState(new int[]{android.R.attr.state_enabled,android.R.attr.state_pressed},new ColorDrawable(tint));
        feedback.addState(new int[]{android.R.attr.state_enabled,android.R.attr.state_selected},new ColorDrawable(tint));
        feedback.addState(new int[0],new ColorDrawable(Color.TRANSPARENT));
        return feedback;
    }
    private TextView label(int size){TextView label=new TextView(getContext());label.setTextColor(theme.ink);label.setTextSize(size);
        label.setGravity(Gravity.CENTER_VERTICAL);label.setSingleLine();label.setEllipsize(TextUtils.TruncateAt.END);return label;}
    private int dp(int value){return Math.round(value*getContext().getResources().getDisplayMetrics().density);}
    private void position(){Rect frame=new Rect();anchor.getWindowVisibleDisplayFrame(frame);int[] at=new int[2];anchor.getLocationOnScreen(at);
        int width=Math.min(dp(272),frame.width()-dp(16));int y=Math.max(frame.top+dp(8),at[1]+anchor.getHeight());
        // Reserve enough room even above a visible keyboard; the rows scroll on short windows.
        y=Math.min(y,Math.max(frame.top+dp(8),frame.bottom-dp(160)));
        maxHeight=Math.max(dp(96),frame.bottom-y-dp(8));
        int aligned=at[0]+anchor.getWidth()/2<frame.centerX()?at[0]:at[0]+anchor.getWidth()-width;
        int left=Math.max(frame.left+dp(8),Math.min(aligned,frame.right-width-dp(8)));
        WindowManager.LayoutParams params=getWindow().getAttributes();params.gravity=Gravity.TOP|Gravity.LEFT;
        params.x=left-frame.left;
        params.y=y-frame.top;params.width=width;params.height=WindowManager.LayoutParams.WRAP_CONTENT;getWindow().setAttributes(params);
    }
    private void render(){boolean nested=levels.size()>1;header.setVisibility(nested?View.VISIBLE:View.GONE);
        title.setText(titles.get(titles.size()-1));back.setContentDescription("Back to "+titles.get(Math.max(0,titles.size()-2)));
        items.clear();Menu current=levels.get(levels.size()-1);for(int n=0;n<current.size();n++)if(current.getItem(n).isVisible())items.add(current.getItem(n));
        list.setLayoutParams(new LinearLayout.LayoutParams(-1,Math.min(dp(48)*items.size(),maxHeight-dp(nested?56:8))));
        adapter.notifyDataSetChanged();list.setSelection(0);setTitle(titles.get(titles.size()-1));
        if(nested)back.requestFocus();else list.requestFocus();
    }
    private void goBack(){if(levels.size()==1){dismiss();return;}levels.remove(levels.size()-1);titles.remove(titles.size()-1);render();}
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed(){goBack();}
    @Override protected void onStart(){super.onStart();
        if(Build.VERSION.SDK_INT>=33){backCallback=this::goBack;getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,backCallback);}}
    @Override protected void onStop(){if(Build.VERSION.SDK_INT>=33&&backCallback!=null){getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);backCallback=null;}
        super.onStop();}
}
