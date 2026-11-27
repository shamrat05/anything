package dev.fastsearch.android;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Bounded file comparison. Never extracts, verifies signatures, loads code or writes either input. */
final class FileComparison {
    static final int MAX_ROWS=220, MAX_TEXT_BYTES=128*1024, MAX_TEXT_LINES=2_000, MAX_DEX_BYTES=16*1024*1024;
    private static final long MAX_APK_BYTES=768L*1024*1024;
    private static final int MAX_DISPLAY_CHARS=480;

    enum Mode { APK, DEX, TEXT }
    enum Kind { ADDED, REMOVED, CHANGED }

    static final class Row {
        final Kind kind;
        final String label, before, after;
        Row(Kind kind,String label,String before,String after){
            this.kind=kind;this.label=label;this.before=before;this.after=after;
        }
    }

    static final class Result {
        final Mode mode;
        final long beforeBytes,afterBytes;
        final int beforeItems,afterItems,added,removed,changed;
        final List<Row> rows;
        final String beforeDetail,afterDetail;

        private Result(Mode mode,long beforeBytes,long afterBytes,int beforeItems,int afterItems,
                       Rows result,String beforeDetail,String afterDetail){
            this.mode=mode;this.beforeBytes=beforeBytes;this.afterBytes=afterBytes;
            this.beforeItems=beforeItems;this.afterItems=afterItems;
            added=result.added;removed=result.removed;changed=result.changed;
            rows=Collections.unmodifiableList(result.rows);
            this.beforeDetail=beforeDetail;this.afterDetail=afterDetail;
        }

        int totalChanges(){return added+removed+changed;}
    }

    private static final class Rows {
        final ArrayList<Row> rows=new ArrayList<>();
        int added, removed, changed;
        boolean count(Kind kind){
            switch(kind){case ADDED: added++;break;case REMOVED: removed++;break;case CHANGED: changed++;break;}
            return rows.size()<MAX_ROWS;
        }
    }

    private FileComparison(){}

    static Mode mode(String a,String b){
        Mode left=nameMode(a),right=nameMode(b);
        return left==right?left:null;
    }
    private static Mode nameMode(String name){
        String lower=name.toLowerCase(Locale.ROOT);
        if(lower.endsWith(".apk"))return Mode.APK;
        if(lower.endsWith(".dex"))return Mode.DEX;
        return Mode.TEXT;
    }

    static Result compare(Mode mode,FileChannel before,FileChannel after)throws IOException{
        PreviewText.cancelled();
        if(mode==null)throw new IOException("Choose two APKs, two DEX files, or two text files.");
        long a=before.size(),b=after.size();
        Result result;
        switch(mode){
            case APK: result=apk(before,after,a,b);break;
            case DEX: result=dex(before,after,a,b);break;
            default: result=text(before,after,a,b);break;
        }
        PreviewText.cancelled();
        if(before.size()!=a||after.size()!=b)throw new IOException("A file changed during comparison. Try again.");
        return result;
    }

    private static Result apk(FileChannel before,FileChannel after,long a,long b)throws IOException{
        if(a>MAX_APK_BYTES||b>MAX_APK_BYTES)
            throw new IOException("APK comparison supports files up to 768 MiB each.");
        Map<String,ZipDirectory.Entry> left=entries(ZipDirectory.read(before)),right=entries(ZipDirectory.read(after));
        Rows rows=new Rows();
        TreeSet<String> names=new TreeSet<>(left.keySet());names.addAll(right.keySet());
        for(String name:names){
            PreviewText.cancelled();
            ZipDirectory.Entry from=left.get(name),to=right.get(name);
            Kind kind=from==null?Kind.ADDED:to==null?Kind.REMOVED:sameMetadata(from,to)?null:Kind.CHANGED;
            if(kind!=null&&rows.count(kind))rows.rows.add(new Row(kind,safe(name),metadata(from),metadata(to)));
        }
        return new Result(Mode.APK,a,b,left.size(),right.size(),rows,null,null);
    }

    private static Map<String,ZipDirectory.Entry> entries(ZipDirectory.Listing directory)throws IOException{
        if(directory.truncated)
            throw new IOException("This APK exceeds the comparison limit of 2,000 ZIP entries or 2 MiB of directory metadata.");
        Map<String,ZipDirectory.Entry> entries=new TreeMap<>();
        for(ZipDirectory.Entry entry:directory.entries){
            PreviewText.cancelled();
            if(entry.rawName==null||entry.rawName.isEmpty()||entries.put(entry.rawName,entry)!=null)
                throw new IOException("An APK has duplicate or unreadable entry names. No comparison was made.");
        }
        return entries;
    }

