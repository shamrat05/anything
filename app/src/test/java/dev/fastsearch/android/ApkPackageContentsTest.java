package dev.fastsearch.android;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class ApkPackageContentsTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path synthetic=Files.createTempFile("anything-synthetic-package-", ".apk");
        byte[] text="literal <script>hello</script>".getBytes(StandardCharsets.UTF_8);
        try {
            try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(synthetic))) {
                put(zip,"AndroidManifest.xml",new byte[]{1,2});put(zip,"classes.dex",new byte[]{3});
                put(zip,"resources.arsc",new byte[]{4});put(zip,"lib/x86/libdemo.so",new byte[]{5});
                put(zip,"assets/hello.html",text);put(zip,"../deceptive.txt",text);
                put(zip,"/absolute.txt",text);put(zip,"assets/has\\slash.txt",text);
                put(zip,"assets/nonvisual.bin",new byte[]{1});put(zip,"res/drawable/icon.png",new byte[]{1,2,3});
                put(zip,"assets/",new byte[0]);
            }
            try(FileChannel file=FileChannel.open(synthetic)) {
                ZipDirectory.Listing listing=ZipDirectory.read(file);
                check(!listing.truncated&&listing.entries.size()==11,"bounded directory enumerates names");
                check("Android manifest".equals(ApkPackageContents.type(listing.entries.get(0))),"manifest type");
                check(ApkPackageContents.format(listing.entries.get(0))==ApkPackageContents.Format.XML,"manifest uses literal XML viewer");
                check("DEX bytecode".equals(ApkPackageContents.type(listing.entries.get(1)))
                        &&ApkPackageContents.format(listing.entries.get(1))==ApkPackageContents.Format.NONE,"DEX is metadata only");
                check("Compiled resources".equals(ApkPackageContents.type(listing.entries.get(2)))
                        &&ApkPackageContents.format(listing.entries.get(2))==ApkPackageContents.Format.RESOURCE_TABLE,
                        "bounded compiled resource decoder");
                check("Native library".equals(ApkPackageContents.type(listing.entries.get(3))),"library type");
                ZipDirectory.Entry entry=listing.entries.get(4);
                check(ApkPackageContents.format(entry)==ApkPackageContents.Format.TEXT,"plain literal HTML type");
                check(Arrays.equals(text,ApkPackageContents.readVerified(file,entry,ApkPackageContents.Format.TEXT)),"exact bytes from checked entry");
                for(int i=5;i<=8;i++)check(ApkPackageContents.format(listing.entries.get(i))==ApkPackageContents.Format.NONE,
                        "untrusted or unsupported path not opened: "+i);
                check(ApkPackageContents.format(listing.entries.get(9))==ApkPackageContents.Format.IMAGE,"raster preview type");
                check(ApkPackageContents.format(listing.entries.get(10))==ApkPackageContents.Format.NONE,"directory metadata only");
                reject(file,copy(entry,entry.size,entry.crc^1,false),ApkPackageContents.Format.TEXT,"bad CRC");
                reject(file,copy(entry,entry.size-1,entry.crc,false),ApkPackageContents.Format.TEXT,"bad length");
                reject(file,copy(entry,512*1024+1,entry.crc,false),ApkPackageContents.Format.TEXT,"text limit");
                reject(file,copy(entry,entry.size,entry.crc,true),ApkPackageContents.Format.TEXT,"special entry");
                reject(file,entry,ApkPackageContents.Format.IMAGE,"wrong decoder class");
                ZipDirectory.Entry manifest=listing.entries.get(0);
                byte[] bad=ApkPackageContents.readVerified(file,manifest,ApkPackageContents.Format.XML);
                try{ApkPackageContents.decodeXml(bad);throw new AssertionError("non-XML was opened");}
                catch(IOException expected){checks++;}
                check("<literal />".equals(ApkPackageContents.decodeXml("<literal />".getBytes(StandardCharsets.UTF_8))),
                        "plain XML stays literal without parsing or resolving references");
            }
        } finally { Files.deleteIfExists(synthetic); }
        System.out.println("PASS "+checks+" APK package contents assertions.");
    }

    private static ZipDirectory.Entry copy(ZipDirectory.Entry source,long size,long crc,boolean special) {
        return new ZipDirectory.Entry(source.name,size,source.compressedSize,false,source.flags,source.method,
                source.offset,source.boundary,source.rawName,crc,special);
    }

    private static void reject(FileChannel file,ZipDirectory.Entry entry,ApkPackageContents.Format format,String label)
            throws Exception {
        try { ApkPackageContents.readVerified(file,entry,format); }
        catch(IOException expected) { checks++; return; }
        throw new AssertionError(label);
    }

    private static void put(ZipOutputStream zip,String name,byte[] bytes)throws Exception {
        zip.putNextEntry(new ZipEntry(name));zip.write(bytes);zip.closeEntry();
    }

    private static void check(boolean pass,String label) {
        if(!pass)throw new AssertionError(label);
        checks++;
    }
}
