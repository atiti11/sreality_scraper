package com.sreality.etl.config;

import java.util.List;

/**
 * All environment variables consumed by the ETL process.
 * Defaults are safe for local docker-compose development.
 *
 * Copy .env.example to .env and set real values before running.
 */
public class EtlConfig {

    // ── MongoDB (source) ──────────────────────────────────────────────────────
    public final String mongoHost;
    public final int    mongoPort;
    public final String mongoDatabase;
    public final String mongoUsername;
    public final String mongoPassword;

    // ── PostgreSQL (destination) ──────────────────────────────────────────────
    public final String pgHost;
    public final int    pgPort;
    public final String pgDatabase;
    public final String pgUsername;
    public final String pgPassword;
    public final String pgSchema;

    // ── RUIAN WFS endpoint ────────────────────────────────────────────────────
    // Default: Czech RUIAN open data GeoJSON via geodata.gov.cz
    public final String ruianCastObceUrl;
    public final String ruianObecUrl;
    public final String ruianOkresUrl;
    public final String ruianKrajUrl;

    // ── CSU demographics CSV URL ──────────────────────────────────────────────
    public final String csuDemographicsUrl;

    // ── Processing ────────────────────────────────────────────────────────────
    // Batch size for streaming MongoDB → PostgreSQL (keeps heap flat)
    public final int batchSize;

    // HTTP download timeout (ms)
    public final int httpTimeoutMs;

    private EtlConfig(
            String mongoHost, int mongoPort, String mongoDatabase,
            String mongoUsername, String mongoPassword,
            String pgHost, int pgPort, String pgDatabase,
            String pgUsername, String pgPassword, String pgSchema,
            String ruianCastObceUrl, String ruianObecUrl,
            String ruianOkresUrl, String ruianKrajUrl,
            String csuDemographicsUrl,
            int batchSize, int httpTimeoutMs) {
        this.mongoHost          = mongoHost;
        this.mongoPort          = mongoPort;
        this.mongoDatabase      = mongoDatabase;
        this.mongoUsername      = mongoUsername;
        this.mongoPassword      = mongoPassword;
        this.pgHost             = pgHost;
        this.pgPort             = pgPort;
        this.pgDatabase         = pgDatabase;
        this.pgUsername         = pgUsername;
        this.pgPassword         = pgPassword;
        this.pgSchema           = pgSchema;
        this.ruianCastObceUrl   = ruianCastObceUrl;
        this.ruianObecUrl       = ruianObecUrl;
        this.ruianOkresUrl      = ruianOkresUrl;
        this.ruianKrajUrl       = ruianKrajUrl;
        this.csuDemographicsUrl = csuDemographicsUrl;
        this.batchSize          = batchSize;
        this.httpTimeoutMs      = httpTimeoutMs;
    }

    public static EtlConfig fromEnv() {
        return new EtlConfig(
            env("MONGO_HOST",     "mongodb"),
            Integer.parseInt(env("MONGO_PORT", "27017")),
            env("MONGO_DATABASE", "sreality"),
            env("MONGO_USERNAME", "scraper"),
            env("MONGO_PASSWORD", "changeme"),

            env("PG_HOST",     "postgres"),
            Integer.parseInt(env("PG_PORT", "5432")),
            env("PG_DATABASE", "sreality_dw"),
            env("PG_USERNAME", "etl"),
            env("PG_PASSWORD", "changeme"),
            env("PG_SCHEMA",   "dw"),

            // RUIAN GeoJSON via ArcGIS FeatureServer — returns all features with geometry
            env("RUIAN_CAST_OBCE_URL",
                "https://services6.arcgis.com/NMfBpFl1DRNR3yBa/arcgis/rest/services/RUIAN_CAST_OBCE_P/FeatureServer/0/query?where=1%3D1&outFields=*&f=geojson&resultRecordCount=10000"),
            env("RUIAN_OBEC_URL",
                "https://services6.arcgis.com/NMfBpFl1DRNR3yBa/arcgis/rest/services/RUIAN_OBEC_P/FeatureServer/0/query?where=1%3D1&outFields=*&f=geojson&resultRecordCount=10000"),
            env("RUIAN_OKRES_URL",
                "https://services6.arcgis.com/NMfBpFl1DRNR3yBa/arcgis/rest/services/RUIAN_OKRES_P/FeatureServer/0/query?where=1%3D1&outFields=*&f=geojson&resultRecordCount=1000"),
            env("RUIAN_KRAJ_URL",
                "https://services6.arcgis.com/NMfBpFl1DRNR3yBa/arcgis/rest/services/RUIAN_KRAJ_P/FeatureServer/0/query?where=1%3D1&outFields=*&f=geojson&resultRecordCount=100"),

            // CSU: population by municipality (yearly updated Excel/CSV)
            env("CSU_DEMOGRAPHICS_URL",
                "https://www.czso.cz/documents/10180/25233174/1300721903.csv"),

            Integer.parseInt(env("ETL_BATCH_SIZE",    "500")),
            Integer.parseInt(env("ETL_HTTP_TIMEOUT_MS", "60000"))
        );
    }

    /**
     * Returns all MongoDB collection names that contain estates for the
     * given deal type. The scraper stores one collection per property×deal
     * combination (apartments_sale, houses_sale, ...) so we collect them all.
     */
    public List<String> mongoCollectionsFor(String dealType) {
        List<String> propertyTypes = List.of(
            "apartments", "houses", "land", "commercial", "other"
        );
        return propertyTypes.stream()
            .map(pt -> pt + "_" + dealType)
            .toList();
    }

    public String mongoUri() {
        if (mongoUsername != null && !mongoUsername.isBlank()) {
            return String.format("mongodb://%s:%s@%s:%d/%s",
                mongoUsername, mongoPassword, mongoHost, mongoPort, mongoDatabase);
        }
        return String.format("mongodb://%s:%d", mongoHost, mongoPort);
    }

    public String pgJdbcUrl() {
        return String.format("jdbc:postgresql://%s:%d/%s", pgHost, pgPort, pgDatabase);
    }

    private static String env(String name, String def) {
        String v = System.getenv(name);
        return (v != null && !v.isBlank()) ? v : def;
    }

    @Override
    public String toString() {
        return "EtlConfig{mongo=" + mongoHost + ":" + mongoPort + "/" + mongoDatabase
            + ", pg=" + pgHost + ":" + pgPort + "/" + pgDatabase + "/" + pgSchema
            + ", batchSize=" + batchSize + "}";
    }
}
