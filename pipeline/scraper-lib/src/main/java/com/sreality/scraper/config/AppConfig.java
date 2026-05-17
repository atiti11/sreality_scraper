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

    // Delay between HTTP requests in ms (default 300 — average with ±50% jitter = 150–450ms)
    public final long   requestDelayMs;

    // HTTP client timeouts (ms)
    public final int    httpConnectTimeoutMs;
    public final int    httpReadTimeoutMs;

    // Telegram notifications (optional — leave blank to disable)
    public final String telegramBotToken;
    public final String telegramChatId;

    private AppConfig(
            String mongoHost, int mongoPort,
            String mongoDatabase, String mongoUsername, String mongoPassword,
            String srealityBaseUrl, int perPage, int maxEstates,
            int httpConnectTimeoutMs, int httpReadTimeoutMs,
            String telegramBotToken, String telegramChatId,
            long requestDelayMs) {
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
        this.telegramBotToken     = telegramBotToken;
        this.telegramChatId       = telegramChatId;
        this.requestDelayMs       = requestDelayMs;
    }

    /** Factory for tests — bypasses environment variables entirely. */
    public static AppConfig forTesting(
            String mongoHost, int mongoPort,
            String mongoDatabase, String mongoUsername, String mongoPassword,
            String srealityBaseUrl, int perPage, int maxEstates,
            int httpConnectTimeoutMs, int httpReadTimeoutMs) {
        return new AppConfig(
            mongoHost, mongoPort, mongoDatabase, mongoUsername, mongoPassword,
            srealityBaseUrl, perPage, maxEstates,
            httpConnectTimeoutMs, httpReadTimeoutMs,
            "", "",  // Telegram disabled in tests
            300L    // default delay for tests
        );
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
            Integer.parseInt(env("HTTP_READ_TIMEOUT_MS",    "30000")),
            env("TELEGRAM_BOT_TOKEN", ""),
            env("TELEGRAM_CHAT_ID",   ""),
            Long.parseLong(env("REQUEST_DELAY_MS", "300"))
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
