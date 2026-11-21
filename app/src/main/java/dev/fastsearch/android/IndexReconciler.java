package dev.fastsearch.android;

import dev.fastsearch.core.FileEntry;
import dev.fastsearch.core.FileEntryTable;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Revalidates cached metadata with native stat calls; enumerates changed and new directories only. */
final class IndexReconciler {
    interface Check { void run() throws Exception; }
    interface Emit { void run(JSONObject message) throws Exception; }
    private final Check check;
    private final Emit emit;
    private final FileEntryTable known=new FileEntryTable();
    private final Map<String,List<FileEntry>> children=new HashMap<>();
    private final Set<String> blocked=new HashSet<>(),retry=new HashSet<>();
    private Set<String> targets;
    private final List<String> roots=new ArrayList<>();
    private final Set<String> excludes=new HashSet<>();
    private JSONArray updates=new JSONArray(),removals=new JSONArray();
    private boolean foldersOnly;
    private int checked,changed,denied,listed,pathChecks;
    private long notified;
    private IndexReconciler(Check check,Emit emit){this.check=check;this.emit=emit;}
    static JSONObject run(JSONObject request,Check check,Emit emit)throws Exception{return new IndexReconciler(check,emit).run(request,null);}
    static JSONObject run(JSONObject request,List<FileEntry> snapshot,Check check,Emit emit)throws Exception{return new IndexReconciler(check,emit).run(request,snapshot);}
    private void remember(FileEntry entry){known.put(entry);
        children.computeIfAbsent(entry.parent,ignored->new ArrayList<>()).add(entry);}
    private JSONObject run(JSONObject request,List<FileEntry> snapshot)throws Exception{
        foldersOnly=request.optBoolean("foldersOnly");
        JSONArray rootPaths=request.getJSONArray("roots");for(int i=0;i<rootPaths.length();i++)roots.add(rootPaths.getString(i));
        if(request.optBoolean("root")){
            excludes.addAll(IndexRootScanner.exclusions(new String(Files.readAllBytes(Paths.get("/proc/self/mountinfo")),java.nio.charset.StandardCharsets.UTF_8)));
            try{
                android.system.StructStat owner=android.system.Os.stat(IndexRootScanner.OWNER_DATA);
                android.system.StructStat alias=android.system.Os.stat(IndexRootScanner.OWNER_DATA_ALIAS);
                if(owner.st_dev==alias.st_dev&&owner.st_ino==alias.st_ino)excludes.add(IndexRootScanner.OWNER_DATA_ALIAS);
            }catch(android.system.ErrnoException unavailable){
                // An absent or inaccessible candidate cannot establish an alias.
            }
        }
        JSONArray deniedPaths=request.getJSONArray("denied");for(int i=0;i<deniedPaths.length();i++)retry.add(deniedPaths.getString(i));
        JSONArray selected=request.optJSONArray("targets");
        if(selected!=null){targets=new HashSet<>();for(int i=0;i<selected.length();i++)targets.add(FileOperationWorker.requirePath(selected.getString(i)));}
        if(snapshot!=null){int count=0;for(FileEntry entry:snapshot){if((count++&255)==0)check.run();remember(entry);}}
        else try(DataInputStream input=new DataInputStream(new BufferedInputStream(new FileInputStream(request.getString("snapshot")),64*1024))){
            if(input.readInt()!=1)throw new java.io.IOException("Invalid index snapshot.");int count=input.readInt();
            if(count<0||count>10_000_000)throw new java.io.IOException("Invalid index size.");
            Map<String,String> parents=new HashMap<>();
            for(int i=0;i<count;i++){if((i&255)==0)check.run();String path=input.readUTF();boolean directory=input.readBoolean();long size=input.readLong(),modified=input.readLong();
                remember(new FileEntry(path,directory,size,modified,parents));}
        }
        List<FileEntry> directories=new ArrayList<>();for(FileEntry entry:known.values())if(entry.directory&&targeted(entry.path()))directories.add(entry);
        directories.sort(java.util.Comparator.comparing(entry->entry.path()));
        boolean verifyFiles=request.optBoolean("verifyFiles",true);
        for(int offset=0;offset<directories.size();offset+=128){check.run();List<String> paths=new ArrayList<>();int end=Math.min(offset+128,directories.size());
            for(int i=offset;i<end;i++)paths.add(directories.get(i).path());
            long[] stamps=verifyFiles?null:NativeDiscovery.modified(paths);
            for(int i=offset;i<end;i++){FileEntry entry=directories.get(i);check.run();if(blocked(entry.path()))continue;
                checked++;boolean forced=retry.contains(entry.path())||entry.path().equals("/")||entry.path().startsWith("/mnt/");
                if(stamps!=null&&stamps[i-offset]>=0&&stamps[i-offset]==entry.modified&&!forced){progress();continue;}
                FileEntry fresh=stat(entry.path());
                if(fresh==null){progress();continue;}if(!fresh.directory){remove(entry.path());update(fresh);blocked.add(entry.path());continue;}
                boolean relist=fresh.modified!=entry.modified||forced;
                if(!same(entry,fresh))update(fresh);if(relist)list(entry.path());progress();}}
        if(request.optBoolean("verifyFiles",true))for(FileEntry entry:known.values()){check.run();if(entry.directory||!targeted(entry.path())||blocked(entry.path()))continue;FileEntry fresh=stat(entry.path());checked++;
            if(fresh!=null&&!same(entry,fresh)){update(fresh);if(fresh.directory)walk(fresh.path());}progress();}
        for(String root:targets==null?roots:targets)if(known.get(root)==null)walk(root);
        JSONArray parents=request.optJSONArray("parents");if(parents!=null)for(int i=0;i<parents.length();i++){
            String path=parents.getString(i);FileEntry fresh=stat(path);checked++;
            if(fresh!=null&&!sameNullable(known.get(path),fresh))update(fresh);}
        flush();return new JSONObject().put("changedRows",changed).put("checkedRows",targets==null?checked:pathChecks).put("listedDirectories",listed).put("accessErrors",denied);
    }
    private boolean targeted(String path){if(targets==null||targets.contains(path))return true;
        for(int slash=path.lastIndexOf('/');slash>0;slash=path.lastIndexOf('/',slash-1))if(targets.contains(path.substring(0,slash)))return true;
        return false;}
    private boolean allowed(String path){boolean inside=false;for(String root:roots)if(root.equals("/")||path.equals(root)||path.startsWith(root+"/")){inside=true;break;}
        if(!inside)return false;if(excludes.contains(path))return false;
        for(int slash=path.lastIndexOf('/');slash>0;slash=path.lastIndexOf('/',slash-1))if(excludes.contains(path.substring(0,slash)))return false;return true;}
    private boolean blocked(String path){if(!allowed(path))return true;for(String parent=path;!parent.isEmpty();){if(blocked.contains(parent))return true;int slash=parent.lastIndexOf('/');if(slash<=0)break;parent=parent.substring(0,slash);}return false;}
    private FileEntry stat(String path)throws Exception{
        check.run();if(!allowed(path))return null;
        if(targets!=null)pathChecks++;
        try{android.system.StructStat metadata=android.system.Os.lstat(path);boolean directory=android.system.OsConstants.S_ISDIR(metadata.st_mode);
            if(!directory&&!android.system.OsConstants.S_ISREG(metadata.st_mode)){if(foldersOnly||known.get(path)!=null)remove(path);blocked.add(path);return null;}
            return new FileEntry(path,directory,directory?0:metadata.st_size,NativeDiscovery.modified(metadata));
        }catch(android.system.ErrnoException failure){
            if(failure.errno==android.system.OsConstants.ENOENT||failure.errno==android.system.OsConstants.ENOTDIR){if(foldersOnly||known.get(path)!=null)remove(path);}
            else denied++;
            blocked.add(path);return null;
        }
    }
    private void list(String path)throws Exception{
        check.run();listed++;Set<String> seen=new HashSet<>();boolean complete=false;JSONArray names=new JSONArray();
        try(DirectoryStream<Path> stream=Files.newDirectoryStream(Paths.get(path))){
            for(Path child:stream){check.run();String name=child.toString();seen.add(name);
                if(foldersOnly){names.put(child.getFileName().toString());if(names.length()>=256){listing(path,names,false);names=new JSONArray();}}
                if(!allowed(name))continue;FileEntry fresh=stat(name);if(fresh==null)continue;
                FileEntry old=known.get(name);if(old==null){if(fresh.directory)walk(name);else update(fresh);}else if(!same(old,fresh))update(fresh);}
            complete=true;
        }catch(AccessDeniedException denied){this.denied++;}catch(java.io.IOException unavailable){denied++;}
        if(foldersOnly)listing(path,names,complete);
        // A completed changed-folder listing detects deletions without statting every cached file.
        // Retain unseen children when permission or IO prevented a complete listing.
        if(complete)for(FileEntry old:children.getOrDefault(path,Collections.emptyList())){
            String previous=old.path();
            if(!seen.contains(previous)&&!blocked(previous)){remove(previous);blocked.add(previous);}
        }
    }
    private void listing(String path,JSONArray names,boolean complete)throws Exception{
        emit.run(new JSONObject().put("event","delta").put("listing",path).put("seen",names).put("complete",complete));
    }
    private void walk(String root)throws Exception{
        ArrayDeque<String> queue=new ArrayDeque<>();queue.add(root);
        while(!queue.isEmpty()){check.run();String path=queue.removeFirst();if(blocked(path))continue;FileEntry fresh=stat(path);if(fresh==null)continue;
            update(fresh);if(!fresh.directory)continue;listed++;
            try(DirectoryStream<Path> stream=Files.newDirectoryStream(Paths.get(path))){for(Path child:stream){check.run();String name=child.toString();if(!allowed(name))continue;
                FileEntry item=stat(name);if(item==null)continue;if(item.directory)queue.addLast(name);else update(item);}}
            catch(AccessDeniedException denied){this.denied++;}catch(java.io.IOException unavailable){denied++;}progress();}
    }
    private static boolean same(FileEntry before,FileEntry after){return before.directory==after.directory&&before.size==after.size&&before.modified==after.modified;}
    private static boolean sameNullable(FileEntry before,FileEntry after){return before!=null&&same(before,after);}
    private void update(FileEntry entry)throws Exception{updates.put(new JSONObject().put("path",entry.path()).put("directory",entry.directory).put("size",entry.size).put("modified",entry.modified));changed++;if(updates.length()+removals.length()>=256)flush();}
    private void remove(String path)throws Exception{removals.put(path);changed++;if(updates.length()+removals.length()>=256)flush();}
    private void flush()throws Exception{if(updates.length()+removals.length()==0)return;emit.run(new JSONObject().put("event","delta").put("entries",updates).put("removed",removals));updates=new JSONArray();removals=new JSONArray();}
    private void progress()throws Exception{long now=android.os.SystemClock.elapsedRealtime();if(now-notified<350)return;notified=now;
        emit.run(new JSONObject().put("event","progress").put("completed",checked).put("total",known.size()));}
}
