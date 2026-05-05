package com.sreality.pipeline.scraper;

import com.fasterxml.jackson.databind.JsonNode;
import com.sreality.pipeline.scraper.db.PostgresLookup;
import com.sreality.pipeline.shared.model.ContentHasher;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Pipeline-aware scraper. Replaces the original EstateScraper with a version
 * that uses Postgres for change detection instead of MongoDB content hashes.
 *
 * MongoDB is now a pure download queue — only changed/new estates are written
 * there. The enricher (JAR 4) drains it and deletes documents after Postgres write.
 *
 * Per-category flow:
 *   1. Load (hash_id → content_hash) from matching Postgres fact table
 *   2. Fetch all listing pages from Sreality API
 *   3. Per estate: compute listing hash → compare → skip or fetch detail + write Mongo
 *   4. After full scan: mark inactive in Postgres any hash_id not seen in API
 */
public class PipelineEstateScraper {

    private static final Logger log = LoggerFactory.getLogger(PipelineEstateScraper.class);

    private static final int[] CATEGORY_MAIN_CBS = {1, 2, 3, 4, 5};
    private static final int[] CATEGORY_TYPE_CBS = {1, 2, 3};

    private final AppConfig          config;
    private final SrealityHttpClient http;
    private final MongoRepository    mongo;
    private final PostgresLookup     pgLookup;
    private       ScrapeRunReport    lastReport;

    public PipelineEstateScraper(AppConfig config, SrealityHttpClient http,
                                  MongoRepository mongo, PostgresLookup pgLookup) {
        this.config   = config;
        this.http     = http;
        this.mongo    = mongo;
        this.pgLookup = pgLookup;
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
        log.info("=== Pipeline scrape run finished: processed={} upserted={} skipped={} ===",
            report.totalProcessed, report.totalUpserted, report.totalSkipped);
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

        // Load current Postgres state for this category
        Map<Long, Long> pgHashes = pgLookup.loadCurrentHashes(propertyType, dealType);
        Set<Long> knownIds = new HashSet<>(pgHashes.keySet());
        Set<Long> seenIds  = new HashSet<>();

        int totalCount;
        try {
            totalCount = fetchTotalCount(cm, ct);
        } catch (IOException e) {
            log.error("Count fetch failed for {}/{}: {}", cm, ct, e.getMessage());
            return;
        }
        if (totalCount == 0) { log.info("No estates found."); return; }

        int totalPages = (int) Math.ceil((double) totalCount / config.perPage);
        log.info("Total: {}, pages: {}", totalCount, totalPages);

        for (int page = 1; page <= totalPages; page++) {
            fetchAndProcessPage(cm, ct, page, collectionName, pgHashes, seenIds, report);
            sleep();
        }

        int inactive = pgLookup.markInactiveBatch(propertyType, dealType, seenIds, knownIds);
        report.totalMarkedInactive += inactive;
        log.info("{} {} done — queued={} skipped={} inactive={}",
            propertyType, dealType, report.totalUpserted, report.totalSkipped, inactive);
    }

    private void fetchAndProcessPage(int cm, int ct, int page, String collection,
                                      Map<Long, Long> pgHashes, Set<Long> seenIds,
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
                processEstate(estate, collection, pgHashes, seenIds, report);
                report.totalProcessed++;
            }
        } catch (IOException e) {
            log.error("Listing page {} failed: {}", page, e.getMessage());
            report.totalListingErrors++;
        }
    }

    private void processEstate(JsonNode node, String collection,
                                Map<Long, Long> pgHashes, Set<Long> seenIds,
                                ScrapeRunReport report) {
        long hashId = node.path("hash_id").asLong();
        if (hashId == 0) return;
        seenIds.add(hashId);

        // Quick hash from listing fields only (price + name as signal)
        long apiHash = computeListingHash(node);
        Long pgHash  = pgHashes.get(hashId);
        if (pgHash != null && pgHash == apiHash) {
            report.totalSkipped++;
            return;
        }

        // Changed or new — fetch detail and queue in MongoDB
        JsonNode detail = fetchDetail(hashId, collection, pgHash != null, report);
        Document doc    = EstateDocumentBuilder.build(node, detail);
        // Store the listing-time hash so enricher knows a full recompute is needed
        doc.append("_pipeline_content_hash", apiHash);
        mongo.upsert(collection, doc);
        report.totalUpserted++;
    }

    /**
     * Listing-time hash: uses price + name as a lightweight change signal.
     * The enricher computes the definitive full hash from all detail fields.
     */
    private static long computeListingHash(JsonNode n) {
        String price = String.valueOf(n.path("price_czk").path("value_raw").asLong());
        String name  = n.path("name").asText("");
        return ContentHasher.compute(price, null, null, null, null, name, null);
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
