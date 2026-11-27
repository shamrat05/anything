package dev.fastsearch.android;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.ZipException;

/** Bounded TAR browsing and a single selected member copy into caller-owned private staging. */
final class TarArchiveListing {
    static final int MAX_ENTRIES=60;
    private static final int BLOCK=512, META_PER_RECORD=16*1024, META_BUDGET=512*1024;
    static final long MAX_COMPRESSED=8L*1024*1024, MAX_INFLATED=16L*1024*1024;
    private static final long MAX_SELECTED_BYTES=32L*1024*1024;

    enum Stop { COMPLETE, ENTRY_LIMIT, READ_LIMIT, UNSUPPORTED_RECORD }
    static final class Member {
        final String name;
        final long size;
        final boolean directory,special;
        private final int ordinal;
        private final long payload;
        private Member(String name,long size,boolean directory,boolean special,int ordinal,long payload){
            this.name=name;this.size=size;this.directory=directory;this.special=special;
            this.ordinal=ordinal;this.payload=payload;
        }
        private boolean matches(Member other){
            return ordinal==other.ordinal&&payload==other.payload&&size==other.size&&directory==other.directory
                    &&special==other.special&&name.equals(other.name);
        }
    }
    static final class Listing {
        final List<Member> entries;
        final Stop stop;
        final boolean specialEntries;
        Listing(List<Member> entries,Stop stop,boolean special){
            this.entries=Collections.unmodifiableList(new ArrayList<>(entries));this.stop=stop;specialEntries=special;
        }
        boolean requiresDedicatedApp(){return specialEntries||stop==Stop.UNSUPPORTED_RECORD;}
    }

    static Listing read(FileChannel input,boolean gzip)throws IOException{
        return read(input,gzip,MAX_COMPRESSED,MAX_INFLATED);
    }

    static Listing read(FileChannel input,boolean gzip,long compressedBudget,long inflatedBudget)throws IOException{
        long size=input.size();
        if(compressedBudget<=0||inflatedBudget<=0||size<0)throw new IllegalArgumentException("Invalid TAR preview bounds.");
        List<Member> entries=new ArrayList<>();
        if(!gzip)return collect(new Cursor(input,size,null,0),entries,null);
        BoundedCompressed source=new BoundedCompressed(input,size,compressedBudget);
        try(InputStream decoded=new GZIPInputStream(new BufferedInputStream(source,32*1024),32*1024)){
            return collect(new Cursor(input,size,decoded,inflatedBudget),entries,null);
        }catch(PreviewLimit limit){return incomplete(entries,Stop.READ_LIMIT);}
        catch(EOFException|ZipException wrong){
            if(source.hitBound())return incomplete(entries,Stop.READ_LIMIT);
            throw damaged();
        }
    }

    static boolean canPreview(Member selected,boolean gzip){
        return selected!=null&&!selected.directory&&!selected.special&&selected.size>=0
                &&selected.size<=MAX_SELECTED_BYTES&&(!gzip||selected.payload<=MAX_INFLATED
                &&selected.size<=MAX_INFLATED-selected.payload);
    }

    /**
     * Writes only the selected regular record to private staging. Do not publish staging until
     * this call succeeds. Plain TAR authenticates neither payloads nor their bytes with CRC.
     * GZIP-compressed TAR requires a complete verified single-member GZIP stream before success.
     */
    static void copyEntry(FileChannel input,boolean gzip,Member selected,OutputStream privateStage)throws IOException{
        copyEntry(input,gzip,selected,privateStage,MAX_COMPRESSED,MAX_INFLATED);
    }

