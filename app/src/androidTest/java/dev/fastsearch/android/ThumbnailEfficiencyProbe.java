package dev.fastsearch.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.pdf.PdfDocument;
import android.os.Bundle;
import android.os.Debug;
import android.os.SystemClock;
import dev.fastsearch.core.FileEntry;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONObject;

/** Actual old/new decoders and disk encoders on identical owned raster/video/document fixtures. */
public final class ThumbnailEfficiencyProbe extends Instrumentation {
    private Context target;private Activity foreground;private File fixture;private int assertions;
    private android.net.Uri mediaFixture;private File mediaDirectory;
    private final List<String> paths=new ArrayList<>();private final List<Engine> engines=new ArrayList<>();
    private final StringBuilder report=new StringBuilder();
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){boolean passed=false;
        try{target=getTargetContext();if(!target.getPackageName().endsWith(".fresh"))throw new AssertionError("Isolated test app required");run();passed=true;}
        catch(Throwable failure){java.io.StringWriter trace=new java.io.StringWriter();failure.printStackTrace(new java.io.PrintWriter(trace));report.append(trace);}
        finally{for(Engine engine:engines)try{engine.close();}catch(Exception ignored){}if(target!=null)ThumbnailStore.invalidate(target,paths);if(mediaFixture!=null)target.getContentResolver().delete(mediaFixture,null,null);if(mediaDirectory!=null)mediaDirectory.delete();if(fixture!=null)remove(fixture);if(foreground!=null)runOnMainSync(foreground::finish);}
        Bundle result=new Bundle();result.putBoolean("passed",passed);result.putInt("assertions",assertions);result.putString("stream",report.toString());finish(passed?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
    private void run()throws Exception{
        foreground=startActivitySync(new android.content.Intent(target,MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("permissionProbe",true));
        fixture=new File(target.getCacheDir(),"ThumbnailEfficiency-"+SystemClock.elapsedRealtimeNanos()).getCanonicalFile();check(fixture.mkdir(),"owned fixture");
        File jpeg=new File(fixture,"photo.jpg");try(java.io.InputStream in=getContext().getAssets().open("samples/Earth.jpg")){java.nio.file.Files.copy(in,jpeg.toPath());}
        Bitmap original=BitmapFactory.decodeFile(jpeg.getPath());Bitmap large=Bitmap.createBitmap(4096,3072,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(large);canvas.drawBitmap(original,null,new Rect(0,0,4096,3072),new Paint(Paint.FILTER_BITMAP_FLAG));original.recycle();large.setHasAlpha(false);
        File opaque=new File(fixture,"opaque.png"),alpha=new File(fixture,"alpha.png"),webp=new File(fixture,"photo.webp");save(large,opaque);large.setHasAlpha(true);Paint transparent=new Paint();transparent.setColor(Color.TRANSPARENT);transparent.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC));canvas.drawRect(0,0,512,512,transparent);save(large,alpha);try(FileOutputStream out=new FileOutputStream(webp)){check(large.compress(Bitmap.CompressFormat.WEBP,85,out),"WebP fixture");}large.recycle();
        File pdf=new File(fixture,"page.pdf");PdfDocument document=new PdfDocument();try{
            PdfDocument.Page page=document.startPage(new PdfDocument.PageInfo.Builder(600,800,1).create());Paint ink=new Paint();ink.setColor(Color.BLACK);ink.setTextSize(16);
            for(int y=40;y<780;y+=24)page.getCanvas().drawText("Preview row "+y,24,y,ink);document.finishPage(page);try(FileOutputStream out=new FileOutputStream(pdf)){document.writeTo(out);}}finally{document.close();}
        File video=new File(fixture,"video.mp4");try(java.io.InputStream in=getContext().getAssets().open("samples/Earthrise.mp4")){java.nio.file.Files.copy(in,video.toPath());}
        File svg=new File(fixture,"vector.svg"),svgz=new File(fixture,"vector.svgz"),gif=new File(fixture,"still.gif");byte[] vector="<svg xmlns='http://www.w3.org/2000/svg' width='1000' height='800'><rect width='800' height='800' fill='blue'/></svg>".getBytes(java.nio.charset.StandardCharsets.UTF_8);java.nio.file.Files.write(svg.toPath(),vector);try(java.util.zip.GZIPOutputStream out=new java.util.zip.GZIPOutputStream(new FileOutputStream(svgz))){out.write(vector);}java.nio.file.Files.write(gif.toPath(),android.util.Base64.decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7",android.util.Base64.DEFAULT));
        Engine baseline=null;
        try{android.content.pm.PackageInfo installed=target.getPackageManager().getPackageInfo("dev.fastsearch.android",0);
            if(installed.versionCode==10){dalvik.system.PathClassLoader old=new dalvik.system.PathClassLoader(installed.applicationInfo.sourceDir,Activity.class.getClassLoader());
                Class<?> loader=old.loadClass("dev.fastsearch.android.ThumbnailLoader");check(loader!=ThumbnailLoader.class,"baseline uses installed build 10 bytecode");baseline=new Engine(loader,old.loadClass("dev.fastsearch.core.FileEntry"),old.loadClass("dev.fastsearch.android.ThumbnailStore"));engines.add(baseline);}}
        catch(android.content.pm.PackageManager.NameNotFoundException absent){report.append("No installed build 10 baseline in this isolated emulator.\n");}
        Engine current=new Engine(ThumbnailLoader.class,FileEntry.class,ThumbnailStore.class);engines.add(current);
        for(File file:new File[]{opaque,alpha,jpeg,webp,gif,video,pdf,svg,svgz}){paths.add(file.getPath());JSONObject row=new JSONObject().put("type",file.getName()).put("sourceBytes",file.length());
            if(baseline!=null)row.put("build10",measure(baseline,file,false));row.put("current",measure(current,file,true));report.append(row).append('\n');}
        providerReuse(opaque);
        report.append("PASS ").append(assertions).append(" thumbnail efficiency assertions; includes source decode and persistent-cache encoding CPU, not synthetic cache-hit timings.\n");
    }
    private void providerReuse(File png)throws Exception{
        if(android.os.Build.VERSION.SDK_INT<29)return;
        android.content.ContentResolver resolver=target.getContentResolver();android.content.ContentValues values=new android.content.ContentValues();
        String relative=android.os.Environment.DIRECTORY_PICTURES+"/AnythingProbe-"+SystemClock.elapsedRealtimeNanos();
        values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,"thumbnail.png");values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE,"image/png");values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,relative);values.put(android.provider.MediaStore.MediaColumns.IS_PENDING,1);
        try{mediaFixture=resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);}catch(SecurityException unavailable){report.append("Provider creation unavailable under this emulator's storage permission; local decode/cache checks retained.\n");return;}
        check(mediaFixture!=null,"owned provider thumbnail fixture");try(java.io.InputStream in=new java.io.FileInputStream(png);java.io.OutputStream out=resolver.openOutputStream(mediaFixture)){byte[] bytes=new byte[32768];int n;while((n=in.read(bytes))!=-1)out.write(bytes,0,n);}
        values.clear();values.put(android.provider.MediaStore.MediaColumns.IS_PENDING,0);resolver.update(mediaFixture,values,null,null);
        FileEntry entry;
        try(android.database.Cursor cursor=resolver.query(mediaFixture,new String[]{android.provider.MediaStore.MediaColumns.DATA,android.provider.MediaStore.MediaColumns.SIZE,android.provider.MediaStore.MediaColumns.DATE_MODIFIED},null,null,null)){
            check(cursor!=null&&cursor.moveToFirst(),"owned provider metadata");entry=new FileEntry(cursor.getString(0),false,cursor.getLong(1),cursor.getLong(2)*1000);mediaDirectory=new File(entry.parent);}
        Bitmap warm=resolver.loadThumbnail(mediaFixture,new android.util.Size(384,384),null);check(warm!=null,"platform thumbnail ready");warm.recycle();
        int before=MediaThumbnails.hitsForProbe();long started=SystemClock.elapsedRealtimeNanos();Bitmap reused=MediaThumbnails.load(target,entry,384,false,new android.os.CancellationSignal());
        check(reused!=null&&MediaThumbnails.hitsForProbe()==before+1,"shared image uses existing platform thumbnail");check(Math.max(reused.getWidth(),reused.getHeight())<=384,"provider thumbnail stays cell-sized");reused.recycle();
        report.append("Existing platform PNG thumbnail lookup (ms): ").append((SystemClock.elapsedRealtimeNanos()-started)/1e6).append("; original PNG bytes: ").append(entry.size).append('\n');
        String shared=android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
        if(shared.startsWith("/storage/emulated/")&&entry.path().startsWith(shared+"/")){
            String backing="/data/media/"+new File(shared).getName()+entry.path().substring(shared.length());before=MediaThumbnails.hitsForProbe();
            Bitmap rootAlias=MediaThumbnails.load(target,new FileEntry(backing,false,entry.size,entry.modified),384,true,new android.os.CancellationSignal());
            check(rootAlias!=null&&MediaThumbnails.hitsForProbe()==before+1,"root internal-storage alias reuses platform thumbnail without a root reader");if(rootAlias!=null)rootAlias.recycle();}
        android.os.CancellationSignal cancelled=new android.os.CancellationSignal();cancelled.cancel();before=MediaThumbnails.hitsForProbe();check(MediaThumbnails.load(target,entry,384,false,cancelled)==null&&MediaThumbnails.hitsForProbe()==before,"cancelled platform request does not decode");
    }
    private JSONObject measure(Engine engine,File source,boolean current)throws Exception{
        long[] cpu=new long[6],wall=new long[6];int bytes=0,width=0,height=0;long encoded=0;
        for(int n=-1;n<cpu.length;n++){long started=SystemClock.elapsedRealtimeNanos(),before=Debug.threadCpuTimeNanos();Bitmap bitmap=engine.decode(source);
            check(bitmap!=null,"native thumbnail decoded");int expected=384;if(current)check(Math.max(bitmap.getWidth(),bitmap.getHeight())<=expected,"exact cell-sized bitmap");
            if(source.getName().equals("alpha.png")||source.getName().equals("photo.webp"))check(bitmap.hasAlpha()&&Color.alpha(bitmap.getPixel(1,1))==0,"PNG/WebP alpha survives thumbnail decode");
            String key=source.getPath()+'\0'+"efficiency:"+engine.label+':'+n;engine.write(key,bitmap);long duration=SystemClock.elapsedRealtimeNanos()-started,spent=Debug.threadCpuTimeNanos()-before;
            if(n>=0){cpu[n]=spent;wall[n]=duration;}bytes=bitmap.getAllocationByteCount();width=bitmap.getWidth();height=bitmap.getHeight();encoded=engine.file(key).length();bitmap.recycle();}
        Arrays.sort(cpu);Arrays.sort(wall);return new JSONObject().put("medianCpuMs",cpu[cpu.length/2]/1e6).put("medianWallMs",wall[wall.length/2]/1e6).put("bitmapBytes",bytes).put("width",width).put("height",height).put("cacheBytes",encoded);
    }
    private final class Engine implements AutoCloseable {
        final Object loader;final Constructor<?> entry;final Method decode,write,file,close;final String label;
        Engine(Class<?> type,Class<?> entryType,Class<?> store)throws Exception{label=type==ThumbnailLoader.class?"current":"baseline";
            Constructor<?> constructor=type.getDeclaredConstructor(Context.class);constructor.setAccessible(true);loader=constructor.newInstance(target);
            entry=entryType.getConstructor(String.class,boolean.class,long.class,long.class);decode=type.getDeclaredMethod("decode",entryType,int.class,boolean.class,boolean.class);decode.setAccessible(true);
            write=store.getDeclaredMethod("write",Context.class,String.class,Bitmap.class);write.setAccessible(true);file=store.getDeclaredMethod("file",Context.class,String.class);file.setAccessible(true);close=type.getDeclaredMethod("close");close.setAccessible(true);}
        Bitmap decode(File source)throws Exception{return (Bitmap)decode.invoke(loader,entry.newInstance(source.getPath(),false,source.length(),source.lastModified()),384,false,source.getName().endsWith(".pdf"));}
        void write(String key,Bitmap bitmap)throws Exception{write.invoke(null,target,key,bitmap);}File file(String key)throws Exception{return (File)file.invoke(null,target,key);}public void close()throws Exception{close.invoke(loader);}
    }
    private static void save(Bitmap bitmap,File file)throws Exception{try(FileOutputStream out=new FileOutputStream(file)){if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,out))throw new java.io.IOException("Fixture encoding failed");}}
    private void check(boolean condition,String message){assertions++;if(!condition)throw new AssertionError(message);}
    private static void remove(File file){File[] children=file.listFiles();if(children!=null)for(File child:children)remove(child);file.delete();}
}
