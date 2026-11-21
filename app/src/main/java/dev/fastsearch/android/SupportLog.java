package dev.fastsearch.android;

/** In-memory diagnostics with no input channel for paths, query text or exception messages. */
final class SupportLog {
    enum Event {
        INDEX_STARTED, INDEX_SAVED, INDEX_STOPPED, INDEX_FAILED, INDEX_MEMORY_LIMIT,
        ROOT_NO_HANDSHAKE, ROOT_SCAN_FAILED, ROOT_SCAN_FINISHED, CACHE_LOAD_FAILED, UPDATE_FAILED,
        COPY_FINISHED, MOVE_FINISHED, RENAME_FINISHED, DELETE_FINISHED
    }

    private static final int LIMIT = 16;
    static final int DESCRIPTION_LIMIT = 1200;
    private static final int DIAGNOSTICS_LIMIT = 1700;
    private static final Event[] events = new Event[LIMIT];
    private static final boolean[] roots = new boolean[LIMIT];
    private static final long[] firstCounts = new long[LIMIT], secondCounts = new long[LIMIT];
    private static int next, size;

    private SupportLog() {}

    static synchronized void record(Event event, boolean root, long first, long second) {
        events[next] = event;
        roots[next] = root;
        firstCounts[next] = first;
        secondCounts[next] = second;
        next = (next + 1) % LIMIT;
        if (size < LIMIT) size++;
    }

    private static void append(StringBuilder report, int position) {
        Event event = events[position];
        long first = firstCounts[position], second = secondCounts[position];
        report.append(event.name()).append(roots[position] ? " [root]" : " [shared]");
        switch (event) {
            case INDEX_SAVED:
                report.append(": entries=").append(first).append(", durationMs=").append(second);
                break;
            case ROOT_SCAN_FINISHED:
                report.append(": accessErrors=").append(first).append(", traversalErrors=").append(second);
                break;
            case COPY_FINISHED: case MOVE_FINISHED: case RENAME_FINISHED: case DELETE_FINISHED:
                report.append(": completed=").append(first).append(", requested=").append(second);
                break;
            default:
        }
    }

    static synchronized String snapshot() {
        if (size == 0) return "No recent events in this app session.";
        StringBuilder report = new StringBuilder();
        for (int i = 0; i < size; i++) {
            if (i > 0) report.append('\n');
            append(report, (next + LIMIT - size + i) % LIMIT);
        }
        return report.toString();
    }

    static String report(String description, String diagnostics) {
        String body = "Anything bug report\n\n" + description.trim();
        if (!diagnostics.isEmpty()) body += "\n\n--- Optional diagnostics ---\n" + diagnostics;
        return body;
    }

    static String limitDiagnostics(String text) {
        if (text.length() <= DIAGNOSTICS_LIMIT) return text;
        int lineEnd = text.lastIndexOf('\n', DIAGNOSTICS_LIMIT - 40);
        return text.substring(0, lineEnd < 0 ? DIAGNOSTICS_LIMIT - 40 : lineEnd) + "\n[Additional details omitted]";
    }
}
