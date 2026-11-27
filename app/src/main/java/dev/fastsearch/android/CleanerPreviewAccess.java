package dev.fastsearch.android;

import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.StructStat;

import dev.fastsearch.core.DuplicateFinder;
import dev.fastsearch.core.FileEntry;

import java.io.IOException;
import java.util.Collection;

/** Bounds Cleaner preview access to one still-eligible shared file, with no grant and no root. */
final class CleanerPreviewAccess {
    static boolean eligible(FileEntry file,Collection<String> roots,String scope,Collection<String> exclusions){
        if(file==null||file.directory||file.size<0)return false;
        String path=file.path();
        if(!path.equals(CleanerSheet.normalize(path))||!CleanerSheet.allowedScope(roots,path)
                ||(scope!=null&&!inside(path,scope)))return false;
        if(roots.contains(path))return false;
        for(String excluded:exclusions)if(inside(path,excluded))return false;
        return true;
    }

    private static boolean inside(String file,String directory){
        return file.equals(directory)||file.startsWith(directory+"/");
    }

    /** No file bytes are retained here. The viewer compares the returned revision on its own descriptor. */
    static String check(FileEntry file,DuplicateFinder.Member observed,Collection<String> roots,
                        String scope,Collection<String> exclusions)throws Exception{
        if(!eligible(file,roots,scope,exclusions))throw new IOException("Not in the scanned shared folder.");
        try(ParcelFileDescriptor fd=SafeFileAccess.openLocal(file.path())){
            StructStat stat=Os.fstat(fd.getFileDescriptor());
            String latest=RootFileProvider.identity(stat);
            if(observed!=null){
                DuplicateFinder.Stamp scan=observed.evidence;
                if(!observed.entry.pathEquals(file.path())||!scan.revision.equals(latest)
                        ||scan.size!=stat.st_size||scan.linkCount!=stat.st_nlink)throw changed();
            }else{
                long seconds=Build.VERSION.SDK_INT>=27?stat.st_mtim.tv_sec:stat.st_mtime;
                long millis=Build.VERSION.SDK_INT>=27?stat.st_mtim.tv_nsec/1_000_000L:0L;
                long scanned=Build.VERSION.SDK_INT>=27?file.modified:file.modified/1000*1000;
                if(file.size!=stat.st_size||scanned!=seconds*1000+millis)throw changed();
            }
            if(!latest.equals(RootFileProvider.identity(Os.lstat(file.path())))
                    ||!file.path().equals(Os.readlink("/proc/self/fd/"+fd.getFd())))throw changed();
            return latest;
        }
    }

    private static IOException changed(){return new IOException("The file changed. Rescan before previewing it.");}
    private CleanerPreviewAccess(){}
}
