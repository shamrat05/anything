package dev.fastsearch.android;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

public final class HexInspectionTest {
    private static int checks;

    public static void main(String[] args)throws Exception {
        check(HexInspection.parseOffset("123")==123&&HexInspection.parseOffset(" 0xFF ")==255,"decimal and hex offsets");
        check(HexInspection.parseOffset("0x7FFFFFFFFFFFFFFF")==Long.MAX_VALUE,"large offset");
        for(String malformed:new String[]{"-1","+1","0x","0xZ","0x8000000000000000","9223372036854775808","１２"})
            illegal(()->HexInspection.parseOffset(malformed));
        check(Arrays.equals(HexInspection.parsePattern("0x50 4b\t03 04"),new byte[]{80,75,3,4}),"spaced mixed case bytes");
        check(Arrays.equals(HexInspection.parsePattern("abc01F"),new byte[]{(byte)171,(byte)192,31}),"compact bytes");
        for(String malformed:new String[]{"","0x","2"," A A ","GG","5:4","０１","0x01 0x02"}) {
            illegal(()->HexInspection.parsePattern(malformed));
        }
        char[] oversized=new char[2*(HexInspection.MAX_PATTERN+1)];Arrays.fill(oversized,'0');
        illegal(()->HexInspection.parsePattern(new String(oversized)));
        byte[] source=new byte[HexInspection.SEARCH_CHUNK+50];
        source[HexInspection.SEARCH_CHUNK-1]=42;source[HexInspection.SEARCH_CHUNK]=43;
        source[HexInspection.SEARCH_CHUNK+1]=42;source[HexInspection.SEARCH_CHUNK+2]=43;
        AtomicLong bytesRead=new AtomicLong();
        HexInspection.Source memory=(offset,bytes,length)->{System.arraycopy(source,(int)offset,bytes,0,length);bytesRead.addAndGet(length);return length;};
        HexInspection.Match boundary=HexInspection.find(memory,source.length,0,new byte[]{42,43},()->false);
        check(boundary.offset==HexInspection.SEARCH_CHUNK-1,"search crosses ordinary chunk");
        HexInspection.Match next=HexInspection.find(memory,source.length,boundary.scannedUntil,new byte[]{42,43},()->false);
        check(next.offset==HexInspection.SEARCH_CHUNK+1,"next hit starts after previous result, not next buffer");
        check(HexInspection.find(memory,source.length,source.length,new byte[]{0},()->false).offset==-1,"empty remainder");
        check(HexInspection.find(memory,source.length,source.length-1,new byte[]{0},()->false).offset==source.length-1,"last possible byte");
        check(HexInspection.page(memory,source.length,source.length-5,()->false).length==5,"exact final page bytes");
        check(HexInspection.page(memory,0,0,()->false).length==0,"empty file");
        check(HexInspection.pageStart(513)==512,"align to stable page");
        expectIo(()->HexInspection.page(memory,source.length,source.length,()->false));
        expectIo(()->HexInspection.page((p,b,n)->n-1,512,0,()->false));
        try{HexInspection.find(memory,source.length,0,new byte[]{42},()->true);throw new AssertionError("cancelled work proceeded");}
        catch(InterruptedIOException expected){check(true,"responsive cancellation");}
        long scanCut=HexInspection.MAX_SEARCH_BYTES;
        byte[] needle=new byte[]{42,43,44};
        HexInspection.Source beyond=(offset,bytes,length)->{
            bytesRead.addAndGet(length);Arrays.fill(bytes,0,length,(byte)0);
            for(int i=0;i<needle.length;i++){long at=scanCut+i-offset;
                if(at>=0&&at<length)bytes[(int)at]=needle[i];}
            return length;
        };
        bytesRead.set(0);
        HexInspection.Match misses=HexInspection.find(beyond,scanCut+24,0,needle,()->false);
        check(misses.offset==-1&&misses.scannedUntil==scanCut&&bytesRead.get()<=scanCut+needle.length-1,"bounded scan and explicit continuation");
        check(HexInspection.find(beyond,scanCut+24,misses.scannedUntil,needle,()->false).offset==scanCut,"no skipped hit on continuation");
        HexInspection.Source cut=(offset,bytes,length)->{
            Arrays.fill(bytes,0,length,(byte)0);
            for(int i=0;i<needle.length;i++){long at=scanCut-1+i-offset;
                if(at>=0&&at<length)bytes[(int)at]=needle[i];}
            return length;
        };
        check(HexInspection.find(cut,scanCut+24,0,needle,()->false).offset==scanCut-1,"lookahead finds match straddling scan budget");
        String display=HexInspection.render(new byte[]{65,0,31,32,126,(byte)255},0x100,8);
        check(display.startsWith("00000100  41 00 1F 20 7E FF")&&display.endsWith("|A.. ~.|"),"readable dump and ASCII-safe binary");
        check(HexInspection.render(new byte[]{1},0x1_0000_0000L,16).startsWith("0000000100000000"),"64-bit offset display");
        check(HexInspection.render(new byte[0],0,8).isEmpty(),"empty dump");
        java.nio.file.Path path=Files.createTempFile("anything-hex-fixture-", ".bin");
        byte[] fixture=new byte[]{9,10,11,12,13};
        try{
            Files.write(path,fixture);
            try(RandomAccessFile file=new RandomAccessFile(path.toFile(),"r")){
                HexInspection.Source ordinary=(position,bytes,count)->{
                    file.seek(position);file.readFully(bytes,0,count);return count;
                };
                check(Arrays.equals(HexInspection.page(ordinary,file.length(),1,()->false),new byte[]{10,11,12,13}),"ordinary file positional read");
                check(HexInspection.find(ordinary,file.length(),0,new byte[]{11,12},()->false).offset==2,"ordinary file exact search");
            }
            check(Arrays.equals(fixture,Files.readAllBytes(path)),"view and search never modify source");
        }finally{Files.deleteIfExists(path);}
        System.out.println("PASS "+checks+" hex inspection checks.");
    }
    private static void illegal(Runnable action){
        try{action.run();throw new AssertionError("invalid input was accepted");}
        catch(IllegalArgumentException expected){checks++;}
    }
    private static void expectIo(Io action)throws Exception {
        try{action.run();throw new AssertionError("invalid read was accepted");}
        catch(IOException expected){checks++;}
    }
    interface Io{void run()throws Exception;}
    private static void check(boolean value,String name){if(!value)throw new AssertionError(name);checks++;}
}
