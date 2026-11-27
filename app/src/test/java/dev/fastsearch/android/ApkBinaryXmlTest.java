package dev.fastsearch.android;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Random;

final class ApkBinaryXmlTest {
    private static int assertions;
    private static final String ANDROID="http://schemas.android.com/apk/res/android";

    public static void main(String[] args)throws Exception{
        byte[] baseline=fixture(true,0);
        String decoded=ApkBinaryXml.decode(baseline);
        check(decoded.equals(ApkBinaryXml.decode(fixture(false,0))),"UTF-8 and UTF-16 pools decode identically");
        check(decoded.contains("<manifest xmlns:android=\""+ANDROID+"\" package=\"demo.synthetic\">"),"root namespace and plain string");
        check(decoded.contains("android:label=\"Fake &lt;title&gt; &amp; &quot;👋&quot; &#x202e;\""),"escaped text, emoji and invisible formatting");
        check(decoded.contains("android:exported=\"false\"")&&decoded.contains("android:minSdkVersion=\"26\""),"compiled boolean and integer attributes");
        check(decoded.contains("android:resource=\"@0x7f010003\"")&&decoded.contains("android:width=\"24dp\""),"reference and dimension");
        check(decoded.contains("android:fraction=\"50%\"")&&decoded.contains("android:color=\"#abc\""),"fraction and short color");
        check(decoded.contains("</application>\n</manifest>\n"),"nested element closing");
        check(decoded.contains("unsafe &lt;value&gt;")&&decoded.contains("long y"),"literal character data");
        check(decoded.contains(new String(new char[132]).replace('\0','y')),"two-byte UTF-8 length prefixes");
        String shortened=ApkBinaryXml.decode(fixture(true,1200));
        check(shortened.contains("Preview shortened.")&&shortened.length()<=128*1024+30,"visible output capped");
        for(int size=0;size<baseline.length;size+=13)reject(Arrays.copyOf(baseline,size),"truncation at "+size);
        reject(new byte[ApkBinaryXml.MAX_BYTES+1],"hard input limit");
        byte[] bad=baseline.clone();bad[4]=(byte)0xff;reject(bad,"bad root chunk size");
        bad=baseline.clone();bad[29]=(byte)0xff;reject(bad,"bad string start");
        bad=baseline.clone();bad[28]=0;bad[29]=0;bad[30]=0;bad[31]=0;reject(bad,"overlapping string table");
        bad=fixture(true,-1);reject(bad,"mismatched element name");
        bad=fixture(true,-2);reject(bad,"duplicate attribute");
        bad=fixture(true,-3);reject(bad,"unbound namespace");
        Thread.currentThread().interrupt();
        try{reject(baseline,"cancellation");}finally{Thread.interrupted();}
        Random random=new Random(4);
        for(int run=0;run<3500;run++){
            bad=baseline.clone();
            int changes=1+random.nextInt(5);
            for(int i=0;i<changes;i++)bad[random.nextInt(bad.length)]=(byte)random.nextInt(256);
            try{check(ApkBinaryXml.decode(bad).length()<129*1024,"fuzz output remains bounded");}
            catch(IOException expected){assertions++;}
        }
        if(args.length>0){
            String aapt=ApkBinaryXml.decode(Files.readAllBytes(Paths.get(args[0])));
            check(aapt.contains("package=\"dev.fastsearch.fixture\"")&&aapt.contains("android:versionCode=\"4\""),"AAPT2 owned manifest identity");
            check(aapt.contains("android:targetSdkVersion=\"35\"")&&aapt.contains("android:name=\"android.permission.VIBRATE\""),"AAPT2 SDK and permission");
            check(aapt.contains("Synthetic 👋")&&aapt.contains("one &amp; &lt;two&gt;"),"AAPT2 unicode and markup remain literal");
        }
        if(args.length>1){
            String app=ApkBinaryXml.decode(Files.readAllBytes(Paths.get(args[1])));
            check(app.contains("<manifest ")&&app.contains("dev.fastsearch.android.MainActivity")&&app.contains("</manifest>"),"actual built Anything preview manifest");
        }
        System.out.println("ApkBinaryXmlTest: "+assertions+" checks passed");
    }

