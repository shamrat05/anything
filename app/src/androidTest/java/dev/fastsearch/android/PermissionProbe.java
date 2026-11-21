package dev.fastsearch.android;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Never run on the owner's app. A separate .fresh install tests platform permission UI. */
public final class PermissionProbe extends Instrumentation {
    private MainActivity activity;
    private int assertions;
    private final StringBuilder report = new StringBuilder();
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        boolean passed = false;
        try { exercise(); passed = true; }
        catch (Throwable failure) { java.io.StringWriter trace = new java.io.StringWriter(); failure.printStackTrace(new java.io.PrintWriter(trace)); report.append(trace);captureFailure(); }
        finally {
            if (activity != null) runOnMainSync(activity::finish);
            MainActivity.setPermissionProbe(false);
        }
        Bundle result = new Bundle(); result.putBoolean("passed", passed); result.putInt("assertions", assertions);
        result.putString("stream", report.append(passed ? "PASS " : "FAIL ").append(assertions).append(" fresh-install permission assertions.\n").toString());
        finish(passed ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }
    private void exercise() throws Exception {
        String packageName = getTargetContext().getPackageName();
        check(BuildConfig.DEBUG && packageName.endsWith(".fresh"), "only separate fresh debug app may run this probe");
        check(!FileIndex.hasAccess(getTargetContext()), "file access initially absent");
        check(denied(Manifest.permission.READ_CONTACTS), "contacts initially denied");
        if (BuildConfig.SMS_ENABLED) check(denied(Manifest.permission.READ_SMS), "SMS initially denied");
        android.accessibilityservice.AccessibilityServiceInfo info = getUiAutomation().getServiceInfo();
        info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        getUiAutomation().setServiceInfo(info);
        MainActivity.setPermissionProbe(true);
        activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitForIdleSync();
        check(!FileIndex.get(getTargetContext()).isRootEnabled(), "fresh install defaults to unprivileged storage");
        clickText("Later");
        check(!FileIndex.hasAccess(getTargetContext()), "skipping introduction does not grant file access");
        runOnMainSync(() -> findButton(activity.getWindow().getDecorView(), "Allow file access").performClick());
        await(() -> systemText("Allow access to manage all files") || systemText("Allow access to all files") || systemText("All files access"), "Android all-files settings");
        check(!FileIndex.hasAccess(getTargetContext()), "opening special-access settings alone grants nothing");
        back(); waitForIdleSync();
        check(!FileIndex.hasAccess(getTargetContext()), "denied settings return remains usable");

        // Deny private-data requests. No real contact or SMS query is ever granted here.
        chooseCategory(10);
        runOnMainSync(() -> findButton(activity.getWindow().getDecorView(), "Allow Contacts").performClick());
        denySystemPermission();
        check(denied(Manifest.permission.READ_CONTACTS), "system Contacts denial is respected");
        check(onMain(() -> findButton(activity.getWindow().getDecorView(), "Allow Contacts") != null), "Contacts denial leaves a usable access action");
        if (BuildConfig.SMS_ENABLED) {
            chooseCategory(11);
            runOnMainSync(() -> findButton(activity.getWindow().getDecorView(), "Allow SMS").performClick());
            denySystemPermission();
            check(denied(Manifest.permission.READ_SMS), "system SMS denial is respected");
        } else {
            check(onMain(() -> ((Spinner) activity.findViewById(R.id.category)).getCount() == 11), "Play has no SMS category");
            check(denied(Manifest.permission.READ_SMS), "Play has no SMS access");
        }
        chooseCategory(9);
        await(() -> onMain(() -> activity.findViewById(R.id.results).isShown()), "app search without private-data access");
        check(denied(Manifest.permission.READ_CONTACTS) && !FileIndex.hasAccess(getTargetContext()), "app search works with data permissions denied");

        // Exercise the actual special-access grant, with indexing suppressed in this debug-only process.
        chooseCategory(0);
        runOnMainSync(() -> findButton(activity.getWindow().getDecorView(), "Allow file access").performClick());
        await(() -> systemText("Allow access to manage all files") || systemText("Allow access to all files") || systemText("All files access"), "special access reopened");
        enableAllFiles();
        await(() -> FileIndex.hasAccess(getTargetContext()), "system grants all-files access");
        back();
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            denySystemPermission();
            check(denied(Manifest.permission.POST_NOTIFICATIONS), "optional notification denial respected");
        }
        await(() -> onMain(() -> activity.hasWindowFocus()), "app usable after file grant and notification denial");
        check(FileIndex.hasAccess(getTargetContext()), "file access survives the return from Settings");
        check(!FileIndex.get(getTargetContext()).getState().scanning && FileIndex.get(getTargetContext()).getState().entries.isEmpty(), "probe never scans real files");
        report.append("First-use Later, Android all-files denial/grant, contextual Contacts/SMS denial, permission-free app search and optional notification denial checked. No real contacts, SMS or file rows read.\n");
    }
    private void chooseCategory(int category) { runOnMainSync(() -> ((Spinner) activity.findViewById(R.id.category)).setSelection(category)); waitForIdleSync(); SystemClock.sleep(200); }
    private void enableAllFiles(){
        await(()->{if(FileIndex.hasAccess(getTargetContext()))return true;
            AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();if(root==null)return false;
            try{CharSequence name=root.getPackageName();if(name==null||!name.toString().contains("settings"))return false;
                clickUnchecked(root);
            }finally{root.recycle();}
            SystemClock.sleep(300);return FileIndex.hasAccess(getTargetContext());
        },"system all-files switch enables access");
    }
    private boolean clickUnchecked(AccessibilityNodeInfo node){
        if(node.isCheckable()&&!node.isChecked()&&node.isVisibleToUser()){
            if(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))return true;
            Rect bounds=new Rect();node.getBoundsInScreen(bounds);if(!bounds.isEmpty()){tap(bounds.centerX(),bounds.centerY());return true;}
        }
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo child=node.getChild(i);if(child==null)continue;
            try{if(clickUnchecked(child))return true;}finally{child.recycle();}}
        return false;
    }
    private void captureFailure(){try{
        AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
        if(root!=null){try{String name=String.valueOf(root.getPackageName());report.append("Active permission window: ").append(name).append('\n');
            if(!name.contains("settings")&&!name.contains("permissioncontroller")&&!name.equals(getTargetContext().getPackageName()))return;
            describe(root);}finally{root.recycle();}}
        android.graphics.Bitmap screenshot=getUiAutomation().takeScreenshot();if(screenshot==null)return;
        java.io.File file=new java.io.File(getTargetContext().getExternalFilesDir(null),"permission-failure.png");
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(file)){screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}finally{screenshot.recycle();}
    }catch(Exception failure){report.append("Capture: ").append(failure).append('\n');}}
    private void describe(AccessibilityNodeInfo node){
        if(node.getText()!=null||node.isCheckable()){Rect bounds=new Rect();node.getBoundsInScreen(bounds);report.append(node.getClassName()).append(' ').append(node.getText()).append(" checked=").append(node.isChecked()).append(" clickable=").append(node.isClickable()).append(' ').append(bounds).append('\n');}
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo child=node.getChild(i);if(child!=null)try{describe(child);}finally{child.recycle();}}
    }
    private boolean denied(String permission) { return getTargetContext().checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED; }
    private void denySystemPermission() { clickOne("Don't allow", "Don’t allow", "Deny"); waitForIdleSync(); }
    private void clickText(String value) { clickOne(value); }
    private void clickOne(String... values) {
        await(() -> {
            AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow(); if (root == null) return false;
            try {
                for (String value : values) {
                    List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(value);
                    for (AccessibilityNodeInfo node : nodes) {
                        try {
                            if (node.getText() == null || !value.equalsIgnoreCase(node.getText().toString()) || !node.isVisibleToUser()) continue;
                            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                            Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
                            if (!bounds.isEmpty()) { tap(bounds.centerX(), bounds.centerY()); return true; }
                        } finally { node.recycle(); }
                    }
                }
                return false;
            } finally { root.recycle(); }
        }, "system action " + java.util.Arrays.toString(values)); waitForIdleSync();
    }
    private boolean systemText(String value) {
        AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow(); if (root == null) return false;
        try { List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(value); boolean found = !nodes.isEmpty(); for (AccessibilityNodeInfo node : nodes) node.recycle(); return found; }
        finally { root.recycle(); }
    }
    private void tap(int x, int y) {
        long time = SystemClock.uptimeMillis(); MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(time, time + 20, MotionEvent.ACTION_UP, x, y, 0);
        try { getUiAutomation().injectInputEvent(down, true); getUiAutomation().injectInputEvent(up, true); } finally { down.recycle(); up.recycle(); }
    }
    private void back() {
        long time = SystemClock.uptimeMillis();
        getUiAutomation().injectInputEvent(new KeyEvent(time, time, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0), true);
        getUiAutomation().injectInputEvent(new KeyEvent(time, time + 20, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0), true);
        SystemClock.sleep(300);
    }
    private static Button findButton(View root, String text) {
        if (root instanceof Button && text.contentEquals(((Button) root).getText()) && root.isShown()) return (Button) root;
        if (root instanceof ViewGroup) for (int n = 0; n < ((ViewGroup) root).getChildCount(); n++) { Button found = findButton(((ViewGroup) root).getChildAt(n), text); if (found != null) return found; }
        return null;
    }
    private boolean onMain(BooleanSupplier action) { boolean[] result = new boolean[1]; runOnMainSync(() -> result[0] = action.getAsBoolean()); return result[0]; }
    private void await(BooleanSupplier condition, String description) {
        long deadline = SystemClock.elapsedRealtime() + 20_000;
        while (SystemClock.elapsedRealtime() < deadline) { if (condition.getAsBoolean()) return; SystemClock.sleep(100); }
        throw new AssertionError("Timed out: " + description);
    }
    private void check(boolean condition, String description) { assertions++; if (!condition) throw new AssertionError(description); }
}
