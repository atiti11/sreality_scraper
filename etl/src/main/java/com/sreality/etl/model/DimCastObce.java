package com.sreality.etl.model;

import org.locationtech.jts.geom.Geometry;

/**
 * Dimension: cast_obce (part of municipality) — child of obec.
 * Finest geographic granularity. Used for Praha MČ, Brno obvody, etc.
 * SCD Type 1. Layer 11 returns POINTS — no polygon geometry.
 *
 * kodObce carries the raw RUIAN "obec" integer FK (→ obec.kod) as a String.
 * It is used by PostgresLoader.upsertCastObce() to resolve the obec_id FK
 * via a subquery: SELECT id FROM dim_obec WHERE kod_obce = kodObce.
 * It is NOT stored as a column in dim_cast_obce — only the resolved obec_id is.
 */
public record DimCastObce(
    int      id,
    String   kodCastObce,
    String   nazevCastObce,
    int      obecId,        // FK → dim_obec.id (0 until resolved by PostgresLoader)
    String   kodObce,       // raw RUIAN obec code — used only during load, not stored
    Geometry geometry       // always null — layer 11 returns points not polygons
) {
    public DimCastObce withId(int id) {
        return new DimCastObce(id, kodCastObce, nazevCastObce, obecId, kodObce, geometry);
    }
}
