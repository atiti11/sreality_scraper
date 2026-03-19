package com.sreality.scraper;

import com.fasterxml.jackson.databind.JsonNode;
import com.sreality.scraper.db.MongoRepository;
import com.sreality.scraper.http.SrealityHttpClient;
import com.sreality.scraper.http.SrealityHttpClient.SrealityHttpException;
import com.sreality.scraper.model.EstateDocumentBuilder;
import com.sreality.scraper.scraper.EstateScraper;
import com.sreality.scraper.scraper.ScrapeRunReport;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;

import static com.sreality.scraper.TestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

/**
 * Integration tests for EstateScraper.
 *
 * Each test covers one cell of the state machine table from README.md.
 * Embedded MongoDB is used — no real network calls. SrealityHttpClient is mocked.
 *
 * State machine being tested:
 *
 *  | Exists? | Hash changed? | Was corrupted? | Detail result | History? | last_update_corrupted |
 *  |---------|--------------|----------------|---------------|----------|-----------------------|
 *  | No      | —            | —              | ✅ success    | No       | false                 |  Case 1a
 *  | No      | —            | —              | ❌ fail       | No       | true                  |  Case 1b
 *  | Yes     | ✅ yes        | No             | ✅ success    | Yes      | false                 |  Case 2a
 *  | Yes     | ✅ yes        | No             | ❌ fail       | Yes      | true (detail kept)    |  Case 2b
 *  | Yes     | ✅ yes        | Yes            | ✅ success    | Yes      | false                 |  Case 2c (hash+repair)
 *  | Yes     | ✅ yes        | Yes            | ❌ fail       | Yes      | true                  |  Case 2d
 *  | Yes     | ❌ no         | Yes            | ✅ success    | Yes      | false                 |  Case 3a
 *  | Yes     | ❌ no         | Yes            | ❌ fail       | No       | true                  |  Case 3b
 *  | Yes     | ❌ no         | No             | —             | No       | false (skipped)       |  Case 4 (skip)
 */
@ExtendWith(MockitoExtension.class)
class EstateScraperIntegrationTest extends ScraperIntegrationTestBase {

    // =========================================================================
    // Case 1 — Brand new estate (never seen before)
    // =========================================================================

    @Nested
    @DisplayName("Case 1 — Brand new estate")
    class NewEstate {

        @Test
        @DisplayName("1a: New estate, detail succeeds → stored complete, no history")
        void newEstate_detailSuccess() throws IOException {
            SrealityHttpClient http = mock(SrealityHttpClient.class);
            when(http.get(contains("/count"))).thenReturn(json("{\"result_size\": 1}"));
            when(http.get(contains("per_page"))).thenReturn(listingPageJson(defaultListing()));
            when(http.get(contains("/" + DEFAULT_HASH_ID))).thenReturn(json(defaultDetail()));

            runScraper(http);

            Document doc = findEstate(COLLECTION, DEFAULT_HASH_ID);
            assertNotNull(doc, "Document should exist");
            assertFalse(doc.getBoolean("last_update_corrupted"), "Should not be corrupted");
            assertTrue(doc.getBoolean("_detail_available"), "Detail should be available");
            assertEquals("Krásný byt v centru.", doc.getString("description"));
            assertNotNull(doc.getString("_first_seen_at"));
            assertEquals(0, doc.getInteger("_update_count"));

            assertEquals(0, historyCount(COLLECTION, DEFAULT_HASH_ID), "No history on first insert");
        }

        @Test
        @DisplayName("1b: New estate, detail fails (410) → stored incomplete, no history, corrupted=true")
        void newEstate_detailFails() throws IOException {
            SrealityHttpClient http = mock(SrealityHttpClient.class);
            when(http.get(contains("/count"))).thenReturn(json("{\"result_size\": 1}"));
            when(http.get(contains("per_page"))).thenReturn(listingPageJson(defaultListing()));
            when(http.get(contains("/" + DEFAULT_HASH_ID)))
                .thenThrow(new SrealityHttpException(410, "url", "Gone"));

            runScraper(http);

            Document doc = findEstate(COLLECTION, DEFAULT_HASH_ID);
            assertNotNull(doc, "Document should exist even if detail failed");
            assertTrue(doc.getBoolean("last_update_corrupted"), "Should be marked corrupted");
            assertFalse(doc.getBoolean("_detail_available"), "Detail should not be available");
            assertNull(doc.getString("description"), "Description should be absent");

            assertEquals(0, historyCount(COLLECTION, DEFAULT_HASH_ID), "No history on first insert");
        }
    }

