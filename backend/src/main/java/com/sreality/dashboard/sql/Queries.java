package com.sreality.dashboard.sql;

import com.sreality.dashboard.DealType;
import com.sreality.dashboard.PropertyType;
import com.sreality.dashboard.RegionLevel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * SQL builders for the dashboard. Centralises:
 *
 * <ul>
 *   <li>The {@code (property_type, deal_type) → fact table} mapping (so
 *       other modules don't have to know that, say, {@code land} has no
 *       pre-computed {@code price_per_m2} column).</li>
 *   <li>The dynamic {@code UNION ALL} CTE used by every aggregation query.</li>
 *   <li>Centroid / aggregation / region-filter SQL for the marker, geo,
 *       region-stats and listings endpoints.</li>
 * </ul>
 *
 * <p>All SQL fragments use {@code ?} placeholders (JDBC positional binding).
 * Where the original Python version inlined {@code $REGION_ID} placeholders,
 * the Java version emits {@code ?} directly — callers stitch the SQL
 * together and set the parameters in order.</p>
 */
public final class Queries {

    private Queries() {}

    /** Per-(property × deal) fact-table configuration. */
    public record TableCfg(
        String table,
        String priceCol,
        String perM2Col,      // null when the table doesn't expose it precomputed
        String areaCol
    ) {
        /** SQL expression that yields price-per-m² for a row in this table. */
        public String perM2Expr() {
            if (perM2Col != null) return perM2Col;
            return "(" + priceCol + "::NUMERIC / NULLIF(" + areaCol + ", 0))";
        }
    }

    public record TableKey(PropertyType ptype, DealType deal) {}

    public static final Map<TableKey, TableCfg> TABLES = Map.ofEntries(
        Map.entry(new TableKey(PropertyType.APARTMENT,  DealType.SALE),
                  new TableCfg("fact_apartment_sale",     "price_asked_czk",        "price_asked_per_m2",   "usable_area_m2")),
        Map.entry(new TableKey(PropertyType.APARTMENT,  DealType.RENT),
                  new TableCfg("fact_apartment_rent",     "price_monthly_czk",      "price_monthly_per_m2", "usable_area_m2")),
        Map.entry(new TableKey(PropertyType.APARTMENT,  DealType.AUCTION),
                  new TableCfg("fact_apartment_auction",  "price_starting_bid_czk", null,                   "usable_area_m2")),
        Map.entry(new TableKey(PropertyType.HOUSE,      DealType.SALE),
                  new TableCfg("fact_house_sale",         "price_asked_czk",        "price_asked_per_m2",   "usable_area_m2")),
        Map.entry(new TableKey(PropertyType.HOUSE,      DealType.RENT),
                  new TableCfg("fact_house_rent",         "price_monthly_czk",      "price_monthly_per_m2", "usable_area_m2")),
        Map.entry(new TableKey(PropertyType.HOUSE,      DealType.AUCTION),
                  new TableCfg("fact_house_auction",      "price_starting_bid_czk", null,                   "usable_area_m2")),
        Map.entry(new TableKey(PropertyType.LAND,       DealType.SALE),
                  new TableCfg("fact_land_sale",          "price_asked_czk",        "price_asked_per_m2",   "plot_area_m2")),
        Map.entry(new TableKey(PropertyType.LAND,       DealType.RENT),
                  new TableCfg("fact_land_rent",          "price_monthly_czk",      null,                   "plot_area_m2")),
        Map.entry(new TableKey(PropertyType.LAND,       DealType.AUCTION),
                  new TableCfg("fact_land_auction",       "price_starting_bid_czk", null,                   "plot_area_m2")),
        Map.entry(new TableKey(PropertyType.COMMERCIAL, DealType.SALE),
                  new TableCfg("fact_commercial_sale",    "price_asked_czk",        "price_asked_per_m2",   "usable_area_m2")),
        Map.entry(new TableKey(PropertyType.COMMERCIAL, DealType.RENT),
                  new TableCfg("fact_commercial_rent",    "price_monthly_czk",      "price_monthly_per_m2", "usable_area_m2")),
        Map.entry(new TableKey(PropertyType.COMMERCIAL, DealType.AUCTION),
                  new TableCfg("fact_commercial_auction", "price_starting_bid_czk", null,                   "usable_area_m2"))
    );

