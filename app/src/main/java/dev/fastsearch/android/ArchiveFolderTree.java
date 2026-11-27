package dev.fastsearch.android;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded virtual archive names; actionable leaves retain their exact original decoder entry. */
final class ArchiveFolderTree {
    private static final int MAX_PATH=4096,MAX_COMPONENTS=40,MAX_NODES=8192;
    static final class Node {
        final Node parent;
        final String leaf;
        final Map<String,Node> children=new LinkedHashMap<>();
        boolean folder,explicit,blocked,enabled;
        Object entry;
        String detail;
        Node(Node parent,String leaf,boolean folder){this.parent=parent;this.leaf=leaf;this.folder=folder;}
        String path(){
            if(parent==null)return "";
            String prefix=parent.path();
            return prefix.isEmpty()?leaf:prefix+"/"+leaf;
        }
        List<Node> visible(){
            List<Node> rows=new ArrayList<>();
            for(Node child:children.values())if(!child.blocked)rows.add(child);
            rows.sort((left,right)->{
                if(left.folder!=right.folder)return left.folder?-1:1;
                int compare=left.leaf.compareToIgnoreCase(right.leaf);
                return compare==0?left.leaf.compareTo(right.leaf):compare;
            });
            return Collections.unmodifiableList(rows);
        }
    }
    final Node root=new Node(null,"",true);
    private int nodes=1;
    boolean omitted,bounded;

    void add(String path,boolean directory,Object entry,boolean enabled,String detail){
        String[] parts=parts(path,directory);
        if(parts==null){omitted=true;return;}
        Node parent=root;
        int missing=-1;
        for(int i=0;i<parts.length;i++){
            String part=parts[i],key=canonical(part);
            boolean last=i==parts.length-1,folder=!last||directory;
            Node child=parent.children.get(key);
            if(child==null){missing=i;break;}
            if(child.blocked){omitted=true;return;}
            if(!child.leaf.equals(part)||child.folder!=folder||last&&child.explicit){
                child.blocked=true;omitted=true;return;
            }
            parent=child;
        }
        if(missing>=0){
            if(parts.length-missing>MAX_NODES-nodes){bounded=true;return;}
            for(int i=missing;i<parts.length;i++){
                Node child=new Node(parent,parts[i],i!=parts.length-1||directory);
                parent.children.put(canonical(parts[i]),child);
                parent=child;
                nodes++;
            }
        }
        parent.explicit=true;
        parent.entry=entry;
        parent.enabled=enabled;
        parent.detail=detail;
    }
    Node folder(String path){
        if(path==null||path.isEmpty())return root;
        String[] parts=parts(path,true);
        if(parts==null)return root;
        Node last=root;
        for(String part:parts){
            Node next=last.children.get(canonical(part));
            if(next==null||next.blocked||!next.folder||!next.leaf.equals(part))return last;
            last=next;
        }
        return last;
    }
    private static String canonical(String value){return Normalizer.normalize(value,Normalizer.Form.NFC);}
    private static String[] parts(String input,boolean directory){
        if(input==null||input.isEmpty()||input.length()>MAX_PATH||input.startsWith("/"))return null;
        String path=directory&&input.endsWith("/")?input.substring(0,input.length()-1):input;
        String[] parts=path.split("/",MAX_COMPONENTS+1);
        if(parts.length>MAX_COMPONENTS)return null;
        for(String part:parts){
            if(part.isEmpty()||".".equals(part)||"..".equals(part))return null;
            for(int i=0;i<part.length();i++){
                char c=part.charAt(i);
                if(c=='\\'||c==':'||c=='\uFEFF'||Character.isISOControl(c)
                        ||c>=0x202A&&c<=0x202E||c>=0x2066&&c<=0x2069)return null;
                if(Character.isHighSurrogate(c)){
                    if(++i>=part.length()||!Character.isLowSurrogate(part.charAt(i)))return null;
                }else if(Character.isLowSurrogate(c))return null;
            }
        }
        return parts;
    }
}
