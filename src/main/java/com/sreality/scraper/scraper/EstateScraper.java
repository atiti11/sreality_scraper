package com.sreality.scraper.scraper;

import com.fasterxml.jackson.databind.JsonNode;
import com.sreality.scraper.config.AppConfig;
import com.sreality.scraper.config.CategoryConfig;
import com.sreality.scraper.db.MongoRepository;
import com.sreality.scraper.http.SrealityHttpClient;
import com.sreality.scraper.http.SrealityHttpClient.SrealityHttpException;
import com.sreality.scraper.model.EstateDocumentBuilder;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * Stateless scraper — fetches all estates from sreality.cz and upserts them
 * into MongoDB. Designed to be run periodically (by cron / docker restart).
 *
 * Logic per estate:
 *  1. Fetch listing page (overall GET)
 *  2. Compute content hash from listing fields
 *  3. If hash matches what is already stored → skip (nothing changed)
 *  4. Otherwise fetch detail endpoint
 *  5. Build combined document and upsert
 *  6. If detail fetch fails → store listing data only, record as incomplete
 *
 * At the end of the run a ScrapeRunReport is saved to the "scrape_runs" collection.
 *
 * All category × deal-type combinations are scraped:
 *   apartments, houses, land, commercial, other  ×  sale, rent, auction
 */
public class EstateScraper {

    private static final Logger log = LoggerFactory.getLogger(EstateScraper.class);

    // Polite delay between HTTP requests (ms) — overridable for tests
    private final long requestDelayMs;

    // All combinations scraped in production
    private static final int[] DEFAULT_CATEGORY_MAIN_CBS = {1, 2, 3, 4, 5};
    private static final int[] DEFAULT_CATEGORY_TYPE_CBS = {1, 2, 3};

    private final AppConfig          config;
    private final SrealityHttpClient http;
    private final MongoRepository    mongo;
    private final int[]              categoryMainCbs;
    private final int[]              categoryTypeCbs;

    // Set after run() completes — accessible via getLastReport()
    private ScrapeRunReport lastReport;

    /** Production constructor — scrapes all 15 category combinations with configurable delay. */
    public EstateScraper(AppConfig config, SrealityHttpClient http, MongoRepository mongo) {
        this(config, http, mongo, DEFAULT_CATEGORY_MAIN_CBS, DEFAULT_CATEGORY_TYPE_CBS, config.requestDelayMs);
    }

    /** Test constructor — allows restricting categories and setting delay to 0 for speed. */
    public EstateScraper(AppConfig config, SrealityHttpClient http, MongoRepository mongo,
                         int[] categoryMainCbs, int[] categoryTypeCbs) {
        this(config, http, mongo, categoryMainCbs, categoryTypeCbs, 300L);
    }

    /** Full constructor. */
    public EstateScraper(AppConfig config, SrealityHttpClient http, MongoRepository mongo,
                         int[] categoryMainCbs, int[] categoryTypeCbs, long requestDelayMs) {
        this.config          = config;
        this.http            = http;
        this.mongo           = mongo;
        this.categoryMainCbs = categoryMainCbs;
        this.categoryTypeCbs = categoryTypeCbs;
        this.requestDelayMs  = requestDelayMs;
    }

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    public void run() {
        log.info("=== Scrape run started ===");
        log.info("Config: {}", config);

        ScrapeRunReport report = new ScrapeRunReport();
        boolean hitMaxEstates  = false;

        outer:
        for (int categoryMainCb : categoryMainCbs) {
            for (int categoryTypeCb : categoryTypeCbs) {

                scrapeCategory(categoryMainCb, categoryTypeCb, report);

                if (config.hasMaxEstatesLimit() && report.totalProcessed >= config.maxEstates) {
                    log.info("MAX_ESTATES={} reached — stopping early.", config.maxEstates);
                    hitMaxEstates = true;
                    break outer;
                }
            }
        }

        report.finish(hitMaxEstates);
        printSummary(report);

        // Persist the run report to MongoDB
        try {
            mongo.saveReport(report);
        } catch (Exception e) {
            log.error("Failed to save scrape run report to MongoDB: {}", e.getMessage());
        }

        this.lastReport = report;
        log.info("=== Scrape run finished ===");
    }

    /** Returns the report from the most recent run(), or null if run() hasn't been called yet. */
    public ScrapeRunReport getLastReport() {
        return lastReport;
    }

    // -------------------------------------------------------------------------
    // Per-category scrape
    // -------------------------------------------------------------------------

