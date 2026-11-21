package dev.fastsearch.android;

public final class SupportLogTest {
    public static void main(String[] args) {
        check(SupportLog.snapshot().contains("No recent events"));
        SupportLog.record(SupportLog.Event.ROOT_NO_HANDSHAKE, true, 0, 0);
        check(SupportLog.snapshot().equals("ROOT_NO_HANDSHAKE [root]"));
        SupportLog.record(SupportLog.Event.ROOT_SCAN_FINISHED, true, 5, 2);
        check(SupportLog.snapshot().contains("accessErrors=5, traversalErrors=2"));
        for (int i = 0; i < 40; i++) SupportLog.record(SupportLog.Event.INDEX_SAVED, false, i, 50);
        String recent = SupportLog.snapshot();
        check(recent.split("\n").length == 16);
        check(!recent.contains("ROOT_NO_HANDSHAKE"));
        check(recent.endsWith("INDEX_SAVED [shared]: entries=39, durationMs=50"));
        String plain = SupportLog.report("  Example reproduction steps.  ", "");
        check(plain.equals("Anything bug report\n\nExample reproduction steps."));
        check(SupportLog.report("Example", recent).endsWith("--- Optional diagnostics ---\n" + recent));
        String longText = new String(new char[5000]).replace('\0', 'x');
        String limited = SupportLog.limitDiagnostics(longText);
        check(limited.endsWith("[Additional details omitted]"));
        check(SupportLog.report(longText.substring(0, SupportLog.DESCRIPTION_LIMIT), limited).length() <= 3000);
        System.out.println("PASS 10 support diagnostics assertions.");
    }

    private static void check(boolean pass) {
        if (!pass) throw new AssertionError("Unexpected diagnostics report");
    }
}
