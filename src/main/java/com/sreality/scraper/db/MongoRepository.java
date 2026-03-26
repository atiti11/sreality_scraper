package com.sreality.scraper.db;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Updates;
import com.sreality.scraper.config.AppConfig;
import com.sreality.scraper.scraper.ScrapeRunReport;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MongoDB access layer.
 *
 * - One MongoClient is shared for the lifetime of the scrape run.
 * - Collections are created on demand and indexed on first use.
 * - Upsert by hash_id (not MongoDB _id) so re-runs are idempotent.
 *
 * History collections:
 *   Every estate collection <col> has a companion <col>_history collection.
 *   Each history document is a snapshot of the fields that CHANGED compared
 *   to the previous version of the document (delta / diff approach, Option A:
 *   nested objects like "seller" and "images" are stored as atomic blobs).
 *
 *   Trigger for writing a history entry:
 *     - The content hash changed (name / price / labels differ)  → full delta written
 *     - The content hash is the same BUT last_update_corrupted was true and the new
 *       document has last_update_corrupted=false  → detail-only delta written
 *       (this covers the corruption-repair case where listing didn't change but
 *       detail was finally fetched successfully)
 *
 *   History is NOT written when:
 *     - The estate is brand-new (first insert) — no previous state to diff against
 *     - The detail fetch failed again on a corrupted document — no new information
 */
