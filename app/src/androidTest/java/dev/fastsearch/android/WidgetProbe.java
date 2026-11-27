package dev.fastsearch.android;

import android.app.Activity;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.AutoCompleteTextView;
import android.widget.FrameLayout;
import android.widget.RemoteViews;
import android.widget.TextView;
import android.widget.Spinner;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;


/** Disposable-device widget test. Uses Android’s normal host binding consent if no grant exists. */
public final class WidgetProbe extends Instrumentation {
    private int assertions;
    private Activity activity;
    private AppWidgetHost host;
    private int searchId=-1,filesId=-1;
    private Context target;
    private String phase;
    private final StringBuilder timings=new StringBuilder();

    @Override public void onCreate(Bundle arguments){
        super.onCreate(arguments);phase=arguments==null?"all":arguments.getString("phase","all");start();
    }

    @Override public void onStart(){
        Bundle result=new Bundle();
        boolean passed=false;
        try{
            target=getTargetContext();runChecks();passed=true;
        }catch(Throwable error){
            StringWriter stack=new StringWriter();error.printStackTrace(new PrintWriter(stack));
            result.putString("stream","Widget assertion "+assertions+": "+stack);
        }finally{
            if(host!=null||activity!=null){
                boolean leaveBound=passed&&"bind".equals(phase);
                runOnMainSync(()->{
                    if(activity!=null)activity.finishAndRemoveTask();
                    if(host!=null){
                        if(!leaveBound&&searchId>=0)host.deleteAppWidgetId(searchId);
                        if(!leaveBound&&filesId>=0)host.deleteAppWidgetId(filesId);
                        host.stopListening();
                    }
                });
            }
        }
        if(passed)result.putString("stream",timings+"PASS "+assertions+" widgets and launch assertions\n");
        result.putBoolean("passed",passed);
        finish(passed?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }

    private void runChecks(){
        target.getSharedPreferences("ui",Context.MODE_PRIVATE).edit().putBoolean("storageMap",true)
                .putString("query","stale search").commit();
        AppWidgetManager manager=AppWidgetManager.getInstance(target);
        AppWidgetProviderInfo search=provider(manager,AnythingWidgetProvider.Search.class);
        AppWidgetProviderInfo files=provider(manager,AnythingWidgetProvider.Files.class);
        check(search!=null&&files!=null,"both widget providers visible to host");
        check(search.updatePeriodMillis==0&&files.updatePeriodMillis==0,"no background widget updates");
        check(!search.provider.equals(files.provider),"distinct launcher entries");

        check(search.minHeight>=48&&files.minHeight>=48,"both tap surfaces have a 48dp minimum");
        if(android.os.Build.VERSION.SDK_INT>=31)
            check(search.targetCellWidth==2&&search.targetCellHeight==1
                    &&files.targetCellWidth==2&&files.targetCellHeight==1,"Android 12+ 2 by 1 placement hint");

        if(phase.startsWith("direct")){
            runDirectRoutes(search,files);
            return;
        }
        runOnMainSync(()->{host=new AppWidgetHost(target,82351);host.startListening();});
        android.content.SharedPreferences fixture=target.getSharedPreferences("widget-probe",Context.MODE_PRIVATE);
        if("restart".equals(phase)){
            searchId=fixture.getInt("search",-1);filesId=fixture.getInt("files",-1);
            check(search.provider.equals(manager.getAppWidgetInfo(searchId).provider),"Search binding survives app restart");
            check(files.provider.equals(manager.getAppWidgetInfo(filesId).provider),"Files binding survives app restart");
        }else{
            searchId=host.allocateAppWidgetId();filesId=host.allocateAppWidgetId();
            Activity[] bindingActivity={null};
            try{
                bind(manager,searchId,search.provider,bindingActivity);
                bind(manager,filesId,files.provider,bindingActivity);
            }finally{
                if(bindingActivity[0]!=null){
                    Activity setup=bindingActivity[0];
                    runOnMainSync(setup::finishAndRemoveTask);
                    await(()->onMain(setup::isDestroyed),"finish temporary Activity so next widget tap starts a new activity");
                }
            }
            if("bind".equals(phase))check(fixture.edit().putInt("search",searchId).putInt("files",filesId).commit(),"retain widget IDs for second process");
        }
        check(searchId!=filesId,"host allocated two independent widget instances");
        AppWidgetHostView[] tiles=new AppWidgetHostView[2];
        runOnMainSync(()->{tiles[0]=host.createView(target,searchId,search);tiles[1]=host.createView(target,filesId,files);});
        await(()->onMain(()->clickableTile(tiles[0])!=null&&clickableTile(tiles[1])!=null),"Android supplied clickable RemoteViews to the host");

        ActivityMonitor launches=addMonitor(MainActivity.class.getName(),null,false);
        long searchTap;
        if("restart".equals(phase)){
            long filesTap=SystemClock.elapsedRealtime();
            check(onMain(()->clickableTile(tiles[1]).performClick()),"tap existing Files widget after process restart");
            activity=launches.waitForActivityWithTimeout(10000);
            check(activity instanceof MainActivity,"Files starts MainActivity cold");
            awaitFiles("Files opens the explorer after app restart without keyboard focus");
            record("Files widget restart route",filesTap);
            searchTap=SystemClock.elapsedRealtime();
            check(onMain(()->clickableTile(tiles[0]).performClick()),"tap existing Search widget after process restart");
        }else{
            searchTap=SystemClock.elapsedRealtime();
            check(onMain(()->clickableTile(tiles[0]).performClick()),"tap actual Search widget");
            activity=launches.waitForActivityWithTimeout(10000);
            check(activity instanceof MainActivity,"Search starts MainActivity cold");
        }
        await(()->onMain(()->query().isFocused()&&query().length()==0&&category()==0),"Search focuses an empty global query");
        record("Search widget "+("restart".equals(phase)?"warm":"cold")+" route",searchTap);
        check(onMain(()->!query().getContentDescription().toString().startsWith("Search in ")),"Search did not retain old folder scope");
        long warmFilesTap=SystemClock.elapsedRealtime();
        check(onMain(()->{query().setText("discarded");return clickableTile(tiles[1]).performClick();}),"tap actual Files widget");
        awaitFiles("Files routes on warm activity without keyboard focus");
        check(onMain(()->"Search internal storage".contentEquals(query().getHint())),"Files opens internal storage");
        record("Files widget warm route",warmFilesTap);

        check(onMain(()->clickableTile(tiles[0]).performClick()),"tap Search from Files");
        await(()->onMain(()->category()==0&&query().isFocused()&&query().length()==0),"Search returns to global results on warm activity");
        check(AnythingWidgetProvider.destination(new Intent("dev.fastsearch.android.widget.SEARCH")
                .setData(Uri.parse("content://test.invalid/file")))==AnythingWidgetProvider.NONE,"widget mode rejects incoming URI");
        check(AnythingWidgetProvider.destination(new Intent(Intent.ACTION_MAIN))==AnythingWidgetProvider.NONE,"normal launches keep their saved mode");
        removeMonitor(launches);
    }

    // Fallback when a headless test cannot drive the Settings dialog. It verifies actual widget
    // resources and click PendingIntents, not home-screen placement or retained launcher IDs.
    private void runDirectRoutes(AppWidgetProviderInfo search,AppWidgetProviderInfo files){
        check(search.initialLayout==R.layout.widget_search&&files.initialLayout==R.layout.widget_files,
                "providers expose distinct Search and Files resources");
        check(!AnythingWidgetProvider.shortcut(target,AnythingWidgetProvider.SEARCH)
                .equals(AnythingWidgetProvider.shortcut(target,AnythingWidgetProvider.FILES)),
                "PendingIntents are two distinct destinations");
        View[] tiles=new View[2];
        runOnMainSync(()->{
            tiles[0]=applyWidget(search,AnythingWidgetProvider.SEARCH);
            tiles[1]=applyWidget(files,AnythingWidgetProvider.FILES);
        });
        check("Search".contentEquals(tileLabel(tiles[0]))&&"Files".contentEquals(tileLabel(tiles[1])),
                "real resources render distinct Search and Files labels");
        boolean startFiles="directRestart".equals(phase);
        ActivityMonitor launches=addMonitor(MainActivity.class.getName(),null,false);
        long tap=SystemClock.elapsedRealtime();
        check(onMain(()->tiles[startFiles?1:0].performClick()),"click the actual widget RemoteViews action");
        activity=launches.waitForActivityWithTimeout(10000);
        check(activity instanceof MainActivity,"widget launches the real MainActivity");
        if(startFiles){
            awaitFiles("Files cold starts after process restart without keyboard focus");
            record("Files widget cold route",tap);
            tap=SystemClock.elapsedRealtime();
            check(onMain(()->tiles[0].performClick()),"actual Search click after process restart");
            await(()->onMain(()->category()==0&&query().length()==0&&query().isFocused()),"Search is focused from existing Files view");
            record("Search widget warm route",tap);
        }else{
            await(()->onMain(()->category()==0&&query().length()==0&&query().isFocused()),"Search opens a blank focused global query");
            record("Search widget cold route",tap);
            tap=SystemClock.elapsedRealtime();
            check(onMain(()->tiles[1].performClick()),"actual Files click from Search");
            awaitFiles("Files opens the internal storage explorer without keeping Search focus");
            record("Files widget warm route",tap);
        }
        removeMonitor(launches);
        timings.append("RemoteViews fallback: no launcher binding or retained widget IDs claimed.\n");
    }

    private View applyWidget(AppWidgetProviderInfo info,int destination){
        RemoteViews view=new RemoteViews(target.getPackageName(),info.initialLayout);
        view.setOnClickPendingIntent(android.R.id.background,AnythingWidgetProvider.shortcut(target,destination));
        View tile=view.apply(target,new FrameLayout(target));
        tile.measure(View.MeasureSpec.makeMeasureSpec(140,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(56,View.MeasureSpec.EXACTLY));
        tile.layout(0,0,140,56);
        return tile.findViewById(android.R.id.background);
    }

    private CharSequence tileLabel(View tile){
        android.view.ViewGroup row=(android.view.ViewGroup)tile;
        for(int i=0;i<row.getChildCount();i++)
            if(row.getChildAt(i) instanceof TextView)return ((TextView)row.getChildAt(i)).getText();
        return "";
    }

    private void bind(AppWidgetManager manager,int widgetId,ComponentName provider,Activity[] setup){
        if(manager.bindAppWidgetIdIfAllowed(widgetId,provider)){
            check(true,"host already allowed to bind "+provider.getShortClassName());
            return;
        }
        if(setup[0]==null){
            setup[0]=startActivitySync(new Intent(target,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        UiAutomation automation=getUiAutomation();
        AccessibilityServiceInfo service=automation.getServiceInfo();
        if(service!=null){
            service.flags|=AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS|AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            automation.setServiceInfo(service);
        }
        await(()->onMain(setup[0]::hasWindowFocus),"widget host is in foreground before requesting consent",20000);
        Intent consent=new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,widgetId)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER,provider);
        runOnMainSync(()->setup[0].startActivityForResult(consent,766));
        await(()->acceptBindingConsent(automation),"Android displays and accepts the widget binding confirmation",25000);
        await(()->{
            AppWidgetProviderInfo actual=manager.getAppWidgetInfo(widgetId);
            return actual!=null&&provider.equals(actual.provider);
        },"Android binds "+provider.getShortClassName()+" to its allocated widget ID");
    }

    private boolean acceptBindingConsent(UiAutomation automation){
        if(clickBindingConsent(automation.getRootInActiveWindow()))return true;
        boolean accepted=false;
        for(AccessibilityWindowInfo window:automation.getWindows()){
            try{
                if(!accepted)accepted=clickBindingConsent(window.getRoot());
            }finally{window.recycle();}
        }
        return accepted;
    }

    private boolean clickBindingConsent(AccessibilityNodeInfo root){
        if(root==null)return false;
        try{
            if(!"com.android.settings".contentEquals(String.valueOf(root.getPackageName())))return false;
            List<AccessibilityNodeInfo> titles=root.findAccessibilityNodeInfosByText("Create widget and allow access");
            boolean matching=!titles.isEmpty();
            for(AccessibilityNodeInfo title:titles)title.recycle();
            if(!matching)return false;
            List<AccessibilityNodeInfo> positive=root.findAccessibilityNodeInfosByViewId("android:id/button1");
            boolean accepted=false;
            for(AccessibilityNodeInfo button:positive){
                try{
                    String label=button.getText()==null?"":button.getText().toString().toLowerCase(Locale.ROOT);
                    if(!accepted&&("create".equals(label)||"allow".equals(label)||"add".equals(label)
                            ||"create widget".equals(label)||"add widget".equals(label)))
                        accepted=button.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                }finally{button.recycle();}
            }
            return accepted;
        }finally{root.recycle();}
    }

    private void awaitFiles(String label){
        try{
            await(()->onMain(()->category()==FileTypes.LABELS.length&&query().length()==0&&!query().isFocused()),label);
        }catch(AssertionError failure){
            String[] state={""};
            runOnMainSync(()->state[0]=" category="+category()+", query length="+query().length()
                    +", query focused="+query().isFocused()+", hint="+query().getHint());
            throw new AssertionError(label+state[0],failure);
        }
    }

    private void record(String label,long tapped){
        timings.append(label).append(": ").append(SystemClock.elapsedRealtime()-tapped)
                .append(" ms (emulator, includes test polling)\n");
    }

    private AppWidgetProviderInfo provider(AppWidgetManager manager,Class<?> type){
        ComponentName name=new ComponentName(target,type);
        for(AppWidgetProviderInfo item:manager.getInstalledProviders())
            if(name.equals(item.provider))return item;
        return null;
    }
    private View clickableTile(AppWidgetHostView view){
        if(view==null)return null;
        for(int i=0;i<view.getChildCount();i++){
            View tile=view.getChildAt(i).findViewById(android.R.id.background);
            if(tile!=null&&tile.hasOnClickListeners())return tile;
        }
        return null;
    }
    private AutoCompleteTextView query(){return activity.findViewById(R.id.query);}
    private int category(){return ((Spinner)activity.findViewById(R.id.category)).getSelectedItemPosition();}
    private boolean onMain(BooleanSupplier action){boolean[] value={false};runOnMainSync(()->value[0]=action.getAsBoolean());return value[0];}
    private void await(BooleanSupplier condition,String label){await(condition,label,10000);}
    private void await(BooleanSupplier condition,String label,long timeout){
        long end=SystemClock.elapsedRealtime()+timeout;
        while(SystemClock.elapsedRealtime()<end){
            if(condition.getAsBoolean()){check(true,label);return;}
            SystemClock.sleep(80);
        }
        check(false,label);
    }
    private void check(boolean result,String label){assertions++;if(!result)throw new AssertionError(label);}
}
