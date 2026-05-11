package com.sreality.dashboard;

import java.util.Locale;

/**
 * Admin levels in the RUIAN hierarchy. Wire tokens: {@code "kraj" | "okres"
 * | "obec" | "cast_obce"}.
 */
public enum RegionLevel {
    KRAJ, OKRES, OBEC, CAST_OBCE;

    public String token() {
        return switch (this) {
            case KRAJ      -> "kraj";
            case OKRES     -> "okres";
            case OBEC      -> "obec";
            case CAST_OBCE -> "cast_obce";
        };
    }

    public static RegionLevel fromPath(String tok) {
        if (tok == null) throw new IllegalArgumentException("level must be set");
        return switch (tok.toLowerCase(Locale.ROOT)) {
            case "kraj"      -> KRAJ;
            case "okres"     -> OKRES;
            case "obec"      -> OBEC;
            case "cast_obce" -> CAST_OBCE;
            default -> throw new IllegalArgumentException(
                "level must be one of kraj|okres|obec|cast_obce, got " + tok);
        };
    }
}