public class MongoRepository implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MongoRepository.class);

    // Fields that come purely from the listing endpoint and are always fresh.
    // Used in two places:
    //   1. preserveDetailFields() — these are NOT backfilled from the old document
    //   2. computeDelta()         — these are still diffed (price change is important)
    private static final Set<String> LISTING_FIELDS = Set.of(
        "hash_id", "sreality_url",
        "category_main_cb", "category_type_cb", "property_type", "deal_type",
        "category_sub_cb", "sub_category",
        "name", "locality", "seo_locality",
        "price_raw", "price_czk_value", "price_czk_unit", "price_czk_name",
        "gps_lat", "gps_lon",
        "is_auction", "has_floor_plan", "has_panorama", "has_video",
        "has_matterport", "is_new", "is_attractive", "exclusively_at_rk",
        "auction_price", "advert_images_count",
        "property_features", "nearby_poi",
        "property_features_full", "nearby_poi_full",
        "agency"
    );

    private final MongoClient   client;
    private final MongoDatabase database;

    // Cache so we only create indexes once per collection per run
    private final ConcurrentHashMap<String, Boolean> indexedCollections = new ConcurrentHashMap<>();

    public MongoRepository(AppConfig config) {
        String uri = buildUri(config);
        log.info("Connecting to MongoDB at {}", config.mongoHost + ":" + config.mongoPort);

        // Limit connection pool to 1 — this is a single-threaded scraper.
        // The default pool of 100 connections wastes memory with BSON buffers.
        com.mongodb.MongoClientSettings settings = com.mongodb.MongoClientSettings.builder()
            .applyConnectionString(new com.mongodb.ConnectionString(uri))
            .applyToConnectionPoolSettings(builder ->
                builder.maxSize(1).minSize(0))
            .build();

        this.client   = MongoClients.create(settings);
        this.database = client.getDatabase(config.mongoDatabase);
        log.info("Connected to database '{}'", config.mongoDatabase);
    }

    // -------------------------------------------------------------------------
    // Core read operations
    // -------------------------------------------------------------------------

    /**
     * Returns true if a document with this hash_id already exists in the
     * collection, its _content_hash matches the provided hash, AND the
     * last update was complete (last_update_corrupted == false).
     *
     * Returns false (= do NOT skip) in these cases:
     *   1. Document does not exist yet — needs full insert
     *   2. Content hash differs — estate changed, needs re-scrape
     *   3. Hash is the same BUT last_update_corrupted == true — the previous
     *      detail fetch failed; we must re-attempt the detail call to fix it
     *      even though the listing data hasn't changed.
     */
    public boolean isUnchanged(String collectionName, long hashId, String contentHash) {
        MongoCollection<Document> col = collection(collectionName);
        // Project only the fields we need — avoids loading the full document into memory
        Document existing = col.find(Filters.eq("hash_id", hashId))
            .projection(new Document("_content_hash", 1)
                .append("last_update_corrupted", 1)
                .append("_id", 0))
            .first();
        if (existing == null) return false;
        if (!contentHash.equals(existing.getString("_content_hash"))) return false;
        Boolean corrupted = existing.getBoolean("last_update_corrupted");
        if (Boolean.TRUE.equals(corrupted)) return false;
        return true;
    }

    /**
     * Returns true if a document with this hash_id already exists in the collection.
     */
    public boolean exists(String collectionName, long hashId) {
        MongoCollection<Document> col = collection(collectionName);
        // Project only _id to minimise data transfer
        return col.find(Filters.eq("hash_id", hashId))
            .projection(new Document("_id", 1))
            .first() != null;
    }

    /**
     * Returns true if the document exists AND last_update_corrupted == true.
     */
    public boolean isCorrupted(String collectionName, long hashId) {
        MongoCollection<Document> col = collection(collectionName);
        return col.find(
            Filters.and(
                Filters.eq("hash_id", hashId),
                Filters.eq("last_update_corrupted", true)
            ))
            .projection(new Document("_id", 1))
            .first() != null;
    }

    // -------------------------------------------------------------------------
    // Upsert — main write operation
    // -------------------------------------------------------------------------

    /**
     * Upsert a single estate document, keyed by hash_id.
     *
     * On INSERT (new estate — first time we've seen it):
     *   - Sets _first_seen_at, _updated_at, _update_count = 0
     *   - No history entry written (no previous state to diff against)
     *
     * On UPDATE (existing estate — content hash changed OR corruption repaired):
     *   - Preserves _first_seen_at from the existing document
     *   - Increments _update_count
     *   - If detail fetch failed (last_update_corrupted=true) AND the existing
     *     document was complete, preserves previous detail fields (no data loss)
     *   - Computes a delta between old and new document and writes it to
     *     <collection>_history as a snapshot of only the changed fields
     *
     * History trigger rules:
     *   - Content hash changed → write delta of all changed fields
     *   - Hash unchanged but was corrupted and now fixed → write detail-only delta
     *   - Detail failed again on already-corrupted doc → NO history entry
     *     (no new information to record)
     */
    public void upsert(String collectionName, Document doc) {
        MongoCollection<Document> col = collection(collectionName);
        long   hashId = doc.getLong("hash_id");
        String now    = Instant.now().toString();

        // Fetch only the metadata fields needed for update logic — not the full document.
        // Fetching the full document on every upsert was the primary cause of memory growth.
        Document existing = col.find(Filters.eq("hash_id", hashId))
            .projection(new Document("_first_seen_at", 1)
                .append("_update_count", 1)
                .append("_content_hash", 1)
                .append("_detail_available", 1)
                .append("last_update_corrupted", 1)
                .append("_id", 0))
            .first();

        if (existing == null) {
            // ── Brand new estate ──────────────────────────────────────────────
            doc.append("_first_seen_at", now);
            doc.append("_updated_at",    now);
            doc.append("_update_count",  0);
            // No history entry on first insert — nothing to diff against
            col.replaceOne(Filters.eq("hash_id", hashId), doc, new ReplaceOptions().upsert(true));
            log.debug("Inserted new estate {} into {}", hashId, collectionName);

        } else {
            // ── Existing estate ───────────────────────────────────────────────
            doc.append("_first_seen_at", existing.getString("_first_seen_at"));
            doc.append("_updated_at",    now);
            doc.append("_update_count",  existing.getInteger("_update_count", 0) + 1);

            boolean newDocCorrupted    = Boolean.TRUE.equals(doc.getBoolean("last_update_corrupted"));
            boolean existingCorrupted  = Boolean.TRUE.equals(existing.getBoolean("last_update_corrupted"));
            boolean existingWasComplete = !existingCorrupted
                && Boolean.TRUE.equals(existing.getBoolean("_detail_available"));

            // If new doc has no detail but old doc was complete → fetch full doc to preserve detail fields
            if (newDocCorrupted && existingWasComplete) {
                log.warn("Detail fetch failed for existing complete estate {} — preserving previous detail fields",
                    hashId);
                Document fullExisting = col.find(Filters.eq("hash_id", hashId)).first();
                if (fullExisting != null) preserveDetailFields(doc, fullExisting);
            }

            // ── History delta ─────────────────────────────────────────────────
            boolean hashChanged      = !doc.getString("_content_hash")
                .equals(existing.getString("_content_hash"));
            boolean corruptionFixed  = existingCorrupted && !newDocCorrupted;

            if (hashChanged || corruptionFixed) {
                // Only fetch full doc when we actually need to write a history delta
                String reason = hashChanged ? "content_changed" : "corruption_repaired";
                Document fullExisting = col.find(Filters.eq("hash_id", hashId)).first();
                if (fullExisting != null) {
                    writeHistoryEntry(collectionName, hashId, fullExisting, doc, now, reason);
                }
            }

            col.replaceOne(Filters.eq("hash_id", hashId), doc, new ReplaceOptions().upsert(true));
        }
    }

    // -------------------------------------------------------------------------
    // History
    // -------------------------------------------------------------------------

    /**
     * Computes which fields changed between the old and new document, then
     * writes a delta document to the <collection>_history collection.
     *
     * Delta document structure:
     * {
     *   hash_id:        long,
     *   recorded_at:    ISO timestamp,
     *   change_number:  int   (= new _update_count),
     *   reason:         "content_changed" | "corruption_repaired",
     *   delta: {
     *     <field>: <old value>,   ← only fields that changed; new value is in main doc
     *     ...
     *   }
     * }
     *
     * Nested objects (seller, images) are compared as atomic blobs — if any
     * sub-field changed, the entire old object is stored in the delta.
     *
     * Fields excluded from the delta:
     *   - Internal metadata fields (_id, _scraped_at, _updated_at, _update_count,
     *     _first_seen_at, _content_hash, _detail_preserved_from_previous)
     *     because these change on every run and carry no domain-meaningful history.
     *   - last_update_corrupted is included because it is meaningful state.
     */
    private void writeHistoryEntry(String collectionName, long hashId,
                                   Document oldDoc, Document newDoc,
                                   String now, String reason) {
        Document delta = computeDelta(oldDoc, newDoc);

        if (delta.isEmpty()) {
            // Nothing actually changed in meaningful fields — skip writing
            log.debug("No meaningful delta for estate {} — skipping history entry", hashId);
            return;
        }

        Document historyEntry = new Document()
            .append("hash_id",       hashId)
            .append("recorded_at",   now)
            .append("change_number", newDoc.getInteger("_update_count", 1))
            .append("reason",        reason)
            .append("delta",         delta);

        String historyCollection = collectionName + "_history";
        MongoCollection<Document> histCol = historyCollection(historyCollection);
        histCol.insertOne(historyEntry);
        log.debug("History entry written for estate {} ({} changed fields, reason={})",
            hashId, delta.size(), reason);
    }

    /**
     * Computes the delta between old and new documents.
     * Returns a Document containing only the OLD values of fields that changed.
     * (The current/new value is always in the main estate document.)
     *
     * Excluded from delta: purely internal metadata fields that change on
     * every run regardless of content.
     */
    private static Document computeDelta(Document oldDoc, Document newDoc) {
        // Metadata fields that are irrelevant for history purposes
        Set<String> excludedFromDelta = Set.of(
            "_id", "_scraped_at", "_updated_at", "_update_count",
            "_first_seen_at", "_content_hash", "_detail_preserved_from_previous"
        );

        Document delta = new Document();

        // Check fields present in the old document
        for (String key : oldDoc.keySet()) {
            if (excludedFromDelta.contains(key)) continue;

            Object oldVal = oldDoc.get(key);
            Object newVal = newDoc.get(key);

            if (!valuesEqual(oldVal, newVal)) {
                delta.append(key, oldVal);  // store the OLD value
            }
        }

        // Also capture fields that exist in old but are absent in new
        // (field was removed — store old value with explicit note)
        for (String key : oldDoc.keySet()) {
            if (excludedFromDelta.contains(key)) continue;
            if (!newDoc.containsKey(key) && !delta.containsKey(key)) {
                delta.append(key, oldDoc.get(key));
            }
        }

        return delta;
    }

    /**
     * Compares two MongoDB field values for equality.
     * Documents and Lists are compared by their string representation
     * (atomic blob comparison — Option A for nested objects).
     */
    private static boolean valuesEqual(Object a, Object b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        // For Documents and Lists, compare serialized form (atomic blob)
        return a.toString().equals(b.toString());
    }

    // -------------------------------------------------------------------------
    // Scrape run report
    // -------------------------------------------------------------------------

    /**
     * Saves a completed scrape run report to the "scrape_runs" collection.
     * The collection is append-only — one document per run, never updated.
     */
    public void saveReport(ScrapeRunReport report) {
        MongoCollection<Document> col = database.getCollection("scrape_runs");

        Document stats = new Document()
            .append("total_processed",      report.totalProcessed)
            .append("total_upserted",       report.totalUpserted)
            .append("total_skipped",        report.totalSkipped)
            .append("total_gone",           report.totalGone)
            .append("total_half_success",   report.totalHalfSuccess)
            .append("total_listing_errors", report.totalListingErrors)
            .append("total_repaired",         report.totalRepaired)
            .append("total_marked_inactive",   report.totalMarkedInactive)
            .append("total_errors",            report.totalErrors());

        List<Document> incomplete = new ArrayList<>();
        for (ScrapeRunReport.IncompleteEstate ie : report.incompleteEstates) {
            incomplete.add(new Document()
                .append("hash_id",               ie.hashId)
                .append("collection",             ie.collection)
                .append("reason",                 ie.reason)
                .append("http_status",            ie.httpStatus)
                .append("error_message",          ie.errorMessage)
                .append("was_existing_document",  ie.wasExistingDocument));
        }

        Document runDoc = new Document()
            .append("started_at",         report.startedAt)
            .append("finished_at",        report.finishedAt)
            .append("status",             report.status)
            .append("stats",              stats)
            .append("incomplete_estates", incomplete);

        col.insertOne(runDoc);
        log.info("Scrape run report saved to 'scrape_runs' (status={}, errors={})",
            report.status, report.totalErrors());
    }

    /**
     * Marks all estates in a collection as inactive if they were not seen
     * since the given runStartedAt timestamp.
     * Called after each category is fully scraped.
     * Returns the number of estates marked inactive.
     */
    public long markInactiveNotSeenSince(String collectionName, String runStartedAt) {
        MongoCollection<Document> col = collection(collectionName);
        var result = col.updateMany(
            Filters.and(
                Filters.eq("active", true),
                Filters.lt("_last_seen_at", runStartedAt)
            ),
            Updates.set("active", false)
        );
        return result.getModifiedCount();
    }

    /**
     * Updates only _last_seen_at for an estate that was skipped (hash unchanged).
     * This allows tracking which estates are no longer appearing in sreality results
     * by querying: db.<col>.find({_last_seen_at: {$lt: <date>}})
     */
    public void touchLastSeen(String collectionName, long hashId) {
        MongoCollection<Document> col = collection(collectionName);
        col.updateOne(
            Filters.eq("hash_id", hashId),
            Updates.set("_last_seen_at", Instant.now().toString())
        );
    }

    /**
     * Returns how many documents are in a given collection.
     */
    public long count(String collectionName) {
        return collection(collectionName).countDocuments();
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private MongoCollection<Document> collection(String name) {
        MongoCollection<Document> col = database.getCollection(name);
        ensureIndexes(name, col);
        return col;
    }

    /** Returns the history collection for a given estate collection, with indexes. */
    private MongoCollection<Document> historyCollection(String historyName) {
        MongoCollection<Document> col = database.getCollection(historyName);
        if (indexedCollections.putIfAbsent(historyName, Boolean.TRUE) == null) {
            // Non-unique index on hash_id — many history entries per estate
            col.createIndex(
                new Document("hash_id", 1),
                new IndexOptions().name("idx_hash_id")
            );
            // Index for chronological retrieval of an estate's full history
            col.createIndex(
                new Document("hash_id", 1).append("recorded_at", -1),
                new IndexOptions().name("idx_hash_recorded")
            );
            // Index for querying all changes across the collection by date
            col.createIndex(
                new Document("recorded_at", -1),
                new IndexOptions().name("idx_recorded_at")
            );
            log.debug("Ensured indexes on history collection '{}'", historyName);
        }
        return col;
    }

    private void ensureIndexes(String name, MongoCollection<Document> col) {
        if (indexedCollections.putIfAbsent(name, Boolean.TRUE) == null) {
            col.createIndex(
                new Document("hash_id", 1),
                new IndexOptions().unique(true).name("idx_hash_id")
            );
            col.createIndex(
                new Document("_content_hash", 1),
                new IndexOptions().name("idx_content_hash")
            );
            col.createIndex(
                new Document("_scraped_at", -1),
                new IndexOptions().name("idx_scraped_at")
            );
            col.createIndex(
                new Document("_updated_at", -1),
                new IndexOptions().name("idx_updated_at")
            );
            col.createIndex(
                new Document("_first_seen_at", -1),
                new IndexOptions().name("idx_first_seen_at")
            );
            col.createIndex(
                new Document("last_update_corrupted", 1),
                new IndexOptions().name("idx_corrupted").sparse(true)
            );
            col.createIndex(
                new Document("_last_seen_at", -1),
                new IndexOptions().name("idx_last_seen_at")
            );
            col.createIndex(
                new Document("active", 1),
                new IndexOptions().name("idx_active").sparse(true)
            );
            log.debug("Ensured indexes on collection '{}'", name);
        }
    }

    /**
     * Copies detail-endpoint fields from a previously complete document into
     * the new (corrupted) document, so that a failed detail re-fetch doesn't
     * destroy data that was already successfully scraped.
     *
     * Only copies keys that are NOT already present in the new document
     * (listing fields from the fresh response are never overwritten).
     */
    private static void preserveDetailFields(Document newDoc, Document existing) {
        int preserved = 0;
        for (String key : existing.keySet()) {
            if (key.equals("_id")) continue;
            if (key.startsWith("_")) continue;
            if (key.equals("last_update_corrupted")) continue;
            if (LISTING_FIELDS.contains(key)) continue;
            if (!newDoc.containsKey(key)) {
                newDoc.append(key, existing.get(key));
                preserved++;
            }
        }
        newDoc.put("_detail_available",              true);
        newDoc.put("_detail_preserved_from_previous", true);
        log.debug("Preserved {} detail fields from previous complete document", preserved);
    }

    private static String buildUri(AppConfig config) {
        if (config.mongoUsername != null && !config.mongoUsername.isBlank()
                && config.mongoPassword != null && !config.mongoPassword.isBlank()) {
            return String.format("mongodb://%s:%s@%s:%d/%s",
                config.mongoUsername,
                config.mongoPassword,
                config.mongoHost,
                config.mongoPort,
                config.mongoDatabase);
        }
        return String.format("mongodb://%s:%d", config.mongoHost, config.mongoPort);
    }

    @Override
    public void close() {
        client.close();
        log.info("MongoDB connection closed");
    }
}
