package com.sreality.scraper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.sreality.scraper.config.AppConfig;
import com.sreality.scraper.db.MongoRepository;
import de.flapdoodle.embed.mongo.MongodExecutable;
import de.flapdoodle.embed.mongo.MongodStarter;
import de.flapdoodle.embed.mongo.config.ImmutableMongodConfig;
import de.flapdoodle.embed.mongo.config.MongodConfig;
import de.flapdoodle.embed.mongo.config.Net;
import de.flapdoodle.embed.mongo.distribution.Version;
import de.flapdoodle.embed.process.runtime.Network;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;

/**
 * Base class for all scraper integration tests.
 *
 * Spins up an embedded MongoDB instance once for the entire test class,
 * then drops the test database before each individual test so every test
 * starts with a clean slate.
 *
 * Uses Flapdoodle 3.x API (MongodStarter / MongodConfig).
 * The embedded MongoDB runs on a random free port to avoid conflicts.
 * No real network access required — fully in-process.
 */
public abstract class ScraperIntegrationTestBase {

    private static MongodExecutable mongodExecutable;
    private static int              mongoPort;

    protected static MongoClient   mongoClient;
    protected static MongoDatabase testDatabase;

    protected static final String TEST_DB_NAME = "sreality_test";
    private   static final ObjectMapper MAPPER  = new ObjectMapper();

    @BeforeAll
    static void startEmbeddedMongo() throws IOException {
        mongoPort = Network.getFreeServerPort();

        ImmutableMongodConfig mongodConfig = MongodConfig.builder()
            .version(Version.Main.V6_0)
            .net(new Net("localhost", mongoPort, Network.localhostIsIPv6()))
            .build();

        MongodStarter starter = MongodStarter.getDefaultInstance();
        mongodExecutable = starter.prepare(mongodConfig);
        mongodExecutable.start();

        mongoClient  = MongoClients.create("mongodb://localhost:" + mongoPort);
        testDatabase = mongoClient.getDatabase(TEST_DB_NAME);
    }

    @AfterAll
    static void stopEmbeddedMongo() {
        if (mongoClient      != null) mongoClient.close();
        if (mongodExecutable != null) mongodExecutable.stop();
    }

    /** Drop all collections in the test database before each test — clean slate. */
    @BeforeEach
    void clearDatabase() {
        for (String name : testDatabase.listCollectionNames()) {
            testDatabase.getCollection(name).drop();
        }
    }

    // -------------------------------------------------------------------------
    // Factory helpers
    // -------------------------------------------------------------------------

    /** Build an AppConfig that points to the embedded MongoDB test instance. */
    protected AppConfig testConfig() {
        return AppConfig.forTesting(
            "localhost", mongoPort, TEST_DB_NAME, "", "",
            "https://www.sreality.cz/api/cs/v2/estates",
            100, 0, 10_000, 30_000
        );
    }

    /** Build a MongoRepository connected to the embedded test database. */
    protected MongoRepository testRepo() {
        return new MongoRepository(testConfig());
    }

    /** Parse a JSON string into a JsonNode. */
    protected JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (IOException e) {
            throw new RuntimeException("Invalid test JSON", e);
        }
    }

    /** Fetch the single document for a given hash_id from the given collection. */
    protected Document findEstate(String collection, long hashId) {
        return testDatabase.getCollection(collection)
            .find(new Document("hash_id", hashId))
            .first();
    }

    /** Count documents in the given collection matching the filter. */
    protected long count(String collection, Document filter) {
        return testDatabase.getCollection(collection).countDocuments(filter);
    }

    /** Count all documents in the given collection. */
    protected long countAll(String collection) {
        return testDatabase.getCollection(collection).countDocuments();
    }

    /** Count history entries for a given hash_id. */
    protected long historyCount(String collection, long hashId) {
        return testDatabase.getCollection(collection + "_history")
            .countDocuments(new Document("hash_id", hashId));
    }

    /** Fetch the most recent history entry for a given hash_id. */
    protected Document latestHistory(String collection, long hashId) {
        return testDatabase.getCollection(collection + "_history")
            .find(new Document("hash_id", hashId))
            .sort(new Document("recorded_at", -1))
            .first();
    }
}
