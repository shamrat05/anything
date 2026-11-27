package dev.fastsearch.android;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Bounded read-only view of Android resource XML, not an XML writer or APK loader. */
final class ApkBinaryXml {
    static final int MAX_BYTES=1024*1024;
    private static final int MAX_STRINGS=32768, MAX_STRING=16*1024, MAX_DECODED=1024*1024;
    private static final int MAX_TAG_LENGTH=256, MAX_NS_LENGTH=1024;
    private static final int MAX_CHUNKS=24_000, MAX_DEPTH=64, MAX_ATTRIBUTES=256, MAX_NS=128;
    private static final int MAX_OUTPUT=128*1024, MAX_LINES=2000;
    private static final String XML_URI="http://www.w3.org/XML/1998/namespace";
    private static final String XMLNS_URI="http://www.w3.org/2000/xmlns/";
    private static final String[] DIMENSIONS={"px","dp","sp","pt","in","mm"};
    private static final double[] RADIX={1d/256,1d/32768,1d/8388608,1d/2147483648d};

    static boolean looksLike(byte[] data){return data!=null&&data.length>=2&&(data[0]&255)==3&&data[1]==0;}
    static String decode(byte[] data)throws IOException{return decode(data,null);}
    static String decode(byte[] data,java.util.function.IntFunction<String> resourceNames)throws IOException{
        if(data==null||data.length>MAX_BYTES)throw new IOException("Compiled XML exceeds the 1 MiB preview limit.");
        return new Reader(data,resourceNames).read();
    }

    private static final class Reader {
        final byte[] data;
        final java.util.function.IntFunction<String> resourceNames;
        final ArrayList<Element> elements=new ArrayList<>();
        final ArrayList<Namespace> namespaces=new ArrayList<>();
        final StringBuilder output=new StringBuilder(4096);
        final Map<String,String> tagPrefixes=new HashMap<>();
        final Map<String,String> attrPrefixes=new HashMap<>();
        Pool strings;
        int lines, roots, chunks;
        boolean truncated, treeStarted, resourceMap;

        Reader(byte[] data,java.util.function.IntFunction<String> resourceNames){
            this.data=data;this.resourceNames=resourceNames;
        }
        String read()throws IOException{
            Chunk root=chunk(0,data.length);
            if(root.type!=3||root.end!=data.length)throw invalid();
            for(int at=root.body;at<root.end;){
                if(Thread.currentThread().isInterrupted())throw new IOException("Preview stopped.");
                if(++chunks>MAX_CHUNKS)throw limit();
                Chunk item=chunk(at,root.end);
                if(item.type==1){
                    if(strings!=null||treeStarted||resourceMap)throw invalid();
                    strings=new Pool(item);
                }else if(item.type==0x180){
                    if(strings==null||treeStarted||resourceMap||(item.end-item.body)%4!=0
                            ||(item.end-item.body)/4>strings.values.length)throw invalid();
                    resourceMap=true;
                }else{
                    if(strings==null||item.body-item.start<16)throw invalid();
                    treeStarted=true;
                    switch(item.type){
                        case 0x100: namespace(item,true);break;
                        case 0x101: namespace(item,false);break;
                        case 0x102: open(item);break;
                        case 0x103: close(item);break;
                        case 0x104: characters(item);break;
                        default:throw invalid();
                    }
                }
                at=item.end;
            }
            if(roots!=1||!elements.isEmpty()||!namespaces.isEmpty())throw invalid();
            if(truncated)output.append("\n… Preview shortened.\n");
            return output.toString();
        }

        void namespace(Chunk chunk,boolean begin)throws IOException{
            range(chunk.body,8,chunk.end);
            String prefix=strings.get(i32(chunk.body),true);
            if(prefix==null)prefix="";
            String uri=strings.get(i32(chunk.body+4),false);
            if((!prefix.isEmpty()&&!ncName(prefix))||prefix.equals("xmlns")||uri.length()>MAX_NS_LENGTH
                    ||XMLNS_URI.equals(uri)||prefix.equals("xml")!=XML_URI.equals(uri)
                    ||uri.isEmpty()&&!prefix.isEmpty())throw invalid();
            int depth=elements.size();
            tagPrefixes.clear();attrPrefixes.clear();
            if(begin){
                if(namespaces.size()>=MAX_NS)throw limit();
                for(int i=namespaces.size()-1;i>=0;i--){
                    Namespace ns=namespaces.get(i);
                    if(ns.depth!=depth)break;
                    if(ns.prefix.equals(prefix))throw invalid();
                }
                namespaces.add(new Namespace(prefix,uri,depth));
            }else{
                if(namespaces.isEmpty())throw invalid();
                Namespace ns=namespaces.remove(namespaces.size()-1);
                if(ns.depth!=depth||!ns.shown||!prefix.equals(ns.prefix)||!uri.equals(ns.uri))throw invalid();
            }
        }