    private static byte[] fixture(boolean utf8,int repeated){
        String longValue="long "+new String(new char[132]).replace('\0','y');
        String[] strings={"android",ANDROID,"manifest","package","demo.synthetic","application",
                "label","Fake <title> & \"👋\" \u202e","exported","minSdkVersion","resource",
                "width","fraction","color","unsafe <value>","longValue",longValue};
        ByteArrayOutputStream events=new ByteArrayOutputStream();
        add(events,pool(utf8,strings));
        add(events,chunk(0x180,new byte[0],words(0,0,0)));
        if(repeated!=-3)add(events,node(0x100,words(0,1)));
        add(events,open(2,new int[][]{{-1,3,4,3,4}}));
        int[][] app={{1,6,-1,3,7},{1,8,-1,18,0},{1,9,-1,16,26},{1,10,-1,1,0x7f010003},
                {1,11,-1,5,0x1801},{1,12,-1,6,0x40000030},{1,13,-1,31,0xffaabbcc},{-1,15,16,3,16}};
        if(repeated==-2)app[7]=app[0];
        add(events,open(5,app));
        add(events,node(0x104,concat(words(14),typed(3,14))));
        for(int i=0;i<repeated;i++){add(events,open(5,app));add(events,node(0x103,words(-1,5)));}
        add(events,node(0x103,words(-1,repeated==-1?2:5)));
        add(events,node(0x103,words(-1,2)));
        if(repeated!=-3)add(events,node(0x101,words(0,1)));
        return chunk(3,new byte[0],events.toByteArray());
    }

    private static byte[] open(int name,int[][] attrs){
        ByteArrayOutputStream body=new ByteArrayOutputStream();
        add(body,words(-1,name));
        for(int value:new int[]{20,20,attrs.length,0,0,0})shortValue(body,value);
        for(int[] attr:attrs){add(body,words(attr[0],attr[1],attr[2]));add(body,typed(attr[3],attr[4]));}
        return node(0x102,body.toByteArray());
    }

    private static byte[] pool(boolean utf8,String[] strings){
        ByteArrayOutputStream data=new ByteArrayOutputStream(),offsets=new ByteArrayOutputStream();
        for(String value:strings){
            add(offsets,words(data.size()));
            byte[] encoded=value.getBytes(utf8?StandardCharsets.UTF_8:StandardCharsets.UTF_16LE);
            if(utf8){len8(data,value.length());len8(data,encoded.length);}
            else shortValue(data,value.length());
            add(data,encoded);data.write(0);if(!utf8)data.write(0);
        }
        while(data.size()%4!=0)data.write(0);
        return chunk(1,words(strings.length,0,utf8?256:0,28+offsets.size(),0),concat(offsets.toByteArray(),data.toByteArray()));
    }
    private static byte[] node(int kind,byte[] body){return chunk(kind,words(1,-1),body);}
    private static byte[] typed(int kind,int data){return concat(new byte[]{8,0,0,(byte)kind},words(data));}
    private static byte[] words(int... data){
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        for(int value:data){shortValue(out,value);shortValue(out,value>>>16);}
        return out.toByteArray();
    }
    private static byte[] chunk(int kind,byte[] header,byte[] body){
        ByteArrayOutputStream file=new ByteArrayOutputStream();
        shortValue(file,kind);shortValue(file,8+header.length);
        add(file,words(8+header.length+body.length));add(file,header);add(file,body);
        return file.toByteArray();
    }
    private static byte[] concat(byte[] first,byte[] second){
        ByteArrayOutputStream data=new ByteArrayOutputStream();add(data,first);add(data,second);return data.toByteArray();
    }
    private static void shortValue(ByteArrayOutputStream stream,int value){stream.write(value&255);stream.write((value>>>8)&255);}
    private static void len8(ByteArrayOutputStream stream,int value){if(value>127)stream.write(((value>>>8)&127)|128);stream.write(value&255);}
    private static void add(ByteArrayOutputStream stream,byte[] bytes){stream.write(bytes,0,bytes.length);}
    private static void reject(byte[] bytes,String description)throws Exception{
        try{ApkBinaryXml.decode(bytes);throw new AssertionError(description);}
        catch(IOException expected){assertions++;}
    }
    private static void check(boolean pass,String description){if(!pass)throw new AssertionError(description);assertions++;}
}