    // =========================================================================
    // Case 2 — Estate exists, hash changed
    // =========================================================================

    @Nested
    @DisplayName("Case 2 — Existing estate, hash changed")
    class ExistingEstateHashChanged {

        @Test
        @DisplayName("2a: Hash changed, was complete, detail succeeds → history written, corrupted=false")
        void hashChanged_wasComplete_detailSuccess() throws IOException {
            // Seed: insert a complete document with price 9_700_000
            insertComplete(DEFAULT_HASH_ID, DEFAULT_PRICE);

            // Run: price changes to 8_500_000, detail succeeds
            SrealityHttpClient http = mock(SrealityHttpClient.class);
            long newPrice = 8_500_000L;
            when(http.get(contains("/count"))).thenReturn(json("{\"result_size\": 1}"));
            when(http.get(contains("per_page"))).thenReturn(listingPageJson(listingWithPrice(newPrice)));
            when(http.get(contains("/" + DEFAULT_HASH_ID))).thenReturn(json(defaultDetail()));

            runScraper(http);

            Document doc = findEstate(COLLECTION, DEFAULT_HASH_ID);
            assertFalse(doc.getBoolean("last_update_corrupted"));
            assertEquals(newPrice, doc.getLong("price_czk_value"));
            assertEquals(1, doc.getInteger("_update_count"));

            // History entry should record the OLD price
            assertEquals(1, historyCount(COLLECTION, DEFAULT_HASH_ID));
            Document history = latestHistory(COLLECTION, DEFAULT_HASH_ID);
            assertEquals("content_changed", history.getString("reason"));
            Document delta = history.get("delta", Document.class);
            assertEquals(DEFAULT_PRICE, delta.getLong("price_czk_value"),
                "Delta should contain the old price");
        }

        @Test
        @DisplayName("2b: Hash changed, was complete, detail fails → history written, old detail preserved, corrupted=true")
        void hashChanged_wasComplete_detailFails() throws IOException {
            // Seed: complete document with description
            insertComplete(DEFAULT_HASH_ID, DEFAULT_PRICE);

            // Run: price changes, detail fails
            SrealityHttpClient http = mock(SrealityHttpClient.class);
            long newPrice = 8_500_000L;
            when(http.get(contains("/count"))).thenReturn(json("{\"result_size\": 1}"));
            when(http.get(contains("per_page"))).thenReturn(listingPageJson(listingWithPrice(newPrice)));
            when(http.get(contains("/" + DEFAULT_HASH_ID)))
                .thenThrow(new SrealityHttpException(410, "url", "Gone"));

            runScraper(http);

            Document doc = findEstate(COLLECTION, DEFAULT_HASH_ID);
            assertTrue(doc.getBoolean("last_update_corrupted"));
            // Price should be updated to the new value
            assertEquals(newPrice, doc.getLong("price_czk_value"));
            // Description from previous complete scrape must be preserved
            assertEquals("Krásný byt v centru.", doc.getString("description"),
                "Previous detail fields must be preserved");
            assertTrue(doc.getBoolean("_detail_preserved_from_previous"));

            // History should still be written for the price change
            assertEquals(1, historyCount(COLLECTION, DEFAULT_HASH_ID));
            Document history = latestHistory(COLLECTION, DEFAULT_HASH_ID);
            assertEquals("content_changed", history.getString("reason"));
        }

