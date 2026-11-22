package dev.fastsearch.android;

import android.content.SharedPreferences;
import dev.fastsearch.core.FileEntry;
import org.json.JSONArray;
import org.json.JSONException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A small, local preview of the last completed file search during a cold index load. */
final class SavedSearchRows {
    private static final String KEY = "startupSearchKey";
    private static final String ROWS = "startupSearchRows";
    static final int MAX_ROWS = 64;
    private static final int MAX_CHARACTERS = 24_000;

    static List<FileEntry> read(SharedPreferences prefs, String key) {
        if (!key.equals(prefs.getString(KEY, ""))) return Collections.emptyList();
        String encoded = prefs.getString(ROWS, "");
        if (encoded.isEmpty() || encoded.length() > MAX_CHARACTERS) return Collections.emptyList();
        try {
            JSONArray records = new JSONArray(encoded);
            if (records.length() > MAX_ROWS) return Collections.emptyList();
            List<FileEntry> rows = new ArrayList<>(records.length());
            for (int i = 0; i < records.length(); i++) {
                JSONArray row = records.getJSONArray(i);
                rows.add(new FileEntry(row.getString(0), row.getBoolean(1), row.getLong(2), row.getLong(3)));
            }
            return rows;
        } catch (JSONException | IllegalArgumentException malformed) {
            return Collections.emptyList();
        }
    }

    static void write(SharedPreferences prefs, String key, List<FileEntry> rows) {
        JSONArray records = new JSONArray();
        int characters = 2;
        for (int i = 0; i < Math.min(rows.size(), MAX_ROWS); i++) {
            FileEntry row = rows.get(i);
            JSONArray record = new JSONArray().put(row.path()).put(row.directory).put(row.size).put(row.modified);
            characters += record.toString().length() + 1;
            if (characters > MAX_CHARACTERS) break;
            records.put(record);
        }
        prefs.edit().putString(KEY, key).putString(ROWS, records.toString()).apply();
    }

    static void clear(SharedPreferences prefs) {
        if (prefs.contains(KEY)) prefs.edit().remove(KEY).remove(ROWS).apply();
    }

    private SavedSearchRows() {}
}
