package dev.fastsearch.android;

import dev.fastsearch.core.FileEntry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Deterministic size, scope and nested space checks without an Android device. */
public final class StorageMapDataTest {
    private static int checks;

    public static void main(String[] args) {
        scopeAndGeometry();
        limits();
        rootSummary();
        searchResults();
        System.out.println(checks+" storage map checks passed.");
    }

    private static void scopeAndGeometry(){
        StorageMapData data=new StorageMapData("/owned",false);
        data.add(file("/owned/empty",0));
        data.add(file("/owned/unknown",-1));
        data.add(folder("/owned/Media"));
        data.add(file("/owned/Media/Film.mp4",75));
        data.add(file("/owned/Media/Photos/X.jpg",25));
        data.add(file("/owned/Media/empty",0));
        data.add(file("/owned/Media/no-stat",-1));
        data.add(file("/owned/Media/.hidden/secret",900));
        data.add(file("/owned-lookalike/wrong",900));
        List<StorageMapData.Tile> tiles=data.tiles();
        StorageMapData.Tile media=tiles.get(0);
        StorageMapData.Analysis analysis=data.analysis(false);
        expect(tiles.size()==3 && media.bytes==100 && media.fileCount==4, "known scope byte sum and scanned descendant count");
        expect(media.children.size()==2 && media.children.get(0).name.equals("Film.mp4"), "sample uses actual largest descendants");
        expect(analysis.largestFile.name.equals("Film.mp4") && !analysis.indexed, "summary reports exact live largest");
        expect(analysis.scale.band(0)==0 && analysis.scale.band(75)==4 && analysis.scale.band(-1)==StorageMapData.UNKNOWN,
                "min blue, max red, unknown neutral");
        expect(analysis.scale.band(1)<analysis.scale.band(74), "midrange hues remain monotonic");
        expect(new StorageMapData.Scale(10,10).band(10)==4, "all tied largest files are red");
        expect(new StorageMapData.Scale(0,0).band(0)==0, "all-zero sizes use smallest hue");
        Map<String,Float> areas=new HashMap<>();
        StorageMapData.regions(media,100,120,(entry,l,t,r,b)->{
            expect(l>=0&&t>=0&&r<=100&&b<=120&&r>l&&b>t,"no empty or out-of-bounds tile");
            areas.put(entry.name,(r-l)*(b-t));
        });
        expect(areas.get("Film.mp4")==9000f && areas.get("X.jpg")==3000f,"visual leaf areas have the exact byte ratio");
        expect(tiles.get(2).bytes==-1,"unknown stays unknown in the list");
    }

    private static void limits(){
        StorageMapData data=new StorageMapData("/owned",true);
        for(int d=0;d<410;d++)for(int f=1;f<=20;f++) data.add(file("/owned/"+d+"/item-"+f,100*d+f));
        List<StorageMapData.Tile> tiles=data.tiles();
        int retained=0;
        for(StorageMapData.Tile tile:tiles)retained+=tile.children.size();
        expect(retained==StorageMapData.SAMPLE_LIMIT,"sample has a hard scope limit");
        StorageMapData.Tile largest=tiles.get(0);
        expect(largest.children.size()==StorageMapData.PER_FOLDER && largest.fileCount==20,
                "per-folder bound preserves accurate total count");
        expect(largest.children.get(0).size==40920 && tiles.get(409).children.isEmpty(),
                "largest files survive regardless of breadth");
        float[] area={0,0};
        StorageMapData.regions(largest,200,100,(entry,l,t,r,b)->{
            area[0]+=(r-l)*(b-t);
            if(entry==null)area[1]+=(r-l)*(b-t);
        });
        expect(Math.abs(area[0]-20000)<.1 && area[1]>7000,"unsampled bytes keep a proportional neutral rectangle");
        expect(StorageMapData.sum(Long.MAX_VALUE-2,5)==Long.MAX_VALUE,"bytes never wrap negative");
    }

    private static void rootSummary(){
        StorageMapData data=new StorageMapData("/owned",false);
        data.addTile(folder("/owned/Media"),100);
        data.addTile(file("/owned/note",20),20);
        data.addIndexedFile(file("/owned/deleted/ghost",900));
        data.addIndexedFile(file("/owned/Media/stale",101));
        data.addIndexedFile(file("/owned/Media/older",65));
        data.addIndexedFile(file("/owned/Media/two",61));
        StorageMapData.Tile first=data.tiles().get(0);
        expect(first.bytes==100 && first.children.size()==1 && first.children.get(0).name.equals("older"),
                "samples cannot overstate live folder size or add a deleted folder");
        expect(first.indexedChildren&&data.analysis(false).indexed,"fallback is explicitly indexed");
    }

    private static void searchResults(){
        StorageMapData.ResultTiles results=new StorageMapData.ResultTiles();
        results.add(folder("/owned/Photos"));
        results.add(file("/owned/Photos/Album1/Sunset.jpg",5));
        results.add(file("/owned/Photos/Album2/Sunset.jpg",40));
        results.add(file("/owned/Photos/empty.jpg",0));
        results.add(file("/owned/Photos/unknown.jpg",-1));
        List<StorageMapData.Tile> tiles=results.tiles(()->{});
        StorageMapData.Analysis analysis=results.analysis();
        expect(tiles.size()==5&&tiles.get(0).bytes==40&&tiles.get(1).bytes==5,
                "Map Results preserves same-name matches from different paths and orders by size");
        expect(tiles.stream().filter(tile->tile.entry.directory).allMatch(tile->tile.bytes==StorageMapData.UNKNOWN),
                "indexed directories never pretend to have exact recursive sizes");
        expect(analysis.indexed&&analysis.largestFile.path().endsWith("Album2/Sunset.jpg")
                &&analysis.scale.band(0)==0&&analysis.scale.band(40)==4,
                "temperature scale measures all known files in the result set");
    }

    private static FileEntry folder(String path){return new FileEntry(path,true,0,0);}
    private static FileEntry file(String path,long size){return new FileEntry(path,false,size,0);}
    private static void expect(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
}