    static void copyEntry(FileChannel input,boolean gzip,Member selected,OutputStream privateStage,
                          long compressedBudget,long inflatedBudget)throws IOException{
        PreviewText.cancelled();
        if(!canPreview(selected,gzip))throw new IOException("This TAR entry is unavailable or exceeds the in-app preview limit.");
        if(privateStage==null||compressedBudget<=0||inflatedBudget<=0)throw new IllegalArgumentException("Invalid TAR preview request.");
        long size=input.size();
        Selection choice=new Selection(selected,privateStage);
        if(!gzip){
            try{collect(new Cursor(input,size,null,0),new ArrayList<>(),choice);}
            catch(PreviewLimit over){throw new IOException("This TAR member is beyond the in-app metadata scan limit.");}
        }else{
            // Unlike the partial metadata view, direct preview must be able to verify the trailer.
            if(size>compressedBudget)throw bounded();
            try(VerifiedGzip decoded=new VerifiedGzip(new BoundedCompressed(input,size,compressedBudget))){
                Cursor cursor=new Cursor(input,size,decoded,inflatedBudget);
                collect(cursor,new ArrayList<>(),choice);
                if(choice.copied)cursor.finishGzip(decoded);
            }catch(PreviewLimit over){throw bounded();}
            catch(EOFException truncated){throw new IOException(damaged().getMessage(),truncated);}
        }
        if(!choice.copied)throw new IOException("The TAR directory changed or the entry is beyond the preview limit.");
    }

    private static final class Selection{
        final Member entry;
        final OutputStream stage;
        boolean copied;
        Selection(Member entry,OutputStream stage){this.entry=entry;this.stage=stage;}
    }

    private static Listing collect(Cursor cursor,List<Member> entries,Selection selection)throws IOException{
        byte[] header=new byte[BLOCK];
        Map<String,Boolean> paths=new HashMap<>();
        Set<String> explicit=new HashSet<>();
        Map<String,String> metadata=null;
        try{
            while(true){
                cursor.metadata(header);
                if(StreamArchiveExtraction.isZero(header)){
                    if(metadata!=null)throw damaged();
                    cursor.metadata(header);
                    if(!StreamArchiveExtraction.isZero(header))throw damaged();
                    return incomplete(entries,Stop.COMPLETE);
                }
                if(entries.size()>=MAX_ENTRIES)return incomplete(entries,Stop.ENTRY_LIMIT);
                boolean posix=StreamArchiveExtraction.checksum(header);
                int type=header[156]&255;
                long size=StreamArchiveExtraction.octal(header,124,12);
                if(type=='x'||type=='L'){
                    if(metadata!=null||size==0||size>META_PER_RECORD) return incomplete(entries,Stop.UNSUPPORTED_RECORD);
                    byte[] value=new byte[(int)size];
                    cursor.metadata(value);
                    cursor.skip(StreamArchiveExtraction.padding(size));
                    if(type=='x')metadata=StreamArchiveExtraction.pax(value);
                    else {
                        metadata=new HashMap<>();
                        metadata.put("path",StreamArchiveExtraction.string(value,0,value.length));
                    }
                    continue;
                }
                boolean directory=type=='5';
                boolean special=type=='1'||type=='2'||type=='3'||type=='4'||type=='6';
                if(type!=0&&type!='0'&&!directory&&!special)return incomplete(entries,Stop.UNSUPPORTED_RECORD);
                String name;
                if(metadata!=null&&metadata.containsKey("path"))name=metadata.get("path");
                else{
                    String base=StreamArchiveExtraction.string(header,0,100);
                    String prefix=posix?StreamArchiveExtraction.string(header,345,155):"";
                    name=prefix.isEmpty()?base:prefix+"/"+base;
                }
                if(metadata!=null&&metadata.containsKey("size"))size=StreamArchiveExtraction.decimal(metadata.get("size"));
                metadata=null;
                if((directory||special)&&size!=0)throw damaged();
                if(directory&&(name.equals(".")||name.equals("./")))continue;
                while(name.startsWith("./"))name=name.substring(2);
                if(directory&&name.endsWith("/"))name=name.substring(0,name.length()-1);
                StreamArchiveExtraction.register(name,directory,paths,explicit);
                Member member=new Member(name,size,directory,special,entries.size(),cursor.position);
                if(selection!=null&&member.ordinal==selection.entry.ordinal){
                    if(!member.matches(selection.entry)||!canPreview(member,cursor.decoded!=null))
                        throw new IOException("The selected TAR member changed. Reopen the archive.");
                    cursor.copy(size,selection.stage);
                    selection.copied=true;
                    return incomplete(entries,Stop.COMPLETE);
                }
                entries.add(member);
                cursor.skip(size);
                cursor.skip(StreamArchiveExtraction.padding(size));
            }
        }catch(PreviewLimit limit){
            if(selection!=null)throw limit;
            return incomplete(entries,Stop.READ_LIMIT);
        }
    }

