package com.sreality.etl.extract;

import com.mongodb.MongoClientSettings;
import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.sreality.etl.config.EtlConfig;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Extracts estate documents from MongoDB by streaming them in batches.
 *
 * Memory design: documents are NOT loaded all at once. Instead, a cursor
 * streams them in configurable batch sizes (default 500). The consumer
 * callback processes each batch and discards it before the next is read.
 * This keeps heap usage flat regardless of collection size.
 *
 * Connection pool is limited to 1 since ETL is single-threaded.
 */
public class MongoExtractor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MongoExtractor.class);

    private final MongoClient   client;
    private final MongoDatabase database;
    private final int           batchSize;

    public MongoExtractor(EtlConfig config) {
        MongoClientSettings settings = MongoClientSettings.builder()
            .applyConnectionString(new ConnectionString(config.mongoUri()))
            .applyToConnectionPoolSettings(b -> b.maxSize(1).minSize(0))
            .build();
        this.client   = MongoClients.create(settings);
        this.database = client.getDatabase(config.mongoDatabase);
        this.batchSize = config.batchSize;
        log.info("MongoExtractor connected to {}/{}", config.mongoHost, config.mongoDatabase);
    }

    /**
     * Streams all documents from the named collection in batches.
     * The consumer receives each batch as a List<Document>.
     * After the consumer returns, the batch list is discarded (eligible for GC).
     *
     * Only reads documents where active=true OR active field is absent
     * (to include estates the scraper has not yet marked inactive).
     * Also includes inactive estates so we can set valid_to on their
     * current snapshot row in the warehouse.
     */
    public void streamCollection(String collectionName, Consumer<List<Document>> batchConsumer) {
        MongoCollection<Document> col = database.getCollection(collectionName);
        long total = col.countDocuments();
        if (total == 0) {
            log.debug("Collection '{}' is empty — skipping", collectionName);
            return;
        }
        log.info("Streaming '{}': {} documents, batch size {}", collectionName, total, batchSize);

        // Projection: only load the fields we actually use — saves memory and bandwidth
        Document projection = new Document()
            .append("hash_id",               1)
            .append("sreality_url",          1)
            .append("property_type",         1)
            .append("deal_type",             1)
            .append("sub_category",          1)
            .append("price_czk_value",       1)
            .append("price_raw",             1)
            .append("usable_area_m2",        1)
            .append("area_uzitna_plocha",    1)
            .append("gps_lat",               1)
            .append("gps_lon",               1)
            .append("count_podlazi_umisteni",1)
            .append("count_pocet_podlazi",   1)
            .append("ownership_label",       1)
            .append("building_type_label",   1)
            .append("building_condition_label", 1)
            .append("energy_efficiency_label",  1)
            .append("is_new",                1)
            .append("is_furnished",          1)
            .append("has_balcony",           1)
            .append("has_terrace",           1)
            .append("has_loggia",            1)
            .append("has_cellar",            1)
            .append("has_elevator",          1)
            .append("has_garage",            1)
            .append("has_parking",           1)
            .append("has_pool",              1)
            .append("is_barrier_free",       1)
            .append("active",                1)
            .append("_first_seen_at",        1)
            .append("advert_images_count",   1)
            .append("has_floor_plan",        1)
            .append("has_video",             1)
            .append("agency",                1)
            .append("_id",                   0);  // exclude MongoDB internal _id

        long processed = 0;
        List<Document> batch = new ArrayList<>(batchSize);

        try (MongoCursor<Document> cursor = col.find()
                .projection(projection)
                .batchSize(batchSize)
                .iterator()) {

            while (cursor.hasNext()) {
                batch.add(cursor.next());
                processed++;

                if (batch.size() >= batchSize) {
                    batchConsumer.accept(batch);
                    batch = new ArrayList<>(batchSize);  // discard old batch
                    log.debug("  processed {}/{}", processed, total);
                }
            }

            // Process remaining
            if (!batch.isEmpty()) {
                batchConsumer.accept(batch);
            }
        }

        log.info("Finished streaming '{}': {} documents processed", collectionName, processed);
    }

    @Override
    public void close() {
        client.close();
        log.info("MongoExtractor disconnected");
    }
}