    // ------------------------------------------------------------------------
    // Facts CTE
    // ------------------------------------------------------------------------

    /**
     * Build a {@code facts} CTE that yields a homogeneous
     * {@code (property_type, obec_id, cast_obce_id, hash_id, price, per_m2,
     * area, …)} stream by UNION-ing every selected property type for the
     * given deal.
     *
     * @param extraWhere appended verbatim to each branch's WHERE clause
     *                   (must already start with {@code "AND "} when
     *                   non-empty). Pass {@code ""} when no extra filter.
     */
    public static String buildFactsCte(
        DealType deal,
        Collection<PropertyType> propertyTypes,
        String extraWhere
    ) {
        List<String> parts = new ArrayList<>();
        for (PropertyType ptype : propertyTypes) {
            TableCfg cfg = TABLES.get(new TableKey(ptype, deal));
            if (cfg == null) continue;
            parts.add("""
                SELECT '%s'::TEXT             AS property_type,
                       f.obec_id,
                       f.cast_obce_id,
                       f.hash_id,
                       f.gps_lat, f.gps_lon,
                       f.%s::BIGINT           AS price,
                       %s::NUMERIC            AS per_m2,
                       f.%s::NUMERIC          AS area,
                       f.first_seen_date,
                       f.sreality_url
                FROM   %s f
                WHERE  f.valid_to IS NULL
                  AND  f.is_active = TRUE
                  AND  f.%s IS NOT NULL
                  AND  f.%s > 0
                  %s
                """.formatted(
                    ptype.token(),
                    cfg.priceCol(),
                    cfg.perM2Expr(),
                    cfg.areaCol(),
                    cfg.table(),
                    cfg.priceCol(),
                    cfg.priceCol(),
                    extraWhere
                ));
        }
        if (parts.isEmpty()) {
            // Defensive fallback — schema-shaped NULL row so downstream
            // queries can still parse, but produces zero rows.
            parts.add(
                "SELECT NULL::TEXT AS property_type, NULL::INT AS obec_id, "
              + "NULL::INT AS cast_obce_id, NULL::BIGINT AS hash_id, "
              + "NULL::NUMERIC AS gps_lat, NULL::NUMERIC AS gps_lon, "
              + "NULL::BIGINT AS price, NULL::NUMERIC AS per_m2, "
              + "NULL::NUMERIC AS area, NULL::DATE AS first_seen_date, "
              + "NULL::TEXT AS sreality_url WHERE FALSE"
            );
        }
        return String.join("\nUNION ALL\n", parts);
    }

    // ------------------------------------------------------------------------
    // Marker (centroid) queries — used when polygons aren't loaded.
    // ------------------------------------------------------------------------