    private void scrapeCategory(int categoryMainCb, int categoryTypeCb, ScrapeRunReport report) {
        String collectionName = CategoryConfig.collectionName(categoryMainCb, categoryTypeCb);
        String propertyLabel  = CategoryConfig.PROPERTY_TYPE.containsKey(categoryMainCb)
            ? CategoryConfig.PROPERTY_TYPE.get(categoryMainCb).label() : "unknown";
        String dealLabel      = CategoryConfig.DEAL_TYPE.containsKey(categoryTypeCb)
            ? CategoryConfig.DEAL_TYPE.get(categoryTypeCb).label() : "unknown";

        log.info("--- Scraping: {} {} (collection: {}) ---",
            propertyLabel, dealLabel, collectionName);

        // Step 1: get total count for this category
        int totalCount;
        try {
            totalCount = fetchTotalCount(categoryMainCb, categoryTypeCb);
        } catch (IOException e) {
            log.error("Failed to get count for {}/{}: {}", categoryMainCb, categoryTypeCb, e.getMessage());
            report.totalListingErrors++;
            return;
        }

        if (totalCount == 0) {
            log.info("No estates found for {} {}", propertyLabel, dealLabel);
            return;
        }

        // How many estates can we still process given the global MAX_ESTATES budget?
        int remaining    = config.hasMaxEstatesLimit()
            ? config.maxEstates - report.totalProcessed
            : Integer.MAX_VALUE;
        int effectiveMax = Math.min(totalCount, remaining);
        int totalPages   = (int) Math.ceil((double) effectiveMax / config.perPage);

        log.info("Total in category: {}, will process: {}, pages: {} (perPage={})",
            totalCount, effectiveMax, totalPages, config.perPage);

        int categoryProcessed = 0;

        // Step 2: iterate pages
        for (int page = 1; page <= totalPages; page++) {
            if (categoryProcessed >= effectiveMax) break;

            // Process each estate immediately and discard — do not hold the full
            // page list in memory while processing individual estates.
            int[] counts = {0}; // effectively final wrapper
            fetchAndProcessListingPage(
                categoryMainCb, categoryTypeCb, page, collectionName, report,
                estateNode -> {
                    if (categoryProcessed + counts[0] >= effectiveMax) return;
                    processEstate(estateNode, collectionName, report);
                    report.totalProcessed++;
                    counts[0]++;
                }
            );
            categoryProcessed += counts[0];

            log.info("Page {}/{} done — processed: {}, upserted: {}, skipped: {}, " +
                     "half-success: {}, gone: {}",
                page, totalPages,
                report.totalProcessed, report.totalUpserted, report.totalSkipped,
                report.totalHalfSuccess, report.totalGone);

            sleep();
        }

        log.info("Category done: {} {} → categoryProcessed={}, totalProcessed={}",
            propertyLabel, dealLabel, categoryProcessed, report.totalProcessed);
    }

    // -------------------------------------------------------------------------
    // Per-estate processing
    // -------------------------------------------------------------------------

    private void processEstate(JsonNode estateNode, String collectionName, ScrapeRunReport report) {
        long hashId = estateNode.path("hash_id").asLong();
        if (hashId == 0) {
            log.warn("Estate with missing hash_id — skipping");
            return;
        }

        // Compute content hash from listing fields only.
        // Uses EstateDocumentBuilder.computeContentHash() to guarantee the hash
        // is identical to what gets stored in the document by build().
        String contentHash = EstateDocumentBuilder.computeContentHash(estateNode);

        // Skip if nothing changed and the document is complete.
        // If the document exists but last_update_corrupted=true, isUnchanged()
        // returns false even when the hash matches, forcing a repair attempt.
        if (mongo.isUnchanged(collectionName, hashId, contentHash)) {
            report.totalSkipped++;
            log.debug("Skipping estate {} — content hash unchanged and document complete", hashId);
            return;
        }

        // Check whether this estate already exists in DB (before detail fetch)
        // so we can correctly classify a detail failure as update vs. new insert,
        // and to detect the corruption-repair case for logging.
        boolean wasExisting = mongo.exists(collectionName, hashId);
        if (wasExisting && mongo.isCorrupted(collectionName, hashId)) {
            log.info("Repairing corrupted estate {} in {} — re-fetching detail", hashId, collectionName);
            report.totalRepaired++;
        }

        // Fetch detail
        DetailResult detail = fetchDetail(hashId, collectionName, wasExisting, report);

        // Build and upsert combined document (detailNode may be null)
        Document doc = EstateDocumentBuilder.build(estateNode, detail.node);
        mongo.upsert(collectionName, doc);
        report.totalUpserted++;
    }

    // -------------------------------------------------------------------------
    // HTTP helpers
    // -------------------------------------------------------------------------

    private int fetchTotalCount(int categoryMainCb, int categoryTypeCb) throws IOException {
        String url = config.srealityBaseUrl + "/count"
            + "?category_main_cb=" + categoryMainCb
            + "&category_type_cb=" + categoryTypeCb
            + "&locality_country_id=10001";
        JsonNode response = http.get(url);
        return response.path("result_size").asInt(0);
    }

