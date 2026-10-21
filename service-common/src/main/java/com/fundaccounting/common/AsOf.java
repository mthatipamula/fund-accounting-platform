package com.fundaccounting.common;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** Parses the as-of date and known-at instant that every read endpoint accepts. */
public final class AsOf {
    private AsOf() {}

    public static LocalDate date(String value) {
        if (value == null || value.isBlank()) return LocalDate.now();
        try { return LocalDate.parse(value); }
        catch (DateTimeParseException e) { throw new IllegalArgumentException("asOf must be a date like 2026-03-31"); }
    }

    /** Empty means "now": the latest corrections. */
    public static Instant knownAt(String value) {
        if (value == null || value.isBlank()) return Instant.now();
        try { return Instant.parse(value); }
        catch (DateTimeParseException e) { throw new IllegalArgumentException("knownAt must be an instant like 2026-04-02T09:00:00Z"); }
    }

    /** Like {@link #knownAt} but empty stays empty, so the caller can tell "not given" from "now". */
    public static Instant knownAtOrNull(String value) {
        return value == null || value.isBlank() ? null : knownAt(value);
    }
}