    /**
     * Returns SQL with one row per region, including a centroid. For
     * {@code obec} / {@code cast_obce} levels the query expects 4
     * parameters {@code (minlon, minlat, maxlon, maxlat)} — pass NULL for
     * all four to disable the bbox filter.
     */
    public static String markersQuery(RegionLevel level) {
        return switch (level) {
            case KRAJ -> """
                SELECT k.id, k.kod_kraje AS code, k.nazev_kraje AS name,
                       NULL::INT AS parent_id,
                       AVG(c.centroid_lat) AS lat,
                       AVG(c.centroid_lon) AS lon
                FROM   dim_kraj k
                JOIN   dim_okres   r ON r.kraj_id = k.id
                JOIN   dim_obec    o ON o.okres_id = r.id
                JOIN   dim_cast_obce c ON c.obec_id = o.id
                   AND c.centroid_lat IS NOT NULL AND c.centroid_lon IS NOT NULL
                GROUP  BY k.id, k.kod_kraje, k.nazev_kraje
                """;
            case OKRES -> """
                SELECT r.id, r.kod_okresu AS code, r.nazev_okresu AS name,
                       r.kraj_id AS parent_id,
                       AVG(c.centroid_lat) AS lat,
                       AVG(c.centroid_lon) AS lon
                FROM   dim_okres r
                JOIN   dim_obec o ON o.okres_id = r.id
                JOIN   dim_cast_obce c ON c.obec_id = o.id
                   AND c.centroid_lat IS NOT NULL AND c.centroid_lon IS NOT NULL
                GROUP  BY r.id, r.kod_okresu, r.nazev_okresu, r.kraj_id
                """;
            case OBEC -> """
                SELECT o.id, o.kod_obce AS code, o.nazev_obce AS name,
                       o.okres_id AS parent_id,
                       AVG(c.centroid_lat) AS lat,
                       AVG(c.centroid_lon) AS lon
                FROM   dim_obec o
                JOIN   dim_cast_obce c ON c.obec_id = o.id
                   AND c.centroid_lat IS NOT NULL AND c.centroid_lon IS NOT NULL
                WHERE  o.is_active = TRUE
                  AND  (?::FLOAT IS NULL OR (
                       c.centroid_lon BETWEEN ? AND ?
                   AND c.centroid_lat BETWEEN ? AND ?))
                GROUP  BY o.id, o.kod_obce, o.nazev_obce, o.okres_id
                """;
            case CAST_OBCE -> """
                SELECT c.id, c.kod_cast_obce AS code, c.nazev_cast_obce AS name,
                       c.obec_id AS parent_id,
                       c.centroid_lat AS lat,
                       c.centroid_lon AS lon
                FROM   dim_cast_obce c
                WHERE  c.centroid_lat IS NOT NULL AND c.centroid_lon IS NOT NULL
                  AND  (?::FLOAT IS NULL OR (
                       c.centroid_lon BETWEEN ? AND ?
                   AND c.centroid_lat BETWEEN ? AND ?))
                """;
        };
    }

    // ------------------------------------------------------------------------
    // GeoJSON polygon queries (only kraj / okres in this snapshot).
    // ------------------------------------------------------------------------

    private static final Map<RegionLevel, Double> SIMPLIFY_TOLERANCE = Map.of(
        RegionLevel.KRAJ,      0.005,
        RegionLevel.OKRES,     0.002,
        RegionLevel.OBEC,      0.0008,
        RegionLevel.CAST_OBCE, 0.0003
    );

    public static String geoQuery(RegionLevel level) {
        double tol = SIMPLIFY_TOLERANCE.getOrDefault(level, 0.001);
        return switch (level) {
            case KRAJ -> """
                SELECT id, kod_kraje AS code, nazev_kraje AS name,
                       NULL::INT AS parent_id,
                       ST_AsGeoJSON(ST_SimplifyPreserveTopology(geom, %s))::JSON AS geom
                FROM   dim_kraj
                WHERE  geom IS NOT NULL
                """.formatted(tol);
            case OKRES -> """
                SELECT id, kod_okresu AS code, nazev_okresu AS name,
                       kraj_id AS parent_id,
                       ST_AsGeoJSON(ST_SimplifyPreserveTopology(geom, %s))::JSON AS geom
                FROM   dim_okres
                WHERE  geom IS NOT NULL
                """.formatted(tol);
            default -> throw new IllegalArgumentException(
                "geoQuery: level=" + level + " not supported (only kraj | okres)."
            );
        };
    }

    // ------------------------------------------------------------------------
    // Aggregation queries (run over a ``facts`` CTE in the outer SQL).
    // ------------------------------------------------------------------------