        void open(Chunk chunk)throws IOException{
            int base=chunk.body;
            range(base,20,chunk.end);
            String uri=strings.get(i32(base),true), local=name(i32(base+4));
            int offset=u16(base+8), width=u16(base+10), count=u16(base+12);
            if(count>MAX_ATTRIBUTES||elements.size()>=MAX_DEPTH)throw limit();
            if(offset<20||width<20||u16(base+14)>count||u16(base+16)>count||u16(base+18)>count)throw invalid();
            if((long)offset+(long)width*count>chunk.end-base)throw invalid();
            int depth=elements.size();
            if(depth==0&&++roots!=1)throw invalid();
            String qualified=qualified(uri,local,false);
            indent(depth);
            append("<");append(qualified);
            for(Namespace ns:namespaces)if(ns.depth==depth){
                if(ns.shown)throw invalid();
                ns.shown=true;
                append(" xmlns");if(!ns.prefix.isEmpty()){append(":");append(ns.prefix);}
                append("=\"");escape(ns.uri,true);append("\"");
            }
            Set<String> attributes=new HashSet<>();
            for(int a=0;a<count;a++){
                int at=base+offset+a*width;
                String attr=qualified(strings.get(i32(at),true),name(i32(at+4)),true);
                if(!attributes.add(attr))throw invalid();
                String raw=strings.get(i32(at+8),true);
                String typed=value(at+12);
                append(" ");append(attr);append("=\"");escape(raw==null?typed:raw,true);append("\"");
            }
            append(">\n");
            elements.add(new Element(uri,local,qualified));
        }

        void close(Chunk chunk)throws IOException{
            range(chunk.body,8,chunk.end);
            String uri=strings.get(i32(chunk.body),true), local=name(i32(chunk.body+4));
            if(elements.isEmpty())throw invalid();
            int depth=elements.size()-1;
            if(!namespaces.isEmpty()&&namespaces.get(namespaces.size()-1).depth>depth)throw invalid();
            Element previous=elements.remove(depth);
            if(!previous.local.equals(local)||!java.util.Objects.equals(previous.uri,uri))throw invalid();
            indent(depth);append("</");append(previous.qualified);append(">\n");
        }

        void characters(Chunk chunk)throws IOException{
            range(chunk.body,12,chunk.end);
            if(elements.isEmpty())throw invalid();
            String raw=strings.get(i32(chunk.body),false);
            value(chunk.body+4);
            indent(elements.size());escape(raw,false);append("\n");
        }

        String name(int index)throws IOException{
            String name=strings.get(index,false);
            if(!ncName(name))throw invalid();
            return name;
        }

        String qualified(String uri,String local,boolean attribute)throws IOException{
            if(uri==null||uri.isEmpty()){
                if(attribute&&local.equals("xmlns"))throw invalid();
                if(!attribute)for(int i=namespaces.size()-1;i>=0;i--){
                    Namespace ns=namespaces.get(i);
                    if(ns.prefix.isEmpty()){
                        if(!ns.uri.isEmpty())throw invalid();
                        break;
                    }
                }
                return local;
            }
            if(uri.length()>MAX_NS_LENGTH)throw limit();
            if(XML_URI.equals(uri))return "xml:"+local;
            Map<String,String> prefixes=attribute?attrPrefixes:tagPrefixes;
            String prefix=prefixes.get(uri);
            if(prefix!=null)return prefix+local;
            // Find the nearest active declaration, respecting prefix shadowing.
            Set<String> seen=new HashSet<>();
            for(int i=namespaces.size()-1;i>=0;i--){
                Namespace ns=namespaces.get(i);
                if(!seen.add(ns.prefix))continue;
                if(!ns.uri.equals(uri)||attribute&&ns.prefix.isEmpty())continue;
                prefix=ns.prefix.isEmpty()?"":ns.prefix+":";
                prefixes.put(uri,prefix);
                return prefix+local;
            }
            throw invalid();
        }

