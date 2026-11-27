package dev.fastsearch.android;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Read-only names and IDs from a bounded Android resources.arsc table. Never interprets values. */
final class ApkResources {
    static final int MAX_BYTES=8*1024*1024;
    private static final int MAX_PACKAGES=32, MAX_CHUNKS=60_000, MAX_INSPECTED=320_000;
    private static final int MAX_NAMES=24_000, MAX_STRING_CHARS=512, MAX_POOL_NAMES=65_536;
    private static final int MAX_NAME_CHARS=1024*1024, MAX_OUTPUT=128*1024, MAX_LINES=1800;
    private static final int TYPE_PACKAGE=0x200, TYPE_VALUES=0x201, TYPE_SPEC=0x202;
    private static final int SPARSE=1, OFFSET16=2, COMPLEX=1, COMPACT=8;
    private static final long NO_ENTRY=0xffffffffL;

    private ApkResources(){}

    static Table read(byte[] bytes)throws IOException{
        if(bytes==null||bytes.length>MAX_BYTES)throw new IOException("Compiled resources exceed the 8 MiB preview limit.");
        return new Reader(bytes).read();
    }

    static final class Table {
        private final Map<Integer,String> names;
        final int packages,types;
        final boolean shortened;
        Table(Map<Integer,String> names,int packages,int types,boolean shortened){
            this.names=Collections.unmodifiableMap(names);
            this.packages=packages;this.types=types;this.shortened=shortened;
        }
        int count(){return names.size();}
        String name(int id){return (id>>>24)==0?null:names.get(id);}
        String describe()throws IOException{
            StringBuilder out=new StringBuilder("Compiled resources · read only\n");
            out.append(packages).append(packages==1?" package":" packages").append(" · ")
               .append(types).append(types==1?" type":" types").append(" · ")
               .append(shortened?"at least ":"").append(names.size()).append(" resource names\n\n");
            if(names.isEmpty())out.append("No resource names were found.\n");
            int lines=0;
            for(Map.Entry<Integer,String> entry:names.entrySet()){
                if(Thread.currentThread().isInterrupted())throw new IOException("Preview stopped.");
                String name=entry.getValue();
                if(++lines>MAX_LINES||out.length()+name.length()+13>MAX_OUTPUT){
                    out.append("\n… Preview shortened.\n");
                    return out.toString();
                }
                out.append("0x").append(hex(entry.getKey())).append("  ").append(name).append('\n');
            }
            if(shortened)out.append("\n… Preview shortened.\n");
            return out.toString();
        }
    }

    private static final class Reader {
        final byte[] bytes;
        final Map<Integer,String> names=new TreeMap<>(Integer::compareUnsigned);
        final Set<Integer> types=new HashSet<>();
        int chunks,inspected,decodedChars,storedChars;
        boolean shortened;
        Reader(byte[] bytes){this.bytes=bytes;}

        Table read()throws IOException{
            Chunk root=chunk(0,bytes.length);
            if(root.kind!=2||root.header<12||root.end!=bytes.length)throw invalid();
            long declaredPackages=u32(8);
            if(declaredPackages>MAX_PACKAGES)throw limit();
            int packages=0;
            for(int at=root.body();at<root.end;){
                Chunk child=chunk(at,root.end);
                if(child.kind==TYPE_PACKAGE){
                    if(++packages>declaredPackages)throw invalid();
                    packageTable(child);
                }
                at=child.end;
            }
            if(packages!=declaredPackages)throw invalid();
            return new Table(names,packages,types.size(),shortened);
        }

