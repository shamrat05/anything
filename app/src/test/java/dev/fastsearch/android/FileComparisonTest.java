package dev.fastsearch.android;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Host fixtures: no actual app packages or user files. */
public final class FileComparisonTest {
    private static int count;
    private static Path temp;

    public static void main(String[] args)throws Exception{
        temp=Files.createTempDirectory("anything-compare-");
        try{
            apkCases(); dexCases(); textCases(); rulesAndLimits();
        }finally{
            Files.deleteIfExists(temp.resolve("a"));Files.deleteIfExists(temp.resolve("b"));
            Files.deleteIfExists(temp);
        }
        System.out.println("PASS "+count+" read-only file comparison assertions");
    }

    private static void apkCases()throws Exception{
        byte[] a=zip("same.txt","same","changed.txt","old","removed.txt","bye");
        byte[] b=zip("same.txt","same","changed.txt","new","added.txt","hi");
        FileComparison.Result r=compare(FileComparison.Mode.APK,a,b);
        check(r.beforeItems==3&&r.afterItems==3,"complete inventory counts");
        check(r.added==1&&r.removed==1&&r.changed==1&&r.rows.size()==3,"three kinds of metadata changes");
        FileComparison.Row change=r.rows.get(1);
        check(change.kind==FileComparison.Kind.CHANGED&&"changed.txt".equals(change.label),"stable name ordering");
        check(change.before.startsWith("3 B")&&change.after.startsWith("3 B")
                &&change.before.contains("CRC32")&&!change.before.equals(change.after),"same size with different CRC");
        check(r.rows.get(0).before==null&&r.rows.get(2).after==null,"side absent for removed/added");
        check(Arrays.equals(Files.readAllBytes(temp.resolve("a")),a)&&Arrays.equals(Files.readAllBytes(temp.resolve("b")),b),
                "metadata comparison never writes either input");
        check(compare(FileComparison.Mode.APK,a,a).rows.isEmpty(),"identical metadata reports no change");
        byte[] reordered=zip("removed.txt","bye","same.txt","same","changed.txt","old");
        check(compare(FileComparison.Mode.APK,a,reordered).rows.isEmpty(),"metadata equality ignores package order and offset");
        FileComparison.Result literal=compare(FileComparison.Mode.APK,zip(),zip("../junk\nname","value"));
        check(literal.rows.get(0).label.equals("../junk\\nname"),"entry names are displayed as literal metadata only");
        invalid(()->compare(FileComparison.Mode.APK,a,relabel(zip("dual1.txt","one","dual2.txt","two"),"dual2.txt","dual1.txt")),
                "duplicate names are ambiguous, never merged");
        invalid(()->compare(FileComparison.Mode.APK,a,new byte[30]),"non-ZIP data does not pretend to be APK");
        String[] entries=new String[(ZipDirectory.MAX_ENTRIES+1)*2];
        for(int i=0;i<entries.length;i+=2){entries[i]="fixture-"+i;entries[i+1]="";}
        byte[] oversized=zip(entries);
        invalid(()->compare(FileComparison.Mode.APK,a,oversized),"over-limit inventory refused instead of partial result");
    }

    private static void dexCases()throws Exception{
        byte[] a=dexFile("039",0,(byte)1),b=dexFile("040",1,(byte)2);
        FileComparison.Result r=compare(FileComparison.Mode.DEX,a,b);
        check(r.beforeItems==0&&r.afterItems==1&&r.changed==1,"declared class counts and one changed byte stream");
        check(r.beforeDetail.startsWith("DEX 039")&&r.afterDetail.startsWith("DEX 040"),
                "reports both supported versions");
        String hash=hash(a);
        check(r.rows.get(0).before.equals(hash)&&r.beforeDetail.endsWith(hash),"matches independent SHA-256 of entire input");
        check(compare(FileComparison.Mode.DEX,a,a.clone()).changed==0,"same digest and length");
        invalid(()->compare(FileComparison.Mode.DEX,a,dexFile("041",0,(byte)0)),"new container DEX unsupported");
        byte[] corrupt=dexFile("039",1,(byte)0);
        corrupt[100]=111;
        invalid(()->compare(FileComparison.Mode.DEX,a,corrupt),"reject misaligned or incomplete class table");
        invalid(()->compare(FileComparison.Mode.DEX,a,new byte[100]),"reject short DEX");
    }

    private static void textCases()throws Exception{
        FileComparison.Result r=compare(FileComparison.Mode.TEXT,utf("same\r\nold\rsame\nশেষ"),
                utf("start\nsame\nsame\nশেষ"));
        check(r.added==1&&r.removed==1&&r.changed==0,"text LCS tolerates an insertion or deletion shift");
        check("start".equals(r.rows.get(0).after)&&"B line 1".equals(r.rows.get(0).label),"added text and new number");
        check("old".equals(r.rows.get(1).before)&&"A line 2".equals(r.rows.get(1).label),"removed text and old number");
        check(compare(FileComparison.Mode.TEXT,utf("বাংলা\r\nnext"),"বাংলা\nnext".getBytes(StandardCharsets.UTF_16)).totalChanges()==0,
                "strict UTF-8/UTF-16 and CRLF compare normalized logical lines");
        check(compare(FileComparison.Mode.TEXT,utf("line"),utf("line\n")).added==1,"final newline still contributes a difference");
        check(compare(FileComparison.Mode.TEXT,new byte[0],utf("first")).added==1,"empty input has no lines");
        check(compare(FileComparison.Mode.TEXT,utf("same"),utf("same")).rows.isEmpty(),"text no changes");
        byte[] invalidUtf={(byte)0xC0,(byte)0x8A};
        invalid(()->compare(FileComparison.Mode.TEXT,utf("sample"),invalidUtf),"invalid UTF8 refused");
        invalid(()->compare(FileComparison.Mode.TEXT,utf("sample"),new byte[]{'a',0,'b'}),"binary NUL refused");
        invalid(()->compare(FileComparison.Mode.TEXT,utf("sample"),new byte[]{(byte)0xff,(byte)0xfe,'a'}),"truncated UTF16 refused");
        StringBuilder x=new StringBuilder(),y=new StringBuilder();
        for(int i=0;i<550;i++){
            if(i>0){x.append('\n');y.append('\n');}
            x.append("a_").append(i);y.append("b_").append(i);
        }
        r=compare(FileComparison.Mode.TEXT,utf(x.toString()),utf(y.toString()));
        check(r.added==550&&r.removed==550&&r.rows.size()==FileComparison.MAX_ROWS,
                "display cap never understates whole-document totals");
        check(compare(FileComparison.Mode.TEXT,utf("a"),utf("long\tline")).rows.get(1).after.equals("long\\tline"),
                "control notation is safe and explicit");
    }

