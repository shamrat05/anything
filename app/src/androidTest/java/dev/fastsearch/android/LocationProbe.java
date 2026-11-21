package dev.fastsearch.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.view.View;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import dev.fastsearch.core.FileEntry;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Synthetic saved indexes only. No root traversal, real file reads or owner data changes. */
public final class LocationProbe extends Instrumentation {
    private static final int EXTRA=60;
    private Context target;
    private volatile MainActivity activity;
    private FileIndex index;
    private File fixture;
    private String id,shared,internal;
    private int assertions;
    private Map<String,?> saved;
    private final StringBuilder report=new StringBuilder();
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void callActivityOnResume(Activity resumed){super.callActivityOnResume(resumed);if(resumed instanceof MainActivity)activity=(MainActivity)resumed;}
    @Override public void onStart(){boolean passed=false;
        try{target=getTargetContext();exercise();passed=true;}
        catch(Throwable failure){java.io.StringWriter trace=new java.io.StringWriter();failure.printStackTrace(new java.io.PrintWriter(trace));report.append(trace);
            try{if(activity!=null&&!activity.isDestroyed())capture("failure.png");}catch(Exception ignored){}}
        finally{try{cleanup();}catch(Throwable failure){passed=false;report.append("Cleanup: ").append(failure).append('\n');}}
        Bundle result=new Bundle();result.putBoolean("passed",passed);result.putInt("assertions",assertions);
        result.putString("stream",report.append(passed?"PASS ":"FAIL ").append(assertions).append(" menu/location assertions.\n").toString());
        finish(passed?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
    private void exercise()throws Exception{
        check(BuildConfig.DEBUG&&target.getPackageName().endsWith(".fresh"),"isolated debug package only");
        saved=target.getSharedPreferences("ui",0).getAll();
        check(target.getSharedPreferences("ui",0).edit().clear().putInt("view",1).putInt("theme",1).putBoolean("fileAccessIntroShown",true).commit(),"isolated preferences");
        android.accessibilityservice.AccessibilityServiceInfo info=getUiAutomation().getServiceInfo();
        info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS|android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        getUiAutomation().setServiceInfo(info);
        fixture=new File(target.getCacheDir(),"IndexProbe-Locations-"+SystemClock.elapsedRealtimeNanos()).getCanonicalFile();
        check(fixture.mkdir(),"owned empty fixture");id="IndexProbeLocations"+SystemClock.elapsedRealtimeNanos();
        shared=Environment.getExternalStorageDirectory().getAbsolutePath();
        check(shared.startsWith("/storage/emulated/")&&new File(shared).getName().matches("[0-9]+"),"device emulated storage mapping");
        internal="/data/media/"+new File(shared).getName();
        seed(true);seed(false);load(true);launch();
        List<FileEntry> snapshot=index.getState().entries;long checked=index.getState().lastScanMillis;
        check(scope()==null&&count()==7+EXTRA,"existing default retains all indexed results");
        int top=onMain(()->activity.findViewById(R.id.results).getTop());
        runOnMainSync(()->activity.findViewById(R.id.overflow).performClick());
        await(()->visible("File actions"),"overflow is visible after launch transition");
        for(String label:new String[]{"Search","Filters","File actions","Storage","Appearance","Help"})check(visible(label),"top-level category "+label);
        check(!visible("Hidden files")&&!visible("Root folders")&&!visible("Select all"),"leaf settings are nested");capture("01-menu-light.png");
        click("File actions");check(enabled("Paste")== (FileClipboard.count(target)>0),"Paste enabled state follows the clipboard");
        runOnMainSync(()->menu().findViewById(R.id.menu_back).performClick());
        checkStoragePress();check(visible("Start location")&&visible("Index scope")&&visible("File access")&&visible("Index statistics"),"storage category contents");
        check(menuTitle().equals("Storage")&&onMain(()->menu().findViewById(R.id.menu_back).isShown()),"Storage has a visible parent back control");
        capture("02-storage-light.png");click("Index scope");check(visible("Root folders")&&visible("Shared storage"),"both index choices remain in the nested scope");
        MenuDialog sameWindow=onMain(this::menu);capture("06-index-scope-light.png");systemBack();
        check(menuTitle().equals("Storage")&&visible("Start location")&&onMain(this::menu)==sameWindow,"Android Back returns to Storage in the same window");
        systemBack();check(visible("File actions")&&onMain(this::menu)==sameWindow,"second Android Back returns to the top-level menu");
        systemBack();await(()->onMain(()->menu()==null&&activity.hasWindowFocus()),"top-level Back dismisses only menu");
        check(!activity.isFinishing()&&!activity.isDestroyed()&&scope()==null,"menu Back leaves results and browse scope intact");
        runOnMainSync(()->activity.findViewById(R.id.overflow).performClick());click("Storage");click("Index scope");
        runOnMainSync(()->menu().findViewById(R.id.menu_back).performClick());check(menuTitle().equals("Storage"),"visible Back returns one parent level");
        runOnMainSync(()->menu().findViewById(R.id.menu_back).performClick());check(visible("File actions")&&!onMain(()->menu().findViewById(R.id.menu_back).isShown()),"visible Back returns to root without a redundant root header");
        click("Storage");click("Index scope");click("Root folders");waitResults();check(onMain(this::menu)==null,"leaf action dismisses menu before executing");
        runOnMainSync(()->activity.findViewById(R.id.overflow).performClick());click("Storage");
        android.graphics.Rect outside=onMain(()->{android.graphics.Rect bounds=new android.graphics.Rect();activity.findViewById(R.id.results).getGlobalVisibleRect(bounds);return bounds;});
        tap(outside.left+8,outside.bottom-8);await(()->onMain(()->menu()==null&&activity.hasWindowFocus()),"outside touch dismisses nested menu");
        check(!activity.isFinishing()&&!activity.isDestroyed(),"outside dismissal retains the Activity");
        runOnMainSync(()->((Spinner)activity.findViewById(R.id.category)).setSelection(4));waitResults();
        runOnMainSync(()->activity.findViewById(R.id.sort).performClick());click("Modified");waitResults();setQuery("file: Notes");
        location("Internal storage");
        check(internal.equals(scope())&&count()==2+EXTRA,"internal shortcut scopes the root cache to its backing path");
        check(query().getHint().toString().equals("Search internal storage")&&query().length()==0,"location hint and query reset");
        check(onMain(()->((Spinner)activity.findViewById(R.id.category)).getSelectedItemPosition())==4,"location navigation retains document category");
        check(onMain(()->(Integer)field(activity,"sort"))==3&&(Boolean)onMain(()->field(activity,"descending")),"location navigation retains sort and direction");
        check(onMain(()->activity.findViewById(R.id.results).getTop())==top,"location switch consumes no result height");
        check(onMain(()->activity.findViewById(R.id.browse_locations).getWidth())==onMain(()->activity.findViewById(R.id.clear_search).getWidth()),"location reuses an existing icon-width slot");
        unchanged(snapshot,checked,true);
        runOnMainSync(()->activity.findViewById(R.id.clear_search).performClick());waitResults();
        check(scope()==null&&query().getText().toString().equals("file: Notes"),"X returns to prior scope and query");
        location("Internal storage");location("Root");check("/".equals(scope()),"Root shortcut scopes existing root snapshot");unchanged(snapshot,checked,true);
        runOnMainSync(()->((Spinner)activity.findViewById(R.id.category)).setSelection(9));waitResults();
        runOnMainSync(()->((Spinner)activity.findViewById(R.id.category)).setSelection(4));waitResults();
        check("/".equals(scope()),"explicit root survives changing search categories");
        finishActivity();launch();
        check("/".equals(scope()),"explicit root survives a new launch");
        location("Internal storage");check(internal.equals(scope()),"explicit storage switch leaves root browsing");
        check(onMain(()->{AbsListView list=activity.findViewById(R.id.results);
            for(int i=0;i<list.getCount();i++)if(!((FileEntry)list.getItemAtPosition(i)).path().startsWith(internal+"/"))return false;
            return list.getCount()>0;}),"Internal storage contains no root-only results");
        runOnMainSync(()->((Spinner)activity.findViewById(R.id.category)).setSelection(0));waitResults();
        location("Root");
        Object rootRows=onMain(()->field(field(activity,"adapter"),"items"));
        location("Internal storage");
        check(onMain(()->field(field(activity,"adapter"),"items"))!=rootRows,"Internal storage uses its own results");
        location("Root");
        check(onMain(()->field(field(activity,"adapter"),"items"))==rootRows,"Root reuses sorted results from the same snapshot");
        location("Internal storage");
        runOnMainSync(()->((Spinner)activity.findViewById(R.id.category)).setSelection(4));waitResults();
        startLocation("Internal storage");check(internal.equals(scope()),"changing start preference preserves the current screen");
        finishActivity();launch();check(internal.equals(scope())&&query().length()==0,"fixed internal location applied on a new launch");unchanged(snapshot,checked,true);
        runOnMainSync(()->((Spinner)activity.findViewById(R.id.category)).setSelection(0));waitResults();openFolder(internal+"/IndexProbe library");
        startLocation("Root");String current=scope();MainActivity beforeRecreate=activity;runOnMainSync(beforeRecreate::recreate);
        await(()->activity!=beforeRecreate&&!activity.isDestroyed(),"recreation finishes");waitResults();
        check(current.equals(scope()),"recreation preserves current location despite a fixed start preference");
        finishActivity();launch();check("/".equals(scope()),"fixed Root applied only to a new launch");
        startLocation("Last location");location("Internal storage");openFolder(internal+"/IndexProbe library");setQuery("file: Notes");
        runOnMainSync(()->((android.widget.ListView)activity.findViewById(R.id.results)).setSelectionFromTop(25,-8));
        await(()->onMain(()->((AbsListView)activity.findViewById(R.id.results)).getFirstVisiblePosition())>=20,"scroll last location");
        String anchor=onMain(()->{AbsListView list=activity.findViewById(R.id.results);return ((FileEntry)list.getItemAtPosition(list.getFirstVisiblePosition())).path();});
        int offset=onMain(()->((AbsListView)activity.findViewById(R.id.results)).getChildAt(0).getTop());
        int history=onMain(()->((List<?>)field(activity,"folderHistory")).size());
        finishActivity();closeIndex();index=FileIndex.forProbe(target,fixture,id);index.setProbeLiveWatching(false);
        runOnMainSync(()->MainActivity.setProbeIndex(index));launch();
        check((internal+"/IndexProbe library").equals(scope())&&query().getText().toString().equals("file: Notes"),"last location and query survive index/process-state recreation");
        check(onMain(()->((List<?>)field(activity,"folderHistory")).size())==history,"last-location directory history persisted");
        await(()->onMain(()->{AbsListView list=activity.findViewById(R.id.results);return list.getChildCount()>0&&anchor.equals(((FileEntry)list.getItemAtPosition(list.getFirstVisiblePosition())).path())&&list.getChildAt(0).getTop()==offset;}),"last-location result anchor and pixel offset restored");
        check(index.fullScanStartsForProbe()==0,"cold saved-location launch never scans storage");
        snapshot=index.getState().entries;checked=index.getState().lastScanMillis;
        runOnMainSync(()->activity.findViewById(R.id.clear_search).performClick());waitResults();check(internal.equals(scope()),"restored X returns through saved history");unchanged(snapshot,checked,true);
        capture("03-internal-light.png");
        overflow("Appearance","Theme");MainActivity beforeTheme=activity;click("Dark");
        await(()->activity!=beforeTheme&&!activity.isDestroyed(),"dark theme recreates");waitResults();
        check(internal.equals(scope()),"theme retains current browse scope");runOnMainSync(()->activity.findViewById(R.id.overflow).performClick());capture("04-menu-dark.png");
        checkStoragePress();
        click("Start location");capture("05-start-dark.png");click("Cancel");
        // Switching probe caches here is test setup. Home shortcuts above never switch them.
        finishActivity();closeIndex();load(false);launch();
        check(scope()==null,"a saved root-only location is not applied to a shared-only cache");
        setQuery("");location("Internal storage");check(shared.equals(scope())&&count()==3+EXTRA,"shared shortcut uses the primary storage path in the shared index");
        snapshot=index.getState().entries;checked=index.getState().lastScanMillis;
        runOnMainSync(()->activity.findViewById(R.id.browse_locations).performClick());check(!enabled("Root"),"Root navigation disabled in shared-only mode");click("Internal storage");waitResults();
        overflow("Storage","Start location");check(!enabled("Root"),"Root start choice disabled without changing access mode");click("Internal storage");
        runOnMainSync(()->((Spinner)activity.findViewById(R.id.category)).setSelection(9));waitResults();
        location("Internal storage");check(shared.equals(scope())&&onMain(()->((Spinner)activity.findViewById(R.id.category)).getSelectedItemPosition())==0,"file shortcut leaves app search for the file category");
        runOnMainSync(()->((Spinner)activity.findViewById(R.id.category)).setSelection(9));waitResults();
        finishActivity();launch();check(shared.equals(scope())&&onMain(()->((Spinner)activity.findViewById(R.id.category)).getSelectedItemPosition())==0,"fixed storage start wins over a previously saved app category");unchanged(snapshot,checked,false);
        runOnMainSync(()->activity.findViewById(R.id.overflow).performClick());click("Storage");MenuDialog stopped=onMain(this::menu);finishActivity();
        check(!onMain(stopped::isShowing)&&onMain(()->field(stopped,"backCallback"))==null,"stopping the Activity dismisses the menu and unregisters its Back callback");
        report.append("Injected Android Back and visible parent navigation, outside dismissal, native nested menus, indexed root/shared aliases, zero-row location control, fixed/last starts, Back/X history, theme/recreation and unchanged snapshot/build/check counters verified with synthetic caches.\n");
    }
    private void seed(boolean root)throws Exception{
        load(root);closeIndex();String base=root?internal:shared;
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(target.getDatabasePath(id+(root?"-root":"-shared")+"-filenames.db").getPath(),null,SQLiteDatabase.OPEN_READWRITE)){
            String[] paths=root?new String[]{"/","/system",base,base+"/IndexProbe library",base+"/IndexProbe library/Notes A.txt",base+"/IndexProbe library/Notes B.txt","/system/IndexProbe.txt"}:
                new String[]{base,base+"/IndexProbe library",base+"/IndexProbe library/Notes A.txt",base+"/IndexProbe library/Notes B.txt","/storage/IndexProbe-card/IndexProbe.txt"};
            for(String path:paths)db.execSQL("INSERT INTO entries(path,directory,size,modified) VALUES(?,?,?,?)",new Object[]{path,path.endsWith(".txt")?0:1,0,1700000000000L});
            for(int n=0;n<EXTRA;n++)db.execSQL("INSERT INTO entries(path,directory,size,modified) VALUES(?,0,0,1700000000000)",new Object[]{base+"/IndexProbe library/Notes "+String.format(java.util.Locale.ROOT,"%03d",n)+".txt"});
            db.execSQL("INSERT OR REPLACE INTO metadata(name,value) VALUES('last_scan',1700000000000)");
        }
    }
    private void load(boolean root)throws Exception{
        target.getSharedPreferences(id+"-filename-index",0).edit().putBoolean("root_enabled",root).commit();
        index=FileIndex.forProbe(target,fixture,id);index.setProbeLiveWatching(false);index.load();await(()->index.getState().loaded&&!index.hasPendingWork(),"synthetic cache loaded");
        runOnMainSync(()->MainActivity.setProbeIndex(index));
    }
    private void unchanged(List<FileEntry> snapshot,long checked,boolean root){
        check(index.isRootEnabled()==root,"browse keeps access mode");check(index.getState().entries==snapshot,"browse retains exact loaded snapshot");
        check(!index.getState().scanning&&index.fullScanStartsForProbe()==0&&index.wholeReconcileStartsForProbe()==0,"browse starts no builds or whole-cache checks");
        check(index.getState().lastScanMillis==checked,"browse leaves verification timestamp unchanged");
    }
    private void location(String label){runOnMainSync(()->activity.findViewById(R.id.browse_locations).performClick());click(label);waitResults();}
    private void startLocation(String label){overflow("Storage","Start location");click(label);check(target.getSharedPreferences("ui",0).getInt("startLocation",-1)==(label.equals("Internal storage")?0:label.equals("Root")?1:2),"start preference saved: "+label);}
    private void overflow(String group,String leaf){runOnMainSync(()->activity.findViewById(R.id.overflow).performClick());click(group);click(leaf);}
    private EditText query(){return onMain(()->activity.findViewById(R.id.query));}
    private MenuDialog menu(){return (MenuDialog)field(activity,"navigationMenu");}
    private String menuTitle(){return onMain(()->((TextView)menu().findViewById(R.id.menu_title)).getText().toString());}
    private void checkStoragePress(){
        SystemClock.sleep(180);
        ListView list=onMain(()->(ListView)field(menu(),"list"));
        View storage=onMain(()->menuItemRow(list,"Storage"));
        check(storage!=null,"Storage is visible for real touch");
        int[] point=onMain(()->{int[] at=new int[2];storage.getLocationOnScreen(at);
            at[0]+=storage.getWidth()/2;at[1]+=storage.getHeight()/2;return at;});
        long downTime=SystemClock.uptimeMillis();
        check(sendTouch(downTime,MotionEvent.ACTION_DOWN,point),"Storage touch down delivered");
        try{await(()->onMain(storage::isPressed),"Storage row enters pressed state");
            check(onMain(()->feedbackColor(storage)!=Color.TRANSPARENT),"press visibly highlights Storage");
        }finally{check(sendTouch(downTime,MotionEvent.ACTION_UP,point),"Storage touch up delivered");}
        await(()->onMain(()->menuItemRow(list,"Index statistics")!=null),"Storage submenu laid out");
        check(onMain(()->{View statistics=menuItemRow(list,"Index statistics");
            return list.getSelector() instanceof ColorDrawable
                    &&((ColorDrawable)list.getSelector()).getColor()==Color.TRANSPARENT
                    &&!statistics.isPressed()&&feedbackColor(statistics)==Color.TRANSPARENT;
        }),"Storage press leaves Index statistics unhighlighted");
    }
    private static View menuItemRow(ListView list,String title){
        for(int i=0;i<list.getChildCount();i++){
            View row=list.getChildAt(i);
            if(title.contentEquals(((TextView)((ViewGroup)row).getChildAt(0)).getText()))return row;
        }
        return null;
    }
    private static int feedbackColor(View row){
        return row.getBackground()!=null&&row.getBackground().getCurrent() instanceof ColorDrawable
                ?((ColorDrawable)row.getBackground().getCurrent()).getColor():Color.TRANSPARENT;
    }
    private boolean sendTouch(long downTime,int action,int[] at){
        MotionEvent event=MotionEvent.obtain(downTime,SystemClock.uptimeMillis(),action,at[0],at[1],0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try{return getUiAutomation().injectInputEvent(event,true);}finally{event.recycle();}
    }
    private void systemBack(){check(targetWindow(),"target menu has input before injected Back");sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);waitForIdleSync();SystemClock.sleep(160);}
    private void tap(float x,float y){long time=SystemClock.uptimeMillis();android.view.MotionEvent down=android.view.MotionEvent.obtain(time,time,0,x,y,0);
        android.view.MotionEvent up=android.view.MotionEvent.obtain(time,time+25,1,x,y,0);try{check(getUiAutomation().injectInputEvent(down,true)&&getUiAutomation().injectInputEvent(up,true),"real touch delivered");}
        finally{down.recycle();up.recycle();}waitForIdleSync();}
    private String scope(){return onMain(()->(String)field(activity,"folderScope"));}
    private int count(){return onMain(()->((AbsListView)activity.findViewById(R.id.results)).getCount());}
    private void setQuery(String input){runOnMainSync(()->((EditText)activity.findViewById(R.id.query)).setText(input));waitResults();}
    private void openFolder(String path){runOnMainSync(()->{AbsListView list=activity.findViewById(R.id.results);for(int n=0;n<list.getCount();n++){
        Object value=list.getItemAtPosition(n);if(value instanceof FileEntry&&((FileEntry)value).path().equals(path)){list.performItemClick(null,n,n);return;}}
        throw new AssertionError("synthetic directory absent");});waitResults();}
    private void launch(){activity=(MainActivity)startActivitySync(new Intent(target,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(()->index.getState().loaded&&onMain(()->activity.hasWindowFocus()&&activity.findViewById(R.id.results).isShown()),"launched app has focus and cached index");waitResults();
        runOnMainSync(()->{activity.findViewById(R.id.query).clearFocus();target.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(activity.getWindow().getDecorView().getWindowToken(),0);});}
    private void finishActivity(){MainActivity previous=activity;runOnMainSync(previous::finish);await(previous::isDestroyed,"Activity finished");activity=null;}
    private void closeIndex()throws Exception{runOnMainSync(()->MainActivity.setProbeIndex(null));index.shutdown();check(index.awaitShutdown(15_000),"fixture index closed");index=null;}
    private void waitResults(){waitForIdleSync();await(()->onMain(()->!(Boolean)field(activity,"searchRunning")),"results settled");waitForIdleSync();}
    private boolean visible(String value){return nodeState(value,0);}
    private boolean enabled(String value){return nodeState(value,1);}
    private boolean targetWindow(){AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();if(root==null)return false;
        try{return target.getPackageName().contentEquals(root.getPackageName()==null?"":root.getPackageName());}finally{root.recycle();}}
    private boolean nodeState(String value,int state){AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();if(root==null)return false;
        try{List<AccessibilityNodeInfo> nodes=root.findAccessibilityNodeInfosByText(value);boolean found=false;for(AccessibilityNodeInfo node:nodes){
            if(value.contentEquals(node.getText()==null?"":node.getText())&&node.isVisibleToUser())found|=state==0||(state==1?node.isEnabled():node.isChecked());node.recycle();}return found;
        }finally{root.recycle();}}
    private void click(String value){SystemClock.sleep(180);await(()->{AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();if(root==null)return false;
        try{if(!target.getPackageName().contentEquals(root.getPackageName()==null?"":root.getPackageName()))return false;
            List<AccessibilityNodeInfo> nodes=root.findAccessibilityNodeInfosByText(value);boolean clicked=false;for(AccessibilityNodeInfo node:nodes){
            if(!clicked&&node.refresh()&&value.equalsIgnoreCase(node.getText()==null?"":node.getText().toString())&&node.isVisibleToUser()){
                AccessibilityNodeInfo current=AccessibilityNodeInfo.obtain(node);try{for(int depth=0;current!=null&&depth<4;depth++){
                    if(current.isEnabled()&&current.isClickable()&&current.performAction(AccessibilityNodeInfo.ACTION_CLICK)){clicked=true;break;}
                    AccessibilityNodeInfo parent=current.getParent();current.recycle();current=parent;
                }}finally{if(current!=null)current.recycle();}}
            node.recycle();}return clicked;
        }finally{root.recycle();}},"click "+value);SystemClock.sleep(160);waitForIdleSync();}
    private void capture(String name)throws Exception{SystemClock.sleep(350);check(targetWindow(),"only capture isolated app window");Bitmap screen=getUiAutomation().takeScreenshot();check(screen!=null,"screenshot available");
        android.graphics.Rect region=onMain(()->{View root=((ViewGroup)activity.findViewById(android.R.id.content)).getChildAt(0);int[] at=new int[2];root.getLocationOnScreen(at);
            return new android.graphics.Rect(at[0]+root.getPaddingLeft(),at[1]+root.getPaddingTop(),at[0]+root.getWidth()-root.getPaddingRight(),at[1]+root.getHeight()-root.getPaddingBottom());});
        region.intersect(0,0,screen.getWidth(),screen.getHeight());Bitmap image=Bitmap.createBitmap(screen,region.left,region.top,region.width(),region.height());
        File directory=new File(target.getExternalFilesDir(null),"location-screens");if(!directory.isDirectory()&&!directory.mkdirs())throw new java.io.IOException("Cannot create screenshot directory");
        try(FileOutputStream out=new FileOutputStream(new File(directory,name))){check(image.compress(Bitmap.CompressFormat.PNG,100,out),"screenshot saved");}
        finally{if(image!=screen)image.recycle();screen.recycle();}}
    private static Object field(Object owner,String name){try{Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);}catch(Exception failure){throw new AssertionError(failure);}}
    private <T>T onMain(java.util.concurrent.Callable<T> action){Object[] result=new Object[1];Throwable[] failure=new Throwable[1];runOnMainSync(()->{try{result[0]=action.call();}catch(Throwable error){failure[0]=error;}});
        if(failure[0]!=null)throw new AssertionError(failure[0]);@SuppressWarnings("unchecked")T value=(T)result[0];return value;}
    private void await(BooleanSupplier condition,String label){long deadline=SystemClock.elapsedRealtime()+20_000;while(SystemClock.elapsedRealtime()<deadline){if(condition.getAsBoolean())return;SystemClock.sleep(80);}throw new AssertionError("Timed out: "+label);}
    private void check(boolean value,String label){assertions++;if(!value)throw new AssertionError(label);}
    private void cleanup()throws Exception{
        if(activity!=null&&!activity.isDestroyed())finishActivity();runOnMainSync(()->MainActivity.setProbeIndex(null));if(index!=null)closeIndex();
        if(target!=null){if(id!=null){target.deleteDatabase(id+"-root-filenames.db");target.deleteDatabase(id+"-shared-filenames.db");target.deleteSharedPreferences(id+"-filename-index");}
            if(saved!=null){SharedPreferences.Editor edit=target.getSharedPreferences("ui",0).edit().clear();for(Map.Entry<String,?> entry:saved.entrySet()){
                String key=entry.getKey();Object value=entry.getValue();if(value instanceof String)edit.putString(key,(String)value);else if(value instanceof Integer)edit.putInt(key,(Integer)value);
                else if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);else if(value instanceof Long)edit.putLong(key,(Long)value);else if(value instanceof Float)edit.putFloat(key,(Float)value);
                else if(value instanceof java.util.Set){@SuppressWarnings("unchecked")java.util.Set<String> set=(java.util.Set<String>)value;edit.putStringSet(key,set);}}
                check(edit.commit(),"preferences restored");}}
        if(fixture!=null)check(fixture.delete(),"empty owned fixture removed");
    }
}