        void packageTable(Chunk pkg)throws IOException{
            if(pkg.header<284)throw invalid();
            long id=u32(pkg.start+8);
            if(id>255)throw invalid();
            int start=pkg.start+12, units=0;
            while(units<128&&u16(start+units*2)!=0)units++;
            String packageName=decode(start,units*2,false);
            if(!validName(packageName,false,128))throw invalid();
            long typeOffset=u32(pkg.start+268),keyOffset=u32(pkg.start+276);
            long typeIdOffset=pkg.header>=288?u32(pkg.start+284):0;
            if(typeIdOffset>254)throw invalid();
            NamePool typePool=null,keyPool=null;
            if(typeOffset!=0||keyOffset!=0){
                if(typeOffset<pkg.header||typeOffset>pkg.end-pkg.start-8
                        ||keyOffset<pkg.header||keyOffset>pkg.end-pkg.start-8||typeOffset==keyOffset)
                    throw invalid();
                typePool=new NamePool(chunk(pkg.start+(int)typeOffset,pkg.end));
                keyPool=new NamePool(chunk(pkg.start+(int)keyOffset,pkg.end));
            }
            boolean sawTypePool=typePool==null,sawKeyPool=keyPool==null;
            for(int at=pkg.body();at<pkg.end;){
                Chunk child=chunk(at,pkg.end);
                if(at-pkg.start==typeOffset)sawTypePool=true;
                if(at-pkg.start==keyOffset)sawKeyPool=true;
                if(child.kind==TYPE_VALUES){
                    if(typePool==null||keyPool==null)throw invalid();
                    typeValues(child,(int)id,(int)typeIdOffset,packageName,typePool,keyPool);
                }else if(child.kind==TYPE_SPEC){
                    if(child.header<16||u8(child.start+8)==0||u32(child.start+12)>65536
                            ||u32(child.start+12)*4>child.end-child.body())throw invalid();
                }
                at=child.end;
            }
            if(!sawTypePool||!sawKeyPool)throw invalid();
        }

        void typeValues(Chunk type,int pkg,int typeIdOffset,String packageName,NamePool typePool,NamePool keys)throws IOException{
            if(type.header<24)throw invalid();
            int index=u8(type.start+8),logicalType=index+typeIdOffset,flags=u8(type.start+9);
            long count=u32(type.start+12), dataOffset=u32(type.start+16),config=u32(type.start+20);
            if(index==0||logicalType>255||count>65536||dataOffset<type.header||dataOffset>type.end-type.start
                    ||config<4||config>type.header-20||(flags&~3)!=0||flags==3)throw invalid();
            int width=flags==OFFSET16?2:4;
            if(count*width>dataOffset-type.header)throw invalid();
            String typeName=typePool.name(index-1);
            int typeKey=(pkg<<24)|(logicalType<<16),base=type.start+(int)dataOffset;
            types.add(typeKey);
            int previous=-1;
            for(int n=0;n<count;n++){
                if((n&255)==0&&Thread.currentThread().isInterrupted())throw new IOException("Preview stopped.");
                if(++inspected>MAX_INSPECTED){shortened=true;return;}
                int slot=type.body()+n*width,entry=n;
                long offset;
                if(flags==SPARSE){
                    entry=u16(slot);
                    if(entry<=previous)throw invalid();
                    previous=entry;
                    offset=(long)u16(slot+2)*4;
                }else if(flags==OFFSET16){
                    int small=u16(slot);
                    if(small==0xffff)continue;
                    offset=(long)small*4;
                }else{
                    offset=u32(slot);
                    if(offset==NO_ENTRY)continue;
                }
                if((offset&3)!=0||offset>type.end-base-8L)throw invalid();
                int at=base+(int)offset, first=u16(at), bits=u16(at+2);
                int keyIndex;
                if((bits&COMPACT)!=0){
                    if((bits&COMPLEX)!=0)throw invalid();
                    // Compact entry stores the 16-bit key in place of the full header's size.
                    keyIndex=first;
                }else{
                    if(first<8||first>type.end-at)throw invalid();
                    long key=u32(at+4);
                    if(key>Integer.MAX_VALUE)throw invalid();
                    keyIndex=(int)key;
                    if((bits&COMPLEX)!=0){
                        if(first<16)throw invalid();
                        long items=u32(at+12);
                        if(items*12>type.end-at-first)throw invalid();
                    }else{
                        range(at+first,8,type.end);
                        int valueSize=u16(at+first);
                        if(valueSize<8||valueSize>type.end-at-first)throw invalid();
                    }
                }
                int resource=typeKey|entry;
                String key=keys.name(keyIndex);
                String existing=names.get(resource);
                if(existing!=null){
                    int nameStart=packageName.length()+typeName.length()+2;
                    if(existing.length()!=nameStart+key.length()||!existing.startsWith(packageName)
                            ||existing.charAt(packageName.length())!=':'
                            ||!existing.regionMatches(packageName.length()+1,typeName,0,typeName.length())
                            ||existing.charAt(nameStart-1)!='/'||!existing.regionMatches(nameStart,key,0,key.length()))throw invalid();
                }else if(names.size()<MAX_NAMES&&storedChars+packageName.length()+typeName.length()+key.length()+2<=MAX_NAME_CHARS){
                    String name=packageName+":"+typeName+"/"+key;
                    names.put(resource,name);storedChars+=name.length();
                }else shortened=true;
            }
        }

