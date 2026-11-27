package dev.fastsearch.android;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;

public final class TarArchiveListingTest {
    private static int assertions;
    public static void main(String[] args)throws IOException{
        Path root=Files.createTempDirectory("tar-list-test-");
        try{
            Path source=root.resolve("fixture.tar");
            byte[] plain=tar(record("./",'5',new byte[0]),record("./inside/",'5',new byte[0]),
                    record("inside/雨.txt",'0',new byte[]{2,8,4}),record("empty.txt",'0',new byte[0]));
            Files.write(source,plain);
            checkBasic(source,false);
            check(Arrays.equals(Files.readAllBytes(source),plain),"listing did not modify source bytes");
            verifyMemberCopy(source,plain);
            try(java.util.stream.Stream<Path> files=Files.list(root)){check(files.count()==1,"browse publishes no output");}
            byte[] compressed=gzip(plain);
            Files.write(source,compressed);
            checkBasic(source,true);
            check(read(source,true,12,16*1024).stop==TarArchiveListing.Stop.READ_LIMIT,"compressed input budget");

            // Fixed prefix check and decompressed cap stop an optional GZIP filename scan.
            ByteArrayOutputStream longName=new ByteArrayOutputStream();
            compressed[3]|=8;
            longName.write(compressed,0,10);
            byte[] notNul=new byte[65536];Arrays.fill(notNul,(byte)'a');
            longName.write(notNul);longName.write(0);
            longName.write(compressed,10,compressed.length-10);
            Files.write(source,longName.toByteArray());
            check(read(source,true,2048,1024*1024).stop==TarArchiveListing.Stop.READ_LIMIT,
                    "untrusted optional GZIP filename cannot outgrow input budget");

            Files.write(source,gzip(tar(record("large.bin",'0',new byte[200*1024]),record("later.txt",'0',new byte[]{1}))));
            TarArchiveListing.Listing early=read(source,true,1024*1024,4096);
            check(early.stop==TarArchiveListing.Stop.READ_LIMIT&&early.entries.size()==1
                    &&early.entries.get(0).size==200*1024,"inflate cap keeps the header already read and marks partial");

            try(FileChannel sparse=FileChannel.open(source,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING)){
                int payload=96*1024*1024;
                sparse.write(ByteBuffer.wrap(header("large.dat",'0',payload)),0);
                sparse.write(ByteBuffer.wrap(tar(record("after.txt",'0',new byte[]{4}))),512L+payload);
            }
            TarArchiveListing.Listing complete=read(source,false,8,8);
            check(complete.stop==TarArchiveListing.Stop.COMPLETE&&complete.entries.get(1).name.equals("after.txt"),
                    "plain TAR seeks past even a large payload without decompressing or allocating it");
            check(!TarArchiveListing.canPreview(complete.entries.get(0),false),"oversize regular member is not previewable");
            check(Arrays.equals(copy(source,false,complete.entries.get(1)),new byte[]{4}),
                    "direct preview seeks to one selected small member after a very large raw TAR payload");

            byte[][] many=new byte[TarArchiveListing.MAX_ENTRIES+1][];
            for(int i=0;i<many.length;i++)many[i]=record("empty-"+i,'0',new byte[0]);
            Files.write(source,tar(many));
            TarArchiveListing.Listing first=read(source,false,8,8);
            check(first.entries.size()==TarArchiveListing.MAX_ENTRIES&&first.stop==TarArchiveListing.Stop.ENTRY_LIMIT,
                    "list cap is explicit");
            Files.write(source,tar(Arrays.copyOf(many,TarArchiveListing.MAX_ENTRIES)));
            check(read(source,false,8,8).stop==TarArchiveListing.Stop.COMPLETE,"exactly sixty names may still complete");

            String unicode="folders/".concat(String.join("",java.util.Collections.nCopies(40,"雪"))).concat(".txt");
            String value=" path="+unicode+"\n";
            byte[] pax=("" +(value.getBytes(StandardCharsets.UTF_8).length+3)+value).getBytes(StandardCharsets.UTF_8);
            Files.write(source,tar(record("pax-attributes",'x',pax),record("placeholder",'0',new byte[]{9})));
            check(read(source,false,8,8).entries.get(0).name.equals(unicode),"PAX metadata applies before reading the placeholder name");

            Files.write(source,tar(record("link",'2',new byte[0]),record("after.txt",'0',new byte[]{1})));
            TarArchiveListing.Listing link=read(source,false,8,8);
            check(link.entries.size()==2&&link.entries.get(0).special&&link.requiresDedicatedApp(),
                    "special entry is only shown as metadata and disables app extraction");
            Files.write(source,tar(record("plain.txt",'0',new byte[]{1}),record("global",'g',new byte[0])));
            check(read(source,false,8,8).requiresDedicatedApp(),"unsupported global records stop the preview cleanly");

            Files.write(source,tar(record("../outside",'0',new byte[]{1})));
            refuse(source);
            Files.write(source,Arrays.copyOf(plain,507));
            refuse(source);
            byte[] bad=plain.clone();bad[50]^=1;Files.write(source,bad);
            refuse(source);
            Thread.currentThread().interrupt();
            try{
                try(FileChannel input=FileChannel.open(source)){TarArchiveListing.read(input,false);}
                throw new AssertionError("TAR listing did not cancel");
            }catch(IOException expected){check(true,"TAR listing cancellation stops before publishing any list");}
            finally{Thread.interrupted();}
        }finally{ArchiveExtraction.removeStaging(root);}
        System.out.println("PASS "+assertions+" bounded TAR listing assertions.");
    }
    private static void verifyMemberCopy(Path source,byte[] raw)throws IOException{
        TarArchiveListing.Listing tar=read(source,false,1024*1024,16*1024);
        TarArchiveListing.Member folder=tar.entries.get(0), note=tar.entries.get(1), empty=tar.entries.get(2);
        check(!TarArchiveListing.canPreview(folder,false)&&TarArchiveListing.canPreview(note,false),
                "only regular named TAR records can preview");
        check(Arrays.equals(copy(source,false,note),new byte[]{2,8,4}),"exact selected raw TAR bytes");
        check(copy(source,false,empty).length==0,"plain zero-byte member preserved");
        rejectCopy(source,false,folder,TarArchiveListing.MAX_COMPRESSED,TarArchiveListing.MAX_INFLATED,"unavailable",true);
        Files.write(source,tar(record("changed-name",'0',new byte[]{2,8,4})));
        rejectCopy(source,false,note,TarArchiveListing.MAX_COMPRESSED,TarArchiveListing.MAX_INFLATED,"changed",true);
        Files.write(source,raw);

        byte[] compressed=gzip(raw);
        Files.write(source,compressed);
        TarArchiveListing.Member gzipNote=read(source,true,1024*1024,16*1024).entries.get(1);
        check(Arrays.equals(copy(source,true,gzipNote),new byte[]{2,8,4}),
                "GZIP selected bytes returned only after full trailer validation");
        byte[] footer=compressed.clone();footer[footer.length-8]^=8;Files.write(source,footer);
        rejectCopy(source,true,gzipNote,TarArchiveListing.MAX_COMPRESSED,TarArchiveListing.MAX_INFLATED,"checksum",false);
        footer=compressed.clone();footer[footer.length-4]^=8;Files.write(source,footer);
        rejectCopy(source,true,gzipNote,TarArchiveListing.MAX_COMPRESSED,TarArchiveListing.MAX_INFLATED,"length",false);
        Files.write(source,Arrays.copyOf(compressed,compressed.length-2));
        rejectCopy(source,true,gzipNote,TarArchiveListing.MAX_COMPRESSED,TarArchiveListing.MAX_INFLATED,"incomplete",false);
        Files.write(source,Arrays.copyOf(compressed,compressed.length+1));
        rejectCopy(source,true,gzipNote,TarArchiveListing.MAX_COMPRESSED,TarArchiveListing.MAX_INFLATED,"trailing",false);
        ByteArrayOutputStream concat=new ByteArrayOutputStream();
        concat.write(compressed);concat.write(gzip(new byte[]{2}));
        Files.write(source,concat.toByteArray());
        rejectCopy(source,true,gzipNote,TarArchiveListing.MAX_COMPRESSED,TarArchiveListing.MAX_INFLATED,"Concatenated",false);
        Files.write(source,compressed);
        rejectCopy(source,true,gzipNote,compressed.length-1,16*1024,"limit",true);
        rejectCopy(source,true,gzipNote,compressed.length,2000,"limit",false);
        check(Arrays.equals(copy(source,true,gzipNote),new byte[]{2,8,4}),"failed scoped checks did not mutate the archive");

        byte[] stream=new byte[300*1024];new java.util.Random(12).nextBytes(stream);
        byte[] larger=gzip(tar(record("random.bin",'0',stream)));
        Files.write(source,larger);
        TarArchiveListing.Member random=read(source,true,1024*1024,1024*1024).entries.get(0);
        check(Arrays.equals(copy(source,true,random),stream),"CRC and footer seen across many compressed input fills");
        try(FileChannel file=FileChannel.open(source)){
            OutputStream cancellation=new OutputStream(){
                @Override public void write(int ignored){Thread.currentThread().interrupt();}
                @Override public void write(byte[] b,int offset,int length){Thread.currentThread().interrupt();}
            };
            try{
                TarArchiveListing.copyEntry(file,true,random,cancellation);
                throw new AssertionError("member copy ignored cancellation");
            }catch(IOException expected){check(Thread.currentThread().isInterrupted(),"gated copy observes cancellation between bounded chunks");}
            finally{Thread.interrupted();}
        }

        byte[] optional=gzipWithHeader(raw);
        Files.write(source,optional);
        TarArchiveListing.Member option=read(source,true,1024*1024,1024*1024).entries.get(1);
        check(Arrays.equals(copy(source,true,option),new byte[]{2,8,4}),"required gzip FHCRC plus optional basename/comment handled");
        optional[10]^=1;Files.write(source,optional);
        rejectCopy(source,true,option,TarArchiveListing.MAX_COMPRESSED,TarArchiveListing.MAX_INFLATED,"incomplete",true);
        Files.write(source,raw);
    }

