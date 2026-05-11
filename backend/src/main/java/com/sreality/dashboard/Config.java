package com.sreality.dashboard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Process-wide config + lightweight query-string parsers.
 *
 * <p>Values come from environment variables (set by docker-compose's
 * {@code env_file}, or directly by the shell in dev). There's no .env
 * loader — python-dotenv walked up from cwd, but a Java service just
 * relies on the container's environment being populated by the compose
 * layer.</p>
 */
public final class Config {

    private Config() {}

    // ---- HTTP --------------------------------------------------------------
    public static String apiHost()  { return envOr("API_HOST", "0.0.0.0"); }
    public static int    apiPort()  { return Integer.parseInt(envOr("API_PORT", "8000")); }

    public static List<String> corsOrigins() {
        String raw = envOr("CORS_ORIGINS", "http://localhost:5173,http://127.0.0.1:5173");
        List<String> out = new ArrayList<>();
        for (String tok : raw.split(",")) {
            String t = tok.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    // ---- Postgres ----------------------------------------------------------
    public static String pgHost()     { return envOr("PG_HOST",     "localhost"); }
    public static int    pgPort()     { return Integer.parseInt(envOr("PG_PORT", "5433")); }
    public static String pgDatabase() { return envOr("PG_DATABASE", "sreality"); }
    public static String pgUsername() { return envOr("PG_USERNAME", "sreality"); }
    public static String pgPassword() { return envOr("PG_PASSWORD", "changeme"); }
    public static String pgSchema()   { return envOr("PG_SCHEMA",   "public"); }
    public static int    pgPoolSize() { return Integer.parseInt(envOr("PG_MAX_POOL_SIZE", "5")); }

    // ---- Basic Auth credentials -------------------------------------------
    // Both must be non-empty to enable the auth gate. If either is missing,
    // AuthGate becomes a no-op (used by uvicorn-style local dev).
    public static String dashboardUser()     { return envOr("DASHBOARD_USER",     ""); }
    public static String dashboardPassword() { return envOr("DASHBOARD_PASSWORD", ""); }

    // ---- Helpers -----------------------------------------------------------
    private static String envOr(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? fallback : v.trim();
    }

    /**
     * Parse the {@code property_types=apartment,house,...} query string.
     * Empty / missing means "all four". Unknown tokens are silently dropped
     * (same forgiving behavior as the Python version).
     */
    public static List<PropertyType> parsePropertyTypes(String raw) {
        if (raw == null || raw.isBlank()) {
            return Arrays.asList(PropertyType.values());
        }
        List<PropertyType> out = new ArrayList<>();
        for (String tok : raw.split(",")) {
            String t = tok.trim().toLowerCase(Locale.ROOT);
            try {
                out.add(PropertyType.fromQueryToken(t));
            } catch (IllegalArgumentException ignored) {
                // skip unknown token
            }
        }
        return out;
    }

    /** Comma-separated {@code minlon,minlat,maxlon,maxlat}. Returns {@code null} when blank. */
    public static double[] parseBbox(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String[] parts = raw.split(",");
        if (parts.length != 4) {
            throw new IllegalArgumentException("bbox must be 'minlon,minlat,maxlon,maxlat'.");
        }
        double[] out = new double[4];
        for (int i = 0; i < 4; i++) {
            out[i] = Double.parseDouble(parts[i].trim());
        }
        return out;
    }
}
