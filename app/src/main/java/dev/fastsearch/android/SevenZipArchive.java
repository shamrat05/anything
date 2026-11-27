package dev.fastsearch.android;

import org.tukaani.xz.LZMA2InputStream;
import org.tukaani.xz.LZMAInputStream;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.zip.CRC32;

/** Bounded reader for single-coder 7z folders. Only plain files and directories are accepted. */
final class SevenZipArchive {
    private static final byte[] SIGNATURE = {'7', 'z', (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C};
    private static final int START = 32, HEADER_MAX = 1024 * 1024, DICT_MAX = 32 * 1024 * 1024, ENTRIES_MAX = 2000;
    private static final int END = 0, HEADER = 1, ARCHIVE_PROPS = 2, STREAMS = 4, FILES = 5, PACK = 6,
            UNPACK = 7, SUB = 8, SIZE = 9, CRC = 10, FOLDER = 11, RAW_SIZE = 12, SUB_COUNT = 13,
            EMPTY_STREAM = 14, EMPTY_FILE = 15, ANTI = 16, NAME = 17, ATTR = 21, ENCODED = 23;
    private static final int COPY = 0, LZMA2 = 0x21, LZMA = 0x030101;

    static final class Entry {
        final String name;
        final boolean directory;
        final long size;
        Entry(String name, boolean directory, long size) {
            this.name = name; this.directory = directory; this.size = size;
        }
    }

    static final class Listing {
        final List<Entry> entries;
        final int count;
        Listing(List<Entry> entries, int count) { this.entries = Collections.unmodifiableList(entries); this.count = count; }
    }

    private static final class Segment {
        final int coder;
        final byte[] properties;
        long pos, packed, unpacked, crc = -1, packedCrc = -1;
        long[] parts, checksums;
        Segment(int coder, byte[] properties) { this.coder = coder; this.properties = properties; }
        int dictionary() throws IOException {
            if (coder == COPY) return 0;
            long declared;
            if (coder == LZMA2) {
                int bits = properties[0] & 255;
                if (bits > 40) throw damaged();
                declared = bits == 40 ? 0xFFFFFFFFL : (2L | (bits & 1)) << (bits / 2 + 11);
            } else {
                int params = properties[0] & 255;
                if (params >= 9 * 5 * 5) throw damaged();
                if (params % 9 + (params / 9) % 5 > 4) throw unsupported();
                declared = ByteBuffer.wrap(properties,1,4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
            }
            // A window never needs to be larger than the actual uncompressed segment.
            long needed = Math.max(4096, Math.min(declared, unpacked));
            if (needed > DICT_MAX) throw new IOException("This 7z needs a larger decoder window. Use a dedicated archive app.");
            return (int) needed;
        }
    }

    private static final class Parsed {
        final List<Entry> entries;
        final Segment[] segments;
        Parsed(List<Entry> entries, Segment[] segments) { this.entries=entries; this.segments=segments; }
    }

    static Listing inspect(FileChannel file) throws IOException {
        Parsed parsed = parse(file);
        return new Listing(new ArrayList<>(parsed.entries.subList(0, Math.min(60,parsed.entries.size()))),parsed.entries.size());
    }

    /** Copies only a reviewed member into a private staging writer; verifies its whole coder segment. */
    static void copyEntry(FileChannel file,Entry wanted,OutputStream target)throws IOException{
        final long entryCap=32L*1024*1024,segmentCap=96L*1024*1024;
        if(wanted==null||wanted.directory||wanted.size>entryCap)throw new IOException("This 7z entry exceeds the preview limit.");
        Parsed result=parse(file);
        int index=0;
        for(Segment segment:result.segments){
            int[] files=new int[segment.parts.length];
            int found=-1;
            for(int part=0;part<files.length;part++){
                while(index<result.entries.size()&&result.entries.get(index).size<0){
                    Entry empty=result.entries.get(index++);
                    if(!empty.directory&&empty.name.equals(wanted.name)&&wanted.size<0)return;
                }
                if(index>=result.entries.size())throw damaged();
                files[part]=index;
                Entry row=result.entries.get(index++);
                if(row.name.equals(wanted.name)&&row.size==wanted.size)found=part;
            }
            if(found<0)continue;
            if(segment.unpacked>segmentCap||segment.packed>segmentCap)
                throw new IOException("This solid 7z block is too large for a member preview. Use Extract all.");
            if(segment.crc<0&&segment.checksums[found]<0)
                throw new IOException("The selected 7z entry has no checksum to verify for preview.");
            OutputStream discard=new OutputStream(){
                @Override public void write(int value){}
                @Override public void write(byte[] value,int offset,int length){}
            };
            byte[] buffer=new byte[64*1024];
            try(Decoder decoder=new Decoder(file,segment)){
                for(int part=0;part<files.length;part++){
                    Entry row=result.entries.get(files[part]);
                    decoder.copy(part==found?target:discard,row.size,segment.checksums[part],buffer);
                }
                decoder.finish();
            }
            return;
        }
        while(index<result.entries.size()){
            Entry empty=result.entries.get(index++);
            if(!empty.directory&&empty.name.equals(wanted.name)&&wanted.size<0&&empty.size<0)return;
        }
        throw new IOException("The selected 7z entry changed. Reopen the archive.");
    }

    static void extract(FileChannel file, Path fresh) throws IOException {
        Parsed result = parse(file);
        long needed = 0;
        for (Segment segment : result.segments) needed += segment.unpacked;
        if (needed > fresh.toFile().getUsableSpace()) throw new IOException("Not enough free space to prepare extraction.");
        byte[] buffer = new byte[64 * 1024];
        int index = 0;
        for (Segment segment : result.segments) {
            try (Decoder decoder = new Decoder(file,segment)) {
                for (int part = 0; part < segment.parts.length; part++) {
                    PreviewText.cancelled();
                    while (result.entries.get(index).size < 0) createEmpty(fresh,result.entries.get(index++));
                    Entry entry = result.entries.get(index++);
                    Path out = fresh.resolve(entry.name);
                    Files.createDirectories(out.getParent());
                    try (OutputStream target = Files.newOutputStream(out,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
                        decoder.copy(target, entry.size, segment.checksums[part],buffer);
                    }
                }
                decoder.finish();
            }
        }
        while (index < result.entries.size()) {
            PreviewText.cancelled();
            createEmpty(fresh,result.entries.get(index++));
        }
    }

    private static void createEmpty(Path fresh, Entry entry) throws IOException {
        Path out = fresh.resolve(entry.name);
        if (entry.directory) Files.createDirectories(out);
        else { Files.createDirectories(out.getParent()); Files.createFile(out); }
    }

    private static Parsed parse(FileChannel file) throws IOException {
        PreviewText.cancelled();
        long size = file.size();
        if (size > ArchiveExtraction.MAX_BYTES)
            throw new IOException("7z archives larger than 1 GB need a dedicated archive app.");
        if (size < START) throw damaged();
        byte[] start = new byte[START];
        readAt(file,0,start);
        for (int i=0;i<SIGNATURE.length;i++) if (start[i] != SIGNATURE[i]) throw damaged();
        if (start[6] != 0) throw unsupported();
        ByteBuffer b = ByteBuffer.wrap(start).order(ByteOrder.LITTLE_ENDIAN);
        crc(start,12,20,b.getInt(8) & 0xFFFFFFFFL);
        long offset = b.getLong(12), length = b.getLong(20);
        long dataLimit = size - START;
        if (offset < 0 || offset > dataLimit || length != dataLimit - offset || length > HEADER_MAX) throw damaged();
        long headerCrc = b.getInt(28) & 0xFFFFFFFFL;
        if (length == 0 && offset == 0 && headerCrc == 0) return new Parsed(Collections.emptyList(),new Segment[0]);
        byte[] metadata = new byte[(int)length];
        readAt(file,START + offset,metadata);
        crc(metadata,0,metadata.length,headerCrc);
        Reader r = new Reader(metadata);
        int tag = r.one();
        if (tag == ENCODED) {
            Segment[] headers = parseStreams(r,offset,true);
            if (headers.length != 1 || r.more() || headers[0].parts.length != 1 || headers[0].unpacked == 0)
                throw unsupported();
            Segment segment = headers[0];
            byte[] unpacked = new byte[(int) segment.unpacked];
            try (Decoder decoder = new Decoder(file,segment)) {
                int read = 0;
                while (read < unpacked.length) {
                    int n = decoder.read(unpacked,read,Math.min(64 * 1024,unpacked.length-read));
                    if (n <= 0) throw damaged();
                    read += n;
                }
                crc(unpacked,0,unpacked.length,segment.checksums[0]);
                decoder.finish();
            }
            r = new Reader(unpacked);
            tag = r.one();
            offset = segment.pos - START;
        }
        if (tag != HEADER) throw unsupported();
        tag = r.one();
        if (tag == ARCHIVE_PROPS) {
            while (r.one() != END) r.skip(r.count(HEADER_MAX));
            tag = r.one();
        }
        Segment[] folders = new Segment[0];
        if (tag == STREAMS) { folders = parseStreams(r,offset,false); tag = r.one(); }
        List<Entry> entries = Collections.emptyList();
        if (tag == FILES) { entries = parseFiles(r,folders); tag = r.one(); }
        else if (folders.length != 0) throw damaged();
        if (tag != END || r.more()) throw unsupported();
        return new Parsed(entries,folders);
    }

    private static Segment[] parseStreams(Reader r,long maxPacked,boolean metadata) throws IOException {
        r.require(PACK);
        long position = r.number(maxPacked);
        int streams = r.count(metadata ? 1 : ENTRIES_MAX);
        if (streams == 0) throw damaged();
        r.require(SIZE);
        long[] packed = new long[streams];
        long remaining = maxPacked - position;
        for (int i=0;i<streams;i++) { packed[i]=r.number(remaining); remaining-=packed[i]; }
        int tag = r.one();
        long[] packedCrc = noCrc(streams);
        if (tag == CRC) { packedCrc = r.digests(streams); tag = r.one(); }
        if (tag != END) throw unsupported();
        r.require(UNPACK);
        r.require(FOLDER);
        if (r.number(streams) != streams || r.one() != 0) throw unsupported();
        Segment[] folders = new Segment[streams];
        for (int i=0;i<streams;i++) {
            PreviewText.cancelled();
            if (r.number(Long.MAX_VALUE) != 1) throw unsupported();
            int flags = r.one(), idLen = flags & 15;
            if ((flags & 0xD0) != 0 || idLen == 0 || idLen > 4) throw unsupported();
            int method = 0;
            for (int m=0;m<idLen;m++) method = (method << 8) | r.one();
            int requiredProps = idLen==1 && method==COPY ? 0 : idLen==1 && method==LZMA2 ? 1
                    : idLen==3 && method==LZMA ? 5 : -1;
            if (requiredProps < 0) throw unsupported();
            int propsSize = (flags & 32) != 0 ? r.count(5) : 0;
            if (propsSize != requiredProps) throw unsupported();
            Segment f = new Segment(method,r.bytes(propsSize));
            f.pos=START+position; f.packed=packed[i]; position+=packed[i]; f.packedCrc=packedCrc[i]; folders[i]=f;
        }
        r.require(RAW_SIZE);
        remaining = metadata ? HEADER_MAX : ArchiveExtraction.MAX_BYTES;
        for (Segment f : folders) {
            f.unpacked=r.number(remaining); remaining-=f.unpacked;
            if (f.coder==COPY && f.packed!=f.unpacked) throw damaged();
            f.dictionary(); // Reject oversized dictionaries before any decoder allocation.
        }
        tag=r.one();
        if (tag==CRC) {
            long[] digests=r.digests(streams);
            for(int i=0;i<streams;i++)folders[i].crc=digests[i];
            tag=r.one();
        }
        if(tag!=END)throw unsupported();
        tag=r.one();
        int[] counts = new int[streams];
        Arrays.fill(counts,1);
        if (tag==SUB) {
            tag=r.one();
            if(tag==SUB_COUNT) {
                int left=metadata ? 1 : ENTRIES_MAX;
                for(int i=0;i<streams;i++){
                    counts[i]=r.count(left);left-=counts[i];
                    if(counts[i]==0)throw unsupported();
                }
                tag=r.one();
            }
            assignParts(r,tag==SIZE,counts,folders);
            if(tag==SIZE)tag=r.one();
            if(tag==CRC) {
                int missing=0;
                for(int i=0;i<streams;i++)missing+=counts[i]==1&&folders[i].crc>=0 ? 0 : counts[i];
                long[] values=r.digests(missing);
                int n=0;
                for(Segment f:folders)if(f.parts.length!=1||f.crc<0)
                    for(int i=0;i<f.parts.length;i++)f.checksums[i]=values[n++];
                tag=r.one();
            }
            if(tag!=END)throw unsupported();
            tag=r.one();
        } else assignParts(r,false,counts,folders);
        if(tag!=END)throw unsupported();
        for(Segment f:folders) {
            if(f.packedCrc<0&&f.crc<0)for(long value:f.checksums)
                if(value<0)throw new IOException("This 7z has no supported integrity check. Use a dedicated archive app.");
        }
        return folders;
    }

    private static void assignParts(Reader r,boolean hasSizes,int[] counts,Segment[] folders) throws IOException {
        for(int i=0;i<folders.length;i++){
            Segment f=folders[i]; int count=counts[i];
            if(count>1&&!hasSizes)throw damaged();
            f.parts=new long[count]; f.checksums=noCrc(count);
            long left=f.unpacked;
            for(int k=0;k<count-1;k++){f.parts[k]=r.number(left);left-=f.parts[k];}
            f.parts[count-1]=left;
            if(count==1&&f.crc>=0)f.checksums[0]=f.crc;
        }
    }

    private static List<Entry> parseFiles(Reader r,Segment[] folders) throws IOException {
        int count=r.count(ENTRIES_MAX);
        String[] names=null;
        boolean[] noData=new boolean[count], emptyFiles=new boolean[0];
        int noDataCount=0;
        int[] attributes=new int[count];
        boolean[] seen=new boolean[32];
        while(true){
            PreviewText.cancelled();
            int tag=r.one();
            if(tag==END)break;
            Reader data=new Reader(r.bytes(r.count(r.remaining())));
            if(tag>=seen.length || seen[tag])throw unsupported();
            seen[tag]=true;
            switch(tag){
                case NAME:
                    data.require(0); names=new String[count];
                    for(int i=0;i<count;i++)names[i]=data.utf16Name();
                    break;
                case EMPTY_STREAM:
                    noData=data.bits(count);
                    for(boolean flag:noData)if(flag)noDataCount++;
                    break;
                case EMPTY_FILE:
                    if(!seen[EMPTY_STREAM])throw damaged();
                    emptyFiles=data.bits(noDataCount);
                    break;
                case ANTI:
                    if(!seen[EMPTY_STREAM])throw damaged();
                    for(boolean flag:data.bits(noDataCount))if(flag)throw unsupported();
                    break;
                case ATTR:
                    boolean[] defined=data.defined(count);
                    data.require(0);
                    for(int i=0;i<count;i++)if(defined[i])attributes[i]=(int)data.u32();
                    break;
                case 18: case 19: case 20: case 22: case 25:
                    data.skip(data.remaining()); break; // Time, comment, and padding metadata are never applied.
                default: throw unsupported();
            }
            if(data.more())throw damaged();
        }
        if(count!=0&&names==null)throw damaged();
        int streams=0;
        for(Segment f:folders)streams+=f.parts.length;
        if(count-noDataCount!=streams)throw damaged();
        ArrayList<Entry> entries=new ArrayList<>(count);
        HashMap<String,Boolean> paths=new HashMap<>();
        HashSet<String> explicit=new HashSet<>();
        int empty=0, folder=0, part=0;
        for(int i=0;i<count;i++){
            PreviewText.cancelled();
            boolean directory=noData[i]&&(emptyFiles.length==0||!emptyFiles[empty]);
            if(noData[i])empty++;
            int attr=attributes[i];
            int unix=(attr >>> 16)&0170000;
            if((attr&0x440)!=0||((attr&0x10)!=0&&!directory)
                    || unix!=0&&unix!=(directory?0040000:0100000))throw unsupported();
            StreamArchiveExtraction.register(names[i],directory,paths,explicit);
            long length=-1;
            if(!noData[i]){
                length=folders[folder].parts[part++];
                if(part==folders[folder].parts.length){folder++;part=0;}
            }
            entries.add(new Entry(names[i],directory,length));
        }
        return entries;
    }

    private static final class Decoder implements AutoCloseable {
        final Segment segment;
        final Slice slice;
        final BufferedInputStream packed;
        final InputStream input;
        final CRC32 decodedCrc=new CRC32();
        long left;
        Decoder(FileChannel file,Segment segment) throws IOException {
            this.segment=segment; left=segment.unpacked; slice=new Slice(file,segment.pos,segment.packed);
            packed=new BufferedInputStream(slice,32*1024);
            switch(segment.coder) {
                case COPY: input=packed; break;
                case LZMA2: input=new LZMA2InputStream(packed,segment.dictionary()); break;
                case LZMA:
                    LZMAInputStream lzma=new LZMAInputStream(packed,segment.unpacked,segment.properties[0],segment.dictionary());
                    lzma.enableRelaxedEndCondition(); input=lzma; break;
                default: throw unsupported();
            }
        }
        int read(byte[] bytes,int offset,int size) throws IOException {
            PreviewText.cancelled();
            if(size>left)throw damaged();
            int n=input.read(bytes,offset,size);
            if(n>0){decodedCrc.update(bytes,offset,n);left-=n;}
            return n;
        }
        void copy(OutputStream out,long size,long digest,byte[] buffer) throws IOException {
            CRC32 part=new CRC32();
            while(size>0){
                int n=read(buffer,0,(int)Math.min(buffer.length,size));
                if(n<=0)throw damaged();
                part.update(buffer,0,n); out.write(buffer,0,n); size-=n;
            }
            if(digest>=0&&part.getValue()!=digest)throw damaged();
        }
        void finish() throws IOException {
            PreviewText.cancelled();
            if(left!=0||input.read()!=-1||packed.read()!=-1||slice.left!=0
                    || segment.crc>=0&&decodedCrc.getValue()!=segment.crc
                    || segment.packedCrc>=0&&slice.crc.getValue()!=segment.packedCrc)throw damaged();
        }
        @Override public void close() throws IOException { input.close(); }
    }

    private static final class Slice extends InputStream {
        final FileChannel file;
        final CRC32 crc=new CRC32();
        final byte[] single=new byte[1];
        long offset,left;
        Slice(FileChannel file,long offset,long size){this.file=file;this.offset=offset;left=size;}
        @Override public int read() throws IOException { return read(single,0,1)<0 ? -1 : single[0]&255; }
        @Override public int read(byte[] bytes,int start,int length) throws IOException {
            PreviewText.cancelled();
            if(length==0)return 0;
            if(left==0)return -1;
            int n=file.read(ByteBuffer.wrap(bytes,start,(int)Math.min(length,left)),offset);
            if(n<=0)throw damaged();
            crc.update(bytes,start,n); offset+=n; left-=n; return n;
        }
        // Closing does not close the borrowed file channel.
    }

    private static final class Reader {
        final byte[] bytes;
        private int at;
        Reader(byte[] bytes){this.bytes=bytes;}
        int remaining(){return bytes.length-at;}
        boolean more(){return at<bytes.length;}
        int one() throws IOException { if(!more())throw damaged(); return bytes[at++]&255; }
        void require(int expected) throws IOException { if(one()!=expected)throw damaged(); }
        long number(long max) throws IOException {
            int first=one(), mask=128;
            long value=0;
            for(int i=0;i<8;i++){
                if((first&mask)==0){
                    value|=(long)(first&(mask-1))<<(i*8);
                    if(value<0||value>max)throw limit();
                    return value;
                }
                value|=(long)one()<<(i*8); mask>>>=1;
            }
            if(value<0||value>max)throw limit();
            return value;
        }
        int count(int max) throws IOException { return (int)number(max); }
        void skip(int count) throws IOException { if(count<0||count>remaining())throw damaged(); at+=count; }
        byte[] bytes(int count) throws IOException {int start=at; skip(count); return Arrays.copyOfRange(bytes,start,at);}
        long u32() throws IOException { long v=0; for(int i=0;i<4;i++)v|=(long)one()<<(8*i); return v; }
        boolean[] bits(int count) throws IOException {
            boolean[] flags=new boolean[count];
            int v=0;
            for(int i=0;i<count;i++){if(i%8==0)v=one();flags[i]=(v&(128>>>(i%8)))!=0;}
            return flags;
        }
        boolean[] defined(int count) throws IOException {
            int mode=one();
            if(mode==0)return bits(count);
            if(mode!=1)throw damaged();
            boolean[] flags=new boolean[count]; Arrays.fill(flags,true); return flags;
        }
        long[] digests(int count) throws IOException {
            boolean[] defined=defined(count);
            long[] values=noCrc(count);
            for(int i=0;i<count;i++)if(defined[i])values[i]=u32();
            return values;
        }
        String utf16Name() throws IOException {
            int start=at;
            while(remaining()>=2&&(bytes[at]!=0||bytes[at+1]!=0)) {
                at+=2;
                if(at-start>8190)throw limit();
            }
            if(remaining()<2||at==start)throw damaged();
            try {
                String value=StandardCharsets.UTF_16LE.newDecoder().decode(ByteBuffer.wrap(bytes,start,at-start)).toString();
                at+=2; return value;
            } catch(java.nio.charset.CharacterCodingException bad){throw damaged();}
        }
    }

    private static long[] noCrc(int n){long[] a=new long[n];Arrays.fill(a,-1);return a;}
    private static void readAt(FileChannel file,long offset,byte[] bytes) throws IOException {
        ByteBuffer buffer=ByteBuffer.wrap(bytes);
        while(buffer.hasRemaining()){
            PreviewText.cancelled();
            if(file.read(buffer,offset+buffer.position())<=0)throw damaged();
        }
    }
    private static void crc(byte[] bytes,int start,int count,long expected) throws IOException {
        if(expected<0)return;
        CRC32 crc=new CRC32();crc.update(bytes,start,count);
        if(crc.getValue()!=expected)throw damaged();
    }
    private static IOException damaged(){return new IOException("This 7z is incomplete, split, or failed an integrity check.");}
    private static IOException unsupported(){return new IOException("This 7z uses an unsupported codec or feature. Use a dedicated archive app for encrypted, split, filtered, or special-file archives.");}
    private static IOException limit(){return new IOException("This 7z exceeds the in-app size or metadata limit.");}
    private SevenZipArchive(){}
}
