package dev.fastsearch.android;

import dev.fastsearch.core.FileEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Extension counts from the same immutable, scoped result pass. */
final class FileSubtypes {
    static final class Choice {
        final String extension;
        final int count;
        Choice(String extension,int count){this.extension=extension;this.count=count;}
    }
    private FileSubtypes(){}
    static String extension(FileEntry entry){int dot=entry.name.lastIndexOf('.');
        return dot<=0||dot==entry.name.length()-1?"":entry.name.substring(dot+1).toLowerCase(Locale.ROOT);}
    static boolean matches(FileEntry entry,String extension){return extension==null||(!entry.directory&&extension(entry).equals(extension));}
    static List<Choice> sorted(Map<String,Integer> counts){ArrayList<Choice> choices=new ArrayList<>();
        for(Map.Entry<String,Integer> item:counts.entrySet())choices.add(new Choice(item.getKey(),item.getValue()));
        choices.sort((a,b)->{int order=Integer.compare(b.count,a.count);return order==0?a.extension.compareTo(b.extension):order;});return choices;}
}
