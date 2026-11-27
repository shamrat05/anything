package dev.fastsearch.android;

import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** Stable ordering of standard APK entry labels (classes2 before classes10). */
final class DexEntryPicker {
    private DexEntryPicker() {}
    static List<String> ordered(Collection<String> entries) {
        TreeSet<String> sorted = new TreeSet<>((a,b) -> {
            int order = Integer.compare(number(a),number(b));
            return order != 0 ? order : a.compareTo(b);
        });
        for (String entry : entries) if (entry != null && !entry.isEmpty()) sorted.add(entry);
        return new ArrayList<>(sorted);
    }
    private static int number(String name) {
        if ("classes.dex".equals(name)) return 1;
        int end = name.length()-4;
        if (end <= 7 || end > 11 || !name.startsWith("classes") || !name.endsWith(".dex")) return Integer.MAX_VALUE;
        int result=0;
        for (int i=7;i<end;i++) {
            char c=name.charAt(i);
            if (c<'0' || c>'9') return Integer.MAX_VALUE;
            result=result*10+c-'0';
        }
        return result;
    }
}
