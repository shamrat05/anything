package dev.fastsearch.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
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
                    appUpdates(host);
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
            click(root, "FAQ");
            check(findText(root, "Files missing?") != null, "FAQ finds file-access help");
            click(root, "How do I update?");
            check(findPrefix(root, "Open the three-dot menu → Check for updates.") != null, "FAQ points to the main update command");
            check(findText(root, "Check for updates") == null, "no duplicated update action");
            click(root, "How do I update?");
            check(findPrefix(root, "Open the three-dot menu → Check for updates.") == null, "FAQ can collapse an answer");
            click(root, "Report");
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
            click(root, "Report");
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

    private void appUpdates(MainActivity host) throws Exception {
        // Drive MainActivity's actual overflow action; opening it is strictly local.
        java.lang.reflect.Method dispatch = MainActivity.class.getDeclaredMethod("action",String.class);
        dispatch.setAccessible(true);
        dispatch.invoke(host,"Check for updates");
        java.lang.reflect.Field active = MainActivity.class.getDeclaredField("activeSheet");
        active.setAccessible(true);
        AppSheet actual = (AppSheet) active.get(host);
        check(actual!=null&&actual.isShowing(),"main overflow opens local update sheet");
        try {
            View root = actual.getWindow().getDecorView();
            check(findText(root,"Installed version "+BuildConfig.VERSION_NAME+" · build "+BuildConfig.VERSION_CODE)!=null,
                    "shows the installed version and build");
            check(findText(root,"Preview build")!=null,"separate package displays preview edition");
            check(findText(root,"Get this APK")==null&&findText(root,"Open Google Play")==null,
                    "preview cannot select an update for the production package");
        } finally { actual.dismiss(); }

        for(UpdateRoute.Edition edition:new UpdateRoute.Edition[]{UpdateRoute.Edition.STANDARD,UpdateRoute.Edition.SMS}) {
            List<Intent> sent = new ArrayList<>();
            AppSheet sheet = AppUpdates.dialog(host,edition,sent::add);
            sheet.show();
            try {
                View root = sheet.getWindow().getDecorView();
                check(sent.isEmpty(),"showing the sheet never launches an external app");
                check(findText(root,edition.label)!=null,"shows correct GitHub edition");
                check(findText(root,"Update file: "+edition.asset)!=null,"shows exact matching APK");
                click(root,"Release notes");
                check(sent.size()==1&&UpdateRoute.GITHUB_RELEASE.equals(sent.get(0).getDataString()),
                        "release notes are a separate action");
                click(root,"Get this APK");
                check(sent.size()==2&&Intent.ACTION_VIEW.equals(sent.get(1).getAction())
                        &&edition.download().equals(sent.get(1).getDataString())
                        &&sent.get(1).getPackage()==null&&sent.get(1).getFlags()==0,
                        "explicit download opens only the official same-edition HTTPS URL");
            } finally { sheet.dismiss(); }
        }

        List<Intent> opened = new ArrayList<>();
        AppSheet play = AppUpdates.dialog(host,UpdateRoute.Edition.PLAY,intent->{
            opened.add(intent);
            if(intent.getPackage()!=null)throw new ActivityNotFoundException("synthetic missing store");
        });
        play.show();
        try {
            View root = play.getWindow().getDecorView();
            check(findText(root,"Get this APK")==null,"Play edition offers no GitHub APK");
            click(root,"Open Google Play");
            check(opened.size()==2&&UpdateRoute.PLAY_PACKAGE.equals(opened.get(0).getPackage())
                    &&opened.get(1).getPackage()==null
                    &&opened.stream().allMatch(intent->UpdateRoute.PLAY_LISTING.equals(intent.getDataString())),
                    "missing Google Play app falls back only to its exact HTTPS listing");
        } finally { play.dismiss(); }
        List<Intent> store = new ArrayList<>();
        check(AppUpdates.openPlay(store::add)&&store.size()==1
                &&UpdateRoute.PLAY_PACKAGE.equals(store.get(0).getPackage()),
                "available store is addressed directly without launching a fallback");

        AppSheet unavailable = AppUpdates.dialog(host,UpdateRoute.Edition.STANDARD, intent->{
            throw new ActivityNotFoundException("synthetic missing browser");
        });
        unavailable.show();
        try {
            View root = unavailable.getWindow().getDecorView();
            click(root,"Get this APK");
            check(findPrefix(root,"Couldn’t open the same-edition download.")!=null
                    &&unavailable.isShowing(),"no browser shows a readable error instead of switching editions");
        } finally { unavailable.dismiss(); }
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