    private static byte[] gzipWithHeader(byte[] raw)throws IOException{
        byte[] gzip=gzip(raw);
        gzip[3]=30;
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        output.write(gzip,0,10);
        output.write(new byte[]{1,0,7,'a',0,'b',0});
        java.util.zip.CRC32 crc=new java.util.zip.CRC32();crc.update(output.toByteArray());
        output.write((int)crc.getValue()&255);output.write((int)(crc.getValue()>>>8)&255);
        output.write(gzip,10,gzip.length-10);
        return output.toByteArray();
    }

    private static byte[] copy(Path source,boolean gzip,TarArchiveListing.Member member)throws IOException{
        try(FileChannel in=FileChannel.open(source)){
            ByteArrayOutputStream output=new ByteArrayOutputStream();
            TarArchiveListing.copyEntry(in,gzip,member,output);
            return output.toByteArray();
        }
    }
    private static void rejectCopy(Path source,boolean gzip,TarArchiveListing.Member member,long compressed,long decoded,
                                   String message,boolean zeroStage)throws IOException{
        try(FileChannel in=FileChannel.open(source)){
            ByteArrayOutputStream privateStage=new ByteArrayOutputStream();
            try{
                TarArchiveListing.copyEntry(in,gzip,member,privateStage,compressed,decoded);
                throw new AssertionError("invalid preview accepted: "+message);
            }catch(IOException error){
                check(String.valueOf(error.getMessage()).contains(message),"correct refusal: "+error.getMessage());
                if(zeroStage)check(privateStage.size()==0,"refusal happened before any copy");
            }
        }
    }

