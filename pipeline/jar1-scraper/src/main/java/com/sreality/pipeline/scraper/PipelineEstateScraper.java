package com.sreality.pipeline.scraper;

import com.fasterxml.jackson.databind.JsonNode;
import com.sreality.scraper.config.AppConfig;
import com.sreality.scraper.config.CategoryConfig;
import com.sreality.scraper.db.MongoRepository;
import com.sreality.scraper.http.SrealityHttpClient;
import com.sreality.scraper.http.SrealityHttpClient.SrealityHttpException;
import com.sreality.scraper.model.EstateDocumentBuilder;
import com.sreality.scraper.scraper.ScrapeRunReport;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * Pipeline scraper. Same logic as the original {@code EstateScraper} (cron mode):
 * MongoDB is the cache for change detection. After each category sweep, estates
 * not seen in this run are marked inactive in Mongo, and docs older than 7 days
 * since their last sighting are pruned from Mongo entirely.
 *
 * Postgres is intentionally NOT touched here. All Postgres updates flow through
 * jar4-enricher, which reads docs whose {@code _updated_at} bumped since the last
 * enrichment run and applies the delta as SCD writes.
 *
 * Per-category flow:
 *   1. Fetch listing pages from Sreality
 *   2. For each estate:
 *      a) compute listing-content-hash (price + name + listing labels via
 *         {@link EstateDocumentBuilder#computeContentHash})
 *      b) {@code mongo.isUnchanged} → SKIP detail fetch, just touch _last_seen_at
 *         (rebirth handling: if the doc was inactive, _updated_at also bumps)
 *      c) hash differs OR doc was corrupted → fetch detail + upsert to Mongo
 *   3. After the full category: mark estates not seen as inactive
 *      (sets active=false, _inactive_since=now, _updated_at=now)
 *   4. Cleanup: delete Mongo docs whose _last_seen_at &lt; now − 7 days
 */
public class PipelineEstateScraper {

    private static final Logger log = LoggerFactory.getLogger(PipelineEstateScraper.class);

    private static final int[] CATEGORY_MAIN_CBS = {1, 2, 3, 4, 5};
    private static final int[] CATEGORY_TYPE_CBS = {1, 2, 3};

    /** TTL for inactive Mongo docs — once {@code now − _last_seen_at &gt; this}, the doc is dropped. */
    private static final int MONGO_TTL_DAYS = 7;

    private final AppConfig          config;
    private final SrealityHttpClient http;
    private final MongoRepository    mongo;
    private       ScrapeRunReport    lastReport;

    public PipelineEstateScraper(AppConfig config, SrealityHttpClient http, MongoRepository mongo) {
        this.config = config;
        this.http   = http;
        this.mongo  = mongo;
    }

    public void run() {
        log.info("=== Pipeline scrape run started ===");
        ScrapeRunReport report = new ScrapeRunReport();
        outer:
        for (int cm : CATEGORY_MAIN_CBS) {
            for (int ct : CATEGORY_TYPE_CBS) {
                if (config.hasMaxEstatesLimit() && report.totalProcessed >= config.maxEstates) {
                    log.info("MAX_ESTATES={} reached — stopping.", config.maxEstates);
                    break outer;
                }
                scrapeCategory(cm, ct, report);
            }
        }
        report.finish(false);
        this.lastReport = report;
        log.info("=== Pipeline scrape run finished — processed={} upserted={} skipped={} inactive={} ===",
                report.totalProcessed, report.totalUpserted, report.totalSkipped,
                report.totalMarkedInactive);
    }

    public ScrapeRunReport getLastReport() { return lastReport; }

    // -------------------------------------------------------------------------

