package dev.fastsearch.android;

import dev.fastsearch.core.FileEntry;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.Predicate;

/** Bounded ordinary-permission walk, with no symlink traversal and no write operations. */
final class CleanerScopedWalk extends SimpleFileVisitor<Path> {
    static final int MAX_NODES=120_000;
    private static final int MAX_DEPTH=48;

    private final Path root;
    private final int limit;
    private final long deadline;
    private final Predicate<String> allowed;
    private final BooleanSupplier canceled;
    private final Consumer<FileEntry> consumer;
    private final LongConsumer progress;
    long visited, skipped;
    boolean limited;

    private CleanerScopedWalk(Path root,int limit,long deadline,Predicate<String> allowed,
                              BooleanSupplier canceled,Consumer<FileEntry> consumer,LongConsumer progress){
        this.root=root;this.limit=limit;this.deadline=deadline;this.allowed=allowed;
        this.canceled=canceled;this.consumer=consumer;this.progress=progress;
    }

    static CleanerScopedWalk scan(Path root,int limit,Predicate<String> allowed,BooleanSupplier canceled,
                                  Consumer<FileEntry> consumer,LongConsumer progress)throws IOException{
        return scan(root,limit,System.nanoTime()+TimeUnit.SECONDS.toNanos(25),allowed,canceled,consumer,progress);
    }

    static CleanerScopedWalk scan(Path root,int limit,long deadline,Predicate<String> allowed,BooleanSupplier canceled,
                                  Consumer<FileEntry> consumer,LongConsumer progress)throws IOException{
        checkpoint(canceled);
        if(!root.isAbsolute()||!root.equals(root.normalize())||!allowed.test(root.toString())
                ||!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)||!root.equals(root.toRealPath()))
            throw new IOException("Choose a direct shared folder.");
        CleanerScopedWalk result=new CleanerScopedWalk(root,limit,deadline,allowed,canceled,consumer,progress);
        Files.walkFileTree(root,result);
        checkpoint(canceled);
        return result;
    }

    private boolean stop()throws IOException{
        checkpoint(canceled);
        if(visited>=limit||System.nanoTime()-deadline>=0){limited=true;return true;}
        return false;
    }

    private boolean next()throws IOException{
        if(stop())return false;
        visited++;
        if((visited&255)==0)progress.accept(visited);
        return true;
    }

    @Override public FileVisitResult preVisitDirectory(Path dir,BasicFileAttributes before)throws IOException{
        if(!next())return FileVisitResult.TERMINATE;
        if(!allowed.test(dir.toString()))return FileVisitResult.SKIP_SUBTREE;
        if(dir.getNameCount()-root.getNameCount()>=MAX_DEPTH){limited=true;return FileVisitResult.SKIP_SUBTREE;}
        try{
            // The walker opened this directory before invoking us. Confirm that it
            // still resolves directly and that the path did not change type/identity.
            BasicFileAttributes now=Files.readAttributes(dir,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!now.isDirectory()||!Objects.equals(before.fileKey(),now.fileKey())||!dir.equals(dir.toRealPath())){
                skipped++;return FileVisitResult.SKIP_SUBTREE;
            }
        }catch(IOException|SecurityException denied){
            if(dir.equals(root))throw new IOException("Could not read the selected folder.",denied);
            skipped++;return FileVisitResult.SKIP_SUBTREE;
        }
        return FileVisitResult.CONTINUE;
    }

    @Override public FileVisitResult visitFile(Path file,BasicFileAttributes info)throws IOException{
        if(!next())return FileVisitResult.TERMINATE;
        if(!allowed.test(file.toString()))return FileVisitResult.CONTINUE;
        if(info.isRegularFile())consumer.accept(new FileEntry(file.toString(),false,info.size(),info.lastModifiedTime().toMillis()));
        else if(!info.isSymbolicLink())skipped++;
        return FileVisitResult.CONTINUE;
    }

    @Override public FileVisitResult visitFileFailed(Path file,IOException failure)throws IOException{
        if(!next())return FileVisitResult.TERMINATE;
        if(file.equals(root))throw failure;
        if(allowed.test(file.toString()))skipped++;
        return FileVisitResult.CONTINUE;
    }

    @Override public FileVisitResult postVisitDirectory(Path directory,IOException failure)throws IOException {
        checkpoint(canceled);
        if(failure!=null)skipped++;
        return FileVisitResult.CONTINUE;
    }

    static void checkpoint(BooleanSupplier canceled)throws InterruptedIOException{
        if(Thread.currentThread().isInterrupted()||canceled.getAsBoolean())throw new InterruptedIOException("Cleaner canceled.");
    }
}
