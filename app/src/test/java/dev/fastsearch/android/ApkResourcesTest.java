package dev.fastsearch.android;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Random;

final class ApkResourcesTest {
    private static int checks;
    private static final int ID=0x7f010003;
    private static final String NAME="dev.fastsearch.resourcefixture:string/owned_label";

    public static void main(String[] args)throws Exception{
        for(int encoding=0;encoding<3;encoding++){
            byte[] baseline=fixture(encoding);
            ApkResources.Table table=ApkResources.read(baseline);
            check(table.packages==1&&table.types==1&&table.count()==1&&NAME.equals(table.name(ID)),
                    "names and IDs in encoding "+encoding);
            check(table.name(0x01010000)==null&&table.name(0x00010003)==null,"unknown and dynamic resource IDs");
            check(table.describe().contains("0x7f010003  "+NAME),"plain read-only table");
            reject(Arrays.copyOf(baseline,baseline.length-1),"truncated table");
        }
        byte[] baseline=fixture(1);
        reject(new byte[ApkResources.MAX_BYTES+1],"hard table input limit");
        for(int n=0;n<baseline.length;n+=7)reject(Arrays.copyOf(baseline,n),"truncated table at "+n);
        byte[] bad=baseline.clone();bad[8]=2;reject(bad,"incorrect package count");
        bad=baseline.clone();bad[0]=3;reject(bad,"wrong root chunk kind");
        Thread.currentThread().interrupt();
        try{reject(baseline,"cancellation");}finally{Thread.interrupted();}
        Random random=new Random(79);
        for(int i=0;i<3300;i++){
            bad=baseline.clone();
            for(int j=0;j<4;j++)bad[random.nextInt(bad.length)]=(byte)random.nextInt(256);
            try{check(ApkResources.read(bad).describe().length()<=129*1024,"malformed input output remains bounded");}
            catch(IOException bounded){checks++;}
        }
        if(args.length>=4){
            String expected="dev.fastsearch.resourcefixture:string/owned_label";
            for(int i=0;i<3;i++){
                ApkResources.Table fixture=ApkResources.read(Files.readAllBytes(Paths.get(args[i])));
                check(fixture.count()==82&&expected.equals(fixture.name(0x7f010050))
                        &&"dev.fastsearch.resourcefixture:style/OwnedTheme".equals(fixture.name(0x7f020000)),
                        "actual AAPT2 names in dense/sparse/compact table "+i);
                String xml=ApkBinaryXml.decode(Files.readAllBytes(Paths.get(args[3])),fixture::name);
                check(xml.contains("android:label=\"@"+expected+"\"")
                        &&xml.contains("android:theme=\"@dev.fastsearch.resourcefixture:style/OwnedTheme\""),
                        "actual AAPT2 compiled manifest resolves name "+i);
            }
        }
        if(args.length>4){
            ApkResources.Table app=ApkResources.read(Files.readAllBytes(Paths.get(args[4])));
            check(app.count()>50&&app.describe().contains("Compiled resources"),"the project's APK resources table");
        }
        System.out.println("ApkResourcesTest: "+checks+" checks passed");
    }

    private static byte[] fixture(int encoding){
        byte[] types=pool("string"), keys=pool("owned_label");
        ByteArrayOutputStream fixed=new ByteArrayOutputStream();
        add(fixed,words(0x7f));
        byte[] pkg="dev.fastsearch.resourcefixture".getBytes(StandardCharsets.UTF_16LE);
        add(fixed,Arrays.copyOf(pkg,256));
        add(fixed,words(288,0,288+types.length,0,0));
        ByteArrayOutputStream entry=new ByteArrayOutputStream(), index=new ByteArrayOutputStream();
        int entryCount=encoding==1?1:4;
        if(encoding==1){shortValue(index,3);shortValue(index,0);}
        else if(encoding==2){for(int n=0;n<3;n++)shortValue(index,0xffff);shortValue(index,0);}
        else {add(index,words(-1,-1,-1,0));}
        if(encoding==0){
            shortValue(entry,8);shortValue(entry,0);add(entry,words(0));
            shortValue(entry,8);entry.write(0);entry.write(3);add(entry,words(123));
        }else{
            shortValue(entry,0);shortValue(entry,0x0308);add(entry,words(123));
        }
        byte[] typeHeader=concat(new byte[]{1,(byte)encoding,0,0},words(entryCount,24+index.size(),4));
        byte[] type=chunk(0x201,typeHeader,concat(index.toByteArray(),entry.toByteArray()));
        byte[] packageTable=chunk(0x200,fixed.toByteArray(),concat(types,concat(keys,type)));
        return chunk(2,words(1),packageTable);
    }

    private static byte[] pool(String name){
        byte[] bytes=name.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream pool=new ByteArrayOutputStream();
        add(pool,words(0));pool.write(name.length());pool.write(bytes.length);add(pool,bytes);pool.write(0);
        while((pool.size()&3)!=0)pool.write(0);
        return chunk(1,words(1,0,256,32,0),pool.toByteArray());
    }
    private static byte[] words(int... values){
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        for(int value:values){shortValue(out,value);shortValue(out,value>>>16);}
        return out.toByteArray();
    }
    private static byte[] chunk(int kind,byte[] header,byte[] data){
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        shortValue(out,kind);shortValue(out,8+header.length);add(out,words(8+header.length+data.length));
        add(out,header);add(out,data);return out.toByteArray();
    }
    private static byte[] concat(byte[] a,byte[] b){ByteArrayOutputStream out=new ByteArrayOutputStream();add(out,a);add(out,b);return out.toByteArray();}
    private static void add(ByteArrayOutputStream out,byte[] bytes){out.write(bytes,0,bytes.length);}
    private static void shortValue(ByteArrayOutputStream out,int value){out.write(value&255);out.write((value>>>8)&255);}
    private static void reject(byte[] bytes,String label)throws Exception{
        try{ApkResources.read(bytes);throw new AssertionError(label);}catch(IOException expected){checks++;}
    }
    private static void check(boolean success,String label){if(!success)throw new AssertionError(label);checks++;}
}
