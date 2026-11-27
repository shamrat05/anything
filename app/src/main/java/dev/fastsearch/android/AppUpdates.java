package dev.fastsearch.android;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Local update details. Opens only a selected, fixed official URL; never downloads or installs itself. */
final class AppUpdates {
    private AppUpdates() {}

    static UpdateRoute.Edition installed(Activity activity) {
        return UpdateRoute.installed(activity.getPackageName(), BuildConfig.FLAVOR, BuildConfig.DEBUG);
    }

    static AppSheet dialog(Activity activity) {
        return dialog(activity, installed(activity), activity::startActivity);
    }

    static AppSheet dialog(Activity activity, UpdateRoute.Edition edition, Launcher launch) {
        AppSheet sheet = new AppSheet(activity, "App updates");
        TextView version = sheet.text("Installed version " + BuildConfig.VERSION_NAME
                + " · build " + BuildConfig.VERSION_CODE, 16, sheet.theme.ink);
        version.setTypeface(activity.getResources().getFont(R.font.inter_semibold));
        sheet.content.addView(version, new LinearLayout.LayoutParams(-1,-2));
        append(sheet, edition.label);
        if (edition == UpdateRoute.Edition.PLAY) {
            append(sheet, "Google Play will show whether an update is available. Anything doesn’t check in the background.");
        } else if (edition.asset != null) {
            TextView asset = append(sheet, "Update file: " + edition.asset);
            asset.setTextIsSelectable(true);
            append(sheet, "Compare versions in Release notes first. Open the same-edition APK in Android’s installer. Android checks the update signature. Don’t uninstall first.");
        } else if (edition == UpdateRoute.Edition.PREVIEW) {
            append(sheet, "This build doesn’t update from the public channel. Use a matching preview build.");
        } else {
            append(sheet, "The update channel isn’t recognized. Update from the same source you used to install this build.");
        }
        TextView status = append(sheet, "");
        status.setVisibility(View.GONE);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);

        if (edition == UpdateRoute.Edition.PLAY) {
            action(sheet, "Open Google Play", true, () -> renderStatus(status, openPlay(launch),
                    "Couldn’t open Google Play. Open the store and search for Anything."));
        } else if (edition == UpdateRoute.Edition.UNKNOWN) {
            sheet.action("Close", true, false, null);
        } else {
            action(sheet, "Release notes", edition.asset == null, () -> renderStatus(status,
                    openWeb(launch,UpdateRoute.GITHUB_RELEASE), "Couldn’t open GitHub. Try in your browser."));
            if (edition.asset != null) {
                action(sheet, "Get this APK", true, () -> renderStatus(status,
                        openWeb(launch,edition.download()), "Couldn’t open the same-edition download. Try Release notes."));
            }
        }
        return sheet;
    }

    static boolean openPlay(Launcher launch) {
        Intent store = view(UpdateRoute.PLAY_LISTING).setPackage(UpdateRoute.PLAY_PACKAGE);
        return tryStart(launch,store) || openWeb(launch,UpdateRoute.PLAY_LISTING);
    }

    private static boolean openWeb(Launcher launch, String url) {
        return tryStart(launch,view(url));
    }

    private static Intent view(String url) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse(url));
    }

    private static boolean tryStart(Launcher launch, Intent intent) {
        try {
            launch.start(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            return false;
        }
    }

    private static TextView append(AppSheet sheet,String value) {
        TextView line = sheet.text(value,14,sheet.theme.muted);
        line.setPadding(0,sheet.dp(8),0,0);
        sheet.content.addView(line,new LinearLayout.LayoutParams(-1,-2));
        return line;
    }

    private static void action(AppSheet sheet, String label, boolean primary, Runnable click) {
        Button button = sheet.action(label,primary,false,null);
        // Keep the sheet visible under the browser/store, or so a launch error can be read.
        button.setOnClickListener(v -> click.run());
    }

    private static void renderStatus(TextView status, boolean launched, String message) {
        status.setText(launched ? "" : message);
        status.setVisibility(launched ? View.GONE : View.VISIBLE);
    }

    interface Launcher {
        void start(Intent intent);
    }
}
