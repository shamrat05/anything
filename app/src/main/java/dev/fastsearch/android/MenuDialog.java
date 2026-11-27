package dev.fastsearch.android;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Locale;
import java.util.function.Consumer;

/** One bounded native menu window. Back pops a level before dismissing the root. */
final class MenuDialog extends Dialog {
    private final UiTheme theme;
    private final View anchor;
    private final Consumer<String> action;
    private java.util.function.BiPredicate<Menu,MenuItem> liveAction;
    private final ArrayList<Menu> levels=new ArrayList<>();
    private final ArrayList<String> titles=new ArrayList<>();
    private final ArrayList<MenuItem> items=new ArrayList<>();
    private LinearLayout header;
    private ImageButton back;
    private TextView title, empty;
    private Typeface regular, medium;
    private int rowHeight;
    private AbsListView list;
    private final int columns;
    private Runnable allAction,clearAction,applyAction;
    private java.util.function.BooleanSupplier canApply;
    private Button applyButton;
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
            row.bind(item,position>0&&items.get(position-1).getGroupId()!=item.getGroupId());
            return row;
        }
    };

    // Leave ListView's accessibility delegate in place so ACTION_CLICK still invokes onItemClick.
    private final class MenuRow extends LinearLayout {
        final TextView label;
        final ImageView leading, mark;
        MenuItem item;
        boolean sectionStart;
        final android.graphics.Paint rule=new android.graphics.Paint();
        MenuRow(){
            super(MenuDialog.this.getContext());
            setGravity(Gravity.CENTER_VERTICAL);setWillNotDraw(false);rule.setColor(theme.line);
            setBackground(rowFeedback());
            setPaddingRelative(dp(10),dp(3),dp(8),dp(3));
            setLayoutParams(new AbsListView.LayoutParams(LayoutParams.MATCH_PARENT,rowHeight));
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);

            leading=menuIcon();
            LinearLayout.LayoutParams iconSize=new LinearLayout.LayoutParams(dp(18),dp(18));
            iconSize.setMarginEnd(dp(10));addView(leading,iconSize);
            label=label(14);
            label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            label.setMaxLines(2);
            addView(label,new LinearLayout.LayoutParams(0,LayoutParams.MATCH_PARENT,1));
            mark=menuIcon();
            LinearLayout.LayoutParams markSize=new LinearLayout.LayoutParams(dp(18),dp(18));
            markSize.setMarginStart(dp(6));addView(mark,markSize);
        }
        void bind(MenuItem item,boolean sectionStart){
            this.item=item;this.sectionStart=sectionStart;invalidate();
            boolean enabled=item.isEnabled(), active=item.isChecked();
            int iconColor=active?theme.accent:theme.muted;
            label.setText(item.getTitle());
            label.setTypeface(active?medium:regular);
            label.setEnabled(enabled);
            String text=item.getTitle()==null?"":item.getTitle().toString();
            String normalized=text.toLowerCase(Locale.ROOT);
            label.setTextColor(normalized.equals("delete")||normalized.contains("trash")
                    ? (theme.dark?0xFFFFA5A5:0xFF9C2424) : theme.ink);

            Drawable supplied=item.getIcon();
            if(supplied!=null){
                Drawable.ConstantState state=supplied.getConstantState();
                leading.setImageDrawable(state==null?supplied:state.newDrawable(getResources(),getContext().getTheme()));
                ColorStateList suppliedTint=item.getIconTintList();
                leading.setImageTintList(suppliedTint==null&&columns==1?ColorStateList.valueOf(iconColor):suppliedTint);
            }else{
                leading.setImageResource(menuResource(text,titles.get(titles.size()-1)));
                leading.setImageTintList(ColorStateList.valueOf(iconColor));
            }
            mark.setImageResource(item.hasSubMenu()?R.drawable.ic_chevron_right:R.drawable.ic_check);
            mark.setImageTintList(ColorStateList.valueOf(item.hasSubMenu()?theme.muted:theme.accent));
            mark.setVisibility(item.hasSubMenu()||active?View.VISIBLE:View.INVISIBLE);
            setEnabled(enabled);setActivated(active);setAlpha(enabled?1f:.45f);
        }
        @Override protected void onDraw(android.graphics.Canvas canvas){
            super.onDraw(canvas);
            if(sectionStart)canvas.drawRect(dp(7),0,getWidth()-dp(7),dp(1),rule);
        }
        @Override public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo info){
            super.onInitializeAccessibilityNodeInfo(info);
            if(item==null)return;
            info.setText(item.getTitle());info.setEnabled(item.isEnabled());
            info.setCanOpenPopup(item.hasSubMenu());
            info.setCheckable(item.isCheckable());info.setChecked(item.isChecked());
        }
    }

    private ImageView menuIcon(){
        ImageView icon=new ImageView(getContext());icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return icon;
    }

    MenuDialog(Context context,UiTheme theme,View anchor,Menu root,String rootTitle,Consumer<String> action){
        this(context,theme,anchor,root,rootTitle,1,action);
    }
    MenuDialog(Context context,UiTheme theme,View anchor,Menu root,String rootTitle,int columns,Consumer<String> action){
        super(context,theme.dark?R.style.DarkMenuDialog:R.style.LightMenuDialog);
        this.theme=theme;this.anchor=anchor;this.action=action;this.columns=columns;levels.add(root);titles.add(rootTitle);
        setCanceledOnTouchOutside(true);
    }
    void setLiveAction(java.util.function.BiPredicate<Menu,MenuItem> handler){liveAction=handler;}
    void setFilterFooter(Runnable all,Runnable clear,Runnable apply,java.util.function.BooleanSupplier ready){
        allAction=all;clearAction=clear;applyAction=apply;canApply=ready;
    }
    private void filterChanged(){
        adapter.notifyDataSetChanged();
        if(applyButton!=null)applyButton.setEnabled(canApply.getAsBoolean());
    }
    @Override protected void onCreate(Bundle saved){super.onCreate(saved);
        Window window=getWindow();window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setWindowAnimations(0);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        regular=getContext().getResources().getFont(R.font.inter);
        medium=getContext().getResources().getFont(R.font.inter_medium);
        rowHeight=Math.max(dp(48),(int)Math.ceil(2*label(14).getPaint().getFontSpacing())+dp(8));
        GradientDrawable surface=rounded(theme.chrome,14);surface.setStroke(dp(1),theme.line);
        window.setBackgroundDrawableResource(android.R.color.transparent);
        LinearLayout content=new LinearLayout(getContext());content.setOrientation(LinearLayout.VERTICAL);
        content.setPaddingRelative(dp(5),dp(5),dp(5),dp(5));
        content.setBackground(surface);content.setElevation(dp(6));content.setClipToOutline(true);
        header=new LinearLayout(getContext());header.setGravity(Gravity.CENTER_VERTICAL);
        back=new ImageButton(getContext());back.setId(R.id.menu_back);back.setImageResource(R.drawable.ic_back);
        back.setImageTintList(ColorStateList.valueOf(theme.ink));back.setPadding(dp(14),dp(14),dp(14),dp(14));
        back.setBackground(rowFeedback());back.setOnClickListener(v->goBack());
        header.addView(back,new LinearLayout.LayoutParams(dp(48),rowHeight));title=label(14);title.setId(R.id.menu_title);
        title.setTypeface(medium);title.setMaxLines(2);if(Build.VERSION.SDK_INT>=28)title.setAccessibilityHeading(true);
        header.addView(title,new LinearLayout.LayoutParams(0,rowHeight,1));content.addView(header);
        list=columns==1?new MenuDragList(getContext()):new MenuDragGrid(getContext(),columns);
        if(list instanceof ListView)((ListView)list).setDivider(null);
        list.setVerticalScrollBarEnabled(false);
        // A ListView selector keeps drawing at the old position after the submenu replaces its items.
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));list.setAdapter(adapter);content.addView(list);
        empty=label(14);empty.setText("No actions available");empty.setTextColor(theme.muted);
        empty.setPaddingRelative(dp(10),0,dp(10),0);
        content.addView(empty,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,rowHeight));
        if(applyAction!=null){
            LinearLayout footer=new LinearLayout(getContext());footer.setGravity(Gravity.CENTER_VERTICAL);
            footer.addView(footerButton("All",()->{allAction.run();filterChanged();}),new LinearLayout.LayoutParams(0,dp(48),1));
            footer.addView(footerButton("Clear",()->{clearAction.run();filterChanged();}),new LinearLayout.LayoutParams(0,dp(48),1));
            applyButton=footerButton("Apply",()->{if(canApply.getAsBoolean()){dismiss();applyAction.run();}});
            footer.addView(applyButton,new LinearLayout.LayoutParams(0,dp(48),1));
            content.addView(footer,new LinearLayout.LayoutParams(-1,dp(48)));
            applyButton.setEnabled(canApply.getAsBoolean());
        }
        list.setOnItemClickListener((parent,view,position,id)->{MenuItem item=items.get(position);if(!item.isEnabled())return;
            if(item.hasSubMenu()){levels.add(item.getSubMenu());titles.add(item.getTitle().toString());render();}
            else if(liveAction!=null&&liveAction.test(levels.get(levels.size()-1),item)){
                filterChanged();
                if(item.isCheckable())list.announceForAccessibility(item.getTitle()+(item.isChecked()?", on":", off"));
            }else{String value=item.getTitle().toString();dismiss();action.accept(value);}});
        setContentView(content);position();render();
        if(android.animation.ValueAnimator.areAnimatorsEnabled())animateFromAnchor(content);
    }
    private Button footerButton(String label,Runnable click){
        Button button=new Button(getContext());button.setAllCaps(false);button.setText(label);
        button.setTypeface(medium);button.setTextColor(theme.ink);button.setTextSize(13);
        button.setMinWidth(0);button.setMinimumWidth(0);button.setStateListAnimator(null);
        button.setBackground(rowFeedback());button.setOnClickListener(view->click.run());return button;
    }
    private void animateFromAnchor(View content){
        content.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener(){
            @Override public boolean onPreDraw(){
                content.getViewTreeObserver().removeOnPreDrawListener(this);
                // Do not animate the row geometry while a finger may already be down.
                content.setAlpha(0);
                content.animate().alpha(1).setDuration(120).start();
                return true;
            }
        });
    }
    private int accentAlpha(int alpha){
        return Color.argb(alpha,Color.red(theme.accent),Color.green(theme.accent),Color.blue(theme.accent));
    }
    private GradientDrawable rounded(int color,int radius){
        GradientDrawable shape=new GradientDrawable();shape.setColor(color);shape.setCornerRadius(dp(radius));return shape;
    }
    private StateListDrawable rowFeedback(){
        StateListDrawable feedback=new StateListDrawable();
        feedback.addState(new int[]{android.R.attr.state_enabled,android.R.attr.state_pressed},
                rounded(accentAlpha(45),10));
        feedback.addState(new int[]{android.R.attr.state_enabled,android.R.attr.state_focused},
                rounded(accentAlpha(35),10));
        feedback.addState(new int[]{android.R.attr.state_enabled,android.R.attr.state_selected},
                rounded(accentAlpha(35),10));
        feedback.addState(new int[]{android.R.attr.state_activated},rounded(accentAlpha(theme.dark?26:17),10));
        feedback.addState(new int[0],new ColorDrawable(Color.TRANSPARENT));
        return feedback;
    }
    private TextView label(int size){
        TextView label=new TextView(getContext());
        label.setTextColor(theme.ink);label.setTextSize(size);label.setTypeface(regular);
        label.setIncludeFontPadding(false);label.setGravity(Gravity.CENTER_VERTICAL);
        label.setEllipsize(TextUtils.TruncateAt.END);
        return label;
    }

    private static int menuResource(String label,String level){
        String name=label.trim().toLowerCase(Locale.ROOT);
        switch(name){
            // The three different comparisons are deliberately not variations on the sort arrow.
            case "name": case "file name": case "filename": return R.drawable.ic_rename;
            case "path": case "file path": return R.drawable.ic_locations;
            case "size": case "file size": return R.drawable.ic_menu_size;
            case "modified": case "date modified": case "date": return R.drawable.ic_history;
            case "ascending": return R.drawable.ic_menu_ascending;
            case "descending": case "reverse order": return R.drawable.ic_sort;
            case "auto": case "automatic": return R.drawable.ic_menu_auto;
            case "list": case "file list": case "show file list": return R.drawable.ic_list;
            case "compact": case "compact list": case "details": return R.drawable.ic_compact;
            case "grid": case "tiles": case "storage map": case "show storage map": return R.drawable.ic_grid;
            case "display": case "view": case "change view": return R.drawable.ic_grid;
            case "search": return R.drawable.ic_search;
            case "clear search": case "clear search and folder": case "clear folder": case "clear both": case "close tab": return R.drawable.ic_close;
            case "search options": case "file actions": case "index scope": return R.drawable.ic_options;
            case "match case": case "whole word": case "whole words": return R.drawable.ic_rename;
            case "match path": case "include folder paths": return R.drawable.ic_locations;
            case "reset matching options": return R.drawable.ic_refresh;
            case "regular expressions": case "filters": case "file types": return R.drawable.ic_subtype;
            case "recent searches": case "recent folders": case "folder history": return R.drawable.ic_history;
            case "save search": case "saved searches": case "bookmarked folders": return R.drawable.ic_document;
            case "storage": case "shared storage": case "start location": return R.drawable.ic_locations;
            case "root folders": return R.drawable.ic_root;
            case "check for updates": case "refresh": case "reload": return R.drawable.ic_refresh;
            case "index statistics": case "show status": return R.drawable.ic_menu_size;
            case "export results": return R.drawable.ic_share;
            case "appearance": case "theme": case "wallpaper tint": case "system": case "dark": case "light": return R.drawable.ic_brightness;
            case "touch feedback": return R.drawable.ic_volume;
            case "new tab": return R.drawable.ic_viewer_zoom_in;
            case "move earlier": return R.drawable.ic_back;
            case "move later": return R.drawable.ic_chevron_right;
            case "two-pane files": case "workspace tabs": return R.drawable.ic_workspaces;
            case "file access": case "hidden files": return R.drawable.ic_folder;
            case "help": case "guided tour": return R.drawable.ic_document;
            case "support development": return R.drawable.ic_message;
        }
        if(name.contains("delete")||name.contains("trash"))return R.drawable.ic_delete;
        if(name.contains("paste"))return R.drawable.ic_paste;
        if(name.contains("copy"))return R.drawable.ic_copy;
        if(name.contains("move")||name.contains("cut"))return R.drawable.ic_cut;
        if(name.contains("rename"))return R.drawable.ic_rename;
        if(name.contains("share"))return R.drawable.ic_share;
        if(name.contains("select"))return R.drawable.ic_select;
        if(name.contains("properties")||name.contains("info"))return R.drawable.ic_document;
        if(name.contains("open with"))return R.drawable.ic_app;
        if(name.contains("archive")||name.contains("extract"))return R.drawable.ic_archive;
        if(name.contains("folder")||name.contains("location"))return R.drawable.ic_folder;
        if(name.contains("open")||name.contains("preview"))return R.drawable.ic_file;
        if("Workspace tabs".equals(level))return R.drawable.ic_workspaces;
        if(name.startsWith("/")||"Folder history".equals(level))return R.drawable.ic_folder;
        return R.drawable.ic_more;
    }
    private int dp(int value){return Math.round(value*getContext().getResources().getDisplayMetrics().density);}
    private void position(){Rect frame=new Rect();anchor.getWindowVisibleDisplayFrame(frame);int[] at=new int[2];anchor.getLocationOnScreen(at);
        int width=Math.min(dp(columns>1?380:272),frame.width()-dp(16));int y=Math.max(frame.top+dp(8),at[1]+anchor.getHeight());
        // Reserve enough room even above a visible keyboard; the rows scroll on short windows.
        y=Math.min(y,Math.max(frame.top+dp(8),frame.bottom-dp(160)));
        maxHeight=Math.max(dp(96),frame.bottom-y-dp(8));
        int aligned=at[0]+anchor.getWidth()/2<frame.centerX()?at[0]:at[0]+anchor.getWidth()-width;
        int left=Math.max(frame.left+dp(8),Math.min(aligned,frame.right-width-dp(8)));
        WindowManager.LayoutParams params=getWindow().getAttributes();params.gravity=Gravity.TOP|Gravity.LEFT;
        params.x=left-frame.left;
        params.y=y-frame.top;params.width=width;params.height=WindowManager.LayoutParams.WRAP_CONTENT;getWindow().setAttributes(params);
    }
    private void render(){clearPick();boolean nested=levels.size()>1;header.setVisibility(nested||applyAction!=null?View.VISIBLE:View.GONE);back.setVisibility(nested?View.VISIBLE:View.GONE);
        title.setText(titles.get(titles.size()-1));back.setContentDescription("Back to "+titles.get(Math.max(0,titles.size()-2)));
        items.clear();Menu current=levels.get(levels.size()-1);for(int n=0;n<current.size();n++)if(current.getItem(n).isVisible())items.add(current.getItem(n));
        int space=Math.max(rowHeight,maxHeight-dp(10)-(nested||applyAction!=null?rowHeight:0)-(applyAction!=null?dp(48):0));
        list.setLayoutParams(new LinearLayout.LayoutParams(-1,Math.min(rowHeight*((items.size()+columns-1)/columns),space)));
        list.setVisibility(items.isEmpty()?View.GONE:View.VISIBLE);empty.setVisibility(items.isEmpty()?View.VISIBLE:View.GONE);
        adapter.notifyDataSetChanged();list.setSelection(0);setTitle(titles.get(titles.size()-1));
        if(nested)back.requestFocus();else if(!items.isEmpty())list.requestFocus();
    }
    private void goBack(){if(levels.size()==1){dismiss();return;}levels.remove(levels.size()-1);titles.remove(titles.size()-1);render();}
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed(){goBack();}
    @Override protected void onStart(){super.onStart();
        if(Build.VERSION.SDK_INT>=33){backCallback=this::goBack;getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,backCallback);}}
    private void clearPick(){
        if(list instanceof MenuDragList)((MenuDragList)list).clearPick();
        if(list instanceof MenuDragGrid)((MenuDragGrid)list).clearPick();
    }
    @Override protected void onStop(){clearPick();if(Build.VERSION.SDK_INT>=33&&backCallback!=null){getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);backCallback=null;}
        super.onStop();}
}
