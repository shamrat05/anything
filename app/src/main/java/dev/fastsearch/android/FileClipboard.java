package dev.fastsearch.android;

import android.content.Context;
import android.content.SharedPreferences;
import dev.fastsearch.core.FileEntry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Private, identity-bound clipboard. save() performs I/O and must run off the UI thread. */
public final class FileClipboard {
    static final int MAX_ITEMS = 512;
    static final int MAX_BYTES = 1024 * 1024;
    private static final long LIFETIME = 24L * 60 * 60 * 1000;
    private static final String KEY = "selection";

    private FileClipboard() { }
    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("file-clipboard", Context.MODE_PRIVATE);
    }

    public static void save(Context context, List<FileEntry> entries, boolean move, boolean allowRoot) throws Exception {
        Snapshot snapshot=capture(context,entries,move,allowRoot);
        JSONObject saved = new JSONObject().put("version", 1).put("id", snapshot.id)
                .put("created", System.currentTimeMillis()).put("move", move).put("root", allowRoot).put("entries",snapshot.entries);
        String serialized=saved.toString();
        if(serialized.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw new IOException("The clipboard selection is too large.");
        synchronized(FileClipboard.class){if(!prefs(context).edit().putString(KEY,serialized).commit())throw new IOException("Could not save the file clipboard.");}
    }

    /** Capture filesystem identities without replacing the user's copy/move clipboard. */
    static Snapshot capture(Context context,List<FileEntry> entries,boolean move,boolean allowRoot)throws Exception{
        if (entries == null || entries.isEmpty()) throw new IOException("Select files or folders first.");
        if (entries.size() > MAX_ITEMS) throw new IOException("Select at most " + MAX_ITEMS + " items at a time.");
        for (FileEntry entry : entries) if (entry == null) throw new IOException("Invalid selection.");
        List<FileEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing(entry -> entry.path()));
        JSONArray selected = new JSONArray();
        Set<String> seen = new HashSet<>();
        Set<String> selectedDirectories = new HashSet<>();
        int bytes = 0;
        for (FileEntry entry : sorted) {
            String path = FileOperationWorker.requirePath(entry.path());
            if (!seen.add(path)) continue;
            boolean covered = false;
            for (int slash = path.lastIndexOf('/'); slash > 0; slash = path.lastIndexOf('/', slash - 1)) {
                if (selectedDirectories.contains(path.substring(0, slash))) { covered = true; break; }
            }
            if (covered) continue;
            bytes += path.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_BYTES / 2) throw new IOException("The clipboard selection is too large.");
            selected.put(new JSONObject().put("path", path).put("directory", entry.directory));
            if (entry.directory) selectedDirectories.add(path);
        }
        JSONObject request = new JSONObject().put("mode", "capture").put("move", move).put("entries", selected);
        JSONObject result;
        if (!allowRoot) {
            result = FileOperationWorker.runLocal(context, request, () -> false, null);
        } else {
            try (FileOperationProcess process = new FileOperationProcess(context, request, true, null)) {
                result = process.run();
            }
        }
        if (!result.optBoolean("ok")) throw new IOException(result.optString("error", "Could not prepare the clipboard."));
        return new Snapshot(UUID.randomUUID().toString(),move,allowRoot,result.getJSONArray("entries"));
    }

    public static int count(Context context) {
        Snapshot value = read(context);
        return value == null ? 0 : value.entries.length();
    }

    public static boolean isMove(Context context) {
        Snapshot value = read(context);
        return value != null && value.move;
    }

    public static void clear(Context context) {
        synchronized (FileClipboard.class) { prefs(context).edit().remove(KEY).apply(); }
    }

    static Snapshot read(Context context) {
        try {
            String raw = prefs(context).getString(KEY, "");
            if (raw.isEmpty() || raw.length() > MAX_BYTES) return null;
            JSONObject value = new JSONObject(raw);
            long age = System.currentTimeMillis() - value.getLong("created");
            JSONArray entries = value.getJSONArray("entries");
            if (value.getInt("version") != 1 || age < -60_000 || age > LIFETIME
                    || entries.length() == 0 || entries.length() > MAX_ITEMS) return null;
            return new Snapshot(value.getString("id"), value.getBoolean("move"), value.getBoolean("root"), entries);
        } catch (Exception invalid) { return null; }
    }

    static void completed(Context context, Snapshot snapshot, JSONArray indices) {
        if (indices == null || indices.length() == 0) return;
        synchronized (FileClipboard.class) {
            try {
                String raw = prefs(context).getString(KEY, "");
                JSONObject current = new JSONObject(raw);
                if (!snapshot.id.equals(current.getString("id"))) return;
                Set<Integer> finished = new HashSet<>();
                for (int i = 0; i < indices.length(); i++) finished.add(indices.getInt(i));
                JSONArray remaining = new JSONArray();
                for (int i = 0; i < snapshot.entries.length(); i++) if (!finished.contains(i)) remaining.put(snapshot.entries.getJSONObject(i));
                SharedPreferences.Editor edit = prefs(context).edit();
                if (remaining.length() == 0) edit.remove(KEY);
                else edit.putString(KEY, current.put("entries", remaining).toString());
                edit.commit();
            } catch (Exception ignored) { }
        }
    }

    static synchronized void deleted(Context context,Snapshot deleted,JSONArray indices){
        if(indices==null||indices.length()==0)return;
        try{String raw=prefs(context).getString(KEY,"");if(raw.isEmpty())return;JSONObject value=new JSONObject(raw);JSONArray kept=new JSONArray(),items=value.getJSONArray("entries");
            for(int i=0;i<items.length();i++){String path=items.getJSONObject(i).getString("path");boolean remove=false;
                for(int n=0;n<indices.length();n++){String gone=deleted.entries.getJSONObject(indices.getInt(n)).getString("path");if(path.equals(gone)||path.startsWith(gone+"/")){remove=true;break;}}
                if(!remove)kept.put(items.getJSONObject(i));}
            if(kept.length()==0)prefs(context).edit().remove(KEY).commit();else prefs(context).edit().putString(KEY,value.put("entries",kept).toString()).commit();
        }catch(Exception ignored){}
    }

    static final class Snapshot {
        final String id;
        final boolean move;
        final boolean root;
        final JSONArray entries;
        Snapshot(String id, boolean move, boolean root, JSONArray entries) {
            this.id = id; this.move = move; this.root = root; this.entries = entries;
        }
        JSONObject request(String destination) throws Exception {
            return new JSONObject().put("mode", "paste").put("move", move).put("destination", destination)
                    .put("entries", new JSONArray(entries.toString()));
        }
        JSONObject deleteRequest()throws Exception{return new JSONObject().put("mode","delete").put("entries",new JSONArray(entries.toString()));}
    }
}
