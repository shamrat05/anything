package dev.fastsearch.core;

import java.util.concurrent.CancellationException;

/** Deterministic per-worker work accounting. No records or queries are retained. */
final class PatternBudget {
    static final long MAX_WORK = 8_000_000L;
    private long remaining;
    private int polls;
    private int position;

    void reset() {
        remaining = MAX_WORK;
        polls = 0;
        position = -1;
        check();
    }

    void pattern(int queryPosition) {
        position = queryPosition;
        check();
    }

    void spend(int cost) {
        remaining -= cost;
        if (remaining < 0) throw limited();
        if ((++polls & 63) == 0) check();
    }

    void check() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Search cancelled.");
    }

    private QueryException limited() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Search cancelled.");
        return new QueryException("Pattern matching limit reached. Simplify the regular expression or wildcards.", position);
    }

    /** RE2/J 1.8 reads this directly, including its literal-prefix scan. */
    static final class Input implements CharSequence {
        private final CharSequence source;
        private final int start;
        private final int end;
        private final PatternBudget budget;
        private final int cost;

        Input(CharSequence source, PatternBudget budget, int programSize) {
            this(source, 0, source.length(), budget, Math.max(1, programSize));
        }

        private Input(CharSequence source, int start, int end, PatternBudget budget, int cost) {
            this.source = source;
            this.start = start;
            this.end = end;
            this.budget = budget;
            this.cost = cost;
        }

        @Override public int length() {
            budget.spend(1);
            return end - start;
        }

        @Override public char charAt(int index) {
            if (index < 0 || index >= end - start) throw new IndexOutOfBoundsException();
            budget.spend(cost);
            return source.charAt(start + index);
        }

        @Override public CharSequence subSequence(int from, int to) {
            if (from < 0 || to < from || to > end - start) throw new IndexOutOfBoundsException();
            budget.check();
            return new Input(source, start + from, start + to, budget, cost);
        }

        @Override public String toString() {
            // The pinned matcher never needs this for find(). Refuse a future copy bypass.
            throw new IllegalStateException("The pattern engine attempted to copy guarded input.");
        }
    }
}
