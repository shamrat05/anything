package dev.fastsearch.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import dev.fastsearch.core.FileEntry;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/** Exercises the same viewer action the user taps and reads the exact bytes in another UID. */
public final class ExternalOpenProbe extends Instrumentation {
    private int checks;
    private Context app;
    private File fixtures;
    private final List<String> tokens = new ArrayList<>();
    private final StringBuilder report = new StringBuilder();

    @Override public void onCreate(Bundle state) { super.onCreate(state); start(); }

    @Override public void onStart() {
        Bundle answer = new Bundle();
        boolean ok = false;
        try {
            app = getTargetContext();
            fixtures = new File(app.getCacheDir(),"ExternalOpenProbe-" + SystemClock.elapsedRealtimeNanos()).getCanonicalFile();
            require(fixtures.mkdir(),"create fixture folder");
            require(getContext().getPackageManager().getApplicationInfo(getContext().getPackageName(),0).uid
                    != app.getApplicationInfo().uid,"test receiver has separate UID");
            File text = new File(fixtures,"Mixed case 日本.TXT");
            Files.write(text.toPath(),new byte[]{97,98,99,10});
            File image = new File(fixtures,"Synthetic.png"); PreviewUiFixtures.image(image);
            File pdf = new File(fixtures,"Synthetic.pdf"); PreviewUiFixtures.pdf(pdf);
            File apk = new File(fixtures,"Synthetic.APK");
            Files.copy(new File(app.getApplicationInfo().sourceDir).toPath(),apk.toPath());
            send(text,"text/plain");
            send(image,"image/png");
            send(pdf,"application/pdf");
            send(apk,ApkInstallAction.MIME);
            ok = true;
        } catch(Throwable e) {
            java.io.StringWriter detail = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(detail));
            report.append(detail);
        } finally {
            if (app != null) {
                android.content.SharedPreferences.Editor grants = app.getSharedPreferences("file-grants",0).edit();
                for (String token : tokens) grants.remove(token);
                grants.commit();
            }
            if(fixtures != null) {
                File[] files = fixtures.listFiles();
                if(files != null) for(File file : files) file.delete();
                fixtures.delete();
            }
        }
        answer.putString("stream",report.append(ok?"PASS ":"FAIL ").append(checks).append(" external app assertions.\n").toString());
        answer.putBoolean("passed",ok);
        finish(ok ? Activity.RESULT_OK : Activity.RESULT_CANCELED,answer);
    }

    private void send(File file,String mime) throws Exception {
        Uri uri = RootFileProvider.issue(app,new FileEntry(file.getAbsolutePath(),false,file.length(),file.lastModified()),false);
        tokens.add(uri.getPathSegments().get(0));
        require(mime.equals(app.getContentResolver().getType(uri)),"provider matches " + mime);
        Intent start = FileViewerActivity.intent(app,uri,file.getName(),file.getAbsolutePath(),file.length())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        FileViewerActivity viewer = (FileViewerActivity)startActivitySync(start);
        try {
            require(waitFor(file.getName()) != null,"viewer opens " + mime);
            if (ApkInstallAction.MIME.equals(mime) && ApkInstallAction.supported()) {
                Rect bounds = waitFor("Install APK");
                require(bounds!=null && bounds.centerY() > viewer.getResources().getDisplayMetrics().heightPixels * .70f
                        && bounds.height() >= 47 * viewer.getResources().getDisplayMetrics().density,
                        "Install APK is a lower touch target at least 48 dp in layout");
            }
            require(click("More file actions"),"open viewer menu");
            require(click("Open in another app"),"tap explicit external open");
            require(click("Anything Synthetic Receiver"),"choose independent receiver for " + mime);
            String expected = "OPEN_OK " + file.getName() + " " + mime + " "
                    + ExternalOpenReceiver.hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath())));
            require(waitFor(expected)!=null,"other UID reads exact bytes without write access: " + mime);
            input("input keyevent 4");
        } finally {
            runOnMainSync(viewer::finish);
            waitForIdleSync();
        }
    }

    private boolean click(String label) throws Exception {
        Rect r = waitFor(label);
        if (r == null) return false;
        input("input tap " + r.centerX() + " " + r.centerY());
        return true;
    }

    private void input(String command) throws Exception {
        try (InputStream out = new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                getUiAutomation().executeShellCommand(command))) {
            while (out.read() != -1) { }
        }
    }

    private Rect waitFor(String label) {
        long until = SystemClock.elapsedRealtime() + 12000;
        do {
            AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                try {
                    List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText(label);
                    try {
                        for (AccessibilityNodeInfo node : found) {
                            if ((!label.contentEquals(String.valueOf(node.getText()))
                                    && !label.contentEquals(String.valueOf(node.getContentDescription()))) || !node.isVisibleToUser()) continue;
                            Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
                            if(!bounds.isEmpty())return bounds;
                        }
                    } finally { for(AccessibilityNodeInfo node : found)node.recycle(); }
                } finally { root.recycle(); }
            }
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < until);
        return null;
    }

    private void require(boolean ok,String check) {
        if (!ok)throw new AssertionError(check);
        checks++;
        report.append("OK ").append(check).append('\n');
    }
}
