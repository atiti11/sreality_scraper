package com.sreality.etl.config;

import java.util.List;

/**
 * All environment variables consumed by the ETL process.
 *
 * RUIAN base URLs point to the official ČÚZK ArcGIS MapServer at ags.cuzk.gov.cz.
 * Layer IDs and field names verified from live API:
 *   11 = CastObce  → POINTS,   fields: kod (int), nazev, obec (int FK)
 *   12 = Obec      → POLYGONS, fields: kod (int), nazev, okres (int FK)
 *   15 = Okres     → no geom,  fields: kod (int), nazev, vusc (int FK)
 *   17 = VUSC/Kraj → no geom,  fields: kod (int), nazev
 *
 * CSU demographics URL:
 *   The URL changes every year when CSU migrates their file hosting.
 *   CsuExtractor treats it as optional — on failure it logs a warning and
 *   continues with empty demographics. dim_obec rows are still created,
 *   just with null population/area/age fields.
 *   Set CSU_DEMOGRAPHICS_URL in .env when a working URL is available.
 *
 * Do NOT add resultRecordCount, resultOffset, or f= to RUIAN base URLs —
 * RuianExtractor.fetchInPages() appends those during pagination.
 */
public class EtlConfig {

    private static final String RUIAN_BASE =
        "https://ags.cuzk.gov.cz/arcgis/rest/services/RUIAN/Prohlizeci_sluzba_nad_daty_RUIAN/MapServer";

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

    // ── RUIAN endpoints (base URLs only, no pagination params) ────────────────
    public final String ruianCastObceUrl;
    public final String ruianObecUrl;
    public final String ruianOkresUrl;
    public final String ruianKrajUrl;

    // ── CSU demographics CSV (optional — empty string = skip) ─────────────────
    public final String csuDemographicsUrl;

    // ── Processing ────────────────────────────────────────────────────────────
    public final int batchSize;
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

            env("RUIAN_CAST_OBCE_URL",
                RUIAN_BASE + "/11/query?where=1%3D1&outFields=kod,nazev,obec&returnGeometry=false&outSR=4326"),
            env("RUIAN_OBEC_URL",
                RUIAN_BASE + "/12/query?where=1%3D1&outFields=kod,nazev,okres&returnGeometry=true&outSR=4326"),
            env("RUIAN_OKRES_URL",
                RUIAN_BASE + "/15/query?where=1%3D1&outFields=kod,nazev,vusc&returnGeometry=false"),
            env("RUIAN_KRAJ_URL",
                RUIAN_BASE + "/17/query?where=1%3D1&outFields=kod,nazev&returnGeometry=false"),

            // Empty string = let CsuExtractor try its built-in fallback URLs.
            // Set CSU_DEMOGRAPHICS_URL in .env when you have a working CSV URL.
            env("CSU_DEMOGRAPHICS_URL", ""),

            Integer.parseInt(env("ETL_BATCH_SIZE",      "500")),
            Integer.parseInt(env("ETL_HTTP_TIMEOUT_MS", "60000"))
        );
    }

    public List<String> mongoCollectionsFor(String dealType) {
        return List.of("apartments", "houses", "land", "commercial", "other")
            .stream().map(pt -> pt + "_" + dealType).toList();
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
