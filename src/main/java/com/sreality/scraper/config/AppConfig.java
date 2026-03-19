package com.sreality.scraper.config;

/**
 * Central place for all environment variable names and their defaults.
 *
 * Usage:
 *   AppConfig cfg = AppConfig.fromEnv();
 */
public class AppConfig {

    // MongoDB
    public final String mongoHost;
    public final int    mongoPort;
    public final String mongoDatabase;
    public final String mongoUsername;
    public final String mongoPassword;

    // Sreality API
    public final String srealityBaseUrl;
    public final int    perPage;

    // Development / testing limiter  (0 = no limit → scrape everything)
    public final int    maxEstates;

    // HTTP client timeouts (ms)
    public final int    httpConnectTimeoutMs;
    public final int    httpReadTimeoutMs;

    private AppConfig(
            String mongoHost, int mongoPort,
            String mongoDatabase, String mongoUsername, String mongoPassword,
            String srealityBaseUrl, int perPage, int maxEstates,
            int httpConnectTimeoutMs, int httpReadTimeoutMs) {
        this.mongoHost            = mongoHost;
        this.mongoPort            = mongoPort;
        this.mongoDatabase        = mongoDatabase;
        this.mongoUsername        = mongoUsername;
        this.mongoPassword        = mongoPassword;
        this.srealityBaseUrl      = srealityBaseUrl;
        this.perPage              = perPage;
        this.maxEstates           = maxEstates;
        this.httpConnectTimeoutMs = httpConnectTimeoutMs;
        this.httpReadTimeoutMs    = httpReadTimeoutMs;
    }

    public static AppConfig fromEnv() {
        return new AppConfig(
            env("MONGO_HOST",              "mongodb"),
            Integer.parseInt(env("MONGO_PORT",     "27017")),
            env("MONGO_DATABASE",          "sreality"),
            env("MONGO_USERNAME",          "scraper"),
            env("MONGO_PASSWORD",          "changeme"),
            env("SREALITY_BASE_URL",       "https://www.sreality.cz/api/cs/v2/estates"),
            Integer.parseInt(env("PER_PAGE",        "100")),
            Integer.parseInt(env("MAX_ESTATES",     "0")),
            Integer.parseInt(env("HTTP_CONNECT_TIMEOUT_MS", "10000")),
            Integer.parseInt(env("HTTP_READ_TIMEOUT_MS",    "30000"))
        );
    }

    /** Returns env value, falling back to defaultValue. */
    private static String env(String name, String defaultValue) {
        String val = System.getenv(name);
        return (val != null && !val.isBlank()) ? val : defaultValue;
    }

    public boolean hasMaxEstatesLimit() {
        return maxEstates > 0;
    }

    @Override
    public String toString() {
        return "AppConfig{" +
            "mongoHost='" + mongoHost + '\'' +
            ", mongoPort=" + mongoPort +
            ", mongoDatabase='" + mongoDatabase + '\'' +
            ", srealityBaseUrl='" + srealityBaseUrl + '\'' +
            ", perPage=" + perPage +
            ", maxEstates=" + (maxEstates == 0 ? "unlimited" : maxEstates) +
            ", httpConnectTimeoutMs=" + httpConnectTimeoutMs +
            ", httpReadTimeoutMs=" + httpReadTimeoutMs +
            '}';
    }
}