    private static boolean sameMetadata(ZipDirectory.Entry a,ZipDirectory.Entry b){
        return a.size==b.size&&a.compressedSize==b.compressedSize&&a.crc==b.crc
                &&a.flags==b.flags&&a.method==b.method&&a.directory==b.directory&&a.special==b.special;
    }

    private static String metadata(ZipDirectory.Entry entry){
        if(entry==null)return null;
        return (entry.special?"Special entry · ":entry.directory?"Folder · ":"")
                +entry.size+" B · "+entry.compressedSize+" B compressed"
                +"\nCRC32 "+String.format(Locale.ROOT,"%08x",entry.crc)+" · method "+entry.method
                +" · flags "+String.format(Locale.ROOT,"0x%04x",entry.flags);
    }

    private static Result text(FileChannel before,FileChannel after,long a,long b)throws IOException{
        String[] left=lines(before,a),right=lines(after,b);
        Rows rows=new Rows();
        int prefix=0;
        while(prefix<left.length&&prefix<right.length&&left[prefix].equals(right[prefix]))prefix++;
        int end=left.length,rightEnd=right.length;
        while(end>prefix&&rightEnd>prefix&&left[end-1].equals(right[rightEnd-1])){end--;rightEnd--;}
        int n=end-prefix,m=rightEnd-prefix,stride=m+1;
        if(n==0||m==0){
            for(int at=prefix;at<end;at++)addLine(rows,Kind.REMOVED,at,left[at]);
            for(int at=prefix;at<rightEnd;at++)addLine(rows,Kind.ADDED,at,right[at]);
        }else{
            // A reduced exact LCS grid needs at most ~8 MB (2,000 lines per input).
            short[] common=new short[(n+1)*stride];
            Map<String,Integer> ids=new HashMap<>();
            int[] leftIds=ids(left,prefix,n,ids),rightIds=ids(right,prefix,m,ids);
            for(int i=n-1;i>=0;i--){
                PreviewText.cancelled();
                int line=leftIds[i];
                for(int j=m-1;j>=0;j--){
                    int cell=i*stride+j;
                    common[cell]=(short)(line==rightIds[j]?1+common[cell+stride+1]
                            :Math.max(common[cell+stride],common[cell+1]));
                }
            }
            for(int i=0,j=0;i<n||j<m;){
                PreviewText.cancelled();
                if(i<n&&j<m&&leftIds[i]==rightIds[j]){i++;j++;}
                else if(i<n&&(j==m||common[(i+1)*stride+j]>=common[i*stride+j+1])){
                    addLine(rows,Kind.REMOVED,prefix+i,left[prefix+i]);i++;
                }else{
                    addLine(rows,Kind.ADDED,prefix+j,right[prefix+j]);j++;
                }
            }
        }
        return new Result(Mode.TEXT,a,b,left.length,right.length,rows,null,null);
    }

    private static int[] ids(String[] lines,int offset,int count,Map<String,Integer> ids)throws IOException{
        int[] result=new int[count];
        for(int i=0;i<count;i++){
            PreviewText.cancelled();
            String line=lines[offset+i];
            Integer id=ids.get(line);
            if(id==null){id=ids.size();ids.put(line,id);}
            result[i]=id;
        }
        return result;
    }

    private static void addLine(Rows rows,Kind kind,int index,String line){
        if(!rows.count(kind))return;
        String value=line.isEmpty()?"(empty line)":safe(line);
        rows.rows.add(kind==Kind.ADDED?new Row(kind,"B line "+(index+1),null,value)
                                      :new Row(kind,"A line "+(index+1),value,null));
    }

