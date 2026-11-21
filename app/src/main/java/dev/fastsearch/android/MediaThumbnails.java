package dev.fastsearch.android;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.OperationCanceledException;
import android.provider.MediaStore;
import android.util.Size;
import dev.fastsearch.core.FileEntry;

/** Reuses provider thumbnails for large shared images/video; private root paths stay local. */
final class MediaThumbnails {
    private static volatile int denied;
    private static final java.util.concurrent.atomic.AtomicInteger hits=new java.util.concurrent.atomic.AtomicInteger();
    static Bitmap load(Context context,FileEntry entry,int edge,boolean root,CancellationSignal signal){
        int category=FileTypes.category(entry),kind=category==7?2:1;
        if(Build.VERSION.SDK_INT<29||entry.hidden||(category!=6&&category!=7)||(denied&kind)!=0||(category==6&&entry.size<1024*1024))return null;
        String alias=Environment.getExternalStorageDirectory().getAbsolutePath(),path=entry.path();
        if(root&&alias.startsWith("/storage/emulated/")){String backing="/data/media/"+new java.io.File(alias).getName();if(path.startsWith(backing+"/"))path=alias+path.substring(backing.length());}
        if(!path.startsWith(alias+"/")&&(!path.startsWith("/storage/")||!path.matches("/storage/[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}/.+")))return null;
        ContentResolver resolver=context.getContentResolver();
        Uri collection=category==7?MediaStore.Video.Media.EXTERNAL_CONTENT_URI:MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        try{
            signal.throwIfCanceled();
            try(Cursor cursor=resolver.query(collection,new String[]{MediaStore.MediaColumns._ID,MediaStore.MediaColumns.VOLUME_NAME},
                    MediaStore.MediaColumns.DATA+"=? AND "+MediaStore.MediaColumns.SIZE+"=? AND "+MediaStore.MediaColumns.DATE_MODIFIED+"=?",
                    new String[]{path,Long.toString(entry.size),Long.toString(entry.modified/1000)},null,signal)){
                if(cursor==null||!cursor.moveToFirst())return null;
                String volume=cursor.getString(1);if(volume==null)return null;
                Uri source=ContentUris.withAppendedId(category==7?MediaStore.Video.Media.getContentUri(volume):MediaStore.Images.Media.getContentUri(volume),cursor.getLong(0));
                Bitmap bitmap=resolver.loadThumbnail(source,new Size(edge,edge),signal);
                if(bitmap!=null){hits.incrementAndGet();if(category==7)bitmap.setHasAlpha(false);
                    int longest=Math.max(bitmap.getWidth(),bitmap.getHeight());if(longest>edge){float scale=edge/(float)longest;Bitmap fitted=Bitmap.createScaledBitmap(bitmap,Math.max(1,Math.round(bitmap.getWidth()*scale)),Math.max(1,Math.round(bitmap.getHeight()*scale)),true);if(fitted!=bitmap)bitmap.recycle();bitmap=fitted;}}
                return bitmap;
            }
        }catch(SecurityException unavailable){denied|=kind;return null;}
        catch(OperationCanceledException stopped){return null;}
        catch(java.io.IOException|RuntimeException unavailable){return null;}
    }
    static int hitsForProbe(){return hits.get();}
    private MediaThumbnails(){}
}
