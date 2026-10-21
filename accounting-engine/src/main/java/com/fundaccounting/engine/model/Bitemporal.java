package com.fundaccounting.engine.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * Every business event carries two times:
 * <ul>
 *   <li><b>effective date</b>: the date the event belongs to in the books (e.g. trade date);</li>
 *   <li><b>recorded at</b> / <b>superseded at</b>: when the system learned it, and when a newer version replaced it.</li>
 * </ul>
 * A correction never overwrites history: it supersedes the old version, so any report can be reproduced exactly as it
 * looked at an earlier moment ("as known at").
 */
public interface Bitemporal {
    String id();               // stable business id shared by all versions
    LocalDate effectiveDate();
    Instant recordedAt();
    Instant supersededAt();    // null while this is the current version
    boolean cancelled();       // a cancellation is recorded as a final version with cancelled = true

    default boolean knownAt(Instant knownAt) {
        return !recordedAt().isAfter(knownAt) && (supersededAt() == null || supersededAt().isAfter(knownAt)) && !cancelled();
    }

    /** The versions that were current at a moment, excluding cancelled events. */
    static <T extends Bitemporal> List<T> asKnownAt(Collection<T> versions, Instant knownAt) {
        return versions.stream().filter(v -> v.knownAt(knownAt)).toList();
    }
}
