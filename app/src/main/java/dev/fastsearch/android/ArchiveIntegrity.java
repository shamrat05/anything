package dev.fastsearch.android;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** Read-only ZIP container CRC and structure check. Never extracts or interprets entry contents. */
final class ArchiveIntegrity {
    private static final int BLOCK=64*1024;
    static final long BYTE_BUDGET=512L*1024*1024;
    static final int MENU_ID=0x07000041;
    private static final long U32_MAX=0xFFFFFFFFL;
    private static final int ZIP_SUPPORTED_FLAGS=0x080E;

    static boolean supports(String name){
        int dot=name.lastIndexOf('.');
        if(dot<0)return false;
        switch(name.substring(dot+1).toLowerCase(Locale.ROOT)){
            case "zip":case "apk":case "jar":case "cbz":return true;
            default:return false;
        }
    }

    static final class Result {
        final long entries,unlisted;
        final int verified,damaged,unsupported,overBudget;
        final String firstDamagedEntry;
        Result(long entries,long unlisted,int verified,int damaged,int unsupported,int overBudget,String first){
            this.entries=entries;this.unlisted=unlisted;this.verified=verified;this.damaged=damaged;
            this.unsupported=unsupported;this.overBudget=overBudget;firstDamagedEntry=first;
        }
        boolean incomplete(){return unsupported>0||overBudget>0||unlisted>0;}
        boolean passed(){return damaged==0&&!incomplete();}
    }

    static Result check(FileChannel source,BooleanSupplier cancelled)throws IOException{
        return check(source,BYTE_BUDGET,cancelled);
    }

    static Result check(FileChannel source,long budget,BooleanSupplier cancelled)throws IOException{
        if(budget<0)throw new IllegalArgumentException("Negative archive budget.");
        checkpoint(cancelled);
        ZipDirectory.Listing listing=ZipDirectory.readForIntegrity(source);
        ArrayList<ZipDirectory.Entry> entries=new ArrayList<>(listing.entries);
        entries.sort(Comparator.comparingLong(entry->entry.offset));
        byte[] compressed=new byte[BLOCK], plain=new byte[BLOCK];
        long compressedLeft=budget,unpackedLeft=budget;
        int good=0,damaged=0,unsupported=0,over=0;
        String problem=null;
        for(int i=0;i<entries.size();i++){
            checkpoint(cancelled);
            ZipDirectory.Entry entry=entries.get(i);
            if(entry.encrypted||entry.rawName==null||entry.special||(entry.flags&~ZIP_SUPPORTED_FLAGS)!=0
                    ||entry.method!=0&&entry.method!=8){unsupported++;continue;}
            if(entry.compressedSize>compressedLeft||entry.size>unpackedLeft){over++;continue;}
            // Reserve declared lengths even if a failed record stops early, capping cumulative work.
            compressedLeft-=entry.compressedSize;
            unpackedLeft-=entry.size;
            long bound=entry.boundary;
            if(i+1<entries.size())bound=Math.min(bound,entries.get(i+1).offset);
            try{
                long payload=validateLocal(source,entry,bound,cancelled);
                verifyPayload(source,payload,entry,compressed,plain,cancelled);
                good++;
            }catch(Damaged failure){
                damaged++;
                if(problem==null)problem=entry.name.length()>160?entry.name.substring(0,160)+"…":entry.name;
            }
        }
        checkpoint(cancelled);
        return new Result(listing.totalEntries,listing.totalEntries-entries.size(),good,damaged,unsupported,over,problem);
    }

