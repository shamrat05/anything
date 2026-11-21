package dev.fastsearch.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import java.util.concurrent.atomic.AtomicReference;

/** Uses a separate debug app and never launches email or a browser. */
public final class HelpProbe extends Instrumentation {
    private int assertions;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        MainActivity activity = null;
        boolean passed = false;
        try {
            check(BuildConfig.DEBUG && getTargetContext().getPackageName().endsWith(".fresh"), "isolated debug app");
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("permissionProbe", true));
            MainActivity host = activity;
            AtomicReference<Throwable> failure = new AtomicReference<>();
            runOnMainSync(() -> {
                try {
                    exercise(host);
                } catch (Throwable error) {
                    failure.set(error);
                }
            });
            if (failure.get() != null) throw failure.get();
            passed = true;
        } catch (Throwable error) {
            result.putString("stream", "\n" + android.util.Log.getStackTraceString(error));
        } finally {
            if (activity != null) {
                MainActivity host = activity;
                runOnMainSync(host::finish);
            }
        }
        result.putBoolean("passed", passed);
        result.putInt("assertions", assertions);
        if (passed) result.putString("stream", "\nPASS " + assertions + " Help assertions.\n");
        finish(passed ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private void exercise(MainActivity host) {
        HelpSheet help = new HelpSheet(host, FileIndex.get(host));
        AppSheet sheet = help.dialog();
        sheet.show();
        try {
            View root = sheet.getWindow().getDecorView();
            check(findText(root, "Search by name") != null, "search visible by default");
            click(root, "Troubleshooting");
            check(findText(root, "Files missing?") != null, "troubleshooting tab");
            click(root, "Report a bug");
            CheckBox optional = (CheckBox) findText(root, "Include optional diagnostics");
            check(optional != null && !optional.isChecked(), "diagnostics off by default");
            EditText description = findEdit(root);
            check(description != null, "report field");
            click(root, "Copy report");
            check(description.getError() != null, "blank report must not send or copy");
            description.setText("Sample steps: search for fixture.txt and check the first result.");
            SupportLog.record(SupportLog.Event.ROOT_SCAN_FINISHED, true, 12, 2);
            optional.performClick();
            TextView preview = (TextView) findPrefix(root, "Anything " + BuildConfig.VERSION_NAME);
            check(preview != null, "diagnostics preview");
            check(preview.getText().toString().contains("ROOT_SCAN_FINISHED [root]: accessErrors=12, traversalErrors=2"),
                    "root diagnostics expose only structured counters");
            click(root, "Search");
            click(root, "Report a bug");
            check(optional.isChecked() && description.getText().toString().contains("fixture.txt"),
                    "report and toggle survive tab changes");
            optional.performClick();
            check(findPrefix(root, "Anything " + BuildConfig.VERSION_NAME) == null, "turning diagnostics off hides preview");
            check(!help.checkedReport().contains("Optional diagnostics"), "plain report excludes diagnostics");
            optional.performClick();
            check(help.checkedReport().contains("--- Optional diagnostics ---"), "opted-in report includes diagnostics");
            String sample = "Find a & b? #1\nExpected a match.";
            Intent email = HelpSheet.emailIntent(sample);
            check(Intent.ACTION_SENDTO.equals(email.getAction()), "email opens a draft");
            String destination = email.getDataString();
            check(destination.startsWith("mailto:shamrat@callcraftstudio.com?subject="), "addressed to CallCraft support");
            check(Uri.decode(destination.split("&body=", 2)[1]).equals(sample), "report punctuation encoded as body text");
        } finally {
            sheet.dismiss();
        }
    }

    private void click(View root, String text) {
        View button = findText(root, text);
        if (button == null || !button.performClick()) throw new AssertionError("Could not click " + text);
    }

    private View findText(View view, String label) { return find(view, label, false); }
    private View findPrefix(View view, String label) { return find(view, label, true); }

    private View find(View view, String label, boolean prefix) {
        if (view instanceof TextView && view.getVisibility() == View.VISIBLE) {
            String text = ((TextView) view).getText().toString();
            if (prefix ? text.startsWith(label) : text.equals(label)) return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View match = find(group.getChildAt(i), label, prefix);
                if (match != null) return match;
            }
        }
        return null;
    }

    private EditText findEdit(View view) {
        if (view instanceof EditText) return (EditText) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                EditText match = findEdit(group.getChildAt(i));
                if (match != null) return match;
            }
        }
        return null;
    }

    private void check(boolean pass, String label) {
        if (!pass) throw new AssertionError(label);
        assertions++;
    }
}