        String value(int at)throws IOException{
            if(u16(at)!=8||(data[at+2]&255)!=0)throw invalid();
            int type=data[at+3]&255, bits=i32(at+4);
            switch(type){
                case 0:return bits==1?"@empty":"@null";
                case 1:return reference("@",bits);
                case 2:return reference("?",bits);
                case 7:return "@0x"+hex(bits,8); // Dynamic package IDs need Android's runtime linker.
                case 8:return "?0x"+hex(bits,8);
                case 3:return strings.get(bits,false);
                case 4:return Float.toString(Float.intBitsToFloat(bits));
                case 5:case 6:{
                    int unit=bits&15;
                    double amount=(bits&0xffffff00)*RADIX[(bits>>4)&3];
                    if(type==5&&unit<DIMENSIONS.length)return number(amount)+DIMENSIONS[unit];
                    if(type==6&&unit<2)return number(amount*100)+(unit==0?"%":"%p");
                    break;
                }
                case 16:return Integer.toString(bits);
                case 17:return "0x"+Integer.toHexString(bits);
                case 18:return bits==0?"false":"true";
                case 28:return "#"+hex(bits,8);
                case 29:return "#"+hex(bits&0xffffff,6);
                case 30:case 31:return shortColor(bits,type==30);
                default:break;
            }
            return "[typed 0x"+hex(type,2)+":0x"+hex(bits,8)+"]";
        }

        String reference(String sign,int bits){
            String name=resourceNames==null?null:resourceNames.apply(bits);
            return name==null?sign+"0x"+hex(bits,8):sign+name;
        }

        void indent(int depth){for(int i=0;i<depth&&!truncated;i++)append("  ");}
        void append(String text){
            if(truncated)return;
            if(output.length()+text.length()>MAX_OUTPUT){truncated=true;return;}
            for(int i=0;i<text.length();i++)if(text.charAt(i)=='\n'&&++lines>MAX_LINES){truncated=true;return;}
            output.append(text);
        }
        void escape(String text,boolean attribute){
            for(int i=0;i<text.length()&&!truncated;){
                int cp=text.codePointAt(i);i+=Character.charCount(cp);
                switch(cp){
                    case '&':append("&amp;");break;
                    case '<':append("&lt;");break;
                    case '>':append("&gt;");break;
                    case '"':append(attribute?"&quot;":"\"");break;
                    case '\n':case '\r':case '\t':append("&#x"+Integer.toHexString(cp)+";");break;
                    default:
                        if(cp<32||cp==0xfffe||cp==0xffff||Character.isSurrogate((char)cp)&&cp<=0xffff)append("\ufffd");
                        else if(Character.getType(cp)==Character.FORMAT)append("&#x"+Integer.toHexString(cp)+";");
                        else if(output.length()+Character.charCount(cp)>MAX_OUTPUT)truncated=true;
                        else output.appendCodePoint(cp);
                }
            }
        }

        int u16(int at){return (data[at]&255)|((data[at+1]&255)<<8);}
        int i32(int at){return u16(at)|(u16(at+2)<<16);}
        long u32(int at){return i32(at)&0xffffffffL;}
        Chunk chunk(int start,int enclosing)throws IOException{
            range(start,8,enclosing);
            int header=u16(start+2);
            long length=u32(start+4);
            if(header<8||length<header||length>enclosing-start)throw invalid();
            return new Chunk(start,u16(start),start+header,start+(int)length);
        }

        final class Pool {
            final int offsets,start,end;
            final String[] values;
            final boolean utf8;
            int decoded;

