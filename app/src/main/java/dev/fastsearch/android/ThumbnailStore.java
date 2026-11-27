package dev.fastsearch.android;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;

/** Process-wide bitmaps and bounded private disk copies; never stores Views or Activities. */
final class ThumbnailStore {
    private static final int LIMIT=(int)Math.min(24L*1024*1024,Math.max(2L*1024*1024,Runtime.getRuntime().maxMemory()/20));
    private static final LruCache<String,Bitmap> MEMORY=new LruCache<String,Bitmap>(LIMIT){@Override protected int sizeOf(String key,Bitmap bitmap){return bitmap.getAllocationByteCount();}};
    private static boolean registered;
    private static final Object DISK=new Object();
    private static long diskBytes=-1;
    private static int diskCount;
    static synchronized void register(Context context){if(registered)return;registered=true;context.getApplicationContext().registerComponentCallbacks(new ComponentCallbacks2(){
        public void onConfigurationChanged(Configuration configuration){}public void onLowMemory(){clearMemory();}
        public void onTrimMemory(int level){if(level==TRIM_MEMORY_RUNNING_LOW||level==TRIM_MEMORY_RUNNING_CRITICAL||level>=TRIM_MEMORY_BACKGROUND)clearMemory();}
    });}
    static Bitmap get(String key){return MEMORY.get(key);}
    static void put(String key,Bitmap bitmap){MEMORY.put(key,bitmap);}
    static int bytes(){return MEMORY.size();}static int limit(){return LIMIT;}static void clearMemory(){MEMORY.evictAll();}
    private static File folder(Context context){return new File(context.getCacheDir(),".thumbnails-v2");}
    private static File file(Context context,String key){int end=key.indexOf('\0');return new File(folder(context),hash(end<0?key:key.substring(0,end))+"-"+hash(key)+".thumb");}
    static Bitmap read(Context context,String key){File file=file(context,key);if(!file.isFile())return null;
        BitmapFactory.Options options=new BitmapFactory.Options();options.inScaled=false;options.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getPath(),options);if(options.outWidth<=0||options.outHeight<=0||options.outWidth>768||options.outHeight>768){discard(file);return null;}options.inJustDecodeBounds=false;
        Bitmap bitmap=BitmapFactory.decodeFile(file.getPath(),options);
        if(bitmap!=null){if(bitmap.getWidth()>768||bitmap.getHeight()>768){bitmap.recycle();discard(file);return null;}file.setLastModified(System.currentTimeMillis());}
        else discard(file);return bitmap;
    }
    static void write(Context context,String key,Bitmap bitmap){if(Thread.currentThread().isInterrupted())return;
        synchronized(DISK){File folder=folder(context);if(!folder.isDirectory()){if(!folder.mkdirs())return;diskBytes=0;diskCount=0;}if(diskBytes<0)inventory(folder);File pending=null;
            try{pending=File.createTempFile("pending-",".tmp",folder);try(FileOutputStream out=new FileOutputStream(pending)){if(!bitmap.compress(bitmap.hasAlpha()?Bitmap.CompressFormat.PNG:Bitmap.CompressFormat.JPEG,85,out))return;}
                if(Thread.currentThread().isInterrupted())return;File destination=file(context,key);boolean existed=destination.exists();long old=destination.length(),size=pending.length();if(!pending.renameTo(destination))return;diskBytes+=size-old;if(!existed)diskCount++;
                if(diskBytes>96L*1024*1024||diskCount>1500)trim(folder);
            }catch(Exception ignored){}finally{if(pending!=null)pending.delete();}}
    }
    private static void inventory(File folder){diskBytes=0;diskCount=0;File[] files=folder.listFiles();if(files!=null)for(File file:files){if(file.getName().endsWith(".tmp")&&file.delete())continue;diskBytes+=file.length();diskCount++;}}
    private static void discard(File file){synchronized(DISK){long size=file.length();if(file.delete()&&diskBytes>=0){diskBytes=Math.max(0,diskBytes-size);diskCount=Math.max(0,diskCount-1);}}}
    private static void trim(File folder){File[] files=folder.listFiles();if(files==null)return;Arrays.sort(files,Comparator.comparingLong(File::lastModified));
        for(File file:files){if(diskBytes<=80L*1024*1024&&diskCount<=1200)break;discard(file);}
    }
    static void invalidate(Context context,java.util.Collection<String> paths){
        for(String key:MEMORY.snapshot().keySet()){int end=key.indexOf('\0');String path=end<0?key:key.substring(0,end);for(String changed:paths)if(path.equals(changed)||path.startsWith(changed+"/")){MEMORY.remove(key);break;}}
        java.util.Set<String> prefixes=new java.util.HashSet<>();for(String path:paths)prefixes.add(hash(path));
        synchronized(DISK){File[] files=folder(context).listFiles();if(files!=null)for(File file:files){String name=file.getName();if(name.length()>64&&prefixes.contains(name.substring(0,64)))discard(file);}}
    }
    private static final java.util.regex.Pattern GENERATED=java.util.regex.Pattern.compile("[0-9a-f]{64}-[0-9a-f]{64}[.]thumb");

    static final class CacheReview {
        private final java.nio.file.Path root;
        private final Object directoryKey;
        final java.util.Map<String,java.nio.file.attribute.BasicFileAttributes> entries;
        final long bytes;
        final int skipped;
        private CacheReview(java.nio.file.Path root,Object directoryKey,
                            java.util.Map<String,java.nio.file.attribute.BasicFileAttributes> entries,long bytes,int skipped){
            this.root=root;this.directoryKey=directoryKey;this.entries=java.util.Collections.unmodifiableMap(entries);
            this.bytes=bytes;this.skipped=skipped;
        }
    }
    static final class CacheReceipt {
        int removed,skipped;
        long bytes;
    }
    static CacheReview reviewCache(Context context){
        synchronized(DISK){
            java.util.Map<String,java.nio.file.attribute.BasicFileAttributes> entries=new java.util.LinkedHashMap<>();
            java.nio.file.Path root=folder(context).toPath();
            Object key=null;long bytes=0;int skipped=0;
            try{
                if(java.nio.file.Files.notExists(root,java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    return new CacheReview(root,null,entries,0,0);
                root=confirmedCachePath(context);
                key=directCacheKey(root);
                try(java.nio.file.DirectoryStream<java.nio.file.Path> files=java.nio.file.Files.newDirectoryStream(root)){
                    for(java.nio.file.Path path:files){
                        if(!GENERATED.matcher(path.getFileName().toString()).matches())continue;
                        if(entries.size()==1600){skipped++;break;}
                        try{
                            java.nio.file.attribute.BasicFileAttributes attrs=cacheAttributes(path);
                            if(!attrs.isRegularFile()||attrs.fileKey()==null){skipped++;continue;}
                            entries.put(path.getFileName().toString(),attrs);bytes+=attrs.size();
                        }catch(java.io.IOException|SecurityException inaccessible){skipped++;}
                    }
                }
            }catch(java.io.IOException|RuntimeException unavailable){skipped++;}
            return new CacheReview(root,key,entries,bytes,skipped);
        }
    }
    static CacheReceipt clearReviewedCache(Context context,CacheReview review){
        CacheReceipt receipt=new CacheReceipt();
        receipt.skipped=review.skipped;
        synchronized(DISK){
            try{
                java.nio.file.Path root=confirmedCachePath(context);
                if(!root.equals(review.root)||review.directoryKey==null||!review.directoryKey.equals(directCacheKey(root))){
                    receipt.skipped+=review.entries.size();return receipt;
                }
                for(java.util.Map.Entry<String,java.nio.file.attribute.BasicFileAttributes> row:review.entries.entrySet()){
                    // The app's writes and deletion share DISK. Recheck the reviewed generation;
                    // do not touch new files, links, stale paths, or unrecognized cache entries.
                    java.nio.file.Path path=root.resolve(row.getKey());
                    try{
                        java.nio.file.attribute.BasicFileAttributes old=row.getValue(),current=cacheAttributes(path);
                        if(!current.isRegularFile()||!old.fileKey().equals(current.fileKey())||old.size()!=current.size()
                                ||!old.lastModifiedTime().equals(current.lastModifiedTime())){
                            receipt.skipped++;continue;
                        }
                        java.nio.file.Files.delete(path);receipt.removed++;receipt.bytes+=current.size();
                    }catch(java.io.IOException|SecurityException changed){receipt.skipped++;}
                }
                diskBytes=-1;
                diskCount=0;
            }catch(java.io.IOException|RuntimeException inaccessible){receipt.skipped+=review.entries.size();}
        }
        return receipt;
    }
    private static java.nio.file.Path confirmedCachePath(Context context)throws java.io.IOException{
        // Android can expose the same private cache parent using a system-controlled alias.
        return context.getCacheDir().getCanonicalFile().toPath().resolve(".thumbnails-v2");
    }
    private static Object directCacheKey(java.nio.file.Path root)throws java.io.IOException{
        java.nio.file.attribute.BasicFileAttributes attrs=cacheAttributes(root);
        if(!attrs.isDirectory()||attrs.fileKey()==null||!root.toRealPath().equals(root))
            throw new java.io.IOException("Generated cache is not a direct directory.");
        return attrs.fileKey();
    }
    private static java.nio.file.attribute.BasicFileAttributes cacheAttributes(java.nio.file.Path path)throws java.io.IOException{
        return java.nio.file.Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class,
                java.nio.file.LinkOption.NOFOLLOW_LINKS);
    }
    private static String hash(String value){try{byte[] digest=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));char[] hex=new char[digest.length*2],digits="0123456789abcdef".toCharArray();for(int i=0;i<digest.length;i++){hex[i*2]=digits[(digest[i]&255)>>>4];hex[i*2+1]=digits[digest[i]&15];}return new String(hex);}catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    private ThumbnailStore(){}
}