        final class NamePool {
            final String[] cached;
            final int table,start,end;
            final boolean utf8;
            NamePool(Chunk pool)throws IOException{
                if(pool.kind!=1||pool.header<28)throw invalid();
                long count=u32(pool.start+8), styles=u32(pool.start+12), strings=u32(pool.start+20), styleStart=u32(pool.start+24);
                if(count>MAX_POOL_NAMES||styles>MAX_POOL_NAMES)throw limit();
                if(strings<pool.header+4*(count+styles)||strings>pool.end-pool.start
                        ||styleStart!=0&&(styleStart<strings||styleStart>pool.end-pool.start)
                        ||styles!=0&&styleStart==0)throw invalid();
                cached=new String[(int)count];
                table=pool.body();start=pool.start+(int)strings;
                end=styleStart==0?pool.end:pool.start+(int)styleStart;
                utf8=(u32(pool.start+16)&256)!=0;
            }
            String name(int index)throws IOException{
                if(index<0||index>=cached.length)throw invalid();
                if(cached[index]!=null)return cached[index];
                long off=u32(table+index*4);
                if(off>=end-start)throw invalid();
                int at=start+(int)off,units,size;
                if(utf8){
                    int len=length8(at,end);at+=len>>>16;units=len&0xffff;
                    len=length8(at,end);at+=len>>>16;size=len&0xffff;
                }else{
                    range(at,2,end);units=u16(at);at+=2;
                    if((units&0x8000)!=0)throw limit();
                    size=units*2;
                }
                if(units>MAX_STRING_CHARS||decodedChars+units>MAX_NAME_CHARS)throw limit();
                range(at,size+(utf8?1:2),end);
                if(bytes[at+size]!=0||!utf8&&bytes[at+size+1]!=0)throw invalid();
                String value=decode(at,size,utf8);
                if(value.length()!=units||!validName(value,true,MAX_STRING_CHARS))throw invalid();
                decodedChars+=units;
                return cached[index]=value;
            }
        }

        String decode(int at,int length,boolean utf8)throws IOException{
            try{
                return(utf8?StandardCharsets.UTF_8:StandardCharsets.UTF_16LE).newDecoder()
                        .decode(ByteBuffer.wrap(bytes,at,length)).toString();
            }catch(CharacterCodingException malformed){throw invalid();}
        }
        int length8(int at,int end)throws IOException{
            range(at,1,end);
            int a=u8(at);
            if((a&128)==0)return(1<<16)|a;
            range(at,2,end);
            return(2<<16)|(a&127)<<8|u8(at+1);
        }
        int u8(int at){return bytes[at]&255;}
        int u16(int at){return u8(at)|(u8(at+1)<<8);}
        long u32(int at){return u16(at)|((long)u16(at+2)<<16);}
        Chunk chunk(int start,int limit)throws IOException{
            if(Thread.currentThread().isInterrupted())throw new IOException("Preview stopped.");
            if(++chunks>MAX_CHUNKS)throw limit();
            range(start,8,limit);
            int header=u16(start+2);
            long size=u32(start+4);
            if(header<8||size<header||size>limit-start)throw invalid();
            return new Chunk(start,u16(start),header,start+(int)size);
        }
    }

    private static final class Chunk {
        final int start,kind,header,end;
        Chunk(int start,int kind,int header,int end){this.start=start;this.kind=kind;this.header=header;this.end=end;}
        int body(){return start+header;}
    }

    private static boolean validName(String name,boolean resource,int max){
        if(name.isEmpty()||name.length()>max)return false;
        for(int i=0;i<name.length();i++){
            char c=name.charAt(i);
            if(!(c>='a'&&c<='z'||c>='A'&&c<='Z'||c>='0'&&c<='9'||c=='_'||c=='.'||resource&&(c=='-'||c=='$')))
                return false;
        }
        return true;
    }
    private static String hex(int n){
        String value=Integer.toHexString(n);
        return value.length()<8?"00000000".substring(value.length())+value:value;
    }
    private static void range(int at,int length,int end)throws IOException{
        if(at<0||length<0||at>end||length>end-at)throw invalid();
    }
    private static IOException invalid(){return new IOException("This resource table is damaged or uses an unsupported format.");}
    private static IOException limit(){return new IOException("This resource table exceeds the safe preview limits.");}
}
