package com.sreality.pipeline.ruian.model;

/**
 * CastObce with bounding box derived from centroid + fixed expansion.
 *
 * Expansion ~500m: 0.0045° lat, 0.006° lon (generous to handle bbox overlaps;
 * centroid distance used as tiebreaker when multiple bbox candidates match).
 */
public record CastObceRecord(
        String kodCastObce,
        String nazevCastObce,
        String kodObce,
        double centroidLat,
        double centroidLon,
        double bboxMinLat,
        double bboxMinLon,
        double bboxMaxLat,
        double bboxMaxLon) {

    private static final double LAT_EXP = 0.0045;
    private static final double LON_EXP = 0.006;

    public static CastObceRecord fromCentroid(
            String kod, String nazev, String kodObce, double lat, double lon) {
        return new CastObceRecord(
            kod, nazev, kodObce, lat, lon,
            lat - LAT_EXP, lon - LON_EXP,
            lat + LAT_EXP, lon + LON_EXP);
    }
}
