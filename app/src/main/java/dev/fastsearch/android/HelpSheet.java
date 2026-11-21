package dev.fastsearch.android;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** User-initiated reporting; no root prompt, system bugreport, logcat or network permission. */
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
        String[] titles = {"Search", "Troubleshooting", "Report a bug"};
        for (int i = 0; i < titles.length; i++) {
            final int page = i;
            Button tab = new Button(activity);
            tab.setAllCaps(false);
            tab.setText(titles[i]);
            tab.setMinHeight(sheet.dp(52));
            tab.setPadding(sheet.dp(3), 0, sheet.dp(3), 0);
            tab.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setSelected(host.isSelected());
                }
            });
            tab.setOnClickListener(v -> select(page));
            row.addView(tab, new LinearLayout.LayoutParams(0, -2, 1));
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
            tabs[i].setSelected(i == page);
            tabs[i].setTextColor(i == page ? sheet.theme.accent : sheet.theme.ink);
        }
        body.removeAllViews();
        if (page == 0) {
            paragraph("Search by name", true);
            paragraph("photo vacation\next:jpg;png\nfile: report\nfolder: music\nsize:>10mb", false);
            paragraph("Spaces combine terms. Use | for alternatives, ! to exclude, < > to group, and quotes for phrases. * and ? match wildcards.", false);
            paragraph("Tap a folder to search inside it. Use the file-type and extension filters to narrow the results.", false);
        } else if (page == 1) {
            paragraph("Files missing?", true);
            paragraph("Check File access under Storage, confirm the active folder and file type, then use Refresh. The first index may take time. You can see its progress under Index statistics.", false);
            paragraph("Using root storage?", true);
            paragraph("Root indexing requires a grant from your installed root manager. Open Storage → Index scope to confirm the selected mode, and Refresh to check protected folders. Help does not request root.", false);
            paragraph("If the issue persists, note what you expected and try to reproduce it with sample files before opening Report a bug.", false);
        } else {
            showReport();
        }
        scroll.scrollTo(0, 0);
    }

    private void paragraph(String message, boolean heading) {
        TextView text = sheet.text(message, heading ? 18 : 15, heading ? sheet.theme.ink : sheet.theme.muted);
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
        paragraph("Diagnostics list app and Android versions, index counters, recent Anything events, and app exit reasons when Android makes them available. No system logs, file paths, search terms, contacts or messages. Nothing is sent automatically.", false);
        body.addView(preview, new LinearLayout.LayoutParams(-1, -2));
        updatePreview();
        action("Open email draft", this::email);
        action("Copy report and open web form", this::form);
        action("Copy report", () -> {
            String report = checkedReport();
            if (report != null) copy(report);
        });
    }

    private void action(String label, Runnable action) {
        Button button = new Button(activity);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextColor(sheet.theme.ink);
        button.setMinHeight(sheet.dp(48));
        button.setOnClickListener(v -> action.run());
        body.addView(button, new LinearLayout.LayoutParams(-1, -2));
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
                + (BuildConfig.SMS_ENABLED ? "Full" : "Play") + ")\nAndroid "
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
            description.setError("Describe the issue in at least 10 characters");
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
        } catch (ActivityNotFoundException unavailable) {
            Toast.makeText(activity, "No email app found. Try the web form instead.", Toast.LENGTH_LONG).show();
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
        } catch (ActivityNotFoundException unavailable) {
            Toast.makeText(activity, "No browser found. The report is copied.", Toast.LENGTH_LONG).show();
        }
    }

    private boolean copy(String report) {
        ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
        if (clipboard == null) return false;
        ClipData clip = ClipData.newPlainText(SUBJECT, report);
        android.os.PersistableBundle extras = new android.os.PersistableBundle();
        extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
        clip.getDescription().setExtras(extras);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(activity, "Report copied", Toast.LENGTH_SHORT).show();
        return true;
    }
}
