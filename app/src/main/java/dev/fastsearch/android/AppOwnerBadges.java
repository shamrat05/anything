package dev.fastsearch.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.ImageView;

import dev.fastsearch.core.FileEntry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** A single visible, coalesced stream for results, storage map and the two browsing panes. */
final class AppOwnerBadges implements AutoCloseable {
    private static final long CACHE_MS=12_000;
    private static final int MAX_CACHE=128;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final AppFileOwners owners;
    private final BooleanSupplier rootAllowed;
    private final int edge;
    private final WeakHashMap<View,Boolean> hosts=new WeakHashMap<>();
    private final LinkedHashMap<Key,Answer> cache=new LinkedHashMap<Key,Answer>(MAX_CACHE,.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<Key,Answer> entry){return size()>MAX_CACHE;}
    };
    private final Rect visible=new Rect();
    private final Runnable dispatch=this::dispatch;
    private Runnable cancel;
    private int revision;
    private boolean closed,paused=true,flinging,inFlight,scheduled,root;
    private final View.OnLayoutChangeListener layout=(v,l,t,r,b,ol,ot,or,ob)->viewportChanged();
    private final View.OnAttachStateChangeListener attachment=new View.OnAttachStateChangeListener(){
        @Override public void onViewAttachedToWindow(View v){viewportChanged();}
        @Override public void onViewDetachedFromWindow(View v){viewportChanged();}
    };

    AppOwnerBadges(Context context,BooleanSupplier rootAllowed){
        this.rootAllowed=rootAllowed;
        this.root=rootAllowed.getAsBoolean();
        edge=Math.round(24*context.getResources().getDisplayMetrics().density);
        owners=new AppFileOwners(context,this::invalidate);
    }

    /** The renderer is stored on the View, not as a value against its weak key. */
    void bind(View host,FileEntry entry,Consumer<AppFileOwners.Badge> renderer){
        assertMain();
        syncRoot();
        Binding previous=binding(host);
        String path=entry==null?null:entry.path();
        if(closed||path==null||!candidate(entry,path,root)){
            if(previous!=null){
                host.setTag(R.id.app_owner_binding,null);hosts.remove(host);
                host.removeOnAttachStateChangeListener(attachment);host.removeOnLayoutChangeListener(layout);
                renderer.accept(null);
            }
            return;
        }
        Key key=new Key(path,entry.size,entry.modified);
        boolean same=previous!=null&&previous.key.equals(key);
        if(previous==null){
            hosts.put(host,Boolean.TRUE);host.addOnAttachStateChangeListener(attachment);host.addOnLayoutChangeListener(layout);
        }
        Binding next=new Binding(key,entry,renderer);
        host.setTag(R.id.app_owner_binding,next);
        Answer answer=cache.get(key);
        next.badge=!paused&&answer!=null&&answer.expires>SystemClock.uptimeMillis()?answer.badge:null;
        // The primary row description is rebound even if this is the same entry.
        if(!same||previous.badge!=next.badge||next.badge!=null)renderer.accept(next.badge);
        viewportChanged();
    }

    static boolean candidate(FileEntry entry,String path,boolean root){
        if(path.contains("/Android/data/")||path.contains("/Android/media/")||path.contains("/Android/obb/"))return true;
        if(root&&(path.startsWith("/data/user/")||path.startsWith("/data/user_de/")||path.startsWith("/data/data/")))return true;
        if(Build.VERSION.SDK_INT<29||entry.directory||entry.size<0||entry.modified<=0)return false;
        int type=FileTypes.category(entry);
        return type==3||type==6||type==7;
    }

    void viewportChanged(){
        if(!closed&&!paused&&!flinging&&!inFlight&&!scheduled){scheduled=true;main.post(dispatch);}
    }

    void setFlinging(boolean value){flinging=value;if(!value)viewportChanged();}
    void pause(){paused=true;invalidate();}
    void resume(){paused=false;flinging=false;viewportChanged();}
    @Override public void close(){if(!closed){pause();closed=true;owners.close();hosts.clear();}}

    private void syncRoot(){
        boolean next=rootAllowed.getAsBoolean();
        if(next!=root){root=next;invalidate();}
    }

    private void invalidate(){
        assertMain();
        revision++;scheduled=false;main.removeCallbacks(dispatch);
        if(cancel!=null){cancel.run();cancel=null;}
        inFlight=false;cache.clear();
        for(View view:new ArrayList<>(hosts.keySet())){
            Binding current=binding(view);
            if(current!=null&&current.badge!=null){current.badge=null;current.render.accept(null);}
        }
        viewportChanged();
    }

    private void dispatch(){
        scheduled=false;
        if(closed||paused||flinging||inFlight)return;
        syncRoot();
        LinkedHashMap<Key,FileEntry> missing=new LinkedHashMap<>();
        Map<String,Key> considered=new LinkedHashMap<>();
        long now=SystemClock.uptimeMillis();
        for(View host:new ArrayList<>(hosts.keySet())){
            Binding current=binding(host);
            if(current==null||!host.isAttachedToWindow()||!host.isShown()||!host.getGlobalVisibleRect(visible)
                    ||!candidate(current.entry,current.key.path,root))continue;
            Key chosen=considered.get(current.key.path);
            if(chosen==null&&considered.size()<AppFileOwners.MAX_ROWS){
                chosen=current.key;considered.put(current.key.path,chosen);
            }
            // Two stale directory snapshots can carry different metadata for the same exact path.
            if(!current.key.equals(chosen)){apply(current,null);continue;}
            Answer answer=cache.get(current.key);
            if(answer!=null&&answer.expires>now){apply(current,answer.badge);continue;}
            // An expired association is not evidence; clear it while it is being checked again.
            apply(current,null);
            missing.putIfAbsent(current.key,current.entry);
        }
        if(missing.isEmpty())return;
        int token=revision;
        inFlight=true;
        Runnable requested=owners.request(new ArrayList<>(missing.values()),root,edge,reply->{
            if(token!=revision||closed)return;
            inFlight=false;cancel=null;
            long expires=SystemClock.uptimeMillis()+CACHE_MS;
            for(Key key:missing.keySet())cache.put(key,new Answer(reply.get(key.path),expires));
            // Resolve against current bindings, never the positions/views captured by the request.
            viewportChanged();
        });
        if(token==revision&&inFlight)cancel=requested;
    }

    private static Binding binding(View view){return view==null?null:(Binding)view.getTag(R.id.app_owner_binding);}
    private static void apply(Binding binding,AppFileOwners.Badge badge){
        if(binding.badge!=badge){binding.badge=badge;binding.render.accept(badge);}
    }
    private static void assertMain(){
        if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("Badges must bind on main");
    }
    private static final class Binding {
        final Key key; final FileEntry entry; final Consumer<AppFileOwners.Badge> render;
        AppFileOwners.Badge badge;
        Binding(Key key,FileEntry entry,Consumer<AppFileOwners.Badge> render){this.key=key;this.entry=entry;this.render=render;}
    }
    private static final class Answer {
        final AppFileOwners.Badge badge; final long expires;
        Answer(AppFileOwners.Badge badge,long expires){this.badge=badge;this.expires=expires;}
    }
    private static final class Key {
        final String path; final long size,modified;
        Key(String path,long size,long modified){this.path=path;this.size=size;this.modified=modified;}
        @Override public boolean equals(Object other){
            if(!(other instanceof Key))return false;
            Key key=(Key)other;return size==key.size&&modified==key.modified&&path.equals(key.path);
        }
        @Override public int hashCode(){return 31*(31*path.hashCode()+Long.hashCode(size))+Long.hashCode(modified);}
    }

    static ImageView overlay(Context context){
        ImageView image=new ImageView(context);
        image.setId(R.id.app_owner_badge);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        image.setScaleType(ImageView.ScaleType.FIT_XY);
        image.setVisibility(View.INVISIBLE);
        return image;
    }

    static void present(ImageView badge,UiTheme theme,AppFileOwners.Badge value){
        Object old=badge.getTag();
        if(old==value)return;
        badge.setTag(value);
        badge.setImageDrawable(value==null?null:new Artwork(theme,value.bitmap));
        badge.setVisibility(value==null?View.INVISIBLE:View.VISIBLE);
    }

    static String describe(String label,AppFileOwners.Badge value){
        return value==null?label:label+", "+value.accessibilityLabel();
    }

    /** Installed icon on a neutral, contrasting ring; no icon substitution. */
    static final class Artwork extends Drawable {
        private final Bitmap bitmap;
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        private final RectF inner=new RectF();
        private final Path clip=new Path();
        private final int ring,base;
        Artwork(UiTheme theme,Bitmap bitmap){
            this.bitmap=bitmap;
            ring=theme.dark?0xffe2e8f2:0xff34465d;
            base=theme.dark?0xff202a3a:0xfff6f8fc;
        }
        @Override protected void onBoundsChange(Rect bounds){
            float inset=Math.max(1f,Math.min(bounds.width(),bounds.height())*.09f);
            inner.set(bounds.left+inset,bounds.top+inset,bounds.right-inset,bounds.bottom-inset);
            clip.reset();clip.addOval(inner,Path.Direction.CW);
        }
        @Override public void draw(Canvas canvas){
            Rect bounds=getBounds();
            paint.setColor(ring);canvas.drawOval(bounds.left,bounds.top,bounds.right,bounds.bottom,paint);
            int layer=canvas.save();
            canvas.clipPath(clip);
            paint.setColor(base);canvas.drawOval(inner,paint);canvas.drawBitmap(bitmap,null,inner,paint);
            canvas.restoreToCount(layer);
        }
        @Override public void setAlpha(int alpha){}
        @Override public void setColorFilter(ColorFilter filter){}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
}
