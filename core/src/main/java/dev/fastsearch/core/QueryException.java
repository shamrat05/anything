package dev.fastsearch.core;

/** A query error suitable for showing next to the search field. */
public final class QueryException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;
    /** Zero-based query offset, or -1 for an error applying to the whole query. */
    public final int position;

    public QueryException(String message) {
        this(message, -1);
    }

    public QueryException(String message, int position) {
        super(position < 0 ? message : message + " (at character " + (position + 1) + ")");
        this.position = position;
    }
}
