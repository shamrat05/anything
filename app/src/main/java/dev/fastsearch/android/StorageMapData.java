package dev.fastsearch.android;

import dev.fastsearch.core.FileEntry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Aggregates descendants into the immediate directory entries shown on one map. */
final class StorageMapData {
    static final class Tile {
        final FileEntry entry;
        final long bytes;

        Tile(FileEntry entry,long bytes){this.entry=entry;this.bytes=bytes;}
    }

    private static final class Group {
        FileEntry entry;
        long bytes;
    }

    private final String prefix;
    private final String folder;
    private final boolean showHidden;
    private final Map<String,Group> groups=new LinkedHashMap<>();
    private String recentChild="";
    private String recentChildPrefix="";
    private Group recentGroup;

    StorageMapData(String folder,boolean showHidden){
        this.folder=folder;
        prefix=folder.endsWith("/")?folder:folder+"/";
        this.showHidden=showHidden;
    }

    void add(FileEntry entry){
        if(!showHidden&&entry.hidden)return;
        if(entry.parent.equals(folder)){
            addTile(entry,entry.directory?0:Math.max(0,entry.size));
        }else if(!entry.directory&&entry.size>0&&entry.parent.startsWith(prefix)){
            String parent=entry.parent;
            if(recentGroup==null||(!parent.equals(recentChild)&&!parent.startsWith(recentChildPrefix))){
                int slash=parent.indexOf('/',prefix.length());
                recentChild=slash<0?parent:parent.substring(0,slash);
                recentChildPrefix=recentChild+"/";
                recentGroup=groups.computeIfAbsent(recentChild,key->new Group());
            }
            recentGroup.bytes+=entry.size;
        }
    }

    void addTile(FileEntry entry,long bytes){
        if(!showHidden&&entry.hidden)return;
        Group group=groups.computeIfAbsent(entry.path(),key->new Group());
        group.entry=entry;
        group.bytes+=bytes;
    }

    List<Tile> tiles(){
        ArrayList<Tile> tiles=new ArrayList<>(groups.size());
        for(Map.Entry<String,Group> item:groups.entrySet()){
            Group group=item.getValue();
            FileEntry entry=group.entry==null?new FileEntry(item.getKey(),true,0,-1):group.entry;
            tiles.add(new Tile(entry,group.bytes));
        }
        tiles.sort(order(2,true));
        return tiles;
    }

    static Comparator<Tile> order(int field,boolean descending){
        Comparator<Tile> primary;
        if(field==1)primary=(a,b)->a.entry.comparePathIgnoreCase(b.entry);
        else if(field==2)primary=Comparator.comparingLong(tile->tile.bytes);
        else if(field==3)primary=Comparator.comparingLong(tile->tile.entry.modified);
        else primary=Comparator.comparing(tile->tile.entry.name,String.CASE_INSENSITIVE_ORDER);
        if(descending)primary=primary.reversed();
        return primary.thenComparing(tile->tile.entry.name,String.CASE_INSENSITIVE_ORDER)
                .thenComparing((a,b)->a.entry.comparePath(b.entry));
    }
}
