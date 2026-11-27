package dev.fastsearch.android;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Only inert ZIP payloads are created; no APK is installed or loaded as code. */
public final class ApkInspectionFactsTest {
    private static int assertions;
    public static void main(String[] args)throws Exception{
        byte[] digest=ApkInspectionFacts.sha256().digest(new byte[]{97,98,99});
        check(ApkInspectionFacts.hex(digest,false).equals(
                "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD"),"whole-file SHA-256 formatting");
        check(ApkInspectionFacts.hex(new byte[]{0,15,-1},true).equals("00:0F:FF"),"certificate fingerprint formatting");
        for(String path:Arrays.asList("lib/../libbad.so","/lib/arm64-v8a/libbad.so","lib/arm64-v8a/deep/libbad.so",
                "lib/arm64-v8a/other.so","lib/x86\\bad/libbad.so","assets/lib/x86/libbad.so","lib/x86/libbad.so.exe",
                "lib/x86/lib☃.so","lib//libbad.so","lib/x86/lib.so"))
            check(ApkInspectionFacts.nativeABI(path)==null,"ignored nonstandard native path: "+path);
        Path zip=Files.createTempFile("anything-abi-test-",".zip");
        try{
            try(ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(zip))){
                entry(out,"lib/arm64-v8a/libone.so",true);entry(out,"lib/arm64-v8a/libtwo.so",true);
                entry(out,"lib/x86_64/libsample.so",true);entry(out,"lib/riscv64/libempty.so",false);
                entry(out,"assets/lib/arm/libsample.so",true);entry(out,"lib/../libtraversal.so",true);
            }
            try(FileChannel input=FileChannel.open(zip,StandardOpenOption.READ)){
                ApkInspectionFacts.NativeABIs abis=ApkInspectionFacts.nativeABIs(ZipDirectory.read(input));
                check(abis.names.equals(Arrays.asList("arm64-v8a","x86_64"))&&!abis.partial,
                        "real bounded ZIP listing deduplicates native paths, ignores zero-length and traversal records");
            }
            try(ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(zip))){
                for(int i=0;i<ZipDirectory.MAX_ENTRIES;i++)entry(out,"assets/inert/"+i,true);
                entry(out,"lib/x86/liboverflow.so",true);
            }
            try(FileChannel input=FileChannel.open(zip,StandardOpenOption.READ)){
                ApkInspectionFacts.NativeABIs abis=ApkInspectionFacts.nativeABIs(ZipDirectory.read(input));
                check(abis.names.isEmpty()&&abis.partial,"truncated scan does not report false complete absence");
            }
        }finally{Files.deleteIfExists(zip);}
        List<ZipDirectory.Entry> records=new ArrayList<>();
        records.add(record("lib/armeabi-v7a/libencrypted.so",1,false));
        records.add(record("lib/x86/libspecial.so",0,true));
        for(int i=0;i<40;i++)records.add(record("lib/abi_"+i+"/libinert.so",0,false));
        ApkInspectionFacts.NativeABIs capped=ApkInspectionFacts.nativeABIs(new ZipDirectory.Listing(records,records.size(),false));
        check(capped.names.size()==32&&capped.partial&&!capped.names.contains("x86")&&!capped.names.contains("armeabi-v7a"),
                "native metadata skips encrypted/special entries and safely caps capabilities");
        System.out.println("PASS "+assertions+" APK inspection facts assertions.");
    }
    private static ZipDirectory.Entry record(String name,int flags,boolean special){
        return new ZipDirectory.Entry(name,1,1,false,flags,0,0,0,name,0,special);
    }
    private static void entry(ZipOutputStream out,String name,boolean hasData)throws IOException{
        out.putNextEntry(new ZipEntry(name));if(hasData)out.write(17);out.closeEntry();
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);assertions++;}
}
