package dev.fastsearch.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.ListAdapter;
import android.widget.TextView;

import dev.fastsearch.core.FileEntry;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Synthetic file-selection and comparison sheet smoke test on a disposable app package. */
public final class ComparisonProbe extends Instrumentation {
    private Context target;
    private MainActivity activity;
    private FileIndex index;
    private File fixture,first,second;
    private String id;
    private Map<String,?> previousUi;
    private int assertions;
    private final StringBuilder report=new StringBuilder();

    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);start();}
    @Override public void onStart(){
        boolean passed=false;
        Bundle result=new Bundle();
        try{
            target=getTargetContext();exercise();passed=true;
        }catch(Throwable failure){
            StringWriter stack=new StringWriter();failure.printStackTrace(new PrintWriter(stack));report.append(stack);
        }finally{
            try{cleanup();}catch(Exception failure){passed=false;report.append("Cleanup: ").append(failure).append('\n');}
        }
        result.putBoolean("passed",passed);
        result.putString("stream",report.append(passed?"PASS ":"FAIL ").append(assertions)
                .append(" file comparison UI assertions.\n").toString());
        finish(passed?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }

    private void exercise()throws Exception{
        SharedPreferences prefs=target.getSharedPreferences("ui",0);previousUi=prefs.getAll();
        check(prefs.edit().clear().putInt("sort",0).putBoolean("descendingDefault4",true).commit(),"isolated fixture preferences");
        id="IndexProbeCompare"+SystemClock.elapsedRealtimeNanos();
        fixture=new File(target.getCacheDir(),"IndexProbe-Compare-"+id).getCanonicalFile();
        check(fixture.mkdir(),"isolated owned fixture");
        first=new File(fixture,"Before.txt");second=new File(fixture,"After.txt");
        String a="Original\nShared",b="Updated\nShared\nAdded note";
        Files.write(first.toPath(),a.getBytes(StandardCharsets.UTF_8));
        Files.write(second.toPath(),b.getBytes(StandardCharsets.UTF_8));
        index=FileIndex.forProbe(target,fixture,id);index.setRootEnabled(false);index.startScan();
        await(()->index.getState().loaded&&!index.getState().scanning&&index.getState().lastScanMillis>0
                &&index.getState().entries.stream().filter(row->row.pathEquals(first.getPath())||row.pathEquals(second.getPath())).count()==2,
                "both fixture files indexed");
        runOnMainSync(()->MainActivity.setProbeIndex(index));
        activity=(MainActivity)startActivitySync(new Intent(target,MainActivity.class)
                .putExtra("permissionProbe",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        runOnMainSync(()->((android.widget.EditText)activity.findViewById(R.id.query)).setText("ext:txt"));
        await(()->onMain(()->list().getCount()==2),"two fixture entries displayed");

        Method toggle=MainActivity.class.getDeclaredMethod("toggle",FileEntry.class,View.class);
        toggle.setAccessible(true);
        runOnMainSync(()->{
            try{for(FileEntry entry:index.getState().entries)
                if(entry.pathEquals(first.getPath())||entry.pathEquals(second.getPath()))toggle.invoke(activity,entry,list());}
            catch(Exception failure){throw new AssertionError(failure);}
        });
        await(()->onMain(()->described(activity.getWindow().getDecorView(),"Selection options")!=null),"selection controls");
        runOnMainSync(()->check(described(activity.getWindow().getDecorView(),"Selection options").performClick(),"open actual selection menu"));
        await(()->onMain(()->menu()!=null&&menu().isShowing()),"selection popup open");
        runOnMainSync(()->{
            AbsListView entries=listIn(menu().getWindow().getDecorView());
            check(entries!=null&&entries.getAdapter()!=null,"selection popup list realized");
            ListAdapter adapter=entries.getAdapter();
            int compare=-1;
            for(int i=0;i<adapter.getCount();i++){
                Object item=adapter.getItem(i);
                if(item instanceof MenuItem&&"Compare files".contentEquals(((MenuItem)item).getTitle())){compare=i;break;}
            }
            check(compare>=0&&adapter.isEnabled(compare),"compare enabled for two selections");
            check(entries.performItemClick(adapter.getView(compare,null,entries),compare,adapter.getItemId(compare)),
                    "invoke actual compare menu action");
        });
        await(()->onMain(()->sheet()!=null&&labeled(sheet().getWindow().getDecorView(),"Compare text files",false)!=null),"actual comparison sheet opens");
        await(()->onMain(()->labeled(sheet().getWindow().getDecorView(),"added",true)!=null
                &&labeled(sheet().getWindow().getDecorView(),"removed",true)!=null),"text differences rendered");
        String[] previousSide={""};
        runOnMainSync(()->{
            View decor=sheet().getWindow().getDecorView();
            check(labeled(decor,"B  ",true)!=null,"both input names displayed");
            previousSide[0]=labeled(decor,"A  ",true).getText().toString();
            check(labeled(decor,"Swap sides",false).performClick(),"swap A and B");
        });
        await(()->onMain(()->{TextView side=labeled(sheet().getWindow().getDecorView(),"A  ",true);
            return side!=null&&!previousSide[0].contentEquals(side.getText());}),"swap result in place");
        check(a.equals(new String(Files.readAllBytes(first.toPath()),StandardCharsets.UTF_8))
                &&b.equals(new String(Files.readAllBytes(second.toPath()),StandardCharsets.UTF_8)),"both originals unchanged");
    }

    private AbsListView list(){return activity.findViewById(R.id.results);}
    private MenuDialog menu(){return (MenuDialog)field("navigationMenu");}
    private AppSheet sheet(){return (AppSheet)field("activeSheet");}
    private Object field(String name){
        try{Field field=MainActivity.class.getDeclaredField(name);field.setAccessible(true);return field.get(activity);}
        catch(Exception failure){throw new AssertionError(failure);}
    }
    private static AbsListView listIn(View view){
        if(view instanceof AbsListView)return (AbsListView)view;
        if(view instanceof ViewGroup){
            ViewGroup group=(ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++){
                AbsListView match=listIn(group.getChildAt(i));if(match!=null)return match;
            }
        }
        return null;
    }
    private static View described(View view,String description){
        if(view==null)return null;
        if(view.getContentDescription()!=null&&description.contentEquals(view.getContentDescription()))return view;
        if(view instanceof ViewGroup){
            ViewGroup group=(ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++){View result=described(group.getChildAt(i),description);if(result!=null)return result;}
        }
        return null;
    }
    private static TextView labeled(View view,String label,boolean contains){
        if(view instanceof TextView&&view.isShown()){
            TextView text=(TextView)view;
            if(contains?text.getText().toString().contains(label):label.contentEquals(text.getText()))return text;
        }
        if(view instanceof ViewGroup){
            ViewGroup group=(ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++){
                TextView found=labeled(group.getChildAt(i),label,contains);if(found!=null)return found;
            }
        }
        return null;
    }
    private boolean onMain(BooleanSupplier action){boolean[] value={false};runOnMainSync(()->value[0]=action.getAsBoolean());return value[0];}
    private void await(BooleanSupplier condition,String label){
        long end=SystemClock.elapsedRealtime()+15_000;
        while(SystemClock.elapsedRealtime()<end){
            if(condition.getAsBoolean()){check(true,label);return;}
            SystemClock.sleep(90);
        }
        check(false,label);
    }
    private void cleanup()throws Exception{
        runOnMainSync(()->{if(activity!=null)activity.finishAndRemoveTask();MainActivity.setProbeIndex(null);});
        if(index!=null){index.shutdown();index.awaitShutdown(15_000);}
        if(id!=null){
            target.deleteDatabase(id+"-root-filenames.db");target.deleteDatabase(id+"-shared-filenames.db");
            target.deleteSharedPreferences(id+"-filename-index");
        }
        if(previousUi!=null){
            SharedPreferences.Editor edit=target.getSharedPreferences("ui",0).edit().clear();
            for(Map.Entry<String,?> e:previousUi.entrySet()){
                Object value=e.getValue();String key=e.getKey();
                if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);
                else if(value instanceof String)edit.putString(key,(String)value);
                else if(value instanceof Integer)edit.putInt(key,(Integer)value);
                else if(value instanceof Long)edit.putLong(key,(Long)value);
                else if(value instanceof Float)edit.putFloat(key,(Float)value);
                else if(value instanceof Set){@SuppressWarnings("unchecked") Set<String> set=(Set<String>)value;edit.putStringSet(key,set);}
            }
            edit.commit();
        }
        if(first!=null)Files.deleteIfExists(first.toPath());
        if(second!=null)Files.deleteIfExists(second.toPath());
        if(fixture!=null)Files.deleteIfExists(fixture.toPath());
    }
    private void check(boolean passed,String label){assertions++;if(!passed)throw new AssertionError(label);}
}