            Pool(Chunk chunk)throws IOException{
                if(chunk.body-chunk.start<28)throw invalid();
                long count=u32(chunk.start+8), styles=u32(chunk.start+12);
                if(count>MAX_STRINGS||styles>MAX_STRINGS)throw limit();
                long startAt=u32(chunk.start+20), stylesAt=u32(chunk.start+24);
                if(startAt<(long)chunk.body-chunk.start+4*(count+styles)
                        ||startAt>chunk.end-chunk.start||
                        stylesAt!=0&&(stylesAt<startAt||stylesAt>chunk.end-chunk.start))throw invalid();
                if(styles!=0&&stylesAt==0)throw invalid();
                offsets=chunk.body;start=chunk.start+(int)startAt;
                end=stylesAt==0?chunk.end:chunk.start+(int)stylesAt;
                utf8=(i32(chunk.start+16)&256)!=0;
                values=new String[(int)count];
            }
            String get(int index,boolean optional)throws IOException{
                if(index==-1&&optional)return null;
                if(index<0||index>=values.length)throw invalid();
                if(values[index]!=null)return values[index];
                long relative=u32(offsets+index*4);
                if(relative>=end-start)throw invalid();
                int at=start+(int)relative;
                int unitCount, bytes;
                if(utf8){
                    long first=length8(at);at+=(int)(first>>>32);unitCount=(int)first;
                    long second=length8(at);at+=(int)(second>>>32);bytes=(int)second;
                }else{
                    range(at,2,end);
                    unitCount=u16(at);at+=2;
                    if((unitCount&0x8000)!=0){
                        range(at,2,end);unitCount=((unitCount&0x7fff)<<16)|u16(at);at+=2;
                    }
                    if(unitCount>MAX_STRING)throw limit();
                    bytes=unitCount*2;
                }
                if(unitCount>MAX_STRING||decoded+unitCount>MAX_DECODED)throw limit();
                range(at,bytes+(utf8?1:2),end);
                if(data[at+bytes]!=0||!utf8&&data[at+bytes+1]!=0)throw invalid();
                String result;
                try{
                    result=(utf8?StandardCharsets.UTF_8:StandardCharsets.UTF_16LE).newDecoder()
                            .decode(ByteBuffer.wrap(data,at,bytes)).toString();
                }catch(CharacterCodingException invalidString){throw invalid();}
                if(result.length()!=unitCount)throw invalid();
                decoded+=unitCount;
                return values[index]=result;
            }
            long length8(int at)throws IOException{
                range(at,1,end);
                int value=data[at]&255;
                if((value&128)==0)return (1L<<32)|value;
                range(at,2,end);
                return (2L<<32)|((value&127)<<8)|(data[at+1]&255);
            }
        }
    }

    private static final class Namespace {
        final String prefix,uri;
        final int depth;
        boolean shown;
        Namespace(String prefix,String uri,int depth){this.prefix=prefix;this.uri=uri;this.depth=depth;}
    }
    private static final class Element {
        final String uri,local,qualified;
        Element(String uri,String local,String qualified){this.uri=uri;this.local=local;this.qualified=qualified;}
    }
    private static final class Chunk {
        final int start,type,body,end;
        Chunk(int start,int type,int body,int end){this.start=start;this.type=type;this.body=body;this.end=end;}
    }

    private static boolean ncName(String name){
        if(name.isEmpty()||name.length()>MAX_TAG_LENGTH)return false;
        int first=name.codePointAt(0);
        if(first!='_'&&!Character.isLetter(first))return false;
        for(int at=Character.charCount(first);at<name.length();){
            int cp=name.codePointAt(at);at+=Character.charCount(cp);
            int category=Character.getType(cp);
            if(cp!='_'&&cp!='-'&&cp!='.'&&cp!=0xb7&&!Character.isLetterOrDigit(cp)
                    &&category!=Character.NON_SPACING_MARK&&category!=Character.COMBINING_SPACING_MARK)return false;
        }
        return true;
    }
    private static String shortColor(int bits,boolean alpha){
        String full=hex(bits,8);
        StringBuilder color=new StringBuilder("#");
        for(int i=alpha?0:2;i<8;i+=2){
            char high=full.charAt(i);
            if(high!=full.charAt(i+1))return "#"+(alpha?full:full.substring(2));
            color.append(high);
        }
        return color.toString();
    }
    private static String hex(int value,int chars){
        String result=Integer.toHexString(value);
        return result.length()>=chars?result:"00000000".substring(0,chars-result.length())+result;
    }
    private static String number(double value){
        if(value==(long)value)return Long.toString((long)value);
        return Double.toString(value);
    }
    private static void range(int at,int count,int end)throws IOException{
        if(at<0||count<0||at>end||count>end-at)throw invalid();
    }
    private static IOException invalid(){return new IOException("This compiled XML is damaged or uses an unsupported format.");}
    private static IOException limit(){return new IOException("This compiled XML exceeds the safe preview limits.");}
}