    /**
     * Fetches one listing page and immediately passes each estate to the consumer.
     * This avoids holding the full page (100 JsonNode objects) in memory
     * while processing individual estates one by one.
     */
    private void fetchAndProcessListingPage(int categoryMainCb, int categoryTypeCb,
                                             int page, String collectionName,
                                             ScrapeRunReport report,
                                             Consumer<JsonNode> estateConsumer) {
        String url = config.srealityBaseUrl
            + "?category_main_cb=" + categoryMainCb
            + "&category_type_cb=" + categoryTypeCb
            + "&locality_country_id=10001"
            + "&per_page=" + config.perPage
            + "&page=" + page;
        try {
            JsonNode response = http.get(url);
            JsonNode estates  = response.path("_embedded").path("estates");
            if (estates.isArray()) {
                for (JsonNode estate : estates) {
                    estateConsumer.accept(estate);
                }
            }
            // response and estates go out of scope here and are immediately GC-eligible
        } catch (IOException e) {
            log.error("Failed to fetch listing page {}: {}", url, e.getMessage());
            report.totalListingErrors++;
        }
    }

    /**
     * Holds the result of a detail fetch attempt.
     * node is null if the fetch failed; the failure is already recorded in the report.
     */
    private record DetailResult(JsonNode node) {}

    /**
     * Fetch the detail for a single estate.
     * Always returns a DetailResult — node is null if fetch failed.
     *
     * Failures are recorded in the ScrapeRunReport as incomplete estates.
     *
     * Note: sreality returns 410 Gone when an estate appears in the search index
     * but has already been sold/withdrawn by the time the detail is fetched.
     * The response body is always {"logged_in": false} regardless — this is
     * sreality's generic minimal 410 response and has nothing to do with auth.
     */
    private DetailResult fetchDetail(long hashId, String collectionName,
                                     boolean wasExisting, ScrapeRunReport report) {
        String url = config.srealityBaseUrl + "/" + hashId;
        try {
            sleep();
            JsonNode node = http.get(url);
            return new DetailResult(node);

        } catch (SrealityHttpException e) {
            String reason;
            if (e.isGone()) {
                reason = "gone_410";
                report.totalGone++;
                log.warn("Estate {} is gone (HTTP 410 — sold or removed) — storing listing data only", hashId);
            } else if (e.isNotFound()) {
                reason = "not_found_404";
                log.warn("Estate {} returned 404 — storing listing data only", hashId);
            } else {
                reason = "http_error";
                log.error("Detail fetch failed for estate {} (HTTP {}): {}",
                    hashId, e.getStatusCode(), e.getMessage());
            }
            report.recordIncomplete(hashId, collectionName, reason,
                e.getStatusCode(), e.getMessage(), wasExisting);
            return new DetailResult(null);

        } catch (IOException e) {
            log.error("Detail fetch IO error for estate {}: {}", hashId, e.getMessage());
            report.recordIncomplete(hashId, collectionName, "io_error",
                0, e.getMessage(), wasExisting);
            return new DetailResult(null);
        }
    }

    private void sleep() {
        if (requestDelayMs <= 0) return;
        try {
            Thread.sleep(requestDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // -------------------------------------------------------------------------
    // Summary
    // -------------------------------------------------------------------------

    private void printSummary(ScrapeRunReport report) {
        log.info("╔══════════════════════════════════════╗");
        log.info("║           SCRAPE SUMMARY             ║");
        log.info("╠══════════════════════════════════════╣");
        log.info(String.format("║  Processed:      %8d            ║", report.totalProcessed));
        log.info(String.format("║  Upserted:       %8d            ║", report.totalUpserted));
        log.info(String.format("║  Skipped:        %8d            ║", report.totalSkipped));
        log.info(String.format("║  Gone (410):     %8d            ║", report.totalGone));
        log.info(String.format("║  Half-success:   %8d            ║", report.totalHalfSuccess));
        log.info(String.format("║  Repaired:       %8d            ║", report.totalRepaired));
        log.info(String.format("║  Listing errors: %8d            ║", report.totalListingErrors));
        log.info(String.format("║  Total errors:   %8d            ║", report.totalErrors()));
        log.info("╚══════════════════════════════════════╝");

        if (report.totalGone > 0) {
            log.warn("{} estates gone (HTTP 410 — sold/removed between listing and detail fetch)",
                report.totalGone);
        }
        int otherErrors = report.totalHalfSuccess - report.totalGone;
        if (otherErrors > 0) {
            log.warn("{} estates had other detail fetch errors (stored with listing data only)",
                otherErrors);
        }
        if (report.totalHalfSuccess > 0) {
            log.warn("{} total incomplete estates recorded in 'scrape_runs' collection",
                report.totalHalfSuccess);
        }
    }
}