    private static String[] lines(FileChannel channel,long size)throws IOException{
        if(size>MAX_TEXT_BYTES)throw new IOException("Text comparison supports up to 128 KiB per file.");
        byte[] bytes=new byte[(int)size];readFully(channel,bytes,0,bytes.length);
        Charset charset=StandardCharsets.UTF_8;
        int offset=0;
        if(bytes.length>=3&&u(bytes[0])==0xef&&u(bytes[1])==0xbb&&u(bytes[2])==0xbf)offset=3;
        else if(bytes.length>=2&&u(bytes[0])==0xff&&u(bytes[1])==0xfe){offset=2;charset=StandardCharsets.UTF_16LE;}
        else if(bytes.length>=2&&u(bytes[0])==0xfe&&u(bytes[1])==0xff){offset=2;charset=StandardCharsets.UTF_16BE;}
        String decoded;
        try{
            decoded=charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes,offset,bytes.length-offset)).toString();
        }catch(CharacterCodingException binary){
            throw new IOException("Text must be UTF-8 or BOM-marked UTF-16.");
        }
        if(decoded.isEmpty())return new String[0];
        StringBuilder normalized=new StringBuilder(decoded.length());
        int count=1;
        for(int i=0;i<decoded.length();i++){
            if((i&1023)==0)PreviewText.cancelled();
            char ch=decoded.charAt(i);
            if(Character.isISOControl(ch)&&ch!='\n'&&ch!='\r'&&ch!='\t'&&ch!='\f')
                throw new IOException("This file contains binary data; choose two text files.");
            if(ch=='\r'){
                if(i+1<decoded.length()&&decoded.charAt(i+1)=='\n')i++;
                ch='\n';
            }
            if(ch=='\n'&&++count>MAX_TEXT_LINES)
                throw new IOException("Text comparison supports up to 2,000 lines per file.");
            normalized.append(ch);
        }
        return normalized.toString().split("\n",-1);
    }

    private static Result dex(FileChannel before,FileChannel after,long a,long b)throws IOException{
        DexInfo left=dexInfo(before,a),right=dexInfo(after,b);
        Rows rows=new Rows();
        if(a!=b||!left.hash.equals(right.hash)){
            rows.count(Kind.CHANGED);
            rows.rows.add(new Row(Kind.CHANGED,"DEX bytes",left.hash,right.hash));
        }
        return new Result(Mode.DEX,a,b,left.classes,right.classes,rows,left.label(),right.label());
    }

    private static final class DexInfo {
        final String version,hash;
        final int classes;
        DexInfo(String version,String hash,int classes){this.version=version;this.hash=hash;this.classes=classes;}
        String label(){return "DEX "+version+" · SHA-256\n"+hash;}
    }

    private static DexInfo dexInfo(FileChannel channel,long size)throws IOException{
        if(size<112||size>MAX_DEX_BYTES)
            throw new IOException("DEX comparison requires a complete standard DEX up to 16 MiB.");
        byte[] header=new byte[112];readFully(channel,header,0,header.length);
        if(header[0]!='d'||header[1]!='e'||header[2]!='x'||header[3]!='\n'||header[7]!=0
                ||u32(header,32)!=size||u32(header,36)!=112||u32(header,40)!=0x12345678L)
            throw new IOException("This is not a supported standalone DEX file.");
        String version=new String(header,4,3,StandardCharsets.US_ASCII);
        switch(version){
            case "035": case "037": case "038": case "039": case "040":break;
            default: throw new IOException("DEX comparison supports versions 035 and 037–040.");
        }
        long classes=u32(header,96),table=u32(header,100);
        if(classes>65_536||classes==0&&table!=0||classes>0&&(table<112||(table&3)!=0||table+32*classes>size))
            throw new IOException("The DEX class table is incomplete or invalid.");
        MessageDigest digest;
        try{digest=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException unavailable){
            throw new IOException("SHA-256 is unavailable.",unavailable);
        }
        byte[] buffer=new byte[64*1024];
        for(long start=0;start<size;){
            int count=(int)Math.min(buffer.length,size-start);
            readFully(channel,buffer,start,count);digest.update(buffer,0,count);start+=count;
        }
        return new DexInfo(version,hex(digest.digest()),(int)classes);
    }

    private static void readFully(FileChannel channel,byte[] bytes,long offset,int length)throws IOException{
        ByteBuffer buffer=ByteBuffer.wrap(bytes,0,length);
        while(buffer.hasRemaining()){
            PreviewText.cancelled();
            if(channel.read(buffer,offset+buffer.position())<=0)throw new IOException("A file ended during comparison. Try again.");
        }
    }

    private static int u(byte b){return b&0xff;}
    private static long u32(byte[] b,int at){return u(b[at])|u(b[at+1])<<8|u(b[at+2])<<16|(long)u(b[at+3])<<24;}
    private static String hex(byte[] bytes){
        char[] out=new char[2*bytes.length];
        for(int i=0;i<bytes.length;i++){
            out[2*i]=Character.forDigit(u(bytes[i])>>>4,16);
            out[2*i+1]=Character.forDigit(u(bytes[i])&15,16);
        }
        return new String(out);
    }

    static String safe(String input){
        String escaped=ZipDirectory.safeLabel(input);
        if(escaped.length()<=MAX_DISPLAY_CHARS)return escaped;
        int width=Character.isHighSurrogate(escaped.charAt(MAX_DISPLAY_CHARS-1))?MAX_DISPLAY_CHARS-1:MAX_DISPLAY_CHARS;
        return escaped.substring(0,width)+"…";
    }
}