        @Test
        @DisplayName("2c: Hash changed, was corrupted, detail succeeds → history written, corrupted=false")
        void hashChanged_wasCorrupted_detailSuccess() throws IOException {
            // Seed: corrupted document (no description)
            insertCorrupted(DEFAULT_HASH_ID, DEFAULT_PRICE);

            // Run: price also changes, detail now succeeds
            SrealityHttpClient http = mock(SrealityHttpClient.class);
            long newPrice = 8_000_000L;
            when(http.get(contains("/count"))).thenReturn(json("{\"result_size\": 1}"));
            when(http.get(contains("per_page"))).thenReturn(listingPageJson(listingWithPrice(newPrice)));
            when(http.get(contains("/" + DEFAULT_HASH_ID))).thenReturn(json(defaultDetail()));

            runScraper(http);

            Document doc = findEstate(COLLECTION, DEFAULT_HASH_ID);
            assertFalse(doc.getBoolean("last_update_corrupted"), "Should now be complete");
            assertEquals(newPrice, doc.getLong("price_czk_value"));
            assertEquals("Krásný byt v centru.", doc.getString("description"));

            assertEquals(1, historyCount(COLLECTION, DEFAULT_HASH_ID));
            Document history = latestHistory(COLLECTION, DEFAULT_HASH_ID);
            assertEquals("content_changed", history.getString("reason"));
        }

        @Test
        @DisplayName("2d: Hash changed, was corrupted, detail fails → history written, still corrupted")
        void hashChanged_wasCorrupted_detailFails() throws IOException {
            // Seed: corrupted document
            insertCorrupted(DEFAULT_HASH_ID, DEFAULT_PRICE);

            // Run: price changes, detail fails again
            SrealityHttpClient http = mock(SrealityHttpClient.class);
            long newPrice = 7_500_000L;
            when(http.get(contains("/count"))).thenReturn(json("{\"result_size\": 1}"));
            when(http.get(contains("per_page"))).thenReturn(listingPageJson(listingWithPrice(newPrice)));
            when(http.get(contains("/" + DEFAULT_HASH_ID)))
                .thenThrow(new SrealityHttpException(410, "url", "Gone"));

            runScraper(http);

            Document doc = findEstate(COLLECTION, DEFAULT_HASH_ID);
            assertTrue(doc.getBoolean("last_update_corrupted"), "Still corrupted");
            assertEquals(newPrice, doc.getLong("price_czk_value"), "Price should update");

            // History is still written because listing data changed
            assertEquals(1, historyCount(COLLECTION, DEFAULT_HASH_ID));
            Document history = latestHistory(COLLECTION, DEFAULT_HASH_ID);
            assertEquals("content_changed", history.getString("reason"));
        }
    }

    // =========================================================================
    // Case 3 — Estate exists, hash unchanged, but corrupted
    // =========================================================================

    @Nested
    @DisplayName("Case 3 — Existing estate, hash unchanged, was corrupted")
    class ExistingEstateHashUnchangedCorrupted {

        @Test
        @DisplayName("3a: Hash same, was corrupted, detail now succeeds → corruption_repaired history, corrupted=false")
        void hashUnchanged_wasCorrupted_detailSuccess() throws IOException {
            // Seed: corrupted document — same price, no description
            insertCorrupted(DEFAULT_HASH_ID, DEFAULT_PRICE);

            // Run: same listing (hash won't change), detail now available
            SrealityHttpClient http = mock(SrealityHttpClient.class);
            when(http.get(contains("/count"))).thenReturn(json("{\"result_size\": 1}"));
            when(http.get(contains("per_page"))).thenReturn(listingPageJson(defaultListing()));
            when(http.get(contains("/" + DEFAULT_HASH_ID))).thenReturn(json(defaultDetail()));

            runScraper(http);

            Document doc = findEstate(COLLECTION, DEFAULT_HASH_ID);
            assertFalse(doc.getBoolean("last_update_corrupted"), "Corruption should be repaired");
            assertTrue(doc.getBoolean("_detail_available"));
            assertEquals("Krásný byt v centru.", doc.getString("description"),
                "Description should now be present");

            // History written with reason=corruption_repaired
            assertEquals(1, historyCount(COLLECTION, DEFAULT_HASH_ID));
            Document history = latestHistory(COLLECTION, DEFAULT_HASH_ID);
            assertEquals("corruption_repaired", history.getString("reason"),
                "History reason should be corruption_repaired");
        }

