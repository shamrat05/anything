package dev.fastsearch.android;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.RotateDrawable;
import android.net.Uri;
import android.os.Build;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Quick search tips, common questions and user-initiated reporting. */
final class HelpSheet {
    private static final String EMAIL = "shamrat@callcraftstudio.com";
    private static final String FORM = "https://callcraftstudio.com/contact/";
    private static final String SUBJECT = "Anything bug report";
    private final Activity activity;
    private final FileIndex index;
    private final AppSheet sheet;
    private final LinearLayout body;
    private final ScrollView scroll;
    private final Button[] tabs = new Button[3];
    private EditText description;
    private CheckBox include;
    private TextView preview;

    HelpSheet(Activity activity, FileIndex index) {
        this.activity = activity;
        this.index = index;
        sheet = new AppSheet(activity, "Help", true);
        LinearLayout row = new LinearLayout(activity);
        row.setPadding(sheet.dp(4), 0, sheet.dp(4), 0);
        row.setBackground(AppSheet.shape(sheet.theme.chrome, sheet.dp(14)));
        String[] titles = {"Search", "FAQ", "Report"};
        String[] descriptions = {"Search help", "Frequently asked questions", "Report a bug"};
        int[] icons = {R.drawable.ic_search, R.drawable.ic_document, R.drawable.ic_message};
        boolean expanded = activity.getResources().getConfiguration().screenWidthDp >= 360
                && activity.getResources().getConfiguration().fontScale <= 1.15f;
        for (int i = 0; i < titles.length; i++) {
            final int page = i;
            Button tab = new Button(activity);
            tab.setAllCaps(false);
            tab.setText(titles[i]);
            tab.setContentDescription(descriptions[i]);
            tab.setTextSize(14);
            tab.setTypeface(activity.getResources().getFont(R.font.inter_medium));
            tab.setMinWidth(0);
            tab.setMinimumWidth(0);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(sheet.dp(4), 0, sheet.dp(4), 0);
            if (expanded) {
                Drawable icon = compactIcon(icons[i]);
                tab.setCompoundDrawablePadding(sheet.dp(5));
                tab.setCompoundDrawablesRelative(icon, null, null, null);
            }
            tab.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setSelected(host.isSelected());
                }
            });
            tab.setOnClickListener(v -> select(page));
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, sheet.dp(48), 1);
            size.setMargins(sheet.dp(2), sheet.dp(3), sheet.dp(2), sheet.dp(3));
            row.addView(tab, size);
            tabs[i] = tab;
        }
        sheet.content.addView(row, new LinearLayout.LayoutParams(-1, -2));
        scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(sheet.dp(12), sheet.dp(12), sheet.dp(12), sheet.dp(20));
        scroll.addView(body);
        sheet.content.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        select(0);
    }

    AppSheet dialog() { return sheet; }

    private void select(int page) {
        for (int i = 0; i < tabs.length; i++) {
            Button tab = tabs[i];
            boolean active = i == page;
            int ink = active ? onAccent() : sheet.theme.ink;
            tab.setSelected(active);
            tab.setTextColor(ink);
            tab.setCompoundDrawableTintList(ColorStateList.valueOf(ink));
            tab.setBackground(actionBackground(active ? sheet.theme.accent : Color.TRANSPARENT));
        }
        body.removeAllViews();
        if (page == 0) {
            paragraph("Search by name", true);
            paragraph("Type any part of a filename. Open Files to browse a folder or Map to see what takes up space.", false);
            tip("photo vacation", "Find names containing both words.");
            tip("ext:jpg;png", "Limit results to JPG or PNG.");
            tip("folder: music", "Find folders named music.");
            tip("size:>10mb", "Find files larger than 10 MB.");
            paragraph("Use quotes for an exact phrase, | for either term, and ! to leave a term out.", false);
        } else if (page == 1) {
            showFaq();
        } else {
            showReport();
        }
        scroll.scrollTo(0, 0);
    }

    private void tip(String example, String explanation) {
        TextView code = sheet.text(example, 14, sheet.theme.ink);
        code.setTypeface(activity.getResources().getFont(R.font.inter_semibold));
        code.setPadding(0, 0, 0, sheet.dp(2));
        body.addView(code, new LinearLayout.LayoutParams(-1, -2));
        paragraph(explanation, false);
    }

    private void showFaq() {
        paragraph("Version " + BuildConfig.VERSION_NAME + " (build " + BuildConfig.VERSION_CODE + ")", false);
        question("Files missing?", "Check the folder and filters first. In the three-dot menu, open Storage → File access, then refresh. A first scan can take a while; Index statistics shows progress.");
        question("How do I update?",
                "Open the three-dot menu → Check for updates. "
                        + "It shows the installed version and the update link for your edition. For a GitHub install, get the same-edition APK and open it in Android’s installer. Don’t uninstall first. Anything doesn’t download or install updates in the background."
                        + (AppUpdates.installed(activity) == UpdateRoute.Edition.PREVIEW ? " This preview stays on its separate update channel." : ""));
        question("Does Anything need root?", "No. Shared storage works without root. Protected folders require root access that you explicitly grant in your root manager.");
        question("A file won’t preview?", "The file may be damaged or its format may not be supported. Use Open with to try another app, or report the issue if the file works elsewhere.");
    }

    private void question(String label, String answer) {
        Button disclosure = button(label, R.drawable.ic_chevron_right, false);
        disclosure.setCompoundDrawablesRelative(null, null, chevron(false), null);
        disclosure.setCompoundDrawableTintList(ColorStateList.valueOf(sheet.theme.muted));
        addActionRow(disclosure);
        TextView text = sheet.text(answer, 14, sheet.theme.muted);
        text.setPadding(sheet.dp(10), sheet.dp(4), sheet.dp(10), sheet.dp(12));
        body.addView(text, new LinearLayout.LayoutParams(-1, -2));
        text.setVisibility(View.GONE);
        disclosure.setContentDescription(label + " Collapsed");
        disclosure.setOnClickListener(v -> {
            boolean expand = text.getVisibility() != View.VISIBLE;
            text.setVisibility(expand ? View.VISIBLE : View.GONE);
            disclosure.setSelected(expand);
            disclosure.setContentDescription(label + (expand ? " Expanded" : " Collapsed"));
            disclosure.setCompoundDrawablesRelative(null, null, chevron(expand), null);
        });
    }

    private void paragraph(String message, boolean heading) {
        TextView text = sheet.text(message, heading ? 17 : 14, heading ? sheet.theme.ink : sheet.theme.muted);
        text.setPadding(0, 0, 0, sheet.dp(12));
        if (heading && Build.VERSION.SDK_INT >= 28) text.setAccessibilityHeading(true);
        body.addView(text, new LinearLayout.LayoutParams(-1, -2));
    }

    private void showReport() {
        paragraph("Tell us what happened", true);
        paragraph("Include the steps, what you expected, and what happened instead. Use sample filenames and leave out private information.", false);
        if (description == null) {
            description = new EditText(activity);
            description.setHint("Steps, expected result, and actual result");
            description.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            description.setFilters(new InputFilter[]{new InputFilter.LengthFilter(SupportLog.DESCRIPTION_LIMIT)});
            description.setMinLines(3);
            description.setTextSize(14);
            description.setTypeface(activity.getResources().getFont(R.font.inter));
            description.setTextColor(sheet.theme.ink);
            description.setHintTextColor(sheet.theme.muted);
            include = new CheckBox(activity);
            include.setText("Include optional diagnostics");
            include.setTextColor(sheet.theme.ink);
            preview = sheet.text("", 13, sheet.theme.ink);
            preview.setTextIsSelectable(true);
            preview.setPadding(sheet.dp(8), sheet.dp(8), sheet.dp(8), sheet.dp(8));
            include.setOnCheckedChangeListener((button, selected) -> updatePreview());
        }
        body.addView(description, new LinearLayout.LayoutParams(-1, -2));
        body.addView(include, new LinearLayout.LayoutParams(-1, -2));
        paragraph("Optional diagnostics include version numbers, index counts and recent app exit reasons. They don’t include paths, searches, contacts or messages. Nothing is sent automatically.", false);
        body.addView(preview, new LinearLayout.LayoutParams(-1, -2));
        updatePreview();
        action("Open email draft", R.drawable.ic_message, true, this::email);
        action("Copy and open web form", R.drawable.ic_document, false, this::form);
        action("Copy report", R.drawable.ic_copy, false, () -> {
            String report = checkedReport();
            if (report != null) copy(report);
        });
    }

    private Drawable compactIcon(int id) {
        Drawable icon = activity.getDrawable(id).mutate();
        icon.setBounds(0, 0, sheet.dp(17), sheet.dp(17));
        return icon;
    }

    private Drawable chevron(boolean expanded) {
        Drawable arrow = compactIcon(R.drawable.ic_chevron_right);
        if (!expanded) return arrow;
        RotateDrawable down = new RotateDrawable();
        down.setDrawable(arrow);
        down.setFromDegrees(90f);
        down.setToDegrees(90f);
        down.setLevel(10_000);
        down.setBounds(0, 0, sheet.dp(17), sheet.dp(17));
        return down;
    }

    private int onAccent() {
        return sheet.theme.dark ? sheet.theme.surface : Color.WHITE;
    }

    private RippleDrawable actionBackground(int fill) {
        return new RippleDrawable(ColorStateList.valueOf(sheet.theme.line),
                AppSheet.shape(fill, sheet.dp(10)), AppSheet.shape(Color.WHITE, sheet.dp(10)));
    }

    private Button button(String label, int icon, boolean primary) {
        Button button = new Button(activity);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(14);
        button.setTypeface(activity.getResources().getFont(R.font.inter_medium));
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setMinHeight(sheet.dp(48));
        button.setPadding(sheet.dp(10), sheet.dp(4), sheet.dp(10), sheet.dp(4));
        button.setBackground(actionBackground(primary ? sheet.theme.accent : sheet.theme.chrome));
        int ink = primary ? onAccent() : sheet.theme.ink;
        button.setTextColor(ink);
        button.setCompoundDrawablesRelative(compactIcon(icon), null, null, null);
        button.setCompoundDrawableTintList(ColorStateList.valueOf(ink));
        button.setCompoundDrawablePadding(sheet.dp(8));
        return button;
    }

    private void addActionRow(Button button) {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.bottomMargin = sheet.dp(6);
        body.addView(button, size);
    }

    private void action(String label, int icon, boolean primary, Runnable action) {
        Button button = button(label, icon, primary);
        button.setOnClickListener(v -> action.run());
        addActionRow(button);
    }

    private void updatePreview() {
        boolean selected = include.isChecked();
        preview.setVisibility(selected ? View.VISIBLE : View.GONE);
        if (!selected) {
            preview.setText("");
            return;
        }
        FileIndex.State state = index.getState();
        preview.setText(SupportLog.limitDiagnostics("Anything " + BuildConfig.VERSION_NAME + " (build " + BuildConfig.VERSION_CODE + ", "
                + (BuildConfig.SMS_ENABLED ? "SMS edition" : "Standard edition") + ")\nAndroid "
                + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\nIndex mode: "
                + (index.isRootEnabled() ? "Root folders" : "Shared storage")
                + "\nShared-storage access: " + (FileIndex.hasAccess(activity) ? "granted" : "not granted")
                + "\nIndex loaded: " + state.loaded + "; scanning: " + state.scanning
                + "; error present: " + !state.error.isEmpty()
                + "\nIndexed entries: " + state.entries.size() + "; watch requests: " + state.watchCount
                + "; access errors: " + state.deniedDirectories + "\nLast scan duration (ms): "
                + state.scanDurationMillis + "\n\nRecent app events (counters only):\n" + SupportLog.snapshot()
                + "\n\n" + exitReasons()));
    }

    private String exitReasons() {
        if (Build.VERSION.SDK_INT < 30) return "App exit reason history requires Android 11 or later.";
        ActivityManager manager = activity.getSystemService(ActivityManager.class);
        if (manager == null) return "App exit reason history unavailable.";
        try {
            StringBuilder summary = new StringBuilder();
            for (ApplicationExitInfo exit : manager.getHistoricalProcessExitReasons(activity.getPackageName(), 0, 3)) {
                if (summary.length() > 0) summary.append(", ");
                switch (exit.getReason()) {
                    case ApplicationExitInfo.REASON_CRASH: summary.append("Java crash"); break;
                    case ApplicationExitInfo.REASON_CRASH_NATIVE: summary.append("native crash"); break;
                    case ApplicationExitInfo.REASON_ANR: summary.append("not responding"); break;
                    case ApplicationExitInfo.REASON_LOW_MEMORY: summary.append("low memory"); break;
                    case ApplicationExitInfo.REASON_USER_REQUESTED: summary.append("user request"); break;
                    default: summary.append("Android reason ").append(exit.getReason()); break;
                }
            }
            return summary.length() == 0 ? "No recent app exits reported by Android." : "Recent Anything exit reasons: " + summary;
        } catch (RuntimeException unavailable) {
            return "App exit reason history unavailable.";
        }
    }

    String checkedReport() {
        String text = description.getText().toString().trim();
        if (text.length() < 10) {
            description.setError("Add at least 10 characters describing what happened");
            description.requestFocus();
            return null;
        }
        return SupportLog.report(text, include.isChecked() ? preview.getText().toString() : "");
    }

    private void email() {
        String report = checkedReport();
        if (report == null) return;
        try {
            activity.startActivity(emailIntent(report));
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            Toast.makeText(activity, "Couldn’t open an email draft. Try the web form or copy the report.", Toast.LENGTH_LONG).show();
        }
    }

    static Intent emailIntent(String report) {
        return new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + EMAIL
                + "?subject=" + Uri.encode(SUBJECT) + "&body=" + Uri.encode(report)));
    }

    private void form() {
        String report = checkedReport();
        if (report == null || !copy(report)) return;
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(FORM)));
            Toast.makeText(activity, "Paste the report into the form message, then review before sending.", Toast.LENGTH_LONG).show();
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            Toast.makeText(activity, "Couldn’t open the web form. The report is still copied.", Toast.LENGTH_LONG).show();
        }
    }

    private boolean copy(String report) {
        ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
        if (clipboard == null) {
            Toast.makeText(activity, "Couldn’t copy the report. Try opening an email draft.", Toast.LENGTH_LONG).show();
            return false;
        }
        ClipData clip = ClipData.newPlainText(SUBJECT, report);
        android.os.PersistableBundle extras = new android.os.PersistableBundle();
        extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
        clip.getDescription().setExtras(extras);
        try {
            clipboard.setPrimaryClip(clip);
        } catch (RuntimeException unavailable) {
            Toast.makeText(activity, "Couldn’t copy the report. Try opening an email draft.", Toast.LENGTH_LONG).show();
            return false;
        }
        Toast.makeText(activity, "Report copied", Toast.LENGTH_SHORT).show();
        return true;
    }
}
