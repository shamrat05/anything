package dev.fastsearch.android;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Bounded logical size: regular files including hidden files, not links or special nodes. */
final class FolderSizeWalk {
    static final int ENTRY_LIMIT = 120_000;
    private static final int MAX_DEPTH = 48;
    long bytes;
    boolean readable, partial;
    private boolean stopped;
    private int remaining;
    private final BooleanSupplier shouldStop;

    private FolderSizeWalk(int limit, BooleanSupplier shouldStop) {
        remaining=limit;
        this.shouldStop=shouldStop;
    }

    static FolderSizeWalk measure(Path folder, int limit, BooleanSupplier shouldStop) {
        FolderSizeWalk result = new FolderSizeWalk(limit, shouldStop);
        try {
            BasicFileAttributes top=attributes(folder);
            if(top.isDirectory())result.visit(folder,top,0);
        } catch(IOException|SecurityException unavailable){result.partial=true;}
        return result;
    }

    private void visit(Path folder, BasicFileAttributes before, int depth) {
        if(depth>=MAX_DEPTH||stopRequested()) { partial=true;return; }
        try(DirectoryStream<Path> children=Files.newDirectoryStream(folder)) {
            if(!sameDirectory(before,attributes(folder))) {partial=true;return;}
            if(depth==0)readable=true;
            for(Path child:children) {
                if(stopRequested())return;
                if(remaining--==0) { stopped=partial=true;return; }
                try {
                    BasicFileAttributes info=attributes(child);
                    if(info.isDirectory())visit(child,info,depth+1);
                    else if(info.isRegularFile()){
                        long size=info.size();
                        if(size<0||bytes>Long.MAX_VALUE-size){bytes=Long.MAX_VALUE;partial=true;}
                        else bytes+=size;
                    }
                } catch(IOException|SecurityException unavailable){partial=true;}
                if(stopped)return;
            }
            if(!sameDirectory(before,attributes(folder)))partial=true;
        } catch(IOException|DirectoryIteratorException|SecurityException unavailable){partial=true;}
    }

    private boolean stopRequested() {
        if(stopped||shouldStop.getAsBoolean()){stopped=partial=true;return true;}
        return false;
    }

    private static BasicFileAttributes attributes(Path path)throws IOException{
        return Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean sameDirectory(BasicFileAttributes before,BasicFileAttributes after){
        return after.isDirectory()&&Objects.equals(before.fileKey(),after.fileKey())
                &&before.lastModifiedTime().equals(after.lastModifiedTime());
    }
}