        @Test
        @DisplayName("3b: Hash same, was corrupted, detail fails again → no history, still corrupted")
        void hashUnchanged_wasCorrupted_detailFailsAgain() throws IOException {
            // Seed: corrupted document
            insertCorrupted(DEFAULT_HASH_ID, DEFAULT_PRICE);

            // Run: same listing, detail still fails
            SrealityHttpClient http = mock(SrealityHttpClient.class);
            when(http.get(contains("/count"))).thenReturn(json("{\"result_size\": 1}"));
            when(http.get(contains("per_page"))).thenReturn(listingPageJson(defaultListing()));
            when(http.get(contains("/" + DEFAULT_HASH_ID)))
                .thenThrow(new SrealityHttpException(410, "url", "Gone"));

            runScraper(http);

            Document doc = findEstate(COLLECTION, DEFAULT_HASH_ID);
            assertTrue(doc.getBoolean("last_update_corrupted"), "Still corrupted");

            // No history — nothing new to record
            assertEquals(0, historyCount(COLLECTION, DEFAULT_HASH_ID),
                "No history when nothing new was learned");
        }
    }

    // =========================================================================
    // Case 4 — Estate exists, hash unchanged, NOT corrupted → skipped entirely
    // =========================================================================

    @Nested
    @DisplayName("Case 4 — Existing estate, hash unchanged, complete")
    class ExistingEstateHashUnchangedComplete {

        @Test
        @DisplayName("4: Hash same, complete → skipped, no DB changes, no history, detail API not called")
        void hashUnchanged_complete_skipped() throws IOException {
            // Seed: fully complete document
            insertComplete(DEFAULT_HASH_ID, DEFAULT_PRICE);
            String originalScrapedAt = findEstate(COLLECTION, DEFAULT_HASH_ID).getString("_scraped_at");

            SrealityHttpClient http = mock(SrealityHttpClient.class);
            when(http.get(contains("/count"))).thenReturn(json("{\"result_size\": 1}"));
            when(http.get(contains("per_page"))).thenReturn(listingPageJson(defaultListing()));
            // Detail should NEVER be called — use lenient() so Mockito doesn't
            // complain if the stub is never triggered (that's exactly what we verify)
            lenient().when(http.get(contains("/" + DEFAULT_HASH_ID)))
                .thenThrow(new AssertionError("Detail endpoint should not be called for unchanged complete estate"));

            runScraper(http);

            // Document must not have been touched
            Document doc = findEstate(COLLECTION, DEFAULT_HASH_ID);
            assertEquals(originalScrapedAt, doc.getString("_scraped_at"),
                "_scraped_at must not change when skipped");
            assertEquals(0, doc.getInteger("_update_count"), "update_count must not increment");
            assertEquals(0, historyCount(COLLECTION, DEFAULT_HASH_ID), "No history");
        }
    }

    // =========================================================================
    // Scrape run report tests
    // =========================================================================

    @Nested
    @DisplayName("Scrape run report")
    class ScrapeRunReportTests {

        @Test
        @DisplayName("Report is saved to scrape_runs after each run")
        void reportIsSavedToDatabase() throws IOException {
            SrealityHttpClient http = mock(SrealityHttpClient.class);
            when(http.get(contains("/count"))).thenReturn(json("{\"result_size\": 1}"));
            when(http.get(contains("per_page"))).thenReturn(listingPageJson(defaultListing()));
            when(http.get(contains("/" + DEFAULT_HASH_ID))).thenReturn(json(defaultDetail()));

            runScraper(http);

            long reportCount = countAll("scrape_runs");
            assertEquals(1, reportCount, "One scrape_runs document should be saved");

            Document report = testDatabase.getCollection("scrape_runs").find().first();
            assertNotNull(report.getString("started_at"));
            assertNotNull(report.getString("finished_at"));
            assertEquals("completed", report.getString("status"));

            Document stats = report.get("stats", Document.class);
            assertEquals(1, stats.getInteger("total_processed"));
            assertEquals(1, stats.getInteger("total_upserted"));
            assertEquals(0, stats.getInteger("total_half_success"));
        }

