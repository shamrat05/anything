package dev.fastsearch.android;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.Layout;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.function.Consumer;

import dev.fastsearch.core.FileEntry;

/** One file's actions and metadata; the host owns the menu window and touch tracking. */
// TextView's pre-29 Layout constants have the same values as SDK 36's LineBreaker annotation.
@android.annotation.SuppressLint("WrongConstant")
final class FileActionCard extends LinearLayout {
    private final UiTheme theme;
    private final Consumer<String> action;
    private final TextView name;
    private final ImageButton close;
    private final TextView path;
    private final TextView metadata;
    private final View divider;
    private final LinearLayout actions;
    private final ArrayList<Row> rows = new ArrayList<>();
    private final DateFormat dates = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
    private final int[] hitLocation=new int[2];
    private final android.graphics.Rect hitBounds=new android.graphics.Rect();
    private FileEntry bound;
    private boolean tracking;
    private Consumer<ClipData> probeClipboard; // Instrumentation supplies a sink, leaving the user's system clipboard alone.

    FileActionCard(Context context, UiTheme theme, Consumer<String> action,Runnable dismiss) {
        super(context);
        this.theme = theme;
        this.action = action;
        setOrientation(VERTICAL);
        setBackground(outline(theme.chrome, 16));
        setClipToOutline(true);

        // The host constrains a single outer ScrollView after the finger is released.
        // Keep this view wrap-content so the complete filename, path and metadata can scroll.
        setPaddingRelative(dp(12), dp(6), dp(12), dp(8));

        name = text(16, theme.ink);
        name.setTypeface(getResources().getFont(R.font.inter_semibold));
        copyControl(name,false);
        if (Build.VERSION.SDK_INT >= 28) name.setAccessibilityHeading(true);
        LinearLayout header=new LinearLayout(context);
        header.setGravity(Gravity.TOP);
        LinearLayout identity=new LinearLayout(context);
        identity.setOrientation(VERTICAL);
        identity.addView(name,new LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT));
        header.addView(identity,new LayoutParams(0,LayoutParams.WRAP_CONTENT,1f));
        close=new ImageButton(context);
        close.setImageResource(R.drawable.ic_close);
        close.setImageTintList(ColorStateList.valueOf(theme.muted));
        close.setContentDescription("Close file actions");
        close.setTooltipText("Close file actions");
        close.setPadding(dp(15),dp(15),dp(15),dp(15));
        android.graphics.drawable.StateListDrawable feedback=new android.graphics.drawable.StateListDrawable();
        feedback.addState(new int[]{android.R.attr.state_pressed},rounded(theme.selected,12));
        feedback.addState(new int[]{android.R.attr.state_focused},rounded(theme.selected,12));
        feedback.addState(new int[0],rounded(android.graphics.Color.TRANSPARENT,12));
        close.setBackground(feedback);
        close.setOnTouchListener((view,event)->{
            if(android.animation.ValueAnimator.areAnimatorsEnabled()){
                int type=event.getActionMasked();
                if(type==android.view.MotionEvent.ACTION_DOWN||type==android.view.MotionEvent.ACTION_UP||type==android.view.MotionEvent.ACTION_CANCEL){
                    float scale=type==android.view.MotionEvent.ACTION_DOWN?.92f:1f;
                    view.animate().scaleX(scale).scaleY(scale).setDuration(85).start();
                }
            }
            return false;
        });
        close.setOnClickListener(view->dismiss.run());
        LayoutParams closeSize=new LayoutParams(dp(48),dp(48));
        closeSize.setMarginStart(dp(4));
        header.addView(close,closeSize);
        addView(header,new LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT));

        path = text(12, theme.muted);
        copyControl(path,true);
        LayoutParams pathSize = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        pathSize.bottomMargin = dp(5);
        identity.addView(path, pathSize);

        actions = new LinearLayout(context);
        actions.setOrientation(VERTICAL);
        addView(actions, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        divider = new View(context);
        divider.setBackgroundColor(theme.line);
        divider.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LayoutParams dividerSize = new LayoutParams(LayoutParams.MATCH_PARENT, dp(1));
        dividerSize.topMargin = dp(3);
        dividerSize.bottomMargin = dp(4);
        addView(divider, dividerSize);

        metadata = text(12, theme.muted);
        metadata.setMaxLines(2);
        addView(metadata, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    /** Rebind during a drag without replacing the view tree or executing menu items. */
    void bind(FileEntry entry, Menu model, boolean tracking) {
        this.tracking = tracking;
        setImportantForAccessibility(tracking ? IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                : IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        if (bound != entry) {
            name.setText(entry.name);
            path.setText(entry.parent.isEmpty() ? "File location unavailable" : entry.path());
            name.setContentDescription("Copy filename: "+entry.name);
            path.setContentDescription(entry.parent.isEmpty()?"File location unavailable":"Copy full path: "+entry.path());
            metadata.setText(details(entry,null));
            bound = entry;
        }

        close.setEnabled(!tracking);
        enableCopy(name,!tracking,true);
        enableCopy(path,!tracking&&!entry.parent.isEmpty(),!entry.parent.isEmpty());
        int count = 0;
        for (int i = 0; model != null && i < model.size(); i++) {
            MenuItem item = model.getItem(i);
            if (!item.isVisible() || TextUtils.isEmpty(item.getTitle())) continue;
            int rowIndex = count / 2;
            Row row = rowIndex < rows.size() ? rows.get(rowIndex) : appendRow();
            row.view.setVisibility(VISIBLE);
            row.slots[count % 2].bind(item);
            count++;
        }
        if ((count & 1) != 0) rows.get(count / 2).slots[1].clear();
        for (int i = (count + 1) / 2; i < rows.size(); i++) {
            Row row = rows.get(i);
            row.slots[0].clear();
            row.slots[1].clear();
            row.view.setVisibility(GONE);
        }
        divider.setVisibility(count > 0 ? VISIBLE : GONE);
    }

    void hideStandaloneClose(){close.setVisibility(GONE);}

    View actionAt(float x,float y) {
        if(tracking)return null;
        for(Row row:rows) for(Slot slot:row.slots)
            if(slot.available&&slot.button.isClickable()
                    &&FileHoldActions.inside(slot.button,x,y,hitLocation,hitBounds)) return slot.button;
        return null;
    }

    private void copyControl(TextView view,boolean wholePath){
        view.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);
        view.setMinHeight(dp(48));
        view.setPaddingRelative(dp(3),dp(3),dp(3),dp(3));
        view.setCompoundDrawablePadding(dp(6));
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(theme.line),null,rounded(0xffffffff,9)));
        view.setTooltipText(wholePath?"Copy full path":"Copy filename");
        Drawable glyph=getContext().getDrawable(R.drawable.ic_copy);
        if(glyph!=null){glyph=glyph.mutate();glyph.setTint(theme.muted);glyph.setBounds(0,0,dp(15),dp(15));}
        view.setTag(glyph);
        view.setOnClickListener(clicked->{
            FileEntry entry=bound;
            if(tracking||entry==null||wholePath&&entry.parent.isEmpty())return;
            ClipData clip=ClipData.newPlainText(wholePath?"File path":"File name",wholePath?entry.path():entry.name);
            if(BuildConfig.DEBUG&&probeClipboard!=null){probeClipboard.accept(clip);return;}
            ClipboardManager clipboard=getContext().getSystemService(ClipboardManager.class);
            if(clipboard==null)return;
            clipboard.setPrimaryClip(clip);
            if(Build.VERSION.SDK_INT<33)android.widget.Toast.makeText(getContext(),
                    wholePath?"Path copied":"Filename copied",android.widget.Toast.LENGTH_SHORT).show();
        });
    }

    private void enableCopy(TextView view,boolean enabled,boolean displayIcon){
        view.setClickable(enabled);view.setFocusable(enabled);
        view.setCompoundDrawablesRelative(null,null,displayIcon?(Drawable)view.getTag():null,null);
    }

    private Row appendRow() {
        Row row = new Row();
        rows.add(row);
        LayoutParams size = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        size.bottomMargin = dp(4);
        actions.addView(row.view, size);
        return row;
    }

    private final class Row {
        final LinearLayout view = new LinearLayout(getContext());
        final Slot[] slots = new Slot[2];
        Row() {
            view.setOrientation(HORIZONTAL);
            view.setBaselineAligned(false);
            for (int i = 0; i < slots.length; i++) {
                slots[i] = new Slot();
                LayoutParams slotSize = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
                if (i == 1) slotSize.setMarginStart(dp(6));
                view.addView(slots[i].button, slotSize);
            }
        }
    }

    private final class Slot {
        final Button button = new Button(getContext());
        String label;
        boolean available;
        int icon;
        boolean danger;
        Slot() {
            button.setAllCaps(false);
            button.setTextSize(13);
            button.setLetterSpacing(0);
            button.setTypeface(getResources().getFont(R.font.inter_medium));
            button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            button.setMinWidth(0);
            button.setMinimumWidth(0);
            button.setMinHeight(dp(48));
            button.setMinimumHeight(dp(48));
            button.setPaddingRelative(dp(8), dp(6), dp(8), dp(6));
            button.setCompoundDrawablePadding(dp(6));
            button.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE);
            button.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
            button.setStateListAnimator(null);
            button.setBackground(new RippleDrawable(ColorStateList.valueOf(theme.line), rounded(theme.surface, 10), null));
            button.setOnClickListener(v -> {
                if (!tracking && available && label != null && button.getVisibility() == VISIBLE) action.accept(label);
            });
        }

        void bind(MenuItem item) {
            String value = item.getTitle().toString();
            if (!value.equals(label)) {
                label = value;
                button.setText(value);
            }
            int nextIcon = matchingIcon(value);
            boolean nextDanger = nextIcon == R.drawable.ic_delete;
            if (nextIcon != icon || danger != nextDanger) {
                icon = nextIcon;
                danger = nextDanger;
                int ink = danger ? (theme.dark ? 0xFFFFA5A5 : 0xFF9C2424) : theme.ink;
                button.setTextColor(ink);
                Drawable artwork = getContext().getDrawable(icon);
                if (artwork != null) {
                    artwork = artwork.mutate();
                    artwork.setBounds(0, 0, dp(17), dp(17));
                    artwork.setTint(ink);
                }
                button.setCompoundDrawablesRelative(artwork, null, null, null);
            }
            available = item.isEnabled();
            button.setEnabled(available);
            button.setClickable(available && !tracking);
            button.setFocusable(available && !tracking);
            button.setAlpha(available ? 1f : .45f);
            button.setVisibility(VISIBLE);
        }

        void clear() {
            label = null;
            available = false;
            button.setClickable(false);
            button.setFocusable(false);
            button.setVisibility(INVISIBLE); // Keep the left column's width in a short last row.
        }
    }

    private TextView text(int sp, int color) {
        TextView text = new TextView(getContext());
        text.setTextSize(sp);
        text.setTextColor(color);
        text.setTypeface(getResources().getFont(R.font.inter));
        text.setIncludeFontPadding(false);
        text.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE);
        text.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        text.setTextDirection(TEXT_DIRECTION_FIRST_STRONG);
        return text;
    }

    void setFolderSize(FileEntry entry,String label){
        if(bound==entry&&entry.directory)metadata.setText(details(entry,label));
    }

    private String details(FileEntry entry,String measured) {
        String size = measured!=null?measured:entry.directory && entry.size <= 0 ? "Calculating size…"
                : entry.size < 0 ? "Size unavailable" : Formatter.formatShortFileSize(getContext(), entry.size);
        String modified = entry.modified > 0 ? "Modified " + dates.format(new Date(entry.modified)) : "Modified date unavailable";
        return StorageMapView.typeLabel(entry) + "  ·  " + size + "  ·  " + modified;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private GradientDrawable outline(int color, int radius) {
        GradientDrawable shape = rounded(color, radius);
        shape.setStroke(dp(1), theme.line);
        return shape;
    }

    private int dp(int value) {
        Resources resources = getResources();
        return Math.round(value * resources.getDisplayMetrics().density);
    }

    private static int matchingIcon(String label) {
        String word = label.toLowerCase(Locale.ROOT);
        if (word.contains("delete") || word.contains("trash")) return R.drawable.ic_delete;
        if (word.contains("paste")) return R.drawable.ic_paste;
        if (word.contains("copy")) return R.drawable.ic_copy;
        if (word.contains("move") || word.contains("cut")) return R.drawable.ic_cut;
        if (word.contains("rename")) return R.drawable.ic_rename;
        if (word.contains("share")) return R.drawable.ic_share;
        if (word.contains("select")) return R.drawable.ic_select;
        if (word.contains("property") || word.contains("properties") || word.contains("info"))
            return R.drawable.ic_document;
        if (word.contains("guide")) return R.drawable.ic_document;
        if (word.contains("minimize")) return R.drawable.ic_preview_exit_focus;
        if (word.contains("install apk") || word.contains("with") || word.contains("another app")) return R.drawable.ic_app;
        if (word.contains("extract") || word.contains("archive")) return R.drawable.ic_archive;
        if (word.contains("folder") || word.contains("location") || word.contains("anything files")) return R.drawable.ic_folder;
        if (word.contains("open") || word.contains("preview")) return R.drawable.ic_file;
        return R.drawable.ic_more;
    }
}
