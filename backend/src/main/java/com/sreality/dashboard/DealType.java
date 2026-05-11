package com.sreality.dashboard;

import java.util.Locale;

/**
 * Deal categories. Wire token is lowercase {@code "sale" | "rent" | "auction"}.
 */
public enum DealType {
    SALE, RENT, AUCTION;

    public String token() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static DealType fromQueryToken(String tok) {
        if (tok == null) return SALE;
        return switch (tok.toLowerCase(Locale.ROOT)) {
            case "sale"    -> SALE;
            case "rent"    -> RENT;
            case "auction" -> AUCTION;
            default -> throw new IllegalArgumentException("Unknown deal type: " + tok);
        };
    }
}
