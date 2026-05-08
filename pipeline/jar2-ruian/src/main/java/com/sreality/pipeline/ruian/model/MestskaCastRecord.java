package com.sreality.pipeline.ruian.model;

/**
 * Mestska cast / mestsky obvod dimension record.
 *
 * Covers two RUIAN entities at once:
 *   - MOP  (Mestsky obvod v Praze) — 22 records: Praha 1 … Praha 22
 *   - MOMC (Mestska cast / mestsky obvod) — ~150 records: Praha-Holesovice,
 *          Brno-Zabovresky, Ostrava-Jih, Plzen 3, Liberec XV-Starý Harcov, …
 *
 * Stored together in {@code dim_mestska_cast} (distinguished by {@code typ}).
 *
 * Same S-JTSK passthrough convention as {@link CastObceRecord}: RUIAN's raw
 * EPSG:5514 values are kept; the loader does {@code ST_Transform} in Postgres.
 */
public record MestskaCastRecord(
        String kodMestskaCast,
        String nazevMestskaCast,
        String kodObce,
        String typ,             // "MOP" or "MOMC"
        double sjtskEasting,
        double sjtskNorthing,
        String geomWktSjtsk     // null if RUIAN provides no boundary
) {

    public static MestskaCastRecord fromCentroid(
            String kod, String nazev, String kodObce, String typ,
            double sjtskEasting, double sjtskNorthing) {
        return new MestskaCastRecord(kod, nazev, kodObce, typ, sjtskEasting, sjtskNorthing, null);
    }

    public static MestskaCastRecord fromPolygon(
            String kod, String nazev, String kodObce, String typ,
            double sjtskEasting, double sjtskNorthing,
            String geomWktSjtsk) {
        return new MestskaCastRecord(kod, nazev, kodObce, typ, sjtskEasting, sjtskNorthing, geomWktSjtsk);
    }
}
