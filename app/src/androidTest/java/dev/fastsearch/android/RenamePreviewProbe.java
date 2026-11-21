package dev.fastsearch.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Spanned;
import dev.fastsearch.core.FileEntry;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Owned fixtures only. Real worker checks plus native rich-preview decoding. */
public final class RenamePreviewProbe extends Instrumentation {
    private Context target;private File fixture;private int checks;private Activity foreground;
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);start();}
    @Override public void onStart(){Bundle result=new Bundle();boolean passed=false;StringBuilder report=new StringBuilder();
        try{target=getTargetContext();if(!target.getPackageName().endsWith(".fresh"))throw new AssertionError("Requires isolated test package");run();passed=true;}
        catch(Throwable failure){StringWriter trace=new StringWriter();failure.printStackTrace(new PrintWriter(trace));report.append(trace);}
        finally{if(foreground!=null)runOnMainSync(foreground::finish);if(fixture!=null)remove(fixture);}
        result.putBoolean("passed",passed);result.putInt("assertions",checks);result.putString("stream",report.append(passed?"PASS ":"FAIL ").append(checks).append(" rename/preview assertions.\n").toString());finish(passed?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
    private void run()throws Exception{
        fixture=new File(target.getCacheDir(),"RenamePreviewProbe-"+SystemClock.elapsedRealtimeNanos()).getCanonicalFile();check(fixture.mkdir(),"fixture");
        File visible=new File(fixture,"probe.txt");write(visible,"Checking file operations");foreground=startActivitySync(FileViewerActivity.intent(target,null,visible.getName(),visible.getPath(),visible.length()).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
        File a=new File(fixture,"a.txt"),b=new File(fixture,"b.txt");write(a,"alpha");write(b,"beta");
        FileClipboard.Snapshot selection=capture(a,b);
        JSONObject blocked=rename(selection,"changed.txt","a.txt");
        check(!blocked.optBoolean("ok"),"occupied target rejected before batch");check(a.exists()&&b.exists()&&!new File(fixture,"changed.txt").exists(),"preflight changes nothing");
        JSONObject renamed=rename(selection,"é one.txt","two.txt");check(renamed.optBoolean("ok"),"batch rename");check(renamed.getJSONArray("completedIndices").length()==2,"acknowledged batch");
        File first=new File(fixture,"é one.txt"),second=new File(fixture,"two.txt");check(read(first).equals("alpha")&&read(second).equals("beta"),"renamed bytes");check(!a.exists()&&!b.exists(),"old names removed");
        check(rename(capture(first),"é one.txt").optBoolean("ok"),"unchanged name supported");
        FileClipboard.Snapshot stale=capture(first);File retained=new File(fixture,"retained.txt");check(first.renameTo(retained),"retain original inode");write(first,"replacement");
        check(!rename(stale,"wrong.txt").optBoolean("ok")&&read(first).equals("replacement"),"stale identity rejected");
        File directory=new File(fixture,"folder");check(directory.mkdir(),"directory fixture");write(new File(directory,"child"),"child");check(rename(capture(directory),"folder new").optBoolean("ok"),"directory rename");check(read(new File(fixture,"folder new/child")).equals("child"),"directory contents retained");
        check(!rename(capture(second),"../escape").optBoolean("ok"),"path traversal rejected");
        String svg="<svg xmlns='http://www.w3.org/2000/svg' width='100' height='50'><rect width='100' height='50' fill='red'/></svg>";
        SvgPreview.Result vector=SvgPreview.read(bytes(svg));check(vector.picture.getWidth()==100&&vector.picture.getHeight()==50,"SVG aspect ratio");
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(100,50,android.graphics.Bitmap.Config.ARGB_8888);new android.graphics.Canvas(bitmap).drawPicture(vector.picture);check(bitmap.getPixel(20,20)==android.graphics.Color.RED,"SVG canvas rendering");bitmap.recycle();
        ByteArrayOutputStream compressed=new ByteArrayOutputStream();try(java.util.zip.GZIPOutputStream gzip=new java.util.zip.GZIPOutputStream(compressed)){gzip.write(svg.getBytes(StandardCharsets.UTF_8));}check(SvgPreview.read(new ByteArrayInputStream(compressed.toByteArray())).picture.getHeight()==50,"SVGZ");
        rejectSvg("<!DOCTYPE svg [<!ENTITY x 'unsafe'>]><svg xmlns='http://www.w3.org/2000/svg'><text>&x;</text></svg>");rejectSvg("<svg xmlns='http://www.w3.org/2000/svg'><image href='file:///private'/></svg>");
        Spanned markdown=RichText.markdown("# Title\n\n**Bold** and [link](https://example.com)\n\n`code`\n");check(markdown.toString().contains("Title")&&!markdown.toString().contains("**"),"Markdown formatted");check(markdown.getSpans(0,markdown.length(),android.text.style.StyleSpan.class).length>0,"Markdown native styles");
        Spanned unsafe=RichText.markdown("[bad](javascript:alert) [file](file:///etc/passwd)");check(unsafe.getSpans(0,unsafe.length(),android.text.style.URLSpan.class).length==0,"unsafe document links rejected");
        check(RichText.html("<p>Hello <b>there</b></p>").toString().contains("Hello there"),"HTML native reader");
    }
    private void rejectSvg(String source)throws Exception{try{SvgPreview.read(bytes(source));throw new AssertionError("SVG should reject");}catch(IOException expected){checks++;}}
    private FileClipboard.Snapshot capture(File... files)throws Exception{List<FileEntry> entries=new ArrayList<>();for(File file:files)entries.add(new FileEntry(file.getPath(),file.isDirectory(),file.length(),file.lastModified()));return FileClipboard.capture(target,entries,true,false);}
    private JSONObject rename(FileClipboard.Snapshot selection,String... names)throws Exception{JSONArray entries=new JSONArray(selection.entries.toString());for(int i=0;i<names.length;i++)entries.getJSONObject(i).put("newName",names[i]);try(FileOperationProcess process=new FileOperationProcess(target,new JSONObject().put("mode","rename").put("entries",entries),false,null)){return process.run();}}
    private static ByteArrayInputStream bytes(String value){return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));}
    private static void write(File file,String value)throws Exception{try(FileOutputStream out=new FileOutputStream(file)){out.write(value.getBytes(StandardCharsets.UTF_8));}}
    private static String read(File file)throws Exception{return FileOperationProcess.readSmall(file,1024);}
    private void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    private static void remove(File file){File[] children=file.listFiles();if(children!=null)for(File child:children)remove(child);file.delete();}
}