    private static Listing incomplete(List<Member> entries,Stop stop){
        boolean special=false;
        for(Member entry:entries)if(entry.special){special=true;break;}
        return new Listing(entries,stop,special);
    }

    private static final class Cursor {
        final FileChannel file;
        final long size;
        final InputStream decoded;
        long position,metadataLeft=META_BUDGET,decodedLeft;
        byte[] scratch;
        Cursor(FileChannel file,long size,InputStream decoded,long decodedLimit){
            this.file=file;this.size=size;this.decoded=decoded;decodedLeft=decodedLimit;
        }
        void metadata(byte[] target)throws IOException{
            if(target.length>metadataLeft)throw new PreviewLimit();
            metadataLeft-=target.length;
            read(target,target.length);
        }
        void skip(long bytes)throws IOException{
            PreviewText.cancelled();
            if(bytes==0)return;
            if(decoded==null){
                if(bytes>size-position)throw damaged();
                position+=bytes;return;
            }
            if(bytes>decodedLeft)throw new PreviewLimit();
            if(scratch==null)scratch=new byte[64*1024];
            for(long left=bytes;left>0;){
                int n=(int)Math.min(left,scratch.length);
                read(scratch,n);left-=n;
            }
        }
        void copy(long bytes,OutputStream stage)throws IOException{
            if(decoded==null&&bytes>size-position)throw damaged();
            if(decoded!=null&&bytes>decodedLeft)throw new PreviewLimit();
            if(scratch==null)scratch=new byte[64*1024];
            for(long left=bytes;left>0;){
                int n=(int)Math.min(left,scratch.length);
                read(scratch,n);stage.write(scratch,0,n);left-=n;
            }
        }
        void finishGzip(VerifiedGzip gzip)throws IOException{
            if(scratch==null)scratch=new byte[64*1024];
            while(!gzip.finishedData()){
                PreviewText.cancelled();
                if(decodedLeft==0)throw new PreviewLimit();
                int read=gzip.read(scratch,0,(int)Math.min(decodedLeft,scratch.length));
                if(read<0)return;
                if(read==0)throw damaged();
                decodedLeft-=read;
            }
            gzip.verifyTrailer();
        }
        void read(byte[] output,int length)throws IOException{
            if(decoded!=null){
                if(length>decodedLeft)throw new PreviewLimit();
                decodedLeft-=length;
                int offset=0;
                while(offset<length){
                    PreviewText.cancelled();
                    int n=decoded.read(output,offset,length-offset);
                    if(n<=0)throw new EOFException();
                    offset+=n;
                }
                position+=length;
                return;
            }
            if(length>size-position)throw damaged();
            ByteBuffer buffer=ByteBuffer.wrap(output,0,length);
            while(buffer.hasRemaining()){
                PreviewText.cancelled();
                int n=file.read(buffer,position);
                if(n<=0)throw damaged();
                position+=n;
            }
        }
    }

    /** A positional slice also caps optional gzip filename/comment scanning inside the decoder. */
    private static final class BoundedCompressed extends InputStream {
        final FileChannel file;
        final long size,bound;
        long position;
        final byte[] one=new byte[1];
        BoundedCompressed(FileChannel file,long size,long budget){
            this.file=file;this.size=size;bound=Math.min(size,budget);
        }
        boolean hitBound(){return bound<size&&position>=bound;}
        @Override public int available(){return (int)Math.min(Integer.MAX_VALUE,bound-position);}
        @Override public int read()throws IOException{return read(one,0,1)==-1?-1:one[0]&255;}
        @Override public int read(byte[] data,int offset,int length)throws IOException{
            if(length==0)return 0;
            PreviewText.cancelled();
            if(hitBound())throw new PreviewLimit();
            if(position>=size)return -1;
            int n=file.read(ByteBuffer.wrap(data,offset,(int)Math.min(length,bound-position)),position);
            if(n<=0)throw new EOFException();
            position+=n;return n;
        }
    }

