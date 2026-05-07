package com.sreality.pipeline.enricher;

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
 * JAR 4 entry point — Estate Enricher.
 *
 * Drains the MongoDB staging queue:
 * For each of the 15 MongoDB collections (one per category):
 * 1. Read all documents from the collection.
 * 2. Enrich each document (spatial join, SCD write, field changes, detail
 * text).
 * 3. Delete each document from MongoDB after successful Postgres write.
 *
 * MongoDB is left empty after each enricher run — it is purely a staging queue.
 *
 * Env vars:
 * MONGO_HOST, MONGO_PORT, MONGO_DATABASE, MONGO_USERNAME, MONGO_PASSWORD
 * PG_* (Postgres connection)
 */
public class EnricherMain {

    private static final Logger log = LoggerFactory.getLogger(EnricherMain.class);

    public static void main(String[] args) {
        log.info("=== JAR 4: Estate Enricher ===");

        String mongoUri = buildMongoUri();
        log.info("Connecting to MongoDB: {}", mongoUri.replaceAll(":([^@/]+)@", ":***@"));

        try (MongoClient mongo = MongoClients.create(mongoUri);
                PostgresConnectionPool pg = new PostgresConnectionPool()) {

            MongoDatabase db = mongo.getDatabase(env("MONGO_DATABASE", "sreality"));
            EnricherLoader loader = new EnricherLoader(pg);

            int totalInserted = 0, totalUpdated = 0, totalSkipped = 0, totalErrors = 0;
            int totalObecMatched = 0, totalCastMatched = 0, totalUnmatched = 0;

            // Process all 15 category collections
            for (int cm = 1; cm <= 5; cm++) {
                for (int ct = 1; ct <= 3; ct++) {
                    String collection = CategoryConfig.collectionName(cm, ct);
                    MongoCollection<Document> col = db.getCollection(collection);

                    long queueSize = col.countDocuments();
                    if (queueSize == 0) {
                        log.debug("Collection {} is empty — skipping", collection);
                        continue;
                    }
                    log.info("Processing {} documents from {}", queueSize, collection);

                    // Read all documents, process, then delete successful ones
                    List<Document> docs = col.find().into(new ArrayList<>());
                    for (Document doc : docs) {
                        WriteResult result = loader.process(doc);
                        switch (result) {
                            case INSERTED -> totalInserted++;
                            case UPDATED -> totalUpdated++;
                            case SKIPPED -> totalSkipped++;
                            case ERROR -> {
                                totalErrors++;
                                continue;
                            }
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
                        // Delete from MongoDB only after successful Postgres write
                        col.deleteOne(new Document("_id", doc.get("_id")));
                    }
                }
            }

            log.info("Enricher complete: inserted={} updated={} skipped={} errors={} " +
                    "obec_matched={} cast_matched={} unmatched={}",
                    totalInserted, totalUpdated, totalSkipped, totalErrors,
                    totalObecMatched, totalCastMatched, totalUnmatched);

            if (totalErrors > 0) {
                log.warn("{} documents left in MongoDB due to errors — will be retried next run.", totalErrors);
            }

        } catch (Exception e) {
            log.error("JAR 4 fatal: {}", e.getMessage(), e);
            System.exit(1);
        }
        log.info("=== JAR 4 finished ===");
    }

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
