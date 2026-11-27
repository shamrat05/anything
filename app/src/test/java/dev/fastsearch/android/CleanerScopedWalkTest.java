package dev.fastsearch.android;

import java.io.InterruptedIOException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class CleanerScopedWalkTest {
    private static int checks;
    public static void main(String[] args)throws Exception{
        Path fixture=Files.createTempDirectory("anything-cleaner-walk-").toRealPath();
        try{
            Path selected=Files.createDirectories(fixture.resolve("chosen"));
            Path child=Files.createDirectories(selected.resolve("child"));
            Path blocked=Files.createDirectories(selected.resolve("excluded"));
            Path other=Files.createDirectories(fixture.resolve("unselected"));
            Files.write(selected.resolve("a.txt"),new byte[]{1,2,3});
            Files.write(child.resolve("b.zip"),new byte[]{4});
            Files.write(blocked.resolve("do-not-review.txt"),new byte[]{5});
            Files.write(other.resolve("not-under-selected.txt"),new byte[]{6});
            Files.createSymbolicLink(selected.resolve("outside"),other);
            Files.createSymbolicLink(selected.resolve("outside.txt"),other.resolve("not-under-selected.txt"));
            Files.createSymbolicLink(selected.resolve("loop"),selected);
            List<String> seen=new ArrayList<>();
            CleanerScopedWalk report=CleanerScopedWalk.scan(selected,100,
                    path->!path.equals(blocked.toString())&&!path.startsWith(blocked+"/"),
                    ()->false,entry->seen.add(entry.name+":"+entry.size),n->{});
            seen.sort(String::compareTo);
            check(seen.equals(java.util.Arrays.asList("a.txt:3","b.zip:1")),"only chosen ordinary files, no followed symlinks or exclusions");
            check(!report.limited&&report.skipped==0,"normal complete walk");
            check(CleanerScopedWalk.scan(selected,1,path->true,()->false,entry->{},n->{}).limited,"hard entry budget");
            check(Files.readAllBytes(selected.resolve("a.txt"))[0]==1&&Files.exists(blocked.resolve("do-not-review.txt"))
                    &&Files.exists(other.resolve("not-under-selected.txt")),"all files and bytes unchanged");
            fail(()->CleanerScopedWalk.scan(selected.resolve("outside"),99,path->true,()->false,entry->{},n->{}),IOException.class,
                    "selected scope cannot be a link");
            fail(()->CleanerScopedWalk.scan(selected,99,path->false,()->false,entry->{},n->{}),IOException.class,
                    "selected scope must be explicitly accepted");
            fail(()->CleanerScopedWalk.scan(selected,99,path->true,()->true,entry->{},n->{}),InterruptedIOException.class,
                    "cancellation before access");
            final boolean[] stop={false};
            final int[] received={0};
            fail(()->CleanerScopedWalk.scan(selected,99,path->true,()->stop[0],entry->{received[0]++;stop[0]=true;},n->{}),
                    InterruptedIOException.class,"cancellation during walk");
            check(received[0]==1,"cancellation delivers no more rows");
        }finally{
            try(java.util.stream.Stream<Path> files=Files.walk(fixture)) {
                for(Path file:(Iterable<Path>)files.sorted(Comparator.reverseOrder())::iterator)Files.delete(file);
            }
        }
        System.out.println("PASS "+checks+" Cleaner scoped walk assertions.");
    }
    private interface Checked {void tryRun()throws Exception;}
    private static void fail(Checked op,Class<?> expected,String label)throws Exception {
        try{op.tryRun();}catch(IOException denied){check(expected.isInstance(denied),label);return;}
        throw new AssertionError(label);
    }
    private static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;}
}