    private void scrapeCategory(int cm, int ct, ScrapeRunReport report) {
        var propEntry = CategoryConfig.PROPERTY_TYPE.get(cm);
        var dealEntry = CategoryConfig.DEAL_TYPE.get(ct);
        if (propEntry == null || dealEntry == null) return;

        String propertyType   = propEntry.label();
        String dealType       = dealEntry.label();
        String collectionName = CategoryConfig.collectionName(cm, ct);
        log.info("--- {} {} ---", propertyType, dealType);

        int totalCount;
        try {
            totalCount = fetchTotalCount(cm, ct);
        } catch (IOException e) {
            log.error("Count fetch failed for {}/{}: {}", cm, ct, e.getMessage());
            report.totalListingErrors++;
            return;
        }
        if (totalCount == 0) { log.info("No estates found."); return; }

        int totalPages = (int) Math.ceil((double) totalCount / config.perPage);
        log.info("Total: {}, pages: {}", totalCount, totalPages);

        for (int page = 1; page <= totalPages; page++) {
            fetchAndProcessPage(cm, ct, page, collectionName, report);
            sleep();
        }

        // Mark estates not seen in this run as inactive (also bumps _updated_at).
        long markedInactive = mongo.markInactiveNotSeenSince(collectionName, report.startedAt);
        report.totalMarkedInactive += markedInactive;

        // Drop docs the scraper hasn't seen for over MONGO_TTL_DAYS days.
        String cutoff = Instant.now().minus(MONGO_TTL_DAYS, ChronoUnit.DAYS)
                .atOffset(ZoneOffset.UTC).toInstant().toString();
        long pruned = mongo.deleteStaleDocs(collectionName, cutoff);

        log.info("{} {} done — upserted={} skipped={} inactive={} pruned={}",
                propertyType, dealType, report.totalUpserted, report.totalSkipped,
                markedInactive, pruned);
    }

    private void fetchAndProcessPage(int cm, int ct, int page, String collection,
                                      ScrapeRunReport report) {
        String url = config.srealityBaseUrl
            + "?category_main_cb=" + cm
            + "&category_type_cb=" + ct
            + "&locality_country_id=10001"
            + "&per_page=" + config.perPage
            + "&page=" + page;
        try {
            JsonNode response = http.get(url);
            JsonNode estates  = response.path("_embedded").path("estates");
            if (!estates.isArray()) return;
            for (JsonNode estate : estates) {
                processEstate(estate, collection, report);
                report.totalProcessed++;
            }
        } catch (IOException e) {
            log.error("Listing page {} failed: {}", page, e.getMessage());
            report.totalListingErrors++;
        }
    }

    private void processEstate(JsonNode estateNode, String collectionName, ScrapeRunReport report) {
        long hashId = estateNode.path("hash_id").asLong();
        if (hashId == 0) return;

        // Listing-content hash (price + name + listing labels).
        // Identical to the cron scraper's hash so both can share the same Mongo.
        String contentHash = EstateDocumentBuilder.computeContentHash(estateNode);

        // Fast path: doc already in Mongo with same hash AND not corrupted.
        if (mongo.isUnchanged(collectionName, hashId, contentHash)) {
            report.totalSkipped++;
            // touchLastSeen returns true if the doc was inactive (=> rebirth);
            // in that case it also bumps _updated_at and clears _inactive_since,
            // so the next enricher run opens a fresh SCD row in Postgres.
            mongo.touchLastSeen(collectionName, hashId);
            return;
        }

        boolean wasExisting = mongo.exists(collectionName, hashId);
        if (wasExisting && mongo.isCorrupted(collectionName, hashId)) {
            log.info("Repairing corrupted estate {} in {}", hashId, collectionName);
            report.totalRepaired++;
        }

        // Detail fetch + upsert. MongoRepository.upsert sets _updated_at=now,
        // preserves _first_seen_at, increments _update_count, writes history delta.
        JsonNode detail = fetchDetail(hashId, collectionName, wasExisting, report);
        Document doc    = EstateDocumentBuilder.build(estateNode, detail);
        mongo.upsert(collectionName, doc);
        report.totalUpserted++;
    }

    private JsonNode fetchDetail(long hashId, String collection,
                                  boolean existing, ScrapeRunReport report) {
        try {
            sleep();
            return http.get(config.srealityBaseUrl + "/" + hashId);
        } catch (SrealityHttpException e) {
            if (e.isGone()) report.totalGone++;
            report.recordIncomplete(hashId, collection,
                e.isGone() ? "gone_410" : "http_error",
                e.getStatusCode(), e.getMessage(), existing);
            return null;
        } catch (IOException e) {
            report.recordIncomplete(hashId, collection, "io_error", 0, e.getMessage(), existing);
            return null;
        }
    }

    private int fetchTotalCount(int cm, int ct) throws IOException {
        String url = config.srealityBaseUrl + "/count"
            + "?category_main_cb=" + cm + "&category_type_cb=" + ct
            + "&locality_country_id=10001";
        return http.get(url).path("result_size").asInt(0);
    }

    private static final java.util.Random JITTER = new java.util.Random();
    private void sleep() {
        long ms = config.requestDelayMs;
        if (ms <= 0) return;
        try {
            long jitter = (long)(ms * 0.5 * (JITTER.nextDouble() * 2 - 1));
            Thread.sleep(ms + jitter);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