        @Test
        @DisplayName("Incomplete estates are recorded in the report")
        void incompleteEstatesRecordedInReport() throws IOException {
            SrealityHttpClient http = mock(SrealityHttpClient.class);
            when(http.get(contains("/count"))).thenReturn(json("{\"result_size\": 1}"));
            when(http.get(contains("per_page"))).thenReturn(listingPageJson(defaultListing()));
            when(http.get(contains("/" + DEFAULT_HASH_ID)))
                .thenThrow(new SrealityHttpException(410, "url", "Gone"));

            runScraper(http);

            Document report = testDatabase.getCollection("scrape_runs").find().first();
            Document stats = report.get("stats", Document.class);
            assertEquals(1, stats.getInteger("total_half_success"));
            assertEquals(1, stats.getInteger("total_gone"));

            var incompleteList = report.getList("incomplete_estates", Document.class);
            assertEquals(1, incompleteList.size());
            Document incomplete = incompleteList.get(0);
            assertEquals(DEFAULT_HASH_ID, incomplete.getLong("hash_id"));
            assertEquals(COLLECTION, incomplete.getString("collection"));
            assertEquals("gone_410", incomplete.getString("reason"));
            assertFalse(incomplete.getBoolean("was_existing_document"),
                "Should be false for brand new estate");
        }

        @Test
        @DisplayName("Repaired corruption is counted in the report")
        void repairedCorruptionCountedInReport() throws IOException {
            insertCorrupted(DEFAULT_HASH_ID, DEFAULT_PRICE);

            SrealityHttpClient http = mock(SrealityHttpClient.class);
            when(http.get(contains("/count"))).thenReturn(json("{\"result_size\": 1}"));
            when(http.get(contains("per_page"))).thenReturn(listingPageJson(defaultListing()));
            when(http.get(contains("/" + DEFAULT_HASH_ID))).thenReturn(json(defaultDetail()));

            runScraper(http);

            Document report = testDatabase.getCollection("scrape_runs").find().first();
            Document stats = report.get("stats", Document.class);
            assertEquals(1, stats.getInteger("total_repaired"),
                "Corruption repair should be counted");
        }
    }

    // =========================================================================
    // History delta content tests
    // =========================================================================

    @Nested
    @DisplayName("History delta content")
    class HistoryDeltaContent {

        @Test
        @DisplayName("Delta contains only changed fields, not unchanged ones")
        void deltaContainsOnlyChangedFields() throws IOException {
            insertComplete(DEFAULT_HASH_ID, DEFAULT_PRICE);

            // Only price changes — name stays the same
            SrealityHttpClient http = mock(SrealityHttpClient.class);
            when(http.get(contains("/count"))).thenReturn(json("{\"result_size\": 1}"));
            when(http.get(contains("per_page"))).thenReturn(listingPageJson(listingWithPrice(8_000_000L)));
            when(http.get(contains("/" + DEFAULT_HASH_ID))).thenReturn(json(defaultDetail()));

            runScraper(http);

            Document history = latestHistory(COLLECTION, DEFAULT_HASH_ID);
            assertNotNull(history);
            Document delta = history.get("delta", Document.class);

            assertTrue(delta.containsKey("price_czk_value"), "Delta should contain changed price");
            assertFalse(delta.containsKey("name"), "Delta should not contain unchanged name");
            assertFalse(delta.containsKey("locality"), "Delta should not contain unchanged locality");
        }