    private static void checkBasic(Path source,boolean gzip)throws IOException{
        TarArchiveListing.Listing list=read(source,gzip,1024*1024,16*1024);
        check(list.stop==TarArchiveListing.Stop.COMPLETE&&list.entries.size()==3,"names visible in "+(gzip?"TAR.GZ":"TAR"));
        check(list.entries.get(0).directory&&list.entries.get(1).name.equals("inside/雨.txt")
                &&list.entries.get(1).size==3,"path, folder and exact sizes");
        try{list.entries.clear();throw new AssertionError("mutable output");}
        catch(UnsupportedOperationException expected){check(true,"immutable preview");}
    }
    private static TarArchiveListing.Listing read(Path source,boolean gzip,long a,long b)throws IOException{
        try(FileChannel input=FileChannel.open(source)){return TarArchiveListing.read(input,gzip,a,b);}
    }
    private static void refuse(Path source)throws IOException{
        try{read(source,false,8,8);throw new AssertionError("structurally invalid archive accepted");}
        catch(IOException expected){check(true,"invalid metadata refused");}
    }
    private static byte[] tar(byte[]... records)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        for(byte[] record:records)out.write(record);
        out.write(new byte[1024]);return out.toByteArray();
    }
    private static byte[] record(String name,char type,byte[] bytes)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        out.write(header(name,type,bytes.length));out.write(bytes);
        out.write(new byte[(512-bytes.length%512)%512]);return out.toByteArray();
    }
    private static byte[] header(String name,char kind,long size){
        byte[] result=new byte[512];
        copy(result,0,name);copy(result,100,"0000644");
        copy(result,124,String.format(Locale.ROOT,"%011o",size));
        Arrays.fill(result,148,156,(byte)' ');result[156]=(byte)kind;
        copy(result,257,"ustar\00000");
        int checksum=0;for(byte value:result)checksum+=value&255;
        copy(result,148,String.format(Locale.ROOT,"%06o\0 ",checksum));
        return result;
    }
    private static void copy(byte[] target,int offset,String text){
        byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(bytes,0,target,offset,bytes.length);
    }
    private static byte[] gzip(byte[] plain)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(OutputStream gzip=new GZIPOutputStream(out)){gzip.write(plain);}
        return out.toByteArray();
    }
    private static void check(boolean good,String label){assertions++;if(!good)throw new AssertionError(label);}
}