    /** Strict single-member GZIP. Unlike GZIPInputStream, trailing bytes cannot be ignored. */
    private static final class VerifiedGzip extends InputStream {
        private static final int HEADER_LIMIT=128*1024;
        final BufferedInputStream raw;
        final Inflater inflater;
        final CRC32 checksum=new CRC32();
        final byte[] input=new byte[32*1024],one=new byte[1];
        int lastInput,checkedHeader;
        long inflated;
        boolean verified,closed;

        VerifiedGzip(InputStream source)throws IOException{
            raw=new BufferedInputStream(source,32*1024);
            CRC32 head=new CRC32();
            if(headerByte(head)!=0x1F||headerByte(head)!=0x8B||headerByte(head)!=8)throw damaged();
            int flags=headerByte(head);
            if((flags&0xE0)!=0)throw damaged();
            for(int i=0;i<6;i++)headerByte(head);
            if((flags&4)!=0){
                int extra=headerByte(head)|headerByte(head)<<8;
                for(int i=0;i<extra;i++)headerByte(head);
            }
            if((flags&8)!=0)while(headerByte(head)!=0){/* skip filename */}
            if((flags&16)!=0)while(headerByte(head)!=0){/* skip comment */}
            if((flags&2)!=0&&(required(raw)|required(raw)<<8)!=(head.getValue()&0xFFFF))throw damaged();
            inflater=new Inflater(true);
        }

        private int headerByte(CRC32 checksum)throws IOException{
            if(++checkedHeader>HEADER_LIMIT)throw new PreviewLimit();
            PreviewText.cancelled();
            int result=required(raw);checksum.update(result);return result;
        }
        private static int required(InputStream in)throws IOException{
            int n=in.read();if(n<0)throw new EOFException();return n;
        }
        @Override public int read()throws IOException{return read(one,0,1)<0?-1:one[0]&255;}
        @Override public int read(byte[] output,int offset,int len)throws IOException{
            if(len==0)return 0;
            if(closed)throw new IOException("The TAR preview stream closed.");
            try{
                while(!inflater.finished()){
                    PreviewText.cancelled();
                    if(inflater.needsInput()){
                        lastInput=raw.read(input);
                        if(lastInput<=0)throw new EOFException();
                        inflater.setInput(input,0,lastInput);
                    }
                    long before=inflater.getBytesRead();
                    int read=inflater.inflate(output,offset,len);
                    if(read>0){checksum.update(output,offset,read);inflated+=read;return read;}
                    if(inflater.needsDictionary()||(!inflater.finished()&&!inflater.needsInput()
                            &&inflater.getBytesRead()==before))throw damaged();
                }
                verifyTrailer();
                return -1;
            }catch(DataFormatException bad){throw damaged();}
        }
        boolean finishedData(){return inflater.finished();}
        void verifyTrailer()throws IOException{
            PreviewText.cancelled();
            if(verified)return;
            if(!inflater.finished())throw new PreviewLimit();
            int tail=lastInput-inflater.getRemaining();
            long crc=0,length=0;
            for(int i=0;i<8;i++){
                int n=tail<lastInput?input[tail++]&255:required(raw);
                if(i<4)crc|=(long)n<<(8*i);else length|=(long)n<<(8*(i-4));
            }
            if(crc!=checksum.getValue()||length!=(inflated&0xFFFFFFFFL))
                throw new IOException("This compressed TAR failed its gzip checksum or length check.");
            if(tail<lastInput||raw.read()!=-1)
                throw new IOException("Concatenated gzip members or trailing data need a dedicated archive app.");
            verified=true;
        }
        @Override public void close()throws IOException{
            if(closed)return;
            closed=true;inflater.end();raw.close();
        }
    }

    private static IOException bounded(){return new IOException("This compressed TAR exceeds the limit for a fully verified member preview. Extract it to open the file.");}
    private static IOException damaged(){return new IOException("This TAR directory is incomplete or invalid.");}
    private static final class PreviewLimit extends IOException{private static final long serialVersionUID=1L;}
    private TarArchiveListing(){}
}
