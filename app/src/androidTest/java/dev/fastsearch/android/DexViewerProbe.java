package dev.fastsearch.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.BackgroundColorSpan;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import dev.fastsearch.core.FileEntry;

/** Reserved synthetic test data; no APK is installed or executed. */
public final class DexViewerProbe extends Instrumentation {
    // D8 --min-api 26: package dev.anything.fixture; public final class Alpha {
    // public static int answer() { return 42; } }; Beta.message() returns "Hello".
    private static final String ALPHA = "ZGV4CjAzOACQZ+CtEW6kuWSJVjJUPDu61zEyXXGIs5O0AgAAcAAAAHhWNBIAAAAAAAAAACwCAAAIAAAAcAAAAAQAAACQAAAAAgAAAKAAAAAAAAAAAAAAAAMAAAC4AAAAAQAAANAAAADEAQAA8AAAACgBAAAwAQAAPAEAAD8BAABdAQAAcQEAAHQBAAB8AQAAAgAAAAMAAAAEAAAABQAAAAIAAAAAAAAAAAAAAAUAAAADAAAAAAAAAAEAAQAAAAAAAQAAAAYAAAACAAEAAAAAAAEAAAARAAAAAgAAAAAAAAABAAAAAAAAABwCAAAAAAAAAQAAAAAAAAAgAQAAAwAAABMAKgAPAAAAAQABAAEAAAAkAQAABAAAAHAQAgAAAA4AAwAOAAIADgAGPGluaXQ+AApBbHBoYS5qYXZhAAFJABxMZGV2L2FueXRoaW5nL2ZpeHR1cmUvQWxwaGE7ABJMamF2YS9sYW5nL09iamVjdDsAAVYABmFuc3dlcgCdAX5+RDh7ImJhY2tlbmQiOiJkZXgiLCJjb21waWxhdGlvbi1tb2RlIjoiZGVidWciLCJoYXMtY2hlY2tzdW1zIjpmYWxzZSwibWluLWFwaSI6MjYsInNoYS0xIjoiYTdhZDE4YTcwNDYwYjc5OWQwNDgyZTQ5N2MxMDlhNzViZjdmOTFkZSIsInZlcnNpb24iOiI4LjEwLjktZGV2In0AAAACAACBgASIAgEJ8AEAAAsAAAAAAAAAAQAAAAAAAAABAAAACAAAAHAAAAACAAAABAAAAJAAAAADAAAAAgAAAKAAAAAFAAAAAwAAALgAAAAGAAAAAQAAANAAAAABIAAAAgAAAPAAAAADIAAAAgAAACABAAACIAAACAAAACgBAAAAIAAAAQAAABwCAAAAEAAAAQAAACwCAAA=";
    private static final String BETA = "ZGV4CjAzOAAQc3VbLxOl1hk8WCPBw+sZRoJ7+VbRi27UAgAAcAAAAHhWNBIAAAAAAAAAAEwCAAAKAAAAcAAAAAQAAACYAAAAAgAAAKgAAAAAAAAAAAAAAAMAAADAAAAAAQAAANgAAADcAQAA+AAAADABAAA4AQAAQwEAAEoBAABNAQAAagEAAH4BAACSAQAAlQEAAJ4BAAAEAAAABQAAAAYAAAAHAAAAAwAAAAIAAAAAAAAABwAAAAMAAAAAAAAAAAABAAAAAAAAAAAACAAAAAEAAQAAAAAAAAAAABEAAAABAAAAAAAAAAEAAAAAAAAAPgIAAAAAAAABAAAAAAAAACgBAAADAAAAGgACABEAAAABAAEAAQAAACwBAAAEAAAAcBACAAAADgADAA4AAgAOAAY8aW5pdD4ACUJldGEuamF2YQAFSGVsbG8AAUwAG0xkZXYvYW55dGhpbmcvZml4dHVyZS9CZXRhOwASTGphdmEvbGFuZy9PYmplY3Q7ABJMamF2YS9sYW5nL1N0cmluZzsAAVYAB21lc3NhZ2UAnQF+fkQ4eyJiYWNrZW5kIjoiZGV4IiwiY29tcGlsYXRpb24tbW9kZSI6ImRlYnVnIiwiaGFzLWNoZWNrc3VtcyI6ZmFsc2UsIm1pbi1hcGkiOjI2LCJzaGEtMSI6ImE3YWQxOGE3MDQ2MGI3OTlkMDQ4MmU0OTdjMTA5YTc1YmY3ZjkxZGUiLCJ2ZXJzaW9uIjoiOC4xMC45LWRldiJ9AAAAAgAAgYAEkAIBCfgBCwAAAAAAAAABAAAAAAAAAAEAAAAKAAAAcAAAAAIAAAAEAAAAmAAAAAMAAAACAAAAqAAAAAUAAAADAAAAwAAAAAYAAAABAAAA2AAAAAEgAAACAAAA+AAAAAMgAAACAAAAKAEAAAIgAAAKAAAAMAEAAAAgAAABAAAAPgIAAAAQAAABAAAATAIAAA==";
    private int assertions;
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        StringBuilder report = new StringBuilder();
        boolean passed = false;
        File directory = null;
        try {
            Context target = getTargetContext();
            directory = Files.createTempDirectory(target.getCacheDir().toPath(),"dex-probe-").toFile();
            byte[] alpha = Base64.decode(ALPHA, Base64.NO_WRAP), beta = Base64.decode(BETA, Base64.NO_WRAP);
            File standalone = new File(directory, "sample.dex");
            Files.write(standalone.toPath(), alpha);
            try (FileChannel file = FileChannel.open(standalone.toPath(), StandardOpenOption.READ)) {
                List<DexInspector.Item> classes = DexInspector.read(file,false,DexInspector.MAX_ALL_BYTES);
                check(classes.size() == 1 && "dev.anything.fixture.Alpha".equals(classes.get(0).name), "Read standalone DEX");
                String code = DexInspector.smali(classes.get(0));
                check(code.contains(".class public final Ldev/anything/fixture/Alpha;") && code.contains("answer()I")
                        && code.contains("0x2a"), "Render original class method bytecode");
            }
            File apk = new File(directory, "test.apk");
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(apk.toPath()))) {
                for (int i = 0; i < 2200; i++) put(zip,"assets/item"+i,new byte[]{1});
                put(zip, "assets/classes3.dex", alpha); put(zip,"classes.dex",alpha);
                put(zip, "classes2.dex", beta);
            }
            try (FileChannel file = FileChannel.open(apk.toPath(), StandardOpenOption.READ)) {
                List<DexInspector.Item> classes = DexInspector.read(file,true,DexInspector.MAX_ALL_BYTES);
                check(classes.size() == 2 && "classes2.dex".equals(classes.get(1).dex), "Multidex beyond the normal ZIP listing window");
                check(DexInspector.smali(classes.get(1)).contains("const-string v0, \"Hello\""), "Render second DEX");
            }
            byte[] corrupt = alpha.clone();
            corrupt[96] = (byte) 0xFF; corrupt[97] = (byte) 0xFF;
            File malformed = new File(directory,"bad.dex");
            Files.write(malformed.toPath(),corrupt);
            try (FileChannel file = FileChannel.open(malformed.toPath(), StandardOpenOption.READ)) {
                try {
                    DexInspector.read(file,false,DexInspector.MAX_ALL_BYTES);
                    throw new AssertionError("Malformed file accepted");
                } catch (IOException expected) { check(true,"Malformed DEX rejected"); }
            }
            exportOutput(directory,standalone,alpha);
            FileViewerActivity viewer = (FileViewerActivity) startActivitySync(FileViewerActivity.intent(target,
                    RootFileProvider.issue(target,new FileEntry(standalone.getCanonicalPath(),false,standalone.length(),standalone.lastModified()),false),
                    standalone.getName(),standalone.getPath(),standalone.length()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Instrumentation.ActivityMonitor monitor = addMonitor(DexViewerActivity.class.getName(),null,false);
            await(viewer,"Browse DEX classes");
            runOnMainSync(() -> find(viewer.getWindow().getDecorView(),"Browse DEX classes").performClick());
            Activity inspector = waitForMonitorWithTimeout(monitor,15_000);
            check(inspector != null, "Standalone preview opens inspector");
            await(inspector,"dev.anything.fixture.Alpha");
            AtomicReference<ListView> list = new AtomicReference<>();
            runOnMainSync(() -> list.set(type(inspector.getWindow().getDecorView(), ListView.class)));
            check(list.get() != null, "Class result list shown");
            runOnMainSync(() -> type(inspector.getWindow().getDecorView(), EditText.class).setText("absent"));
            await(inspector,"No matching classes.");
            runOnMainSync(() -> type(inspector.getWindow().getDecorView(), EditText.class).setText("alpha"));
            await(inspector,"dev.anything.fixture.Alpha");
            runOnMainSync(() -> list.get().performItemClick(list.get().getChildAt(0), 0, 0));
            await(inspector,"answer()I");
            runOnMainSync(() -> {
                TextView code = find(inspector.getWindow().getDecorView(),"answer()I");
                check(code.isTextSelectable() && code.getText() instanceof Spanned
                        && ((Spanned) code.getText()).getSpans(0, code.length(), ForegroundColorSpan.class).length > 0,
                        "Selectable colored Smali");
            });
            testSavePicker(target,inspector);
            await(inspector,"Find");
            runOnMainSync(() -> {
                View window = inspector.getWindow().getDecorView();
                find(window,"Find").performClick();
                SmaliCodePane pane = type(window,SmaliCodePane.class);
                check(pane != null && pane.isFinding(),"Smali Find opens inline");
                type(pane,EditText.class).setText(".METHOD");
            });
            await(inspector,"Match 1 of 2");
            runOnMainSync(() -> {
                View window=inspector.getWindow().getDecorView();
                Spanned buffer=(Spanned)find(window,"answer()I").getText();
                BackgroundColorSpan[] highlights=buffer.getSpans(0,buffer.length(),BackgroundColorSpan.class);
                check(highlights.length==1 && buffer.subSequence(buffer.getSpanStart(highlights[0]),buffer.getSpanEnd(highlights[0]))
                        .toString().equals(".method"),"One literal case-insensitive highlight");
                described(window,"Next match").performClick();
            });
            await(inspector,"Match 2 of 2");
            runOnMainSync(() -> described(inspector.getWindow().getDecorView(),"Next match").performClick());
            await(inspector,"Match 1 of 2");
            runOnMainSync(() -> described(inspector.getWindow().getDecorView(),"Previous match").performClick());
            await(inspector,"Match 2 of 2");
            runOnMainSync(() -> {
                View window=inspector.getWindow().getDecorView();
                type(type(window,SmaliCodePane.class),EditText.class).setText("not present");
            });
            await(inspector,"No matches");
            runOnMainSync(() -> {
                View window=inspector.getWindow().getDecorView();
                check(!described(window,"Next match").isEnabled(),"No-match navigation disabled");
                inspector.onBackPressed();
                check(!type(window,SmaliCodePane.class).isFinding(),"First Back closes Find and keeps code open");
                inspector.onBackPressed();
            });
            await(inspector,"dev.anything.fixture.Alpha");
            runOnMainSync(() -> { inspector.finish(); viewer.finish(); });
            multiScope(target,apk);
            crossDexReferences(target,directory);
            passed = true;
        } catch (Throwable failure) {
            java.io.StringWriter trace = new java.io.StringWriter();
            failure.printStackTrace(new java.io.PrintWriter(trace)); report.append(trace);
        } finally {
            if (directory != null) {
                File[] children = directory.listFiles();
                if (children != null) for (File child : children) child.delete();
                directory.delete();
            }
        }
        result.putString("stream",report.append(passed ? "PASS " : "FAIL ").append(assertions).append(" DEX assertions\n").toString());
        result.putBoolean("passed", passed); result.putInt("assertions", assertions);
        finish(passed ? Activity.RESULT_OK : Activity.RESULT_CANCELED,result);
    }
    private void exportOutput(File directory,File source,byte[] original) throws Exception {
        byte[] generated=SmaliExportPlan.bytes(".class public Ldev/anything/fixture/Alpha;\n");
        File newOutput = new File(directory,"Export.smali");
        Files.createFile(newOutput.toPath());
        try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(newOutput,ParcelFileDescriptor.MODE_READ_WRITE)) {
            SmaliDocumentExport.writeEmptyRegular(fd,generated);
        }
        check(java.util.Arrays.equals(generated,Files.readAllBytes(newOutput.toPath())),"Writes exact UTF-8 to empty regular output");
        try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(newOutput,ParcelFileDescriptor.MODE_READ_WRITE)) {
            try { SmaliDocumentExport.writeEmptyRegular(fd,generated); throw new AssertionError("overwrote output"); }
            catch(IOException expected) { check(true,"Refuses a second write to nonempty output"); }
        }
        try(ParcelFileDescriptor ownInput=ParcelFileDescriptor.open(source,ParcelFileDescriptor.MODE_READ_WRITE)) {
            try { SmaliDocumentExport.writeEmptyRegular(ownInput,generated); throw new AssertionError("overwrote original"); }
            catch(IOException expected) { check(true,"Refuses nonempty original input even if FD is writable"); }
        }
        check(java.util.Arrays.equals(original,Files.readAllBytes(source.toPath())),"Original bytes untouched");
        ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createPipe();
        try(ParcelFileDescriptor read=pipe[0]; ParcelFileDescriptor write=pipe[1]) {
            try { SmaliDocumentExport.writeEmptyRegular(write,generated); throw new AssertionError("accepted pipe"); }
            catch(IOException expected) { check(true,"Refuses nonregular output"); }
        }
        try {
            SmaliDocumentExport.write(getTargetContext(),Uri.parse("content://com.google.android.apps.docs.storage/document/mock"),generated);
            throw new AssertionError("accepted cloud authority");
        } catch(IOException expected) { check(true,"Rejects nonlocal destination before provider access"); }
    }

    private void testSavePicker(Context target,Activity activity) {
        Intent validate=SmaliDocumentExport.picker(target,"dev.anything.fixture.Alpha");
        check(validate!=null, "System local Files picker is available for instrumented device");
        check(Intent.ACTION_CREATE_DOCUMENT.equals(validate.getAction()) && validate.getComponent()!=null
                && validate.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY,false)
                && "Alpha.smali".equals(validate.getStringExtra(Intent.EXTRA_TITLE)), "Create a new local .smali document");
        AtomicReference<Intent> launch=new AtomicReference<>();
        AtomicBoolean locked=new AtomicBoolean();
        ActivityMonitor monitor=new ActivityMonitor() {
            @Override public ActivityResult onStartActivity(Intent intent) {
                if(!Intent.ACTION_CREATE_DOCUMENT.equals(intent.getAction()))return null;
                launch.set(new Intent(intent));
                locked.set(!described(activity.getWindow().getDecorView(),"Save Smali as").isEnabled());
                return new ActivityResult(Activity.RESULT_CANCELED,null);
            }
        };
        addMonitor(monitor);
        try {
            runOnMainSync(() -> described(activity.getWindow().getDecorView(),"Save Smali as").performClick());
            Intent intent=launch.get();
            check(intent!=null && locked.get(),"Save launches local picker, not a direct write");
            check(validate.getComponent().equals(intent.getComponent())
                    && "application/octet-stream".equals(intent.getType())
                    && intent.hasCategory(Intent.CATEGORY_OPENABLE)
                    && intent.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY,false)
                    && "Alpha.smali".equals(intent.getStringExtra(Intent.EXTRA_TITLE))
                    && intent.getData()==null && intent.getClipData()==null,
                    "Actual launch targets system Files with local-only metadata and no source URI");
            awaitMain(()->described(activity.getWindow().getDecorView(),"Save Smali as").isEnabled(),
                    "Cancel clears picker lock without writing");
        } finally { removeMonitor(monitor); }
    }

    private void multiScope(Context target,File apk) throws Exception {
        String identity;
        try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(apk,ParcelFileDescriptor.MODE_READ_ONLY)) {
            identity=RootFileProvider.identity(Os.fstat(fd.getFileDescriptor()));
        }
        FileEntry entry=new FileEntry(apk.getCanonicalPath(),false,apk.length(),apk.lastModified());
        Activity inspector=startActivitySync(DexViewerActivity.intent(target,RootFileProvider.issue(target,entry,false),
                apk.getName(),apk.getCanonicalPath(),identity,false,true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(inspector,"2 classes");
        runOnMainSync(() -> type(inspector.getWindow().getDecorView(),EditText.class).setText("beta"));
        await(inspector,"dev.anything.fixture.Beta");
        pickDex(inspector,1,"classes.dex");
        await(inspector,"No matching classes.");
        pickDex(inspector,2,"classes2.dex");
        await(inspector,"dev.anything.fixture.Beta");
        pickDex(inspector,0,"All DEX files");
        await(inspector,"dev.anything.fixture.Beta");
        runOnMainSync(inspector::finish);
    }

    private void crossDexReferences(Context target,File directory) throws Exception {
        String packageName="dev.anything.dexfixture.";
        String descriptor="Ldev/anything/dexfixture/NavigatorTarget;";
        File apk=new File(directory,"reference-navigation.apk");
        try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(apk.toPath()))) {
            dexFixture(zip,"classes.dex","origin");
            dexFixture(zip,"classes2.dex","target");
            dexFixture(zip,"classes3.dex","target");
        }
        byte[] original=Files.readAllBytes(apk.toPath());
        try(FileChannel file=FileChannel.open(apk.toPath(),StandardOpenOption.READ)) {
            DexInspector.Index index=DexInspector.index(file,true,DexInspector.MAX_ALL_BYTES);
            check(index.items.size()==3 && index.targets(descriptor).size()==2,"Two duplicate owners across DEX entries");
            check(index.exact(descriptor,"classes.dex")==null && index.exact(descriptor,"classes2.dex")!=null,
                    "Exact owner lookup is scoped to the chosen DEX");
            check(index.targets("Ljava/lang/Object;").isEmpty(),"External owner is absent from the inspected APK");
            String source=DexInspector.smali(index.exact("Ldev/anything/dexfixture/NavigatorOrigin;","classes.dex"));
            String dest=DexInspector.smali(index.exact(descriptor,"classes2.dex"));
            for(String member:new String[]{"picked(I)I","number:I"}) {
                SmaliReferences.Ref link=SmaliReferences.scan(source).stream()
                        .filter(ref->ref.label().equals(descriptor+"->"+member)).findFirst().orElseThrow(AssertionError::new);
                SmaliReferences.Anchor anchor=SmaliReferences.declaration(dest,link);
                check(anchor!=null && dest.substring(anchor.start,anchor.end).equals(member),"Exact Baksmali member "+member);
            }
        }
        String identity;
        try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(apk,ParcelFileDescriptor.MODE_READ_ONLY)) {
            identity=RootFileProvider.identity(Os.fstat(fd.getFileDescriptor()));
        }
        FileEntry entry=new FileEntry(apk.getCanonicalPath(),false,apk.length(),apk.lastModified());
        Activity activity=startActivitySync(DexViewerActivity.intent(target,RootFileProvider.issue(target,entry,false),
                apk.getName(),apk.getCanonicalPath(),identity,false,true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        View window=activity.getWindow().getDecorView();
        try {
            await(activity,"3 classes");
            await(activity,packageName+"NavigatorOrigin");
            runOnMainSync(()->{
                ListView list=type(window,ListView.class);
                check(list.performItemClick(list.getChildAt(0),0,0),"Open origin from the visible class list");
            });
            await(activity,"linked(I)I");
            AtomicInteger originalY=new AtomicInteger();
            awaitMain(()->{
                SmaliCodePane pane=type(window,SmaliCodePane.class);
                if(pane==null)return false;
                ScrollView scroll=type(pane,ScrollView.class);
                if(scroll==null || scroll.getHeight()==0)return false;
                scroll.scrollTo(0,380);
                originalY.set(scroll.getScrollY());
                return originalY.get()>0;
            },"Synthetic origin has real scroll position");
            runOnMainSync(()->described(window,"Open Smali references").performClick());
            pickReference(activity,descriptor+"->picked(I)I");
            pickReference(activity,"classes2.dex");
            await(activity,packageName+"NavigatorTarget · classes2.dex");
            await(activity,"picked(I)I");
            checkHighlight(window,"picked(I)I");
            runOnMainSync(activity::onBackPressed);
            await(activity,packageName+"NavigatorOrigin · classes.dex");
            await(activity,"linked(I)I");
            awaitMain(()->{
                SmaliCodePane pane=type(window,SmaliCodePane.class);
                return pane!=null && type(pane,ScrollView.class).getScrollY()==originalY.get();
            },"Back restores the original vertical reading position");
            runOnMainSync(()->described(window,"Open Smali references").performClick());
            pickReference(activity,descriptor+"->number:I");
            pickReference(activity,"classes3.dex");
            await(activity,packageName+"NavigatorTarget · classes3.dex");
            await(activity,".field public static number:I");
            checkHighlight(window,"number:I");
            runOnMainSync(activity::onBackPressed);
            await(activity,"linked(I)I");
            runOnMainSync(()->described(window,"Open Smali references").performClick());
            pickReference(activity,"Ljava/lang/Object;");
            await(activity,packageName+"NavigatorOrigin · classes.dex");
            await(activity,"linked(I)I");
            runOnMainSync(activity::onBackPressed);
            await(activity,"3 classes");
            check(java.util.Arrays.equals(original,Files.readAllBytes(apk.toPath())),"Reference navigation did not alter the selected APK");
        } finally { runOnMainSync(activity::finish); }
    }

    private void dexFixture(ZipOutputStream zip,String member,String fixture) throws IOException {
        try(InputStream input=getContext().getAssets().open("dex-reference/"+fixture+".dex")) {
            zip.putNextEntry(new ZipEntry(member));
            byte[] buffer=new byte[4096];
            int n;
            while((n=input.read(buffer))!=-1)zip.write(buffer,0,n);
            zip.closeEntry();
        }
    }

    private void pickReference(Activity activity,String label) {
        awaitMain(()->{
            try {
                java.lang.reflect.Field field=DexViewerActivity.class.getDeclaredField("referenceDialog");
                field.setAccessible(true);
                AlertDialog dialog=(AlertDialog)field.get(activity);
                if(dialog==null || !dialog.isShowing() || dialog.getListView()==null)return false;
                ListView list=dialog.getListView();
                for(int i=0;i<list.getCount();i++)if(label.equals(list.getItemAtPosition(i)))
                    return list.performItemClick(list.getChildAt(i-list.getFirstVisiblePosition()),i,i);
                return false;
            } catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
        },"Choose "+label);
    }

    private void checkHighlight(View window,String signature) {
        runOnMainSync(()->{
            TextView code=find(window,signature);
            Spanned buffer=(Spanned)code.getText();
            BackgroundColorSpan[] spans=buffer.getSpans(0,buffer.length(),BackgroundColorSpan.class);
            check(spans.length==1 && signature.contentEquals(buffer.subSequence(buffer.getSpanStart(spans[0]),
                    buffer.getSpanEnd(spans[0]))),"Highlighted exact declaration "+signature);
        });
    }

    private void awaitMain(BooleanSupplier condition,String description) {
        long end=SystemClock.uptimeMillis()+15_000;
        AtomicBoolean passed=new AtomicBoolean();
        while(SystemClock.uptimeMillis()<end) {
            runOnMainSync(()->passed.set(condition.getAsBoolean()));
            if(passed.get()){check(true,description);return;}
            SystemClock.sleep(50);
        }
        throw new AssertionError("Missing: "+description);
    }

    private void pickDex(Activity activity,int index,String expected) {
        runOnMainSync(() -> {
            View window=activity.getWindow().getDecorView();
            String current=index==1?"All DEX files":index==2?"classes.dex":"classes2.dex";
            View button=described(window,"Filter by DEX file: "+current);
            check(button!=null && button.isShown(),"DEX picker available in APK");
            button.performClick();
            try {
                java.lang.reflect.Field field=DexViewerActivity.class.getDeclaredField("dexDialog");
                field.setAccessible(true);
                AlertDialog dialog=(AlertDialog)field.get(activity);
                check(dialog!=null && dialog.isShowing(),"DEX picker shows list");
                ListView list=dialog.getListView();
                check(expected.equals(list.getAdapter().getItem(index).toString()),"Expected native chooser row");
                list.performItemClick(list.getChildAt(index),index,index);
            } catch(ReflectiveOperationException unavailable) { throw new AssertionError(unavailable); }
        });
    }

    private static void put(ZipOutputStream zip,String name,byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name)); zip.write(data); zip.closeEntry();
    }
    private void check(boolean condition,String message) { if (!condition) throw new AssertionError(message); assertions++; }
    private void await(Activity activity,String text) {
        long end = SystemClock.uptimeMillis()+15000;
        while (SystemClock.uptimeMillis() < end) {
            AtomicReference<TextView> hit = new AtomicReference<>();
            runOnMainSync(() -> hit.set(find(activity.getWindow().getDecorView(),text)));
            if (hit.get() != null) { check(true,text); return; }
            SystemClock.sleep(100);
        }
        throw new AssertionError("Missing: " + text);
    }
    private static TextView find(View root,String text) {
        if (root.getVisibility() != View.VISIBLE) return null;
        if (root instanceof TextView && ((TextView) root).getText().toString().contains(text)) return (TextView) root;
        if (root instanceof ViewGroup) for (int i=0;i<((ViewGroup)root).getChildCount();i++) {
            TextView found = find(((ViewGroup)root).getChildAt(i),text); if (found != null) return found;
        }
        return null;
    }
    private static View described(View root,String label) {
        if (label.contentEquals(root.getContentDescription()==null?"":root.getContentDescription())) return root;
        if (root instanceof ViewGroup) for (int i=0;i<((ViewGroup)root).getChildCount();i++) {
            View found=described(((ViewGroup)root).getChildAt(i),label); if(found!=null)return found;
        }
        return null;
    }
    private static <T> T type(View root,Class<T> type) {
        if (type.isInstance(root)) return type.cast(root);
        if (root instanceof ViewGroup) for (int i=0;i<((ViewGroup)root).getChildCount();i++) {
            T found = type(((ViewGroup)root).getChildAt(i),type); if (found != null) return found;
        }
        return null;
    }
}
