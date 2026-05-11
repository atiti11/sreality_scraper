package com.sreality.dashboard;

import java.util.Locale;

/**
 * Top-level property categories. The wire token (lowercase, e.g.
 * {@code "apartment"}) matches the Python service's query-string vocabulary.
 */
public enum PropertyType {
    APARTMENT, HOUSE, LAND, COMMERCIAL;

    /** Lowercase query-string token: {@code "apartment"}, … */
    public String token() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Parse a single query-string token. Throws if unknown. */
    public static PropertyType fromQueryToken(String tok) {
        return switch (tok.toLowerCase(Locale.ROOT)) {
            case "apartment"  -> APARTMENT;
            case "house"      -> HOUSE;
            case "land"       -> LAND;
            case "commercial" -> COMMERCIAL;
            default -> throw new IllegalArgumentException("Unknown property type: " + tok);
        };
    }
}
