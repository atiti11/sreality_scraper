package com.sreality.pipeline.ruian.model;

/**
 * Cast obce dimension record — carries raw S-JTSK coordinates straight from
 * the RUIAN VFR XML; the loader does {@code ST_Transform(... 5514, 4326)} in
 * Postgres to derive WGS84.
 *
 * Why no Java-side reprojection: the in-house {@code SjtskToWgs84} produced
 * wrong values (axis-order + Krovak constant bugs). PostGIS uses PROJ which
 * is the canonical implementation, so we delegate to it.
 *
 * EPSG:5514 axis order is (easting, northing). RUIAN VFR encodes
 * {@code <gml:pos>} accordingly: first value = easting (Y in Czech historical
 * convention, large negative number for CR), second = northing (X, large
 * negative number for CR).
 */
public record CastObceRecord(
        String kodCastObce,
        String nazevCastObce,
        String kodObce,
        double sjtskEasting,    // <gml:pos> first value (≈ -750 000 for Prague)
        double sjtskNorthing,   // <gml:pos> second value (≈ -1 043 000 for Prague)
        String geomWktSjtsk     // MULTIPOLYGON in S-JTSK, or null when no polygon
) {

    /** Plain factory: only the DefinicniBod centroid is available. */
    public static CastObceRecord fromCentroid(
            String kod, String nazev, String kodObce,
            double sjtskEasting, double sjtskNorthing) {
        return new CastObceRecord(kod, nazev, kodObce, sjtskEasting, sjtskNorthing, null);
    }

    /** Polygon-aware factory. */
    public static CastObceRecord fromPolygon(
            String kod, String nazev, String kodObce,
            double sjtskEasting, double sjtskNorthing,
            String geomWktSjtsk) {
        return new CastObceRecord(kod, nazev, kodObce, sjtskEasting, sjtskNorthing, geomWktSjtsk);
    }
}
