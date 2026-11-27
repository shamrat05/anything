package dev.fastsearch.android;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.format.Formatter;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.Date;

import dev.fastsearch.core.FileEntry;

/** Shared information panel for results, dual-pane Files, and the viewer. */
final class FileProperties {
    private FileProperties() {}

    static AppSheet show(Activity host, FileEntry entry, String path) {
        return show(host,entry,path,entry.directory?FileIndex.get(host):null);
    }

    static AppSheet show(Activity host, FileEntry entry, String path, FileIndex fileIndex) {
        AppSheet sheet = new AppSheet(host, "Properties");
        UiTheme theme = sheet.theme;
        sheet.content.setPadding(0,0,0,sheet.dp(4));
        LinearLayout summary = new LinearLayout(host);
        summary.setGravity(Gravity.CENTER_VERTICAL);
        summary.setPaddingRelative(sheet.dp(12),sheet.dp(10),sheet.dp(12),sheet.dp(10));
        GradientDrawable plate = AppSheet.shape(theme.selected,sheet.dp(14));
        plate.setStroke(sheet.dp(1),theme.line);
        summary.setBackground(plate);
        ImageView icon = new ImageView(host);
        icon.setImageResource(UiTheme.fileIcon(host,entry));
        icon.setImportantForAccessibility(ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO);
        summary.addView(icon,new LinearLayout.LayoutParams(sheet.dp(30),sheet.dp(30)));
        LinearLayout title = new LinearLayout(host);
        title.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titleSize = new LinearLayout.LayoutParams(0,-2,1f);
        titleSize.setMarginStart(sheet.dp(12));
        summary.addView(title,titleSize);
        TextView name = sheet.text(entry.name,16,theme.ink);
        name.setTypeface(host.getResources().getFont(R.font.inter_semibold));
        if (Build.VERSION.SDK_INT >= 28) name.setAccessibilityHeading(true);
        name.setTextIsSelectable(true);
        title.addView(name);
        title.addView(sheet.text(StorageMapView.typeLabel(entry),12,theme.muted));
        sheet.content.addView(summary,new LinearLayout.LayoutParams(-1,-2));

        String size = entry.directory ? "Calculating…"
                : entry.size < 0 ? "Unavailable" : Formatter.formatFileSize(host,entry.size);
        String modified = entry.modified > 0 ? DateFormat.getDateTimeInstance(
                DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(entry.modified)) : "Unavailable";
        LinearLayout stats = new LinearLayout(host);
        stats.setPadding(0,sheet.dp(10),0,sheet.dp(5));
        LinearLayout sizeField=field(sheet,"Size",size);
        TextView sizeValue=(TextView)sizeField.getChildAt(1);
        stats.addView(sizeField,new LinearLayout.LayoutParams(0,-2,1f));
        LinearLayout.LayoutParams modifiedSize = new LinearLayout.LayoutParams(0,-2,1.4f);
        modifiedSize.setMarginStart(sheet.dp(10));
        stats.addView(field(sheet,"Modified",modified),modifiedSize);
        sheet.content.addView(stats,new LinearLayout.LayoutParams(-1,-2));

        boolean canCopy = path != null && !path.isEmpty();
        LinearLayout location = field(sheet,"Location",canCopy ? path : "Unavailable");
        if (canCopy) {
            TextView copy = sheet.text("Copy path",13,theme.accent);
            copy.setMinHeight(sheet.dp(48));
            copy.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);
            copy.setPaddingRelative(sheet.dp(6),0,sheet.dp(6),0);
            copy.setBackground(new RippleDrawable(ColorStateList.valueOf(theme.line),null,
                    AppSheet.shape(theme.surface,sheet.dp(8))));
            copy.setOnClickListener(v -> {
                ClipboardManager clipboard = host.getSystemService(ClipboardManager.class);
                if (clipboard == null) return;
                clipboard.setPrimaryClip(ClipData.newPlainText("File path",path));
                if (Build.VERSION.SDK_INT < 33) Toast.makeText(host,"Path copied",Toast.LENGTH_SHORT).show();
            });
            location.addView(copy,new LinearLayout.LayoutParams(-2,-2));
        }
        sheet.content.addView(location,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout association=field(sheet,"Associated app","");
        association.setVisibility(android.view.View.GONE);
        sheet.content.addView(association,new LinearLayout.LayoutParams(-1,-2));
        sheet.show();
        boolean root=fileIndex!=null&&fileIndex.isRootEnabled();
        if(AppOwnerBadges.candidate(entry,entry.path(),root)){
            AppFileOwners owners=new AppFileOwners(host,()->association.setVisibility(android.view.View.GONE));
            Runnable stop=owners.request(java.util.Collections.singletonList(entry),root,sheet.dp(24),found->{
                AppFileOwners.Badge badge=found.get(entry.path());
                if(badge==null||!sheet.isShowing()||host.isDestroyed())return;
                boolean folder=badge.source==AppFileOwners.Source.APP_FOLDER;
                TextView caption=(TextView)association.getChildAt(0), value=(TextView)association.getChildAt(1);
                caption.setText(folder?"Associated app":"Media contributor");
                value.setText(badge.label+"\n"+badge.packageName+"\n"+(folder?"Stored in this app’s Android folder. This does not identify the file’s creator.":"Android MediaStore lists this app as the contributor, not necessarily the creator."));
                value.setAccessibilityLiveRegion(TextView.ACCESSIBILITY_LIVE_REGION_POLITE);
                association.setVisibility(android.view.View.VISIBLE);
            });
            summary.addOnAttachStateChangeListener(new android.view.View.OnAttachStateChangeListener(){
                @Override public void onViewAttachedToWindow(android.view.View ignored){}
                @Override public void onViewDetachedFromWindow(android.view.View view){
                    stop.run();owners.close();view.removeOnAttachStateChangeListener(this);
                }
            });
        }
        if(entry.directory&&fileIndex!=null){
            sizeValue.setAccessibilityLiveRegion(TextView.ACCESSIBILITY_LIVE_REGION_POLITE);
            Runnable stop=fileIndex.folderSizes().observe(path,true, measured->{
                if(sheet.isShowing()&&!host.isDestroyed())sizeValue.setText(measured.details(host));
            });
            sizeValue.addOnAttachStateChangeListener(new android.view.View.OnAttachStateChangeListener(){
                @Override public void onViewAttachedToWindow(android.view.View ignored){}
                @Override public void onViewDetachedFromWindow(android.view.View view){
                    stop.run();view.removeOnAttachStateChangeListener(this);
                }
            });
            sheet.setOnDismissListener(closed->stop.run());
        }
        return sheet;
    }

    private static LinearLayout field(AppSheet sheet,String label,String value) {
        LinearLayout field = new LinearLayout(sheet.getContext());
        field.setOrientation(LinearLayout.VERTICAL);
        field.setPaddingRelative(sheet.dp(12),sheet.dp(7),sheet.dp(12),sheet.dp(6));
        field.setBackground(AppSheet.shape(sheet.theme.surface,sheet.dp(10)));
        TextView caption = sheet.text(label,12,sheet.theme.muted);
        caption.setPadding(0,0,0,sheet.dp(3));
        field.addView(caption);
        TextView text = sheet.text(value,14,sheet.theme.ink);
        text.setTextIsSelectable(true);
        field.addView(text,new LinearLayout.LayoutParams(-1,-2));
        return field;
    }
}
