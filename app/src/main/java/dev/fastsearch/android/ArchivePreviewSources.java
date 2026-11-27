package dev.fastsearch.android;

import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Short-lived private read-only descriptors; never stage decoded archive member paths. */
final class ArchivePreviewSources {
    static final int MAX_DEPTH=4;
    private static final long BUDGET=80L*1024*1024;
    private static final String AUTHORITY=BuildConfig.APPLICATION_ID+".files";
    private static final String PREFIX="local-archive-view";
    private static final Map<Uri,Source> OPEN=new LinkedHashMap<>();
    private static long bytes;
    private static final class Source{
        final ParcelFileDescriptor file;
        final int depth;
        final long size;
        Source(ParcelFileDescriptor file,long size,int depth){this.file=file;this.size=size;this.depth=depth;}
    }

    static boolean matches(Uri uri){
        return uri!=null&&"content".equals(uri.getScheme())&&AUTHORITY.equals(uri.getAuthority())
                &&uri.getPathSegments().size()==3&&PREFIX.equals(uri.getPathSegments().get(0));
    }
    static synchronized int depth(Uri uri)throws IOException{
        if(!matches(uri))return 0;
        Source source=OPEN.get(uri);
        if(source==null)throw new IOException("This archive entry expired. Reopen it from the outer archive.");
        return source.depth;
    }

    static synchronized Uri publish(ParcelFileDescriptor file,long size,String name,int depth)throws IOException{
        if(depth<1||depth>MAX_DEPTH||size<0||size>ArchiveEntryFile.MAX_BYTES
                ||OPEN.size()>=MAX_DEPTH+1||size>BUDGET-bytes)
            throw new IOException("Close an archive preview before opening another. Nested previews are limited to four levels and 80 MiB total.");
        if(file.getStatSize()!=size)throw new IOException("The verified archive entry size changed.");
        Uri uri=new Uri.Builder().scheme("content").authority(AUTHORITY)
                .appendPath(PREFIX).appendPath(UUID.randomUUID().toString()).appendPath(name).build();
        OPEN.put(uri,new Source(file,size,depth));bytes+=size;
        return uri;
    }

    static synchronized ParcelFileDescriptor open(Uri uri)throws FileNotFoundException{
        Source source=OPEN.get(uri);
        if(source==null)throw new FileNotFoundException("This archive entry expired. Reopen it from the outer archive.");
        // Use a fresh file description, not dup's shared seek offset.
        return ParcelFileDescriptor.open(new File("/proc/self/fd/"+source.file.getFd()),
                ParcelFileDescriptor.MODE_READ_ONLY);
    }

    static synchronized void release(Uri uri){
        if(!matches(uri))return;
        Source source=OPEN.remove(uri);
        if(source!=null){bytes-=source.size;try{source.file.close();}catch(IOException ignored){}}
    }
    private ArchivePreviewSources(){}
}