        @Test
        @DisplayName("Delta contains old seller blob when seller changes")
        void deltaContainsOldSellerWhenSellerChanges() throws IOException {
            insertComplete(DEFAULT_HASH_ID, DEFAULT_PRICE);

            // Price changes → triggers full re-scrape → new seller name in detail
            SrealityHttpClient http = mock(SrealityHttpClient.class);
            when(http.get(contains("/count"))).thenReturn(json("{\"result_size\": 1}"));
            when(http.get(contains("per_page"))).thenReturn(listingPageJson(listingWithPrice(8_000_000L)));
            when(http.get(contains("/" + DEFAULT_HASH_ID)))
                .thenReturn(json(detailWithSeller("Nový Makléř")));

            runScraper(http);

            Document history = latestHistory(COLLECTION, DEFAULT_HASH_ID);
            Document delta   = history.get("delta", Document.class);

            // Old seller should be in the delta as an atomic blob
            assertTrue(delta.containsKey("seller"), "Delta should contain old seller blob");
            Document oldSeller = delta.get("seller", Document.class);
            assertEquals("Jan Novák", oldSeller.getString("user_name"),
                "Delta seller should be the OLD seller name");
        }

        @Test
        @DisplayName("Multiple changes produce multiple history entries")
        void multipleChangesProduceMultipleHistoryEntries() throws IOException {
            insertComplete(DEFAULT_HASH_ID, DEFAULT_PRICE);

            SrealityHttpClient http = mock(SrealityHttpClient.class);

            // Run 2: price drops
            when(http.get(contains("/count"))).thenReturn(json("{\"result_size\": 1}"));
            when(http.get(contains("per_page"))).thenReturn(listingPageJson(listingWithPrice(8_500_000L)));
            when(http.get(contains("/" + DEFAULT_HASH_ID))).thenReturn(json(defaultDetail()));
            runScraper(http);

            clearMocks(http);

            // Run 3: price drops again
            when(http.get(contains("/count"))).thenReturn(json("{\"result_size\": 1}"));
            when(http.get(contains("per_page"))).thenReturn(listingPageJson(listingWithPrice(7_900_000L)));
            when(http.get(contains("/" + DEFAULT_HASH_ID))).thenReturn(json(defaultDetail()));
            runScraper(http);

            assertEquals(2, historyCount(COLLECTION, DEFAULT_HASH_ID),
                "Two price changes → two history entries");
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Run EstateScraper with apartments_sale only (category 1, type 1). */
    private void runScraper(SrealityHttpClient http) {
        try (MongoRepository repo = testRepo()) {
            // Restrict to apartments_sale only — keeps tests fast and predictable
            EstateScraper scraper = new EstateScraper(
                testConfig(), http, repo,
                new int[]{1},   // category_main_cb = 1 (apartments)
                new int[]{1},   // category_type_cb  = 1 (sale)
                0L              // no delay in tests
            );
            scraper.run();
        }
    }

    /** Wrap a single estate JSON into a listing page response. */
    private JsonNode listingPageJson(String estateJson) {
        return json("""
            {
              "result_size": 1,
              "_embedded": {
                "estates": [ %s ]
              }
            }
            """.formatted(estateJson));
    }

    /**
     * Insert a fully complete estate document into the test database directly,
     * bypassing the scraper. Used to set up preconditions for tests.
     */
    private void insertComplete(long hashId, long price) throws IOException {
        JsonNode listing = json(listingJson(hashId, price, "Prodej bytu 3+kk 83 m²"));
        JsonNode detail  = json(defaultDetail());
        Document doc     = EstateDocumentBuilder.build(listing, detail);
        doc.append("_first_seen_at", "2026-01-01T10:00:00Z");
        doc.append("_updated_at",    "2026-01-01T10:00:00Z");
        doc.append("_update_count",  0);
        testDatabase.getCollection(COLLECTION).insertOne(doc);
    }

    /**
     * Insert a corrupted (detail-failed) estate document into the test database.
     */
    private void insertCorrupted(long hashId, long price) throws IOException {
        JsonNode listing = json(listingJson(hashId, price, "Prodej bytu 3+kk 83 m²"));
        Document doc     = EstateDocumentBuilder.build(listing, null);
        doc.append("_first_seen_at", "2026-01-01T10:00:00Z");
        doc.append("_updated_at",    "2026-01-01T10:00:00Z");
        doc.append("_update_count",  0);
        testDatabase.getCollection(COLLECTION).insertOne(doc);
    }

    /** Reset mock expectations between runs in the same test. */
    private void clearMocks(SrealityHttpClient http) {
        reset(http);
    }
}
