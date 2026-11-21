package dev.fastsearch.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import dev.fastsearch.core.FileEntry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Real descriptor, replacement and cold-process grant checks on synthetic files only. */
public final class ProviderProbe extends Instrumentation {
    private Bundle arguments;
    private Context target;
    private int checks;
    private File retainedFixture;
    private final StringBuilder report = new StringBuilder();

    @Override public void onCreate(Bundle args) { super.onCreate(args); arguments=args; start(); }
    @Override public void onStart() {
        Bundle result=new Bundle(); boolean passed=false;
        try {
            target=getTargetContext();
            if(arguments!=null&&"cold".equals(arguments.getString("phase"))) cold(); else warm();
            passed=true;
        } catch(Throwable failure) {
            java.io.StringWriter text=new java.io.StringWriter();failure.printStackTrace(new java.io.PrintWriter(text));report.append(text);
        }
        if (!passed && retainedFixture != null) {
            try { cleanup(retainedFixture); } catch (Exception failure) { report.append("Cleanup failed: ").append(failure.getClass().getSimpleName()).append('\n'); }
        }
        result.putString("stream",report.append(passed?"PASS ":"FAIL ").append(checks).append(" provider assertions.\n").toString());
        result.putBoolean("passed",passed);result.putInt("assertions",checks);
        finish(passed?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
    private void check(boolean value,String description) { if(!value)throw new AssertionError(description);checks++; }
    private void failOpen(Throwing operation,String description) throws Exception {
        boolean failed=false;try{operation.run();}catch(Exception expected){failed=true;}check(failed,description);
    }
    private interface Throwing { void run() throws Exception; }
    private void write(File file,String value)throws Exception{try(FileOutputStream out=new FileOutputStream(file)){out.write(value.getBytes(StandardCharsets.UTF_8));}}
    private Uri issue(File file,boolean root)throws Exception{return RootFileProvider.issue(target,new FileEntry(file.getCanonicalPath(),false,file.length(),file.lastModified()),root);}
    private void read(Uri uri,String expected)throws Exception{
        try(ParcelFileDescriptor file=target.getContentResolver().openFileDescriptor(uri,"r")){
            check(file!=null,"descriptor is present");
            check(Os.lseek(file.getFileDescriptor(),0,OsConstants.SEEK_SET)==0,"descriptor is seekable");
            byte[] bytes=new byte[128];int n=Os.read(file.getFileDescriptor(),bytes,0,bytes.length);
            check(new String(bytes,0,n,StandardCharsets.UTF_8).equals(expected),"exact synthetic contents");
            failOpen(()->Os.write(file.getFileDescriptor(),new byte[]{1},0,1),"descriptor cannot write");
        }
    }
    private void root(String command)throws Exception{
        Process child=new ProcessBuilder(IndexRootScanner.suBinary(),"-c",command).redirectErrorStream(true).start();
        Thread drain=new Thread(()->{try(InputStream in=child.getInputStream()){byte[] bytes=new byte[512];while(in.read(bytes)!=-1){}}catch(Exception ignored){}},"provider-probe-errors");
        drain.setDaemon(true);drain.start();
        if(!child.waitFor(30,java.util.concurrent.TimeUnit.SECONDS)){child.destroy();throw new java.io.IOException("Root probe operation timed out");}
        check(child.exitValue()==0,"synthetic root operation");
    }
    private void warm()throws Exception{
        File fixture=new File(target.getCacheDir(),"ProviderProbe-"+SystemClock.elapsedRealtimeNanos()).getCanonicalFile();
        check(fixture.mkdir(),"create synthetic fixture");retainedFixture=fixture;
        File normal=new File(fixture,"normal '日本.txt"),secret=new File(fixture,"root-only.txt");
        write(normal,"normal fixture");write(secret,"protected fixture");
        root("chown 0:0 "+IndexRootScanner.quote(secret.getPath())+" && chmod 0600 "+IndexRootScanner.quote(secret.getPath()));
        failOpen(()->{try(ParcelFileDescriptor ignored=SafeFileAccess.openLocal(secret.getPath())){}},"private file denied without root");
        Uri normalUri=issue(normal,false),rootUri=issue(secret,true);
        read(normalUri,"normal fixture");read(rootUri,"protected fixture");
        check("text/plain".equals(target.getContentResolver().getType(normalUri)),"extension MIME type");
        failOpen(()->{try(ParcelFileDescriptor ignored=target.getContentResolver().openFileDescriptor(normalUri,"w")){}},"provider rejects write mode");
        Uri invalid=normalUri.buildUpon().path("/00000000-0000-0000-0000-000000000000/normal.txt").build();
        failOpen(()->{try(ParcelFileDescriptor ignored=target.getContentResolver().openFileDescriptor(invalid,"r")){}},"unknown token denied");
        File replaced=new File(fixture,"replaced.txt");write(replaced,"first");Uri replacedUri=issue(replaced,false);
        File old=new File(fixture,"previous.txt");check(replaced.renameTo(old),"preserve old inode");write(replaced,"second");
        failOpen(()->{try(ParcelFileDescriptor ignored=target.getContentResolver().openFileDescriptor(replacedUri,"r")){}},"replaced inode denied");
        check(replaced.delete(),"remove replacement");Os.symlink(secret.getPath(),replaced.getPath());
        failOpen(()->{try(ParcelFileDescriptor ignored=target.getContentResolver().openFileDescriptor(replacedUri,"r")){}},"symlink substitution denied");
        File pipe=new File(fixture,"pipe");root("/system/bin/mkfifo "+IndexRootScanner.quote(pipe.getPath()));
        long begin=SystemClock.elapsedRealtime();
        failOpen(()->{try(ParcelFileDescriptor ignored=SafeFileAccess.openLocal(pipe.getPath())){}},"FIFO denied");
        check(SystemClock.elapsedRealtime()-begin<1000,"FIFO opening does not block");
        target.getSharedPreferences("provider-probe",0).edit().putString("fixture",fixture.getPath()).putString("normal",normalUri.toString()).putString("root",rootUri.toString()).commit();
        report.append("Warm checks passed; synthetic grants retained for a second process.\n");
    }
    private void cleanup(File fixture)throws Exception{
        if(!fixture.getParentFile().getCanonicalFile().equals(target.getCacheDir().getCanonicalFile())||!fixture.getName().startsWith("ProviderProbe-"))throw new java.io.IOException("Invalid cleanup fixture");
        root("rm -rf "+IndexRootScanner.quote(fixture.getPath()));
        android.content.SharedPreferences grants=target.getSharedPreferences("file-grants",0);
        android.content.SharedPreferences.Editor edit=grants.edit();
        for(java.util.Map.Entry<String,?> item:grants.getAll().entrySet()){
            try{if(new org.json.JSONObject(item.getValue().toString()).getString("path").startsWith(fixture.getPath()+"/"))edit.remove(item.getKey());}catch(Exception ignored){}
        }
        edit.commit();target.getSharedPreferences("provider-probe",0).edit().clear().commit();
    }
    private void cold()throws Exception{
        android.content.SharedPreferences prefs=target.getSharedPreferences("provider-probe",0);
        String fixture=prefs.getString("fixture",null);
        check(fixture!=null&&new File(fixture).getParentFile().getCanonicalFile().equals(target.getCacheDir().getCanonicalFile())&&new File(fixture).getName().startsWith("ProviderProbe-"),"bounded retained fixture");
        try{
            read(Uri.parse(prefs.getString("normal","")),"normal fixture");
            read(Uri.parse(prefs.getString("root","")),"protected fixture");
            report.append("Saved grants opened in a fresh process without loading a filename index.\n");
        }finally{
            cleanup(new File(fixture));
        }
    }
}
