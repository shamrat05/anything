package dev.fastsearch.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import dev.fastsearch.core.FileEntry;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Only uses an owned app-specific fixture, never another app's files or permissions. */
public final class AppOwnerBadgeProbe extends Instrumentation {
    private int checks;
    private Activity activity;
    private View row;
    private File fixture;
    private AppSheet properties;

    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();
        Throwable failure=null;
        try{exercise();}catch(Throwable error){failure=error;}
        finally{
            runOnMainSync(()->{
                if(properties!=null)properties.dismiss();
                if(row!=null&&row.getParent() instanceof ViewGroup)((ViewGroup)row.getParent()).removeView(row);
                if(activity!=null)activity.finish();
            });
            if(fixture!=null)fixture.delete();
        }
        result.putBoolean("passed",failure==null);
        result.putString("stream",failure==null?"PASS "+checks+" app owner badge assertions\n"
                :android.util.Log.getStackTraceString(failure));
        finish(failure==null?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }

    private void exercise()throws Exception{
        String pkg=getTargetContext().getPackageName();
        check(pkg.endsWith(".fresh"),"isolated package");
        File directory=getTargetContext().getExternalFilesDir(null);
        check(directory!=null,"owned app folder is available");
        fixture=File.createTempFile("owner-badge-probe-",".txt",directory);
        try(FileOutputStream stream=new FileOutputStream(fixture)){stream.write(65);}
        FileEntry owned=new FileEntry(fixture.getPath(),false,fixture.length(),fixture.lastModified());
        activity=startActivitySync(new Intent(getTargetContext(),MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("permissionProbe",true));
        final Throwable[] error=new Throwable[1];
        runOnMainSync(()->{
            try{
                row=bind(owned,null,true);
                FrameLayout.LayoutParams size=new FrameLayout.LayoutParams(dp(130),-2,Gravity.CENTER);
                activity.addContentView(row,size);
            }catch(Throwable problem){error[0]=problem;}
        });
        if(error[0]!=null)throw new AssertionError("bind owned Grid row",error[0]);
        await(()->onMain(()->row.findViewById(R.id.app_owner_badge).isShown()),"owned file has installed-app badge");
        check(onMain(()->{
            ImageView primary=row.findViewById(R.id.file_icon),owner=row.findViewById(R.id.app_owner_badge);
            View check=row.findViewById(R.id.file_selection_mark);
            Rect mark=new Rect(), art=new Rect(), attribution=new Rect();
            check.getHitRect(mark);primary.getHitRect(art);owner.getHitRect(attribution);
            return primary.getDrawable()!=null&&owner.getDrawable()!=null&&check.isShown()
                    &&attribution.centerY()>art.centerY()&&mark.bottom<attribution.top
                    &&row.getContentDescription().toString().contains("Associated app:")
                    &&((View)primary.getParent()).getContentDescription().toString().contains("Associated app:");
        }),"primary artwork and selection tick stay visible; owner is announced");
        runOnMainSync(()->properties=FileProperties.show(activity,owned,owned.path()));
        await(()->onMain(()->hasText(properties.content,pkg)&&hasText(properties.content,"This does not identify the file’s creator.")),
                "Properties names the installed package and its provenance");
        runOnMainSync(()->{
            properties.dismiss();properties=null;
            try{
                FileEntry unknown=new FileEntry("/storage/emulated/0/Download/"+pkg+".txt",false,1,1);
                bind(unknown,row,false);
            }catch(Throwable problem){error[0]=problem;}
        });
        if(error[0]!=null)throw new AssertionError("recycle owned row",error[0]);
        check(onMain(()->!row.findViewById(R.id.app_owner_badge).isShown()
                &&!row.getContentDescription().toString().contains("Associated app:")),
                "a package-shaped filename in Downloads gets no attribution or recycled accessibility hint");
    }

    @SuppressWarnings("unchecked")
    private View bind(FileEntry entry,View reuse,boolean selected)throws Exception{
        BaseAdapter adapter=(BaseAdapter)field(activity,"adapter").get(activity);
        Field items=field(adapter,"items"),devices=field(adapter,"devices"),layout=field(activity,"activeLayout");
        Field all=field(activity,"allSelected");
        Set<String> selection=(Set<String>)field(activity,"selection").get(activity);
        Object previous=items.get(adapter),previousDevices=devices.get(adapter);
        int oldLayout=layout.getInt(activity);boolean wasAll=all.getBoolean(activity);
        try{
            layout.setInt(activity,3);all.setBoolean(activity,false);
            items.set(adapter,Collections.singletonList(entry));devices.set(adapter,Collections.emptyList());
            if(selected)selection.add(entry.path());
            AbsListView parent=activity.findViewById(R.id.results);
            return adapter.getView(0,reuse,parent);
        }finally{
            selection.remove(entry.path());all.setBoolean(activity,wasAll);layout.setInt(activity,oldLayout);
            items.set(adapter,previous);devices.set(adapter,previousDevices);
        }
    }

    private static boolean hasText(View view,String value){
        if(view instanceof TextView&&view.isShown()&&((TextView)view).getText().toString().contains(value))return true;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)
            if(hasText(((ViewGroup)view).getChildAt(i),value))return true;
        return false;
    }
    private static Field field(Object target,String name)throws Exception{
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field;
    }
    private boolean onMain(BooleanSupplier test){
        boolean[] result=new boolean[1];runOnMainSync(()->result[0]=test.getAsBoolean());return result[0];
    }
    private void await(BooleanSupplier test,String message){
        for(int i=0;i<120;i++){if(test.getAsBoolean()){checks++;return;}SystemClock.sleep(100);}
        throw new AssertionError(message);
    }
    private int dp(int size){return Math.round(size*activity.getResources().getDisplayMetrics().density);}
    private void check(boolean condition,String message){if(!condition)throw new AssertionError(message);checks++;}
}
