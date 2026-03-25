package com.sreality.scraper.scraper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Accumulates statistics and incomplete estate records for a single scrape run.
 * Written to the MongoDB "scrape_runs" collection at the end of each run.
 *
 * Terminology used throughout:
 *
 *   complete      — both listing and detail were successfully fetched and stored
 *   half_success  — listing was fetched (and stored/updated), but detail call
 *                   failed for any reason (410 gone, 404, network error, etc.)
 *                   The document exists in the estate collection but has
 *                   _detail_available = false
 *   listing_error — the listing page itself could not be fetched; affected
 *                   estates were never seen at all in this run
 */
public class ScrapeRunReport {

    // -------------------------------------------------------------------------
    // Run metadata
    // -------------------------------------------------------------------------
    public final String startedAt;
    public       String finishedAt;
    public       String status;          // "completed", "partial" (MAX_ESTATES hit), "failed"

    // -------------------------------------------------------------------------
    // Counters
    // -------------------------------------------------------------------------
    public int totalProcessed    = 0;   // estates visited (excludes skipped)
    public int totalUpserted     = 0;   // fully successful upserts (complete)
    public int totalSkipped      = 0;   // hash unchanged and document complete — not touched
    public int totalGone         = 0;   // HTTP 410 — sold/removed
    public int totalHalfSuccess  = 0;   // detail failed → stored listing data only
    public int totalListingErrors = 0;  // listing page fetch failures
    public int totalRepaired     = 0;   // corruption repairs attempted (detail re-fetched)

    // -------------------------------------------------------------------------
    // Incomplete estate records (half_success)
    // -------------------------------------------------------------------------

    /**
     * One entry per estate whose detail fetch failed.
     * Stored as a sub-document inside the scrape run report.
     */
    public static class IncompleteEstate {
        public final long   hashId;
        public final String collection;
        public final String reason;       // "gone_410", "not_found_404", "http_error", "io_error"
        public final int    httpStatus;   // 0 for IO errors
        public final String errorMessage;
        public final boolean wasExistingDocument; // true = update attempt, false = new estate

        public IncompleteEstate(long hashId, String collection, String reason,
                                int httpStatus, String errorMessage, boolean wasExistingDocument) {
            this.hashId              = hashId;
            this.collection          = collection;
            this.reason              = reason;
            this.httpStatus          = httpStatus;
            this.errorMessage        = errorMessage;
            this.wasExistingDocument = wasExistingDocument;
        }
    }

    // Keep only the last 100 incomplete estates to cap memory usage.
    // The full count is available via totalHalfSuccess.
    private static final int MAX_INCOMPLETE_STORED = 100;
    public final List<IncompleteEstate> incompleteEstates = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public ScrapeRunReport() {
        this.startedAt = Instant.now().toString();
        this.status    = "running";
    }

    // -------------------------------------------------------------------------
    // Mutation helpers (called from EstateScraper)
    // -------------------------------------------------------------------------

    public void recordIncomplete(long hashId, String collection, String reason,
                                 int httpStatus, String errorMessage, boolean wasExisting) {
        // Cap the list size to avoid unbounded memory growth over long runs.
        // totalHalfSuccess always reflects the true count regardless.
        if (incompleteEstates.size() < MAX_INCOMPLETE_STORED) {
            incompleteEstates.add(
                new IncompleteEstate(hashId, collection, reason, httpStatus, errorMessage, wasExisting)
            );
        }
        totalHalfSuccess++;
    }

    public void finish(boolean hitMaxEstates) {
        this.finishedAt = Instant.now().toString();
        this.status     = hitMaxEstates ? "partial" : "completed";
    }

    public int totalErrors() {
        return totalHalfSuccess + totalListingErrors;
    }
}
