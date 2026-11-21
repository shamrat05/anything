package dev.fastsearch.core;

/** Conservative compilation guard before RE2 expands counted repetitions. */
final class RegexLimits {
    static final int MAX_PROGRAM = 8192;
    private RegexLimits() {}

    static void check(String value, int position) {
        long[] costs = new long[65];
        long[] atoms = new long[65];
        int depth = 0;
        int groups = 0;
        boolean quoted = false;
        for (int i = 0; i < value.length(); i++) {
            if ((i & 63) == 0 && Thread.currentThread().isInterrupted()) {
                throw new java.util.concurrent.CancellationException("Search cancelled.");
            }
            char c = value.charAt(i);
            if (quoted) {
                if (c == '\\' && i + 1 < value.length() && value.charAt(i + 1) == 'E') {
                    quoted = false;
                    i++;
                    continue;
                }
            } else if (c == '\\' && i + 1 < value.length()) {
                char escaped = value.charAt(++i);
                if (escaped == 'Q') { quoted = true; continue; }
                if ((escaped == 'p' || escaped == 'P' || escaped == 'x') && i + 1 < value.length() && value.charAt(i + 1) == '{') {
                    int end = value.indexOf('}', i + 2);
                    if (end >= 0) i = end;
                }
            } else if (c == '[') {
                i = classEnd(value, i);
            } else if (c == '(') {
                if (++groups > 128 || ++depth >= costs.length) throw complex(position);
                costs[depth] = 4; // Include capture overhead even for noncapturing groups.
                atoms[depth] = 0;
                continue;
            } else if (c == ')') {
                if (depth == 0) continue; // The actual parser reports malformed syntax.
                long group = Math.max(2, costs[depth--]);
                costs[depth] += group;
                atoms[depth] = group;
                if (costs[depth] > MAX_PROGRAM) throw complex(position);
                continue;
            } else if (c == '{') {
                int end = repeatEnd(value, i);
                if (end >= 0) {
                    int comma = value.indexOf(',', i + 1);
                    if (comma < 0 || comma > end) comma = end;
                    long min = count(value, i + 1, comma);
                    long max = comma == end ? min : comma + 1 == end ? min + 1 : count(value, comma + 1, end);
                    if (min > 1000 || max > 1001) throw complex(position);
                    long replacement = Math.max(1, max) * atoms[depth] + 2;
                    costs[depth] += replacement - atoms[depth];
                    atoms[depth] = replacement;
                    if (costs[depth] > MAX_PROGRAM) throw complex(position);
                    i = end;
                    continue;
                }
            } else if (c == '*' || c == '+' || c == '?') {
                costs[depth] += 2;
                atoms[depth] += 2;
                if (costs[depth] > MAX_PROGRAM) throw complex(position);
                continue;
            } else if (c == '|') {
                costs[depth] += 2;
                atoms[depth] = 0;
                if (costs[depth] > MAX_PROGRAM) throw complex(position);
                continue;
            }
            costs[depth] += 2;
            atoms[depth] = 2;
            if (costs[depth] > MAX_PROGRAM) throw complex(position);
        }
        // Unclosed grouping is left to RE2's syntax error rather than being accepted here.
    }

    private static int classEnd(String value, int start) {
        int at = start + 1;
        if (at < value.length() && value.charAt(at) == '^') at++;
        boolean first = true;
        while (at < value.length()) {
            char c = value.charAt(at);
            if (c == ']' && !first) return at;
            if (c == '\\' && at + 1 < value.length()) at++;
            else if (c == '[' && at + 1 < value.length() && value.charAt(at + 1) == ':') {
                int end = value.indexOf(":]", at + 2);
                if (end >= 0) at = end + 1;
            }
            first = false;
            at++;
        }
        return value.length() - 1;
    }

    private static int repeatEnd(String value, int start) {
        int at = start + 1;
        int first = at;
        while (at < value.length() && digit(value.charAt(at))) at++;
        if (at == first) return -1;
        if (at < value.length() && value.charAt(at) == ',') {
            at++;
            while (at < value.length() && digit(value.charAt(at))) at++;
        }
        return at < value.length() && value.charAt(at) == '}' ? at : -1;
    }

    private static boolean digit(char c) { return c >= '0' && c <= '9'; }

    private static long count(String value, int from, int to) {
        long result = 0;
        for (int i = from; i < to && result <= 1001; i++) result = result * 10 + value.charAt(i) - '0';
        return result;
    }

    static QueryException complex(int position) {
        return new QueryException("Regular expression is too complex. Use fewer groups, shorter alternatives or smaller repetitions.", position);
    }
}
