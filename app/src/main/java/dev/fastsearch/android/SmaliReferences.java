package dev.fastsearch.android;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Bounded lexical positions in Baksmali output. Does not load or evaluate bytecode. */
final class SmaliReferences {
    private static final int MAX_TEXT = 91_000, MAX_REFS = 2048, MAX_TOKEN = 1024;

    static final class Ref {
        final int start, end;
        final String owner, member;
        final boolean method;
        Ref(int start, int end, String owner, String member, boolean method) {
            this.start=start; this.end=end; this.owner=owner; this.member=member; this.method=method;
        }
        String label() { return owner + (member==null ? "" : "->"+member); }
    }
    static final class Anchor {
        final int start, end;
        Anchor(int start,int end){this.start=start;this.end=end;}
    }

    static List<Ref> scan(CharSequence text) {
        int limit=Math.min(MAX_TEXT,text.length());
        List<Ref> refs=new ArrayList<>();
        for(int at=0;at<limit && refs.size()<MAX_REFS;) {
            char c=text.charAt(at);
            if(c=='#') { while(at<limit && text.charAt(at)!='\n')at++; continue; }
            if(c=='"' || c=='\'' || c==0x60) { at=quoted(text,at,limit); continue; }
            if(c!='L'||(at>0 && !boundary(text.charAt(at-1)))) { at++; continue; }
            int ownerEnd=objectEnd(text,at,limit);
            if(ownerEnd<0) { at++; continue; }
            int end=ownerEnd;
            String member=null;
            boolean method=false;
            if(ownerEnd+2<limit && text.charAt(ownerEnd)=='-' && text.charAt(ownerEnd+1)=='>') {
                int memberStart=ownerEnd+2, suffix=nameEnd(text,memberStart,limit);
                if(suffix>memberStart && suffix<limit) {
                    char token=text.charAt(suffix);
                    int stop=signatureEnd(text,suffix,limit,token);
                    if(stop>suffix && stop-memberStart<=MAX_TOKEN) {
                        member=text.subSequence(memberStart,stop).toString(); method=token=='('; end=stop;
                    }
                }
            }
            refs.add(new Ref(at,end,text.subSequence(at,ownerEnd).toString(),member,method));
            at=end;
        }
        return Collections.unmodifiableList(refs);
    }

    static Ref at(List<Ref> references,int offset) {
        int low=0,high=references.size()-1;
        while(low<=high) {
            int mid=(low+high)>>>1;
            Ref r=references.get(mid);
            if(offset<r.start)high=mid-1;
            else if(offset>=r.end)low=mid+1;
            else return r;
        }
        return null;
    }

    static Anchor declaration(CharSequence text,Ref reference) {
        if(reference.member==null)return null;
        int max=Math.min(MAX_TEXT,text.length());
        String directive=reference.method ? ".method" : ".field";
        for(int line=0;line<max;) {
            int end=line;
            while(end<max && text.charAt(end)!='\n')end++;
            int start=line;
            while(start<end && Character.isWhitespace(text.charAt(start)))start++;
            int after=start+directive.length();
            if(after<end && same(text,start,directive) && Character.isWhitespace(text.charAt(after))) {
                for(int word=after;word<end;) {
                    char c=text.charAt(word);
                    if(c=='#'||c=='='||c=='"'||c=='\'')break;
                    if(Character.isWhitespace(c)){word++;continue;}
                    int suffix=nameEnd(text,word,end);
                    if(suffix>word && suffix<end && text.charAt(suffix)==(reference.method?'(':':')) {
                        int stop=signatureEnd(text,suffix,end,text.charAt(suffix));
                        if(stop>suffix && stop-word==reference.member.length() && same(text,word,reference.member))
                            return new Anchor(word,stop);
                    }
                    if(suffix>word)word=suffix;
                    while(word<end && !Character.isWhitespace(text.charAt(word)))word++;
                }
            }
            line=end+1;
        }
        return null;
    }

    private static int signatureEnd(CharSequence text,int start,int limit,char token) {
        if(token==':')return typeEnd(text,start+1,limit,false);
        if(token!='(')return -1;
        int at=start+1;
        while(at<limit && at-start<=MAX_TOKEN) {
            if(text.charAt(at)==')')return typeEnd(text,at+1,limit,true);
            at=typeEnd(text,at,limit,false);
            if(at<0)return -1;
        }
        return -1;
    }

    private static int typeEnd(CharSequence text,int start,int limit,boolean allowVoid) {
        int at=start;
        while(at<limit && text.charAt(at)=='[' && at-start<255)at++;
        if(at>=limit)return -1;
        char c=text.charAt(at);
        if(c=='L')return objectEnd(text,at,limit);
        return "ZBCSIJFD".indexOf(c)>=0 || (c=='V' && at==start && allowVoid) ? at+1 : -1;
    }
    private static int objectEnd(CharSequence text,int start,int limit) {
        boolean part=false;
        for(int i=start+1;i<limit && i-start<=MAX_TOKEN;i++) {
            char c=text.charAt(i);
            if(c==';')return part?i+1:-1;
            if(c=='/'){if(!part)return -1;part=false;continue;}
            if(!identifier(c))return -1;
            part=true;
        }
        return -1;
    }
    private static int nameEnd(CharSequence text,int at,int limit) {
        if(text.charAt(at)==0x60) {
            int end=quoted(text,at,limit);
            return end>at+1 && text.charAt(end-1)==0x60 && end-at<=MAX_TOKEN ? end : at;
        }
        int start=at;
        while(at<limit && at-start<MAX_TOKEN) {
            char c=text.charAt(at);
            if(!identifier(c) && c!='<' && c!='>')break;
            at++;
        }
        return at;
    }
    private static int quoted(CharSequence text,int at,int limit) {
        char quote=text.charAt(at++);
        while(at<limit) {
            char c=text.charAt(at++);
            if(c==quote||c=='\n')break;
            if(c=='\\'&&at<limit&&text.charAt(at)!='\n')at++;
        }
        return at;
    }
    private static boolean same(CharSequence text,int start,String token) {
        for(int i=0;i<token.length();i++)if(text.charAt(start+i)!=token.charAt(i))return false;
        return true;
    }
    private static boolean identifier(char c){return Character.isLetterOrDigit(c)||c=='_'||c=='$'||c=='-';}
    private static boolean boundary(char c){return Character.isWhitespace(c)||",:([{;>=".indexOf(c)>=0;}
    private SmaliReferences(){}
}
