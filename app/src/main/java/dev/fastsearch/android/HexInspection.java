package dev.fastsearch.android;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Arrays;
import java.util.function.BooleanSupplier;

/** Byte layout and bounded exact search. All reads are explicitly positioned; there is no write API. */
final class HexInspection {
    static final int PAGE_BYTES=512, SEARCH_CHUNK=32*1024, MAX_PATTERN=128;
    static final long MAX_SEARCH_BYTES=16L*1024*1024;
    private static final char[] HEX="0123456789ABCDEF".toCharArray();

    interface Source {int read(long offset,byte[] dst,int length)throws IOException;}

    static final class Match {
        final long offset, scannedUntil;
        Match(long offset,long scannedUntil){this.offset=offset;this.scannedUntil=scannedUntil;}
    }

    static long parseOffset(String input){
        String value=input.trim();
        boolean hex=value.startsWith("0x")||value.startsWith("0X");
        String digits=hex?value.substring(2):value;
        if(digits.isEmpty()||digits.length()>(hex?16:19))throw invalidOffset();
        for(int i=0;i<digits.length();i++){
            char c=digits.charAt(i);
            if(hex?digit(c)<0:c<'0'||c>'9')throw invalidOffset();
        }
        try{return Long.parseLong(digits,hex?16:10);}
        catch(NumberFormatException range){throw invalidOffset();}
    }

    private static IllegalArgumentException invalidOffset(){
        return new IllegalArgumentException("Enter a decimal offset or a hex offset such as 0x120.");
    }

    static byte[] parsePattern(String input){
        String value=input.trim();
        if(value.startsWith("0x")||value.startsWith("0X"))value=value.substring(2);
        if(value.length()>3*MAX_PATTERN+16)
            throw new IllegalArgumentException("Search up to 128 bytes at a time.");
        byte[] output=new byte[MAX_PATTERN];
        int length=0,high=-1;
        for(int i=0;i<value.length();i++){
            char c=value.charAt(i);
            if(c==' '||c=='\t'||c=='\r'||c=='\n'){
                if(high>=0)throw invalidPattern();
                continue;
            }
            int digit=digit(c);
            if(digit<0)throw invalidPattern();
            if(high<0)high=digit;
            else{
                if(length==MAX_PATTERN)throw new IllegalArgumentException("Search up to 128 bytes at a time.");
                output[length++]=(byte)((high<<4)|digit);high=-1;
            }
        }
        if(length==0||high>=0)throw invalidPattern();
        return Arrays.copyOf(output,length);
    }

    private static IllegalArgumentException invalidPattern(){
        return new IllegalArgumentException("Use complete hex bytes, for example 50 4B 03 04.");
    }

    private static int digit(char c){
        if(c>='0'&&c<='9')return c-'0';
        if(c>='A'&&c<='F')return c-'A'+10;
        if(c>='a'&&c<='f')return c-'a'+10;
        return -1;
    }

    static long pageStart(long offset){return offset/PAGE_BYTES*PAGE_BYTES;}

    static byte[] page(Source source,long length,long offset,BooleanSupplier cancelled)throws IOException {
        if(length<0||offset<0||offset>length||length!=0&&offset==length)throw new IOException("Offset is outside this file.");
        byte[] output=new byte[(int)Math.min(PAGE_BYTES,length-offset)];
        fill(source,offset,output,output.length,cancelled);
        return output;
    }

    /**
     * Checks at most 16 MiB of candidate starts and at most 127 look-ahead bytes. The
     * next chunk starts at scannedUntil; a delimiter crossing a chunk end is not lost.
     */
    static Match find(Source source,long length,long start,byte[] pattern,BooleanSupplier cancelled)throws IOException {
        if(length<0||start<0||start>length||pattern.length<1||pattern.length>MAX_PATTERN)
            throw new IOException("Search is outside this file.");
        check(cancelled);
        long starts=start+Math.min(MAX_SEARCH_BYTES,length-start);
        long end=starts+Math.min(pattern.length-1,length-starts);
        int[] prefix=new int[pattern.length];
        for(int i=1,k=0;i<pattern.length;i++){
            while(k>0&&pattern[i]!=pattern[k])k=prefix[k-1];
            if(pattern[i]==pattern[k])k++;
            prefix[i]=k;
        }
        int state=0;
        byte[] buffer=new byte[(int)Math.min(SEARCH_CHUNK,end-start)];
        for(long next=start;next<end;){
            check(cancelled);
            int wanted=(int)Math.min(buffer.length,end-next);
            fill(source,next,buffer,wanted,cancelled);
            for(int i=0;i<wanted;i++){
                while(state>0&&buffer[i]!=pattern[state])state=prefix[state-1];
                if(buffer[i]==pattern[state])state++;
                if(state==pattern.length){
                    long hit=next+i-(pattern.length-1L);
                    if(hit<starts)return new Match(hit,hit+1);
                    return new Match(-1,starts);
                }
            }
            next+=wanted;
        }
        return new Match(-1,starts);
    }

    private static void fill(Source source,long position,byte[] output,int count,BooleanSupplier cancelled)throws IOException {
        // The ordinary positioned reader fills one buffer; a changed/truncated source is not shown.
        check(cancelled);
        if(count!=0&&source.read(position,output,count)!=count)throw new IOException("File changed or could not be read. Reopen it.");
        check(cancelled);
    }

    static String offset(long offset){return "0x"+Long.toHexString(offset).toUpperCase(java.util.Locale.ROOT);}

    static String render(byte[] bytes,long offset,int columns){
        if(columns!=8&&columns!=16)throw new IllegalArgumentException("Unsupported row width");
        StringBuilder text=new StringBuilder(bytes.length*5);
        int width=offset+Math.max(0,bytes.length-1L)>0xffffffffL?16:8;
        for(int row=0;row<bytes.length;row+=columns){
            if(row>0)text.append('\n');
            long address=offset+row;
            for(int shift=(width-1)*4;shift>=0;shift-=4)text.append(HEX[(int)(address>>>shift)&15]);
            text.append("  ");
            int length=Math.min(columns,bytes.length-row);
            for(int i=0;i<columns;i++){
                if(i<length){
                    int value=bytes[row+i]&255;text.append(HEX[value>>>4]).append(HEX[value&15]).append(' ');
                }else text.append("   ");
            }
            text.append(" |");
            for(int i=0;i<length;i++){
                int value=bytes[row+i]&255;text.append(value>=32&&value<=126?(char)value:'.');
            }
            text.append('|');
        }
        return text.toString();
    }

    private static void check(BooleanSupplier cancelled)throws InterruptedIOException{
        if(Thread.currentThread().isInterrupted()||cancelled.getAsBoolean())throw new InterruptedIOException("Byte inspection stopped.");
    }

    private HexInspection() {}
}
