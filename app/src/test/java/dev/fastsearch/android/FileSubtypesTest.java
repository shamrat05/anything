package dev.fastsearch.android;

import dev.fastsearch.core.FileEntry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class FileSubtypesTest {
    private static int assertions;
    public static void main(String[] args){
        FileEntry video=file("/Movies/Clip.MP4",false),other=file("/Movies/Clip.mkv",false),folder=file("/Movies/old.mp4",true);
        check(FileSubtypes.extension(video).equals("mp4"),"extensions fold case");
        check(FileSubtypes.matches(video,"mp4")&&!FileSubtypes.matches(other,"mp4"),"selected extension distinguishes file formats");
        check(!FileSubtypes.matches(folder,"mp4")&&FileSubtypes.matches(folder,null),"folders never match an extension");
        check(FileSubtypes.extension(file("/a/.nomedia",false)).isEmpty(),"dotfile without suffix has no extension");
        check(FileSubtypes.extension(file("/a/.config.JSON",false)).equals("json"),"hidden file suffix remains selectable");
        check(FileSubtypes.matches(file("/a/README",false),""),"no-extension choice is distinct from All");
        check(FileSubtypes.extension(file("/a/archive.tar.gz",false)).equals("gz"),"final suffix identifies compound extensions");
        Map<String,Integer> counts=new HashMap<>();counts.put("mkv",4);counts.put("mp4",80);counts.put("avi",4);
        List<FileSubtypes.Choice> choices=FileSubtypes.sorted(counts);
        check(choices.get(0).extension.equals("mp4")&&choices.get(0).count==80,"most frequent type comes first");
        check(choices.get(1).extension.equals("avi")&&choices.get(2).extension.equals("mkv"),"frequency ties sort deterministically");
        check(FileSubtypes.sorted(new HashMap<>()).isEmpty(),"empty category produces no fabricated extensions");
        System.out.println("PASS "+assertions+" file-subtype assertions.");
    }
    private static FileEntry file(String path,boolean directory){return new FileEntry(path,directory,0,0);}
    private static void check(boolean value,String label){assertions++;if(!value)throw new AssertionError(label);}
}