    private static void rulesAndLimits()throws Exception{
        check(FileComparison.mode("one.APK","two.apk")==FileComparison.Mode.APK,"case insensitive APK");
        check(FileComparison.mode("one.DEX","two.dex")==FileComparison.Mode.DEX,"case insensitive DEX");
        check(FileComparison.mode("one.kt","two.java")==FileComparison.Mode.TEXT,"cross language text");
        check(FileComparison.mode("one.apk","two.dex")==null,"package and standalone DEX not mixed");
        check(FileComparison.mode("one.txt","two.apk")==null,"package never misread as text");
        byte[] longText=new byte[FileComparison.MAX_TEXT_BYTES+1];Arrays.fill(longText,(byte)'x');
        invalid(()->compare(FileComparison.Mode.TEXT,utf("x"),longText),"text byte limit");
        byte[] tooManyLines=new byte[FileComparison.MAX_TEXT_LINES];Arrays.fill(tooManyLines,(byte)'\n');
        invalid(()->compare(FileComparison.Mode.TEXT,utf("x"),tooManyLines),"text line limit");
        String escaped=FileComparison.safe("foo\u202Ebar");
        check(!escaped.contains("\u202E"),"bidi control cannot change display direction");
        byte[] exact=new byte[FileComparison.MAX_TEXT_BYTES];Arrays.fill(exact,(byte)'a');
        FileComparison.Result r=compare(FileComparison.Mode.TEXT,exact,utf("b"));
        check(r.rows.get(0).before.length()<=481&&r.rows.get(0).before.endsWith("…"),"line preview is bounded");
        Path left=Files.write(temp.resolve("a"),utf("x")),right=Files.write(temp.resolve("b"),utf("y"));
        try(FileChannel a=FileChannel.open(left,StandardOpenOption.READ);
            FileChannel b=FileChannel.open(right,StandardOpenOption.READ)){
            Thread.currentThread().interrupt();
            try{
                invalid(()->FileComparison.compare(FileComparison.Mode.TEXT,a,b),"engine observes cancellation");
                check(Thread.currentThread().isInterrupted(),"comparison preserves interrupted state");
            }finally{Thread.interrupted();}
        }
    }

    private static FileComparison.Result compare(FileComparison.Mode mode,byte[] a,byte[] b)throws Exception{
        Path left=Files.write(temp.resolve("a"),a),right=Files.write(temp.resolve("b"),b);
        try(FileChannel x=FileChannel.open(left,StandardOpenOption.READ);
            FileChannel y=FileChannel.open(right,StandardOpenOption.READ)){
            return FileComparison.compare(mode,x,y);
        }
    }

    private static byte[] zip(String... entries)throws IOException{
        ByteArrayOutputStream data=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(data)){
            for(int i=0;i<entries.length;i+=2){
                ZipEntry entry=new ZipEntry(entries[i]);entry.setTime(0);
                zip.putNextEntry(entry);zip.write(utf(entries[i+1]));zip.closeEntry();
            }
        }
        return data.toByteArray();
    }

    private static byte[] relabel(byte[] zip,String old,String replacement){
        byte[] from=utf(old),to=utf(replacement);
        for(int i=0;i+from.length<=zip.length;i++){
            boolean found=true;
            for(int j=0;j<from.length;j++)if(zip[i+j]!=from[j]){found=false;break;}
            if(found)System.arraycopy(to,0,zip,i,to.length);
        }
        return zip;
    }

    private static byte[] dexFile(String version,int classes,byte tail){
        byte[] bytes=new byte[113+32*classes];
        ByteBuffer dex=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        dex.put(new byte[]{'d','e','x','\n'});dex.put(utf(version));dex.put((byte)0);
        dex.putInt(32,bytes.length);dex.putInt(36,112);dex.putInt(40,0x12345678);
        dex.putInt(96,classes);dex.putInt(100,classes==0?0:112);
        bytes[bytes.length-1]=tail;
        return bytes;
    }

    private static String hash(byte[] bytes)throws Exception{
        StringBuilder hash=new StringBuilder();
        for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))hash.append(String.format("%02x",b&255));
        return hash.toString();
    }
    private static byte[] utf(String text){return text.getBytes(StandardCharsets.UTF_8);}
    private interface Checked {void call()throws Exception;}
    private static void invalid(Checked work,String label)throws Exception{
        try{work.call();}catch(IOException expected){check(true,label);return;}
        check(false,label);
    }
    private static void check(boolean passed,String label){count++;if(!passed)throw new AssertionError(label);}
}
