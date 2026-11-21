package dev.fastsearch.android;

import dev.fastsearch.core.FileEntry;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Locale;
import java.util.TimeZone;

public final class FileSectionsTest {
    private static int assertions;
    public static void main(String[] args){
        Locale locale=Locale.getDefault();TimeZone timezone=TimeZone.getDefault();
        try{
            Locale.setDefault(Locale.US);TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
            FileSections names=FileSections.build(Arrays.asList(file("Alpha.jpg",1,1),file("audio.wav",1,1),file("Beta.mp4",1,1),file("9.pdf",1,1)),0);
            same(names.labels,new String[]{"A","B","0-9"});check(names.sectionAt(1)==0);check(names.sectionAt(2)==1);check(names.positionAt(1)==2);
            check(names.sectionAt(-1)==0);check(names.positionAt(99)==3);check(FileSections.EMPTY.labelAt(20).isEmpty());
            FileSections sizes=FileSections.build(Arrays.asList(file("a",0,1),file("b",1023,1),file("c",1024,1),file("d",1024*1024,1),file("e",10*1024*1024,1),file("f",1024L*1024*1024,1)),2);
            same(sizes.labels,new String[]{"No recorded size","Under 1 KB","1 KB to 1 MB","1 to 10 MB","10 to 100 MB","1 GB and above"});
            long early=ZonedDateTime.parse("2024-03-10T01:30:00-05:00[America/New_York]").toInstant().toEpochMilli();
            long late=ZonedDateTime.parse("2024-03-10T23:30:00-04:00[America/New_York]").toInstant().toEpochMilli();
            long next=ZonedDateTime.parse("2024-03-11T00:30:00-04:00[America/New_York]").toInstant().toEpochMilli();
            FileSections dates=FileSections.build(Arrays.asList(file("a",1,next),file("b",1,late),file("c",1,early),file("d",1,0)),3);
            same(dates.labels,new String[]{"11 March 2024","10 March 2024","Date unknown"});check(dates.sectionAt(2)==1);check(dates.positionAt(1)==1);
            FileSections paths=FileSections.build(Arrays.asList(file("/a/image.jpg",1,1),file("/a/video.mp4",1,1),file("/b/audio.wav",1,1)),1);
            same(paths.labels,new String[]{"/a","/b"});check(paths.positionAt(1)==2);
            Thread.currentThread().interrupt();boolean cancelled=false;
            try{FileSections.build(Arrays.asList(file("a",1,1)),0);}catch(java.util.concurrent.CancellationException expected){cancelled=true;}
            finally{Thread.interrupted();}check(cancelled);
            System.out.println("PASS "+assertions+" file-section assertions.");
        }finally{Locale.setDefault(locale);TimeZone.setDefault(timezone);}
    }
    private static FileEntry file(String path,long size,long modified){return new FileEntry(path.startsWith("/")?path:"/fixture/"+path,false,size,modified);}
    private static void same(String[] actual,String[] expected){check(Arrays.equals(actual,expected));}
    private static void check(boolean value){assertions++;if(!value)throw new AssertionError("Section assertion "+assertions);}
}
