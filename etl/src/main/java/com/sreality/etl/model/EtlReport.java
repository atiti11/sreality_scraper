package com.sreality.etl.model;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Collects statistics for one ETL run. Thread-safe via atomic counters
 * (though the ETL is single-threaded, this is a good habit).
 */
public class EtlReport {

    private final Instant startedAt = Instant.now();
    private Instant       finishedAt;

    public final AtomicLong estatesRead        = new AtomicLong();
    public final AtomicLong estatesSkipped     = new AtomicLong();  // failed isUsable()
    public final AtomicLong estatesInserted    = new AtomicLong();  // new rows in fact table
    public final AtomicLong estatesUpdated     = new AtomicLong();  // new version row added
    public final AtomicLong estatesUnchanged   = new AtomicLong();  // no change detected
    public final AtomicLong spatialMatchCast   = new AtomicLong();  // matched to cast_obce
    public final AtomicLong spatialMatchObec   = new AtomicLong();  // fallback to obec only
    public final AtomicLong spatialNoMatch     = new AtomicLong();  // no spatial match at all
    public final AtomicLong agenciesCreated    = new AtomicLong();

    public void finish() {
        finishedAt = Instant.now();
    }

    public String summary() {
        long durationMs = finishedAt != null
            ? finishedAt.toEpochMilli() - startedAt.toEpochMilli() : 0;
        return String.format(
            """
            ╔═══════════════════════════════════════╗
            ║           ETL RUN SUMMARY             ║
            ╠═══════════════════════════════════════╣
            ║  Estates read:      %8d           ║
            ║  Estates skipped:   %8d           ║
            ║  Rows inserted:     %8d           ║
            ║  Rows updated:      %8d           ║
            ║  Rows unchanged:    %8d           ║
            ║  Spatial → cast:    %8d           ║
            ║  Spatial → obec:    %8d           ║
            ║  Spatial no-match:  %8d           ║
            ║  Agencies created:  %8d           ║
            ║  Duration:        %6d ms          ║
            ╚═══════════════════════════════════════╝
            """,
            estatesRead.get(),
            estatesSkipped.get(),
            estatesInserted.get(),
            estatesUpdated.get(),
            estatesUnchanged.get(),
            spatialMatchCast.get(),
            spatialMatchObec.get(),
            spatialNoMatch.get(),
            agenciesCreated.get(),
            durationMs
        );
    }
}
