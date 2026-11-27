package dev.fastsearch.android;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ArchiveIntegrityTest {
    private static int assertions;
    public static void main(String[] args)throws IOException{
        Path directory=Files.createTempDirectory("integrity-probe-");
        try {
            Path archive=directory.resolve("case.zip");
            create(archive);
            byte[] clean=Files.readAllBytes(archive);
            ArchiveIntegrity.Result good=test(archive);
            check(good.passed()&&good.verified==4&&good.entries==4,"stored, deflated, directory, and Unicode entries verify");
            try(java.util.stream.Stream<Path> children=Files.list(directory)){
                check(children.count()==1,"read-only test publishes no content");
            }
            check(Arrays.equals(clean,Files.readAllBytes(archive)),"test does not modify source");
            ArchiveIntegrity.Result limited=test(archive,30);
            check(limited.incomplete()&&!limited.passed()&&limited.overBudget>0,"resource limit is partial, not a pass");

            byte[] altered=clean.clone();
            int storedData=30+u16(altered,26)+u16(altered,28);
            altered[storedData]^=1;
            Files.write(archive,altered);
            ArchiveIntegrity.Result bad=test(archive);
            check(bad.damaged==1&&bad.verified==3&&!bad.passed(),"CRC verifies stored bytes, not just local/central header metadata");
            check("standalone.txt".equals(bad.firstDamagedEntry),"identifies first physical failure");
            Files.write(archive,clean);
            altered=clean.clone();altered[30]^=2;
            Files.write(archive,altered);
            check(test(archive).damaged==1,"local filename must agree with central record");

            int end=clean.length-22, central=(int)u32(clean,end+16);
            altered=clean.clone();altered[central+8]|=1;altered[6]|=1;
            Files.write(archive,altered);
            ArchiveIntegrity.Result encrypted=test(archive);
            check(encrypted.incomplete()&&encrypted.unsupported==1&&!encrypted.passed()&&encrypted.damaged==0,
                    "encrypted entries are unsupported, not checked");
            altered=clean.clone();put16(altered,central+10,99);put16(altered,8,99);
            Files.write(archive,altered);
            check(test(archive).unsupported==1&&!test(archive).passed(),"unrecognized methods stay partial");

            altered=clean.clone();
            put16(altered,end+8,3);put16(altered,end+10,3); // Disagreement with actual central records.
            Files.write(archive,altered);
            refuse(archive);
            altered=clean.clone();altered[central]^=1;
            Files.write(archive,altered);
            refuse(archive);
            Files.write(archive,Arrays.copyOf(clean,clean.length-4));
            refuse(archive);
            check(ArchiveIntegrity.supports("archive.ZIP")&&ArchiveIntegrity.supports("example.APK")
                    &&ArchiveIntegrity.supports("book.cbz")&&ArchiveIntegrity.supports("code.jar")
                    &&!ArchiveIntegrity.supports("other.7z"),"routing names only the four supported containers");

            byte[] plaintext="payload for a streaming descriptor".getBytes(StandardCharsets.UTF_8);
            Files.write(archive,fixture(plaintext,true,false,false,false));
            check(test(archive).passed(),"unsigned data descriptor supported");
            Files.write(archive,fixture(plaintext,true,true,false,false));
            check(test(archive).passed(),"ZIP64 streamed signed descriptor supported");
            Files.write(archive,fixture(plaintext,true,false,true,false));
            check(test(archive).damaged==1,"CRC or declared-length mismatch in a data descriptor is detected");
            Files.write(archive,fixture(plaintext,false,false,false,true));
            check(test(archive).damaged==1,"valid CRC cannot conceal excess compressed payload bytes");

            Files.write(archive,clean);
            AtomicInteger loops=new AtomicInteger();
            try(FileChannel source=FileChannel.open(archive)){
                try{ArchiveIntegrity.check(source,()->loops.incrementAndGet()>14);throw new AssertionError("not cancelled");}
                catch(InterruptedIOException expected){check(loops.get()>14,"cancellation cooperates during content checks");}
            }
            try(ZipOutputStream writer=new ZipOutputStream(Files.newOutputStream(archive))){
                for(int i=0;i<=ZipDirectory.MAX_ENTRIES;i++)store(writer,"file-"+i,new byte[0]);
            }
            ArchiveIntegrity.Result many=test(archive);
            check(many.verified==ZipDirectory.MAX_ENTRIES&&many.unlisted==1&&many.incomplete()&&!many.passed(),
                    "ZIP-directory truncation cannot yield a pass");
            try(ZipOutputStream writer=new ZipOutputStream(Files.newOutputStream(archive))){writer.finish();}
            check(test(archive).passed()&&test(archive).entries==0,"valid empty ZIP");
        }finally{ArchiveExtraction.removeStaging(directory);}
        if(args.length>0){
            ArchiveIntegrity.Result apk=test(Paths.get(args[0]));
            check(apk.passed()&&apk.verified>0,"all APK data on a generated Android build passes");
            System.out.println("Verified "+apk.verified+" entries in the generated Android APK.");
        }
        System.out.println("PASS "+assertions+" ZIP/APK archive integrity assertions.");
    }

    private static void create(Path path)throws IOException{
        try(ZipOutputStream writer=new ZipOutputStream(Files.newOutputStream(path))){
            store(writer,"standalone.txt","stored content".getBytes(StandardCharsets.UTF_8));
            writer.putNextEntry(new ZipEntry("nested/"));writer.closeEntry();
            writer.putNextEntry(new ZipEntry("nested/雪.txt"));
            for(int i=0;i<160;i++)writer.write(new byte[2048]);
            writer.closeEntry();
            writer.putNextEntry(new ZipEntry("readme"));writer.write(1);writer.closeEntry();
        }
    }

    private static void store(ZipOutputStream out,String name,byte[] bytes)throws IOException{
        ZipEntry entry=new ZipEntry(name);entry.setMethod(ZipEntry.STORED);
        CRC32 crc=new CRC32();crc.update(bytes);entry.setCrc(crc.getValue());entry.setSize(bytes.length);
        out.putNextEntry(entry);out.write(bytes);out.closeEntry();
    }

    private static byte[] fixture(byte[] plain,boolean descriptor,boolean zip64,boolean badDescriptor,
                                  boolean extraCompressedBytes)throws IOException{
        CRC32 crc=new CRC32();crc.update(plain);
        Deflater deflate=new Deflater(Deflater.DEFAULT_COMPRESSION,true);
        byte[] result=new byte[256];
        int len;
        try{
            deflate.setInput(plain);deflate.finish();len=deflate.deflate(result);
            if(!deflate.finished())throw new IOException("fixture buffer too small");
        }finally{deflate.end();}
        byte[] packed=Arrays.copyOf(result,len+(extraCompressedBytes?4:0));
        byte[] name="fixture.dat".getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        ByteBuffer local=le(30);
        local.putInt(0x04034B50).putShort((short)(zip64?45:20)).putShort((short)(descriptor?8:0))
                .putShort((short)8).putInt(0).putInt(descriptor?0:(int)crc.getValue())
                .putInt(zip64?-1:descriptor?0:packed.length).putInt(zip64?-1:descriptor?0:plain.length)
                .putShort((short)name.length).putShort((short)(zip64?20:0));
        out.write(local.array());out.write(name);
        if(zip64)out.write(le(20).putShort((short)1).putShort((short)16).putLong(0).putLong(0).array());
        out.write(packed);
        if(descriptor){
            ByteBuffer footer=le(zip64?24:12);
            if(zip64)footer.putInt(0x08074B50);
            footer.putInt((int)crc.getValue());
            if(zip64)footer.putLong(packed.length).putLong(plain.length);
            else footer.putInt(packed.length).putInt(plain.length+(badDescriptor?1:0));
            out.write(footer.array());
        }
        int start=out.size();
        ByteBuffer central=le(46);
        central.putInt(0x02014B50).putShort((short)20).putShort((short)(zip64?45:20))
                .putShort((short)(descriptor?8:0)).putShort((short)8).putInt(0).putInt((int)crc.getValue())
                .putInt(packed.length).putInt(plain.length).putShort((short)name.length);
        out.write(central.array());out.write(name);
        out.write(le(22).putInt(0x06054B50).putInt(0).putShort((short)1).putShort((short)1)
                .putInt(46+name.length).putInt(start).putShort((short)0).array());
        return out.toByteArray();
    }

    private static ArchiveIntegrity.Result test(Path file)throws IOException{return test(file,ArchiveIntegrity.BYTE_BUDGET);}
    private static ArchiveIntegrity.Result test(Path file,long limit)throws IOException{
        try(FileChannel input=FileChannel.open(file)){return ArchiveIntegrity.check(input,limit,()->false);}
    }
    private static void refuse(Path invalid)throws IOException{
        try{test(invalid);throw new AssertionError("accepted invalid central directory");}
        catch(IOException expected){check(true,"invalid central directory rejected");}
    }
    private static ByteBuffer le(int size){return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);}
    private static void put16(byte[] bytes,int i,int value){bytes[i]=(byte)value;bytes[i+1]=(byte)(value>>>8);}
    private static int u16(byte[] bytes,int i){return bytes[i]&255|(bytes[i+1]&255)<<8;}
    private static long u32(byte[] bytes,int i){return (long)u16(bytes,i)|(long)u16(bytes,i+2)<<16;}
    private static void check(boolean good,String message){assertions++;if(!good)throw new AssertionError(message);}
}
