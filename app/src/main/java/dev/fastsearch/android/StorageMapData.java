package dev.fastsearch.android;

import dev.fastsearch.core.FileEntry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.TreeSet;

/** Directory totals and a bounded, metadata-only sample of the heaviest files inside them. */
final class StorageMapData {
    static final int PER_FOLDER = 12;
    static final int SAMPLE_LIMIT = 4096;
    static final int UNKNOWN = -1;
    private static final Comparator<Sample> LIGHTEST_FIRST = Comparator
            .comparingLong((Sample sample) -> sample.file.size)
            .thenComparing((a, b) -> a.file.comparePath(b.file));

    static final class Tile {
        final FileEntry entry;
        final long bytes;
        final List<FileEntry> children;
        final long fileCount;
        final boolean indexedChildren;

        Tile(FileEntry entry, long bytes) { this(entry, bytes, Collections.emptyList(), 0, false); }

        Tile(FileEntry entry, long bytes, List<FileEntry> children, long fileCount, boolean indexedChildren) {
            this.entry = entry;
            this.bytes = bytes;
            this.children = children;
            this.fileCount = fileCount;
            this.indexedChildren = indexedChildren;
        }
    }

    static final class Scale {
        final long min, max;
        Scale(long min, long max) { this.min = min; this.max = max; }

        /** Files tied for largest all get red; zero-only scopes remain blue. */
        int band(long bytes) {
            if (bytes < 0) return UNKNOWN;
            if (max == 0) return 0;
            if (bytes >= max) return 4;
            if (bytes <= min) return 0;
            // Reserve the endpoints for the actual minimum/maximum. Compress large outliers.
            double spread = Math.log1p((double)(max - min));
            double position = Math.log1p((double)(bytes - min)) / spread;
            return 1 + Math.min(2, (int)(position * 3));
        }
    }

    static final class Analysis {
        final Scale scale;
        final FileEntry largestFile;
        final boolean indexed;
        Analysis(Scale scale, FileEntry largestFile, boolean indexed) {
            this.scale = scale;
            this.largestFile = largestFile;
            this.indexed = indexed;
        }
    }

    /** A flat projection of file search. Indexed directories have no recursive byte total. */
    static final class ResultTiles {
        private final ArrayList<Tile> tiles = new ArrayList<>();
        private FileEntry largest;
        private long minimum = Long.MAX_VALUE;

        void add(FileEntry entry) {
            long bytes = entry.directory ? UNKNOWN : entry.size;
            tiles.add(new Tile(entry, bytes));
            if (bytes < 0) return;
            minimum = Math.min(minimum, bytes);
            if (largest == null || bytes > largest.size
                    || bytes == largest.size && entry.comparePath(largest) < 0) largest = entry;
        }

        List<Tile> tiles(Runnable checkForCancellation) {
            Comparator<Tile> bySize = order(2, true);
            tiles.sort((a, b) -> {
                checkForCancellation.run();
                return bySize.compare(a, b);
            });
            return Collections.unmodifiableList(tiles);
        }

        Analysis analysis() {
            return new Analysis(new Scale(largest == null ? 0 : minimum,
                    largest == null ? 0 : largest.size), largest, true);
        }
    }

    private static final class Group {
        FileEntry entry;
        long bytes, fileCount;
        PriorityQueue<Sample> samples;
    }

    private static final class Sample {
        final FileEntry file;
        final Group group;
        Sample(FileEntry file, Group group) { this.file = file; this.group = group; }
    }

    private final String prefix;
    private final String folder;
    private final boolean showHidden;
    private final Map<String,Group> groups=new LinkedHashMap<>();
    private final TreeSet<Sample> samples = new TreeSet<>(LIGHTEST_FIRST);
    private String recentChild="";
    private String recentChildPrefix="";
    private Group recentGroup;
    private FileEntry largestFile;
    private long minimum = Long.MAX_VALUE;
    private boolean indexed;

    StorageMapData(String folder,boolean showHidden){
        this.folder=folder;
        prefix=folder.endsWith("/")?folder:folder+"/";
        this.showHidden=showHidden;
    }

    void add(FileEntry entry){
        if(!showHidden&&entry.hidden)return;
        if(entry.parent.equals(folder)){
            addTile(entry,entry.directory?0:entry.size);
        }else if(!entry.directory&&entry.parent.startsWith(prefix)){
            Group group = groupFor(entry.parent, true);
            group.fileCount++;
            if(entry.size>=0) {
                group.bytes = sum(group.bytes, entry.size);
                observe(entry);
                sample(group, entry);
            }
        }
    }

    /** Root's native scan emits authoritative immediate entries. Index samples never change their totals. */
    void addIndexedFile(FileEntry entry){
        if(entry.directory || entry.size < 0 || !showHidden && entry.hidden
                || !entry.parent.startsWith(prefix) || entry.parent.equals(folder)) return;
        Group group = groupFor(entry.parent, false);
        if(group == null || group.entry == null || !group.entry.directory || entry.size > group.bytes) return;
        indexed = true;
        group.fileCount++;
        observe(entry);
        sample(group, entry);
    }

