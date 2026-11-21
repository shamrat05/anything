package dev.fastsearch.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.AbsListView;
import android.widget.EditText;
import dev.fastsearch.core.FileEntry;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Synthetic saved-cache, search and live-event timings. Never touches the owner's index. */
public final class PerformanceProbe extends Instrumentation {
    private static final int ROWS=100_000;
    private Context target;private File fixture;private String id;private FileIndex index;
    private MainActivity activity;private int assertions;private Map<String,?> saved;
    private final List<String> ids=new ArrayList<>();private final StringBuilder report=new StringBuilder();
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){boolean passed=false;
        try{target=getTargetContext();saved=target.getSharedPreferences("ui",0).getAll();savedCache();liveUpdates();passed=true;}
        catch(Throwable failure){java.io.StringWriter trace=new java.io.StringWriter();failure.printStackTrace(new java.io.PrintWriter(trace));report.append(trace);}
        finally{try{cleanup();}catch(Throwable failure){passed=false;report.append("Cleanup: ").append(failure).append('\n');}}
        Bundle result=new Bundle();result.putBoolean("passed",passed);result.putInt("assertions",assertions);
        result.putString("stream",report.append(passed?"PASS ":"FAIL ").append(assertions).append(" performance/correctness assertions.\n").toString());finish(passed?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
    private void savedCache()throws Exception{
        prepare("Startup");index=FileIndex.forProbe(target,fixture,id);index.setProbeLiveWatching(false);index.startScan();
        await(()->index.getState().loaded&&!index.getState().scanning&&index.getState().lastScanMillis>0,"seed schema");close();
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(target.getDatabasePath(id+"-shared-filenames.db").getPath(),null,SQLiteDatabase.OPEN_READWRITE)){
            db.beginTransaction();try(SQLiteStatement insert=db.compileStatement("INSERT INTO entries(rowid,path,directory,size,modified) VALUES(?,?,0,?,?)")){
                String parent=fixture.getPath()+"/Documents/";
                for(int n=0;n<ROWS;n++){
                    insert.clearBindings();
                    insert.bindLong(1,2L*(n-ROWS)); // Sparse IDs spanning many cache read pages.
                    insert.bindString(2,parent+String.format(java.util.Locale.ROOT,"document-%06d.txt",n));
                    insert.bindLong(3,n);
                    insert.bindLong(4,1700000000000L+n);
                    insert.execute();
                }
                db.setTransactionSuccessful();
            }finally{db.endTransaction();}
        }
        long[] load=new long[3];
        for(int n=0;n<load.length;n++){
            index=FileIndex.forProbe(target,fixture,id);index.setProbeLiveWatching(false);long started=SystemClock.elapsedRealtimeNanos();index.load();
            await(()->index.getState().loaded,"saved 100k cache");load[n]=elapsed(started);
            check(index.getState().entries.size()==ROWS+1,"all cached rows loaded without storage traversal");
            check(index.fullScanStartsForProbe()==0,"cache load starts no full scan");
            if(n<load.length-1)close();
        }
        report.append("100000 synthetic filenames, SQLite cache load in an existing process (ms): ").append(Arrays.toString(load)).append('\n');
        check(target.getSharedPreferences("ui",0).edit().clear().putInt("view",1).putBoolean("fileAccessIntroShown",true).commit(),"isolated startup settings");
        runOnMainSync(()->MainActivity.setProbeIndex(index));long started=SystemClock.elapsedRealtimeNanos();
        activity=(MainActivity)startActivitySync(new Intent(target,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(()->onMain(()->{AbsListView list=activity.findViewById(R.id.results);return !(Boolean)field(activity,"searchRunning")&&list.getAdapter().getCount()==ROWS+1&&list.getChildCount()>0;}),"first visible 100k result view");
        report.append("Activity with warm 100k snapshot to bound visible results (ms): ").append(elapsed(started)).append("; worker query (ms): ").append(onMain(()->field(activity,"lastSearchMillis"))).append('\n');
        long[] queries=new long[3];
        for(int n=0;n<queries.length;n++){long begin=SystemClock.elapsedRealtimeNanos();runOnMainSync(()->((EditText)activity.findViewById(R.id.query)).setText("file: document-09"));
            await(()->onMain(()->!(Boolean)field(activity,"searchRunning")),"100k filename query");queries[n]=elapsed(begin);
            check(onMain(()->((AbsListView)activity.findViewById(R.id.results)).getAdapter().getCount())==10_000,"query count over entire cache");
            runOnMainSync(()->((EditText)activity.findViewById(R.id.query)).setText(""));await(()->onMain(()->!(Boolean)field(activity,"searchRunning")),"reset query");}
        report.append("100k filename search including UI delivery (ms): ").append(Arrays.toString(queries)).append('\n');
        runOnMainSync(activity::finish);await(()->activity.isDestroyed(),"benchmark Activity destroyed");activity=null;
        runOnMainSync(()->MainActivity.setProbeIndex(null));close();delete(fixture);fixture=null;
    }
    private void liveUpdates()throws Exception{
        prepare("Live");for(int n=0;n<5000;n++)write(new File(fixture,"file-"+n+".txt"),"before");
        index=FileIndex.forProbe(target,fixture,id);index.startScan();await(()->index.getState().lastScanMillis>0&&!index.getState().scanning,"live fixture built");
        check(index.getState().watchCount>0,"live directory watched");long[] times=new long[5];
        for(int n=0;n<times.length;n++){
            File added=new File(fixture,"new-"+n+".txt");List<FileEntry> previous=index.getState().entries;long begin=SystemClock.elapsedRealtimeNanos();write(added,"new contents");
            await(()->find(added)!=null&&find(added).size==added.length(),"live create delivered");times[n]=elapsed(begin);
            check(index.getState().entries!=previous&&previous.size()==5001+n,"live snapshot immutable and exact");
            check(ordered(index.getState().entries),"delta merge preserves complete name ordering");
            check(index.fullScanStartsForProbe()==1,"live addition never starts another full scan");
        }
        report.append("Shared-storage observer create-to-searchable latency over 5000 existing real files (ms): ").append(Arrays.toString(times)).append('\n');
        File changed=new File(fixture,"file-7.txt");write(changed,"changed and longer contents");await(()->find(changed)!=null&&find(changed).size==changed.length(),"live overwrite metadata");
        File removed=new File(fixture,"file-8.txt");check(removed.delete(),"remove owned fixture");await(()->find(removed)==null,"live deletion");
        check(ordered(index.getState().entries),"overwrites and removals preserve ordering");close();delete(fixture);fixture=null;
    }
    private void prepare(String label)throws Exception{id="IndexProbePerf"+label+SystemClock.elapsedRealtimeNanos();ids.add(id);fixture=new File(target.getCacheDir(),"IndexProbe-Perf-"+SystemClock.elapsedRealtimeNanos()).getCanonicalFile();check(fixture.mkdir(),"owned performance fixture");}
    private FileEntry find(File file){for(FileEntry entry:index.getState().entries)if(entry.path().equals(file.getPath()))return entry;return null;}
    private static boolean ordered(List<FileEntry> rows){for(int i=1;i<rows.size();i++){FileEntry a=rows.get(i-1),b=rows.get(i);int order=a.name.compareToIgnoreCase(b.name);if(order>0||order==0&&a.path().compareTo(b.path())>0)return false;}return true;}
    private static long elapsed(long started){return (SystemClock.elapsedRealtimeNanos()-started)/1_000_000;}
    private static void write(File file,String value)throws Exception{try(FileOutputStream out=new FileOutputStream(file)){out.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));}}
    private void close()throws Exception{index.shutdown();check(index.awaitShutdown(15_000),"benchmark index stopped");index=null;}
    private void await(BooleanSupplier condition,String label){long deadline=SystemClock.elapsedRealtime()+40_000;while(SystemClock.elapsedRealtime()<deadline){if(condition.getAsBoolean())return;SystemClock.sleep(10);}throw new AssertionError("Timed out: "+label);}
    private void check(boolean value,String label){assertions++;if(!value)throw new AssertionError(label);}
    private static Object field(Object value,String name){try{Field field=value.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(value);}catch(Exception failure){throw new AssertionError(failure);}}
    private <T>T onMain(java.util.concurrent.Callable<T> call){Object[] value=new Object[1];Throwable[] error=new Throwable[1];runOnMainSync(()->{try{value[0]=call.call();}catch(Throwable failure){error[0]=failure;}});if(error[0]!=null)throw new AssertionError(error[0]);@SuppressWarnings("unchecked")T result=(T)value[0];return result;}
    private void cleanup()throws Exception{
        if(activity!=null&&!activity.isDestroyed())runOnMainSync(activity::finish);runOnMainSync(()->MainActivity.setProbeIndex(null));
        if(index!=null)close();if(fixture!=null)delete(fixture);
        if(target!=null){for(String name:ids){target.deleteDatabase(name+"-shared-filenames.db");target.deleteSharedPreferences(name+"-filename-index");}
            if(saved!=null){android.content.SharedPreferences.Editor edit=target.getSharedPreferences("ui",0).edit().clear();for(Map.Entry<String,?> entry:saved.entrySet()){
                Object value=entry.getValue();String key=entry.getKey();if(value instanceof String)edit.putString(key,(String)value);else if(value instanceof Integer)edit.putInt(key,(Integer)value);
                else if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);else if(value instanceof Long)edit.putLong(key,(Long)value);else if(value instanceof Float)edit.putFloat(key,(Float)value);
            }if(!edit.commit())throw new java.io.IOException("Could not restore UI preferences");}}
    }
    private static void delete(File file)throws Exception{File[] children=file.listFiles();if(children!=null)for(File child:children)delete(child);if(!file.delete())throw new java.io.IOException("Owned fixture cleanup failed");}
}
