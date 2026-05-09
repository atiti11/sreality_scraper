package com.sreality.pipeline.enricher;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.sreality.pipeline.enricher.load.EnricherLoader;
import com.sreality.pipeline.enricher.load.EnricherLoader.WriteResult;
import com.sreality.pipeline.shared.db.PostgresConnectionPool;
import com.sreality.scraper.config.CategoryConfig;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

/**
 * JAR 4 entry point — Estate Enricher.
 *
 * Delta-based: reads only Mongo docs whose {@code _updated_at} is &gt;= the last
 * enrichment run timestamp (stored in {@code pipeline_state} table). After
 * processing, saves the current run's start timestamp back to pipeline_state.
 *
 * Mongo docs are NEVER deleted — Mongo is the long-term cache used by the
 * scraper for change detection. The 7-day TTL pruning is the scraper's job.
 *
 * Active/inactive transitions are handled in {@link EnricherLoader#process}:
 *   - active=true, content unchanged   → SKIPPED
 *   - active=true, content changed     → close old SCD + insert new
 *   - active=true, no open SCD         → INSERT new (rebirth or first time)
 *   - active=false, has open SCD       → close + is_active=false (no insert)
 *   - active=false, no open SCD        → SKIPPED
 *
 * Env: PG_*, MONGO_*. No CLI args.
 */
public class EnricherMain {

    private static final Logger log = LoggerFactory.getLogger(EnricherMain.class);

    private static final String STATE_KEY = "last_enrich_run_at";
    /** Conservative initial bookmark when {@code pipeline_state} is empty. */
    private static final String EPOCH_ISO = "1970-01-01T00:00:00Z";

    public static void main(String[] args) {
        log.info("=== JAR 4: Estate Enricher (delta mode) ===");

        // Capture run start as ISO string — we'll persist it as the new bookmark
        // after a successful run so the next pass picks up from here.
        String runStartedAt = Instant.now().toString();

        String mongoUri = buildMongoUri();
        log.info("Connecting to MongoDB: {}", mongoUri.replaceAll(":([^@/]+)@", ":***@"));

        try (MongoClient mongo = MongoClients.create(mongoUri);
                PostgresConnectionPool pg = new PostgresConnectionPool()) {

            var db = mongo.getDatabase(env("MONGO_DATABASE", "sreality"));
            EnricherLoader loader = new EnricherLoader(pg);

            String since = readState(pg, STATE_KEY, EPOCH_ISO);
            log.info("Processing Mongo docs with _updated_at >= {}", since);

            int totalInserted = 0, totalUpdated = 0, totalSkipped = 0, totalErrors = 0;
            int totalObecMatched = 0, totalCastMatched = 0, totalUnmatched = 0;

            for (int cm = 1; cm <= 5; cm++) {
                for (int ct = 1; ct <= 3; ct++) {
                    String collection = CategoryConfig.collectionName(cm, ct);
                    var col = db.getCollection(collection);

                    int colInserted = 0, colUpdated = 0, colSkipped = 0, colErrors = 0;
                    int processed = 0;

                    try (var cursor = col.find(new Document("_updated_at",
                                                  new Document("$gte", since)))
                                          .batchSize(500)
                                          .cursor()) {
                        while (cursor.hasNext()) {
                            Document doc = cursor.next();
                            processed++;

                            WriteResult result = loader.process(doc);
                            switch (result) {
                                case INSERTED -> { colInserted++; totalInserted++; }
                                case UPDATED  -> { colUpdated++;  totalUpdated++;  }
                                case SKIPPED  -> { colSkipped++;  totalSkipped++;  }
                                case ERROR    -> { colErrors++;   totalErrors++;   }
                            }
                            if (result != WriteResult.ERROR) {
                                if (Boolean.TRUE.equals(doc.getBoolean("_geo_resolved")))
                                    totalObecMatched++;
                                else
                                    totalUnmatched++;
                                if (Boolean.TRUE.equals(doc.getBoolean("_geo_cast_resolved")))
                                    totalCastMatched++;
                            }
                        }
                    }

                    if (processed > 0) {
                        log.info("[{}] processed={} inserted={} updated={} skipped={} errors={}",
                                collection, processed, colInserted, colUpdated, colSkipped, colErrors);
                    }
                }
            }

            log.info("Enricher complete: inserted={} updated={} skipped={} errors={} " +
                    "obec_matched={} cast_matched={} unmatched={}",
                    totalInserted, totalUpdated, totalSkipped, totalErrors,
                    totalObecMatched, totalCastMatched, totalUnmatched);

            if (totalErrors == 0) {
                writeState(pg, STATE_KEY, runStartedAt);
                log.info("Bookmark saved: pipeline_state[{}] = {}", STATE_KEY, runStartedAt);
            } else {
                log.warn("{} errors during enrichment — bookmark NOT advanced; next run retries the same window.",
                        totalErrors);
            }

        } catch (Exception e) {
            log.error("JAR 4 fatal: {}", e.getMessage(), e);
            System.exit(1);
        }
        log.info("=== JAR 4 finished ===");
    }

    // =========================================================================
    // pipeline_state read / write
    // =========================================================================

    private static String readState(PostgresConnectionPool pg, String key, String def)
            throws SQLException {
        String sql = "SELECT state_value FROM " + pg.t("pipeline_state") + " WHERE state_key=?";
        try (Connection c = pg.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String v = rs.getString(1);
                    return (v == null || v.isBlank()) ? def : v;
                }
            }
        }
        return def;
    }

    private static void writeState(PostgresConnectionPool pg, String key, String value)
            throws SQLException {
        String sql = "INSERT INTO " + pg.t("pipeline_state") + " (state_key, state_value, updated_at)"
                + " VALUES (?, ?, now())"
                + " ON CONFLICT (state_key) DO UPDATE SET"
                + "   state_value = EXCLUDED.state_value, updated_at = EXCLUDED.updated_at";
        try (Connection c = pg.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.execute();
        }
    }

    // =========================================================================

    private static String buildMongoUri() {
        String host = env("MONGO_HOST", "localhost");
        String port = env("MONGO_PORT", "27017");
        String user = env("MONGO_USERNAME", "scraper");
        String pass = env("MONGO_PASSWORD", "changeme");
        String db = env("MONGO_DATABASE", "sreality");
        return "mongodb://" + user + ":" + pass + "@" + host + ":" + port + "/" + db
                + "?authSource=" + db;
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return (v != null && !v.isBlank()) ? v : def;
    }
}
