package dev.fastsearch.android;

import java.util.Arrays;

/** Literal, case-insensitive find over the bounded text viewer. Offsets remain in UTF-16. */
final class SmaliFind {
    static final int MAX_QUERY = 100, MAX_TEXT = 91_000;
    private static final int[] EMPTY = new int[0];
    private SmaliFind() {}

    static int[] matches(CharSequence text, CharSequence query) {
        int n = Math.min(text.length(), MAX_TEXT), m = query.length();
        if (m == 0 || m > MAX_QUERY || m > n) return EMPTY;
        char[] pattern = new char[m];
        int[] prefix = new int[m];
        for (int i = 0, k = 0; i < m; i++) {
            pattern[i] = fold(query.charAt(i));
            if (i == 0) continue;
            while (k > 0 && pattern[i] != pattern[k]) k = prefix[k - 1];
            if (pattern[i] == pattern[k]) k++;
            prefix[i] = k;
        }
        int[] found = new int[Math.min(16, n)];
        int count = 0;
        for (int i = 0, k = 0; i < n; i++) {
            char c = fold(text.charAt(i));
            while (k > 0 && c != pattern[k]) k = prefix[k - 1];
            if (c == pattern[k]) k++;
            if (k == m) {
                if (count == found.length) found = Arrays.copyOf(found, Math.min(found.length * 2, n));
                found[count++] = i - m + 1;
                k = 0; // Advance past the previous occurrence, making direction switches predictable.
            }
        }
        return count == 0 ? EMPTY : Arrays.copyOf(found, count);
    }

    static int navigate(int at, int direction, int count) {
        if (count <= 0) return -1;
        if (at < 0 || at >= count) return direction < 0 ? count - 1 : 0;
        return (at + (direction < 0 ? count - 1 : 1)) % count;
    }
    private static char fold(char ch) { return Character.toLowerCase(Character.toUpperCase(ch)); }
}
