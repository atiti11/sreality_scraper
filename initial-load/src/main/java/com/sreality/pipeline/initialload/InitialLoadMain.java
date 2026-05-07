package com.sreality.pipeline.initialload;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.sreality.pipeline.enricher.load.EnricherLoader;
import com.sreality.pipeline.enricher.load.EnricherLoader.WriteResult;
import com.sreality.pipeline.shared.db.PostgresConnectionPool;
import com.sreality.scraper.config.CategoryConfig;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * One-time initial load: reads ALL documents from every MongoDB collection
 * and writes them into the correct Postgres fact tables.
 *
 * KEY DIFFERENCES from the regular enricher (JAR 4):
 *
 * 1. Reads all documents — not just the staging queue.
 * The regular enricher only processes documents recently written by
 * the scraper. This loader reads everything already in MongoDB.
 *
 * 2. Does NOT delete from MongoDB.
 * Run verification queries in Postgres first. Delete MongoDB documents
 * manually once you are satisfied the data is correct.
 *
 * 3. Uses _first_seen_at as valid_from (not today).
 * The EnricherLoader already does this — _first_seen_at is parsed
 * and used as first_seen_date. For the initial load this also becomes
 * the valid_from date, preserving the real scrape history.
 * NOTE: EnricherLoader uses LocalDate.now() as valid_from for new rows.
 * The InitialLoadEnricherLoader subclass overrides this to use
 * _first_seen_at instead, so history is preserved correctly.
 *
 * 4. Skips documents with last_update_corrupted=true if they also have
 * _detail_available=false — these have no enrichable data.
 * They are counted and logged so you can decide what to do with them.
 *
 * 5. Idempotent — if a document is already in Postgres (same content_hash),
 * it is skipped. Safe to re-run after partial failures.
 *
 * Prerequisites:
 * - Postgres schema must exist (start docker-compose.pipeline.yml first)
 * - RUIAN dimensions must be loaded (run jar2-ruian.jar first)
 * The spatial join will fail for any estate whose GPS is outside the
 * loaded RUIAN bounding boxes — those are counted as errors, not skipped.
 *
 * Env vars:
 * MONGO_HOST, MONGO_PORT, MONGO_DATABASE, MONGO_USERNAME, MONGO_PASSWORD
 * PG_HOST, PG_PORT, PG_DATABASE, PG_USERNAME, PG_PASSWORD, PG_SCHEMA
 * INITIAL_LOAD_BATCH_SIZE (default: 500 — documents fetched per Mongo cursor
 * batch)
 * INITIAL_LOAD_DRY_RUN (default: false — set "true" to count without writing)
 */
public class InitialLoadMain {

    private static final Logger log = LoggerFactory.getLogger(InitialLoadMain.class);

