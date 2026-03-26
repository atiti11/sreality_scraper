package com.sreality.etl.model;

import org.locationtech.jts.geom.Geometry;

/**
 * Dimension: cast_obce (part of municipality) — child of obec.
 * Finest geographic granularity. Used for Praha MČ, Brno obvody, etc.
 * SCD Type 1.
 *
 * The JTS geometry is held in memory during the ETL run for spatial joins.
 * It is NOT persisted to PostgreSQL (too large, not needed for analytics).
 */
public record DimCastObce(
    int      id,
    String   kodCastObce,
    String   nazevCastObce,
    int      obecId,        // FK → dim_obec.id
    // held in memory for spatial join, not stored in PG
    Geometry geometry
) {
    public DimCastObce withId(int id) {
        return new DimCastObce(id, kodCastObce, nazevCastObce, obecId, geometry);
    }
}
