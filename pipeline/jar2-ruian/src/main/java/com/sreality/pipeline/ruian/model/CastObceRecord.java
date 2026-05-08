package com.sreality.pipeline.ruian.model;

/**
 * Cast obce dimension record.
 *
 * Carries:
 *   - identity   : kod / nazev / parent kodObce
 *   - centroid   : RUIAN DefinicniBod, reprojected from S-JTSK to WGS84
 *   - bbox       : envelope of the boundary polygon, kept for human queries
 *                  and as a backup spatial filter
 *   - geomWkt    : authoritative MULTIPOLYGON in WGS84 (lon lat order),
 *                  null when the RUIAN record has no boundary geometry
 *
 * SpatialJoiner uses {@code geom} (loaded from {@link #geomWkt()}) via
 * ST_Contains for accurate point-in-polygon resolution.
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
        double bboxMaxLon,
        String geomWkt) {

    // Fallback expansion (~500 m) used when only the centroid is known.
    private static final double LAT_EXP = 0.0045;
    private static final double LON_EXP = 0.006;

    /**
     * Fallback factory: only the DefinicniBod centroid is available.
     * Bbox is faked from centroid ± fixed expansion; geomWkt is null.
     */
    public static CastObceRecord fromCentroid(
            String kod, String nazev, String kodObce, double lat, double lon) {
        return new CastObceRecord(
            kod, nazev, kodObce, lat, lon,
            lat - LAT_EXP, lon - LON_EXP,
            lat + LAT_EXP, lon + LON_EXP,
            null);
    }

    /**
     * Polygon-aware factory. Bbox is the polygon envelope, geomWkt is the
     * MULTIPOLYGON WKT in WGS84.
     */
    public static CastObceRecord fromPolygon(
            String kod, String nazev, String kodObce,
            double centroidLat, double centroidLon,
            double bboxMinLat, double bboxMinLon,
            double bboxMaxLat, double bboxMaxLon,
            String geomWkt) {
        return new CastObceRecord(
            kod, nazev, kodObce, centroidLat, centroidLon,
            bboxMinLat, bboxMinLon, bboxMaxLat, bboxMaxLon,
            geomWkt);
    }
}
