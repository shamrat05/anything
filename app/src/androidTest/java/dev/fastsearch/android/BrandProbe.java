package dev.fastsearch.android;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;

public final class BrandProbe extends Instrumentation {
    private int assertions;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        Throwable[] failure = new Throwable[1];
        View[] rule = new View[1];
        Context context = getTargetContext();
        runOnMainSync(() -> {
            SharedPreferences prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE);
            boolean hadTheme = prefs.contains("theme");
            int before = prefs.getInt("theme", UiTheme.DEFAULT_MODE);
            try {
                for (int theme : new int[]{1, 2}) {
                    prefs.edit().putInt("theme", theme).apply();
                    check(UiTheme.fileTint(context, 2) == null, "folder gradient is not tinted");
                    check(UiTheme.fileTint(context, 0) != null, "universal filter keeps its tint");
                    checkFileMarks(context);
                    Bitmap icon = paint(context.getDrawable(UiTheme.fileIcon(context, 2)), 24, 24);
                    check(Color.alpha(icon.getPixel(0, 0)) == 0 && Color.alpha(icon.getPixel(6, 5)) == 255
                            && Color.alpha(icon.getPixel(19, 5)) == 0, "folder silhouette has a visible tab");
                    check(icon.getPixel(7, 6) != icon.getPixel(7, 12), "front panel is distinct from the tab");
                    int left = icon.getPixel(4, 18), right = icon.getPixel(20, 12);
                    check(Color.alpha(left) == 255 && Color.alpha(right) == 255, "opaque at actual icon size");
                    check(Color.green(left) < 120 && Color.green(right) > 155 && Color.blue(right) > 190,
                            String.format(java.util.Locale.ROOT,
                                    "indigo through blue to cyan (theme %d: %08X to %08X)", theme, left, right));
                    try (java.io.FileOutputStream png = new java.io.FileOutputStream(
                            new java.io.File(context.getCacheDir(), "brand-folder-" + theme + ".png"))) {
                        check(icon.compress(Bitmap.CompressFormat.PNG, 100, png), "24dp folder render captured");
                    }
                    icon.recycle();
                }
                checkLauncher(context);
                rule[0] = BrandAccent.rule(context);
                check(rule[0].getImportantForAccessibility() == View.IMPORTANT_FOR_ACCESSIBILITY_NO,
                        "decorative rule not exposed to TalkBack");
                Bitmap strip = paint(rule[0].getBackground(), 56, 2);
                check(Color.green(strip.getPixel(3, 1)) < Color.green(strip.getPixel(53, 1)),
                        "rule transitions left to right");
                strip.recycle();
                BrandAccent.reveal(rule[0]);
                if (!ValueAnimator.areAnimatorsEnabled())
                    check(settled(rule[0]), "reduced motion settles immediately");
            } catch (Throwable t) {
                failure[0] = t;
            } finally {
                if (hadTheme) prefs.edit().putInt("theme", before).apply();
                else prefs.edit().remove("theme").apply();
            }
        });
        if (failure[0] == null) {
            SystemClock.sleep(450);
            runOnMainSync(() -> {
                try { check(settled(rule[0]), "brief animation settles at full size and opacity"); }
                catch (Throwable t) { failure[0] = t; }
            });
        }
        boolean passed = failure[0] == null;
        result.putBoolean("passed", passed);
        result.putString("stream", passed ? "PASS " + assertions + " brand assertions\n"
                : android.util.Log.getStackTraceString(failure[0]));
        finish(passed ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private void checkFileMarks(Context context) {
        String[] names = {"README", "backup.ZIP", "setup.exe", "install.sh", "Camera.APK", "Camera.apks",
                "image.webp", "video.mkv", "music.opus", "report.PDF", "letter.docx", "letter.DOC", "readme.txt", "notes.md"};
        int[] expected = {R.drawable.ic_type_file, R.drawable.ic_type_archive, R.drawable.ic_type_executable,
                R.drawable.ic_type_executable, R.drawable.ic_type_apk, R.drawable.ic_type_apk,
                R.drawable.ic_type_photo, R.drawable.ic_type_video, R.drawable.ic_type_audio,
                R.drawable.ic_type_pdf, R.drawable.ic_type_word, R.drawable.ic_type_word,
                R.drawable.ic_type_text, R.drawable.ic_type_document};
        for (int i = 0; i < names.length; i++) {
            dev.fastsearch.core.FileEntry entry = new dev.fastsearch.core.FileEntry("/Download/" + names[i], false, 100, 0);
            check(UiTheme.fileIcon(context, entry) == expected[i], names[i] + " resolves to its file mark");
        }
        check(UiTheme.fileIcon(context, 9) == R.drawable.ic_type_app
                && UiTheme.fileIcon(context, 10) == R.drawable.ic_type_contact, "distinct installed-app and contact marks");
        for (int type = 1; type < FileTypes.LABELS.length; type++) {
            check(UiTheme.fileTint(context, type) == null, "type " + type + " preserves original color planes");
            Bitmap mark = paint(context.getDrawable(UiTheme.fileIcon(context, type)), 24, 24);
            java.util.HashSet<Integer> colors = new java.util.HashSet<>();
            for (int y = 0; y < 24; y++) for (int x = 0; x < 24; x++) {
                int pixel = mark.getPixel(x, y);
                if (Color.alpha(pixel) == 255) colors.add(pixel);
            }
            check(Color.alpha(mark.getPixel(0, 0)) == 0 && colors.size() > 1,
                    "type " + type + " reads as a multi-color mark on a transparent background");
            mark.recycle();
        }
    }

    private void checkLauncher(Context context) throws Exception {
        LauncherApps launcher = context.getSystemService(LauncherApps.class);
        check(launcher != null, "Android launcher service available");
        java.util.List<LauncherActivityInfo> entries = launcher.getActivityList(
                context.getPackageName(), android.os.Process.myUserHandle());
        LauncherActivityInfo entry = null;
        for (LauncherActivityInfo candidate : entries) {
            if (MainActivity.class.getName().equals(candidate.getComponentName().getClassName())) {
                entry = candidate;
                break;
            }
        }
        check(entry != null, "installed Anything preview is registered with Android launcher");
        Drawable installed = entry.getIcon(context.getResources().getDisplayMetrics().densityDpi);
        check(installed instanceof AdaptiveIconDrawable, "installed launcher icon uses the native mask");
        AdaptiveIconDrawable adaptive = (AdaptiveIconDrawable) installed;
        check(!adaptive.getIconMask().isEmpty(), "Android provides the device-specific launcher shape");
        if (Build.VERSION.SDK_INT >= 33)
            check(adaptive.getMonochrome() != null, "themed launcher keeps the monochrome search mark");
        checkLauncherRender(context, "adaptive", adaptive, true);
        checkLauncherRender(context, "legacy", context.getDrawable(R.drawable.ic_launcher_legacy), false);
    }

    private void checkLauncherRender(Context context, String type, Drawable drawable, boolean masked)
            throws java.io.IOException {
        Bitmap bitmap = paint(drawable, 108, 108);
        try {
            int left = bitmap.getPixel(9, 54), center = bitmap.getPixel(55, 70), right = bitmap.getPixel(98, 54);
            check(Color.alpha(left) == 255 && Color.alpha(right) == 255,
                    type + " launcher has no edge gaps");
            check(Color.green(left) < Color.green(center) && Color.green(center) < Color.green(right),
                    type + " launcher keeps the subtle indigo-to-cyan transition");
            check(masked || Color.alpha(bitmap.getPixel(0, 0)) == 255, "legacy launcher covers the square fallback");
            int white = 0;
            for (int y = 20; y < 89; y++) for (int x = 20; x < 89; x++) {
                int pixel = bitmap.getPixel(x, y);
                if (Color.red(pixel) > 235 && Color.green(pixel) > 235 && Color.blue(pixel) > 235) white++;
            }
            check(white > 160, type + " launcher keeps the recognizable white search mark");
            try (java.io.FileOutputStream png = new java.io.FileOutputStream(
                    new java.io.File(context.getCacheDir(), "brand-launcher-" + type + ".png"))) {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, png), type + " launcher render captured");
            }
        } finally { bitmap.recycle(); }
    }

    private static Bitmap paint(Drawable drawable, int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        drawable.setBounds(0, 0, width, height);
        drawable.draw(new Canvas(bitmap));
        return bitmap;
    }

    private static boolean settled(View rule) {
        return rule.getAlpha() == 1f && rule.getScaleX() == 1f;
    }

    private void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        assertions++;
    }
}