    public static void main(String[] args) {
        log.info("=== Initial Load: MongoDB → Postgres ===");
        log.info("NOTE: MongoDB documents will NOT be deleted.");
        log.info("Verify Postgres data before manually cleaning up MongoDB.");

        boolean dryRun = "true".equalsIgnoreCase(env("INITIAL_LOAD_DRY_RUN", "false"));
        int batchSize = Integer.parseInt(env("INITIAL_LOAD_BATCH_SIZE", "500"));

        if (dryRun)
            log.info("DRY RUN MODE — counting documents only, nothing will be written.");

        String mongoUri = buildMongoUri();

        // Counters across all collections
        long totalDocs = 0;
        long totalInserted = 0;
        long totalSkipped = 0;
        long totalErrors = 0;
        long totalCorrupted = 0;
        long totalObecMatched = 0;
        long totalCastMatched = 0;
        long totalUnmatched = 0;

        try (MongoClient mongo = MongoClients.create(mongoUri);
                PostgresConnectionPool pg = new PostgresConnectionPool()) {

            MongoDatabase db = mongo.getDatabase(env("MONGO_DATABASE", "sreality"));
            InitialEnricher enricher = dryRun ? null : new InitialEnricher(pg);

            // Iterate all 15 MongoDB collections in order
            for (int cm = 1; cm <= 5; cm++) {
                for (int ct = 1; ct <= 3; ct++) {
                    String collection = CategoryConfig.collectionName(cm, ct);
                    MongoCollection<Document> col = db.getCollection(collection);
                    long count = col.countDocuments();

                    if (count == 0) {
                        log.info("[{}] empty — skipping", collection);
                        continue;
                    }
                    log.info("[{}] {} documents to process", collection, count);

                    long colInserted = 0, colSkipped = 0, colErrors = 0, colCorrupted = 0;

                    // Stream with batchSize to avoid loading all into memory at once
                    try (var cursor = col.find().batchSize(batchSize).cursor()) {
                        while (cursor.hasNext()) {
                            Document doc = cursor.next();
                            totalDocs++;

                            // Skip documents with no enrichable detail
                            Boolean corrupted = doc.getBoolean("last_update_corrupted");
                            Boolean detailAvail = doc.getBoolean("_detail_available");
                            if (Boolean.TRUE.equals(corrupted) && !Boolean.TRUE.equals(detailAvail)) {
                                colCorrupted++;
                                totalCorrupted++;
                                continue;
                            }

                            if (dryRun) {
                                colInserted++; // count as would-be inserts in dry run
                                continue;
                            }

                            WriteResult result = enricher.process(doc);
                            switch (result) {
                                case INSERTED -> colInserted++;
                                case UPDATED -> colInserted++; // treat updates same as inserts for reporting
                                case SKIPPED -> colSkipped++;
                                case ERROR -> colErrors++;
                            }
                            if (result != WriteResult.ERROR) {
                                boolean geoResolved = Boolean.TRUE.equals(doc.getBoolean("_geo_resolved"));
                                if (geoResolved)
                                    totalObecMatched++;
                                if (Boolean.TRUE.equals(doc.getBoolean("_geo_cast_resolved")))
                                    totalCastMatched++;
                                if (!geoResolved)
                                    totalUnmatched++;
                            }
                        }
                    }

                    totalInserted += colInserted;
                    totalSkipped += colSkipped;
                    totalErrors += colErrors;

                    log.info("[{}] done — written={} skipped={} errors={} corrupted_skipped={}",
                            collection, colInserted, colSkipped, colErrors, colCorrupted);
                }
            }

        } catch (Exception e) {
            log.error("Initial load failed: {}", e.getMessage(), e);
            System.exit(1);
        }

        log.info("=== Initial Load Complete ===");
        log.info("Total documents seen:    {}", totalDocs);
        log.info("Written to Postgres:     {}", totalInserted);
        log.info("Already in Postgres:     {}", totalSkipped);
        log.info("Obec matched:            {}", totalObecMatched);
        log.info("Cast obce matched:       {}", totalCastMatched);
        log.info("Unmatched geography:     {}", totalUnmatched);
        log.info("Errors (GPS/schema):     {}", totalErrors);
        log.info("Corrupted docs skipped:  {}", totalCorrupted);
        log.info("");
        log.info("Next steps:");
        log.info("  1. Verify: SELECT COUNT(*) FROM fact_apartment_sale;  -- etc.");
        log.info("  2. Check errors in log — most will be GPS mismatches needing RUIAN.");
        log.info("  3. Once satisfied, you can delete MongoDB documents.");

        if (totalErrors > 0) {
            log.warn("{} documents failed — check logs for GPS/spatial join issues.", totalErrors);
            log.warn("These estates had no Czech GPS or fell outside RUIAN bounding boxes.");
        }
    }

    // -------------------------------------------------------------------------

    private static String buildMongoUri() {
        String host = env("MONGO_HOST", "localhost");
        String port = env("MONGO_PORT", "27017");
        String user = env("MONGO_USERNAME", "scraper");
        String pass = env("MONGO_PASSWORD", "changeme");
        String db = env("MONGO_DATABASE", "sreality");
        return "mongodb://" + user + ":" + pass + "@" + host + ":" + port
                + "/" + db + "?authSource=" + db;
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return (v != null && !v.isBlank()) ? v : def;
    }
}