    private static long validateLocal(FileChannel source,ZipDirectory.Entry e,long bound,
                                      BooleanSupplier cancelled)throws IOException{
        if(e.offset<0||bound-e.offset<30||e.directory&&e.size!=0)throw bad();
        byte[] local=read(source,e.offset,30,cancelled);
        if(u32(local,0)!=0x04034B50L||u16(local,6)!=e.flags||u16(local,8)!=e.method)throw bad();
        int nameLength=u16(local,26), extraLength=u16(local,28);
        long payload=e.offset+30L+nameLength+extraLength;
        if(nameLength==0||nameLength>ZipDirectory.MAX_NAME_BYTES||payload>bound||e.compressedSize>bound-payload)throw bad();
        byte[] name=read(source,e.offset+30,nameLength,cancelled);
        Charset charset=(e.flags&0x0800)!=0?StandardCharsets.UTF_8:legacyCharset();
        if(!Arrays.equals(e.rawName.getBytes(charset),name))throw bad();
        long crc=u32(local,14), packed=u32(local,18), size=u32(local,22);
        boolean stream=(e.flags&8)!=0, zip64=packed==U32_MAX||size==U32_MAX;
        if(zip64){
            byte[] extra=read(source,e.offset+30+nameLength,extraLength,cancelled);
            boolean found=false;
            for(int i=0; i+4<=extra.length;){
                int length=u16(extra,i+2), end=i+4+length;
                if(end>extra.length)throw bad();
                if(u16(extra,i)==1){
                    int next=i+4;
                    if(size==U32_MAX){if(end-next<8)throw bad();size=u64(extra,next);next+=8;}
                    if(packed==U32_MAX){if(end-next<8)throw bad();packed=u64(extra,next);}
                    found=true;break;
                }
                i=end;
            }
            if(!found)throw bad();
        }
        if(!stream){
            if(crc!=e.crc||size!=e.size||packed!=e.compressedSize)throw bad();
        }else{
            if((crc!=0&&crc!=e.crc)||(size!=0&&size!=e.size)||(packed!=0&&packed!=e.compressedSize))throw bad();
            long tail=payload+e.compressedSize;
            int available=(int)Math.min(bound-tail,24);
            if(available<12)throw bad();
            byte[] descriptor=read(source,tail,available,cancelled);
            if(!descriptorMatches(descriptor,0,zip64,e)
                    &&!(u32(descriptor,0)==0x08074B50L&&descriptorMatches(descriptor,4,zip64,e)))throw bad();
        }
        return payload;
    }

    private static boolean descriptorMatches(byte[] data,int at,boolean zip64,ZipDirectory.Entry e){
        if(data.length-at<(zip64?20:12)||u32(data,at)!=e.crc)return false;
        if(zip64)return u64(data,at+4)==e.compressedSize&&u64(data,at+12)==e.size;
        return u32(data,at+4)==e.compressedSize&&u32(data,at+8)==e.size;
    }

    private static void verifyPayload(FileChannel source,long payload,ZipDirectory.Entry e,byte[] input,byte[] output,
                                      BooleanSupplier cancelled)throws IOException{
        CRC32 crc=new CRC32();
        if(e.method==0){
            if(e.compressedSize!=e.size)throw bad();
            long left=e.size;
            while(left>0){
                int n=(int)Math.min(left,input.length);
                readInto(source,payload,input,n,cancelled);
                crc.update(input,0,n);left-=n;payload+=n;
            }
        }else{
            Inflater inflater=new Inflater(true);
            long consume=e.compressedSize,remaining=e.size;
            try{
                while(!inflater.finished()){
                    checkpoint(cancelled);
                    if(inflater.needsInput()){
                        if(consume==0)throw bad();
                        int n=(int)Math.min(consume,input.length);
                        readInto(source,payload,input,n,cancelled);
                        inflater.setInput(input,0,n);payload+=n;consume-=n;
                    }
                    int capacity=(int)Math.max(1,Math.min(remaining,output.length));
                    int count=inflater.inflate(output,0,capacity);
                    if(count>remaining||count==0&&!inflater.finished()&&(!inflater.needsInput()||inflater.needsDictionary()))
                        throw bad();
                    if(count>0){crc.update(output,0,count);remaining-=count;}
                }
                if(remaining!=0||inflater.getBytesRead()!=e.compressedSize)throw bad();
            }catch(DataFormatException corrupted){throw bad();}
            finally{inflater.end();}
        }
        if(crc.getValue()!=e.crc)throw bad();
    }

    private static Charset legacyCharset(){
        try{return Charset.forName("Cp437");}catch(Exception unavailable){return StandardCharsets.ISO_8859_1;}
    }
    private static int u16(byte[] data,int at){return data[at]&255|(data[at+1]&255)<<8;}
    private static long u32(byte[] data,int at){return (long)u16(data,at)|(long)u16(data,at+2)<<16;}
    private static long u64(byte[] data,int at){return u32(data,at)|u32(data,at+4)<<32;}
    private static byte[] read(FileChannel source,long position,int size,BooleanSupplier cancelled)throws IOException{
        byte[] result=new byte[size];readInto(source,position,result,size,cancelled);return result;
    }
    private static void readInto(FileChannel source,long position,byte[] data,int length,BooleanSupplier cancelled)throws IOException{
        ByteBuffer out=ByteBuffer.wrap(data,0,length);
        while(out.hasRemaining()){
            checkpoint(cancelled);
            if(source.read(out,position+out.position())<=0)throw bad();
        }
    }
    private static void checkpoint(BooleanSupplier cancelled)throws InterruptedIOException{
        if(Thread.currentThread().isInterrupted()||cancelled!=null&&cancelled.getAsBoolean())
            throw new InterruptedIOException("Archive integrity check cancelled.");
    }
    private static final class Damaged extends IOException{private static final long serialVersionUID=1L;}
    private static Damaged bad(){return new Damaged();}
    private ArchiveIntegrity(){}
}