    public static String aggregationQuery(RegionLevel level) {
        return switch (level) {
            case KRAJ -> """
                SELECT k.id AS region_id,
                       COUNT(*) AS n,
                       AVG(f.per_m2)::NUMERIC(12,2) AS avg_per_m2,
                       PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY f.per_m2)::NUMERIC(12,2) AS median_per_m2
                FROM facts f
                JOIN dim_obec  o ON o.id = f.obec_id
                JOIN dim_okres r ON r.id = o.okres_id
                JOIN dim_kraj  k ON k.id = r.kraj_id
                WHERE f.per_m2 IS NOT NULL AND f.per_m2 > 0
                GROUP BY k.id
                """;
            case OKRES -> """
                SELECT r.id AS region_id,
                       COUNT(*) AS n,
                       AVG(f.per_m2)::NUMERIC(12,2) AS avg_per_m2,
                       PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY f.per_m2)::NUMERIC(12,2) AS median_per_m2
                FROM facts f
                JOIN dim_obec  o ON o.id = f.obec_id
                JOIN dim_okres r ON r.id = o.okres_id
                WHERE f.per_m2 IS NOT NULL AND f.per_m2 > 0
                GROUP BY r.id
                """;
            case OBEC -> """
                SELECT f.obec_id AS region_id,
                       COUNT(*) AS n,
                       AVG(f.per_m2)::NUMERIC(12,2) AS avg_per_m2,
                       PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY f.per_m2)::NUMERIC(12,2) AS median_per_m2
                FROM facts f
                WHERE f.per_m2 IS NOT NULL AND f.per_m2 > 0
                GROUP BY f.obec_id
                """;
            case CAST_OBCE -> """
                SELECT f.cast_obce_id AS region_id,
                       COUNT(*) AS n,
                       AVG(f.per_m2)::NUMERIC(12,2) AS avg_per_m2,
                       PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY f.per_m2)::NUMERIC(12,2) AS median_per_m2
                FROM facts f
                WHERE f.per_m2 IS NOT NULL AND f.per_m2 > 0
                  AND f.cast_obce_id IS NOT NULL
                GROUP BY f.cast_obce_id
                """;
        };
    }

    // ------------------------------------------------------------------------
    // Region context (name + admin chain) for the side panel.
    // ------------------------------------------------------------------------

    public static String regionContextQuery(RegionLevel level) {
        return switch (level) {
            case KRAJ -> """
                SELECT k.id, k.nazev_kraje AS name, NULL::TEXT AS parent_name,
                       NULL::INT AS obec_id_for_stats
                FROM dim_kraj k WHERE k.id = ?
                """;
            case OKRES -> """
                SELECT r.id, r.nazev_okresu AS name, k.nazev_kraje AS parent_name,
                       NULL::INT AS obec_id_for_stats
                FROM dim_okres r JOIN dim_kraj k ON k.id = r.kraj_id WHERE r.id = ?
                """;
            case OBEC -> """
                SELECT o.id, o.nazev_obce AS name,
                       r.nazev_okresu || ', ' || k.nazev_kraje AS parent_name,
                       o.id AS obec_id_for_stats
                FROM dim_obec o
                JOIN dim_okres r ON r.id = o.okres_id
                JOIN dim_kraj  k ON k.id = r.kraj_id
                WHERE o.id = ?
                """;
            case CAST_OBCE -> """
                SELECT c.id, c.nazev_cast_obce AS name,
                       o.nazev_obce || ', ' || r.nazev_okresu AS parent_name,
                       o.id AS obec_id_for_stats
                FROM dim_cast_obce c
                JOIN dim_obec  o ON o.id = c.obec_id
                JOIN dim_okres r ON r.id = o.okres_id
                WHERE c.id = ?
                """;
        };
    }

    // ------------------------------------------------------------------------
    // Region filter clause — AND-able fragment that keeps only fact rows
    // belonging to the selected region. Always uses a single ? placeholder
    // (the caller binds the region id).
    // ------------------------------------------------------------------------

    public static String regionFilterClause(RegionLevel level) {
        return switch (level) {
            case KRAJ -> "EXISTS (SELECT 1 FROM dim_obec o JOIN dim_okres r ON r.id = o.okres_id "
                       + "WHERE o.id = f.obec_id AND r.kraj_id = ?)";
            case OKRES -> "EXISTS (SELECT 1 FROM dim_obec o WHERE o.id = f.obec_id "
                        + "AND o.okres_id = ?)";
            case OBEC -> "f.obec_id = ?";
            // cast_obce — use the pre-computed FK (geom may not be loaded).
            case CAST_OBCE -> "f.cast_obce_id = ?";
        };
    }
}