    private Group groupFor(String parent, boolean create){
        if(recentGroup==null||(!parent.equals(recentChild)&&!parent.startsWith(recentChildPrefix))){
            int slash=parent.indexOf('/',prefix.length());
            recentChild=slash<0?parent:parent.substring(0,slash);
            recentChildPrefix=recentChild+"/";
            recentGroup=create?groups.computeIfAbsent(recentChild,key->new Group()):groups.get(recentChild);
        }
        return recentGroup;
    }

    private void observe(FileEntry entry){
        if(entry.size<0)return;
        minimum = Math.min(minimum, entry.size);
        if(largestFile == null || entry.size > largestFile.size
                || entry.size == largestFile.size && entry.comparePath(largestFile) < 0) largestFile = entry;
    }

    private void sample(Group group, FileEntry file){
        if(file.size == 0)return; // A zero-byte file has no area. It remains in the folder's list.
        if(group.samples != null && group.samples.size() >= PER_FOLDER
                && notHeavier(file, group.samples.peek().file)) return;
        if(samples.size() >= SAMPLE_LIMIT && notHeavier(file, samples.first().file)) return;
        Sample candidate = new Sample(file, group);
        if(!samples.add(candidate))return;
        if(group.samples == null) group.samples = new PriorityQueue<>(PER_FOLDER, LIGHTEST_FIRST);
        group.samples.add(candidate);
        if(group.samples.size() > PER_FOLDER) samples.remove(group.samples.remove());
        if(samples.size() > SAMPLE_LIMIT){
            Sample victim = samples.pollFirst();
            victim.group.samples.remove(victim);
            if(victim.group.samples.isEmpty()) victim.group.samples = null;
        }
    }

    private static boolean notHeavier(FileEntry candidate, FileEntry retained) {
        return candidate.size < retained.size
                || candidate.size == retained.size && candidate.comparePath(retained) <= 0;
    }

    void addTile(FileEntry entry,long bytes){
        if(!showHidden&&entry.hidden)return;
        Group group=groups.computeIfAbsent(entry.path(),key->new Group());
        group.entry=entry;
        if(entry.directory)group.bytes=sum(group.bytes,Math.max(0,bytes));
        else {group.bytes=bytes;observe(entry);}
    }

    Analysis analysis(boolean provisional){
        return new Analysis(new Scale(largestFile == null ? 0 : minimum,
                largestFile == null ? 0 : largestFile.size), largestFile, indexed||provisional);
    }

    List<Tile> tiles(){
        ArrayList<Tile> tiles=new ArrayList<>(groups.size());
        for(Map.Entry<String,Group> item:groups.entrySet()){
            Group group=item.getValue();
            FileEntry entry=group.entry==null?new FileEntry(item.getKey(),true,0,-1):group.entry;
            List<FileEntry> children=Collections.emptyList();
            if(group.samples!=null){
                ArrayList<Sample> ordered=new ArrayList<>(group.samples);
                ordered.sort(LIGHTEST_FIRST.reversed());
                children=new ArrayList<>(ordered.size());
                long remaining=group.bytes;
                for(Sample sample:ordered) if(sample.file.size<=remaining){
                    children.add(sample.file);remaining-=sample.file.size;
                }
            }
            tiles.add(new Tile(entry,group.bytes,children,group.fileCount,indexed && !children.isEmpty()));
        }
        tiles.sort(order(2,true));
        return tiles;
    }

    interface Region {
        void add(FileEntry file, float left, float top, float right, float bottom);
    }

    /** Split the whole preview area by byte share. Null is unsampled (not zero-byte) space. */
    static void regions(Tile tile, float width, float height, Region out) {
        if(tile.children.isEmpty() || tile.bytes <= 0 || width <= 0 || height <= 0)return;
        long covered=0;
        for(FileEntry file:tile.children)covered=sum(covered,file.size);
        long remaining=Math.max(0,tile.bytes-covered);
        partition(tile.children,0,tile.children.size()+(remaining>0?1:0),
                0,0,width,height,tile.bytes,out);
    }

    private static void partition(List<FileEntry> files, int start, int end,
            float left, float top, float right, float bottom, long bytes, Region out) {
        if(end-start==1){out.add(start<files.size()?files.get(start):null,left,top,right,bottom);return;}
        long prefix=0;
        int split=start+1;
        for(int i=start;i<end-1;i++){
            long weight=files.get(i).size; // The optional remainder is always at the end.
            if(i>start && Math.abs(bytes/2.0-prefix)<=Math.abs(bytes/2.0-prefix-weight))break;
            prefix+=weight;split=i+1;
        }
        double share=(double)prefix/bytes;
        if(right-left>=bottom-top){
            float mid=(float)(left+(right-left)*share);
            partition(files,start,split,left,top,mid,bottom,prefix,out);
            partition(files,split,end,mid,top,right,bottom,bytes-prefix,out);
        }else{
            float mid=(float)(top+(bottom-top)*share);
            partition(files,start,split,left,top,right,mid,prefix,out);
            partition(files,split,end,left,mid,right,bottom,bytes-prefix,out);
        }
    }

    static long sum(long a, long b) { return Long.MAX_VALUE - a < b ? Long.MAX_VALUE : a + b; }

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
