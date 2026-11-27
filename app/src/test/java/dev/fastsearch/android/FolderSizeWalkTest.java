package dev.fastsearch.android;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;

public final class FolderSizeWalkTest {
    private static int assertions;
    public static void main(String[] args)throws Exception{
        Path fixture=Files.createTempDirectory("folder-size-fixture-");
        try {
            Path outside=Files.createDirectory(fixture.resolve("outside"));
            Files.write(outside.resolve("must-not-count"),new byte[20_000]);
            Path measured=Files.createDirectory(fixture.resolve("measure"));
            Path hidden=Files.createDirectory(measured.resolve(".nested"));
            Files.write(measured.resolve("part-one"),new byte[1600]);
            Files.write(hidden.resolve(".part-two"),new byte[1137]);
            Files.createSymbolicLink(measured.resolve("skip-link"),outside);
            Files.createSymbolicLink(hidden.resolve("no-cycle"),measured);
            Files.createSymbolicLink(measured.resolve("skip-file"),outside.resolve("must-not-count"));
            FolderSizeWalk size=FolderSizeWalk.measure(measured,100,()->false);
            check(size.readable&&!size.partial&&size.bytes==2737,"recursive bytes include hidden regular files only");
            Path empty=Files.createDirectory(fixture.resolve("empty"));
            size=FolderSizeWalk.measure(empty,100,()->false);
            check(size.readable&&!size.partial&&size.bytes==0,"verified empty folder is zero");
            size=FolderSizeWalk.measure(fixture.resolve("no-such-path"),100,()->false);
            check(!size.readable&&size.partial,"missing folder is unavailable, not zero");
            size=FolderSizeWalk.measure(measured.resolve("part-one"),100,()->false);
            check(!size.readable,"regular file is not a measured folder");
            size=FolderSizeWalk.measure(measured.resolve("skip-link"),100,()->false);
            check(!size.readable,"explicit final-component link is not followed");
            size=FolderSizeWalk.measure(measured,1,()->false);
            check(size.readable&&size.partial&&size.bytes<2737,"entry-bounded walk reports partial");
            AtomicInteger current=new AtomicInteger();
            size=FolderSizeWalk.measure(measured,100,()->current.getAndIncrement()>=3);
            check(size.readable&&size.partial&&size.bytes<2737,"cancellation leaves a visibly incomplete walk");
            Path deep=Files.createDirectory(fixture.resolve("deep"));
            Path leaf=deep;
            for(int depth=0;depth<49;depth++)leaf=Files.createDirectory(leaf.resolve("d"));
            Files.write(leaf.resolve("too-deep"),new byte[]{1});
            size=FolderSizeWalk.measure(deep,100,()->false);
            check(size.readable&&size.partial&&size.bytes==0,"maximum depth is not mislabeled as empty");
            System.out.println("FolderSizeWalkTest: "+assertions+" checks passed");
        }finally{
            try(java.util.stream.Stream<Path> owned=Files.walk(fixture)){
                for(Path child:owned.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.delete(child);
            }
        }
    }
    private static void check(boolean passed,String name){
        if(!passed)throw new AssertionError(name);
        assertions++;
    }
}
