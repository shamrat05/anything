package dev.fastsearch.android;

import dev.fastsearch.core.FileEntry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/** File metadata only. Never stores a Context, View, Activity, bitmap or open descriptor. */
final class PreviewSession {
    static final String EXTRA = "preview.session";
    private static final LinkedHashMap<String, PreviewSession> ACTIVE = new LinkedHashMap<>();
    final String id = UUID.randomUUID().toString();
    final List<FileEntry> entries;
    final boolean root;
    final LinkedHashMap<String, FileEntry> selected = new LinkedHashMap<>();
    int position;
    final long created = android.os.SystemClock.elapsedRealtime();

    private PreviewSession(List<FileEntry> entries, String path, boolean root) {
        ArrayList<FileEntry> files=new ArrayList<>();this.root=root;
        for(FileEntry entry:entries)if(!entry.directory){if(entry.pathEquals(path))position=files.size();files.add(entry);}
        this.entries=Collections.unmodifiableList(files);
    }
    static synchronized PreviewSession create(List<FileEntry> entries, String path, boolean root) {
        expire(); while (ACTIVE.size()>=2) ACTIVE.remove(ACTIVE.keySet().iterator().next());
        PreviewSession session = new PreviewSession(entries,path,root); ACTIVE.put(session.id,session); return session;
    }
    static synchronized PreviewSession get(String id) { expire(); return ACTIVE.get(id); }
    static synchronized void release(String id) { ACTIVE.remove(id); }
    private static void expire() { long now=android.os.SystemClock.elapsedRealtime();ACTIVE.values().removeIf(s->now-s.created>30*60_000L); }
    boolean toggle(FileEntry entry) {
        if(selected.remove(entry.path())!=null)return true;
        if(selected.size()>=FileClipboard.MAX_ITEMS)return false;selected.put(entry.path(),entry);return true;
    }
}
