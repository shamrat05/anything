package dev.fastsearch.core;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/** Deterministic name-only plans. Filesystem identities/collisions are rechecked by the worker. */
public final class RenameRules {
    public enum Mode { NAME, REPLACE, ADD, NUMBER }
    public static Map<String,String> plan(List<FileEntry> entries,Mode mode,String first,String second,int start,int digits,boolean keepExtension){
        if(entries==null||entries.isEmpty()||entries.size()>512)throw new IllegalArgumentException("Choose 1 to 512 items.");
        if(mode==Mode.NAME&&entries.size()!=1)throw new IllegalArgumentException("Choose one item for a single name.");
        if(start<0||start>999999999||digits<1||digits>9)throw new IllegalArgumentException("Check the starting number and digits.");
        if(mode==Mode.REPLACE&&first.isEmpty())throw new IllegalArgumentException("Enter text to find.");
        Set<String> sources=new HashSet<>(),targets=new HashSet<>();for(FileEntry entry:entries)if(!sources.add(entry.path()))throw new IllegalArgumentException("An item is selected twice.");
        Map<String,String> result=new LinkedHashMap<>();int index=0;
        for(FileEntry entry:entries){for(String parent=entry.parent;parent!=null&&!parent.equals("/");){
                if(sources.contains(parent))throw new IllegalArgumentException("Choose a folder or its contents, not both.");int slash=parent.lastIndexOf('/');parent=slash>0?parent.substring(0,slash):null;}
            String name=entry.name,extension="";int dot=name.lastIndexOf('.');
            if(keepExtension&&!entry.directory&&dot>0&&mode!=Mode.NAME){extension=name.substring(dot);name=name.substring(0,dot);}
            switch(mode){case NAME:name=first;break;case REPLACE:name=name.replace(first,second);break;
                case ADD:name=first+name+second;break;case NUMBER:String number=Long.toString((long)start+index);StringBuilder padded=new StringBuilder();
                    for(int n=number.length();n<digits;n++)padded.append('0');name=first+padded+number+second;break;}
            name+=extension;validate(name);String destination=(entry.parent.equals("/")?"/":entry.parent+"/")+name;
            if(!targets.add(destination))throw new IllegalArgumentException("Two items would have the same name.");result.put(entry.path(),name);index++;
        }
        return result;
    }
    public static void validate(String name){if(name.isEmpty()||name.equals(".")||name.equals("..")||name.indexOf('/')>=0||name.indexOf('\0')>=0)throw new IllegalArgumentException("Enter a valid file name.");
        if(name.getBytes(StandardCharsets.UTF_8).length>255)throw new IllegalArgumentException("A file name is too long.");}
    private RenameRules(){}
}
