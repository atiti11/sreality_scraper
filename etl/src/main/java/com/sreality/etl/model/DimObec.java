package com.sreality.etl.model;

import org.locationtech.jts.geom.Geometry;

/**
 * Dimension: obec (municipality) — child of okres.
 * Sourced from RUIAN layer 12. Carries optional CSU demographic data. SCD Type 1.
 *
 * kodOkresu carries the raw RUIAN "okres" integer FK (→ okres.kod) as a String.
 * It is used by PostgresLoader.upsertObec() to resolve the okres_id FK
 * via a subquery: SELECT id FROM dim_okres WHERE kod_okresu = kodOkresu.
 * It is NOT stored as a column in dim_obec — only the resolved okres_id is.
 *
 * The JTS geometry (polygon from RUIAN layer 12) is held in memory during
 * the ETL run for point-in-polygon spatial join. It is NOT persisted to
 * PostgreSQL — only surrogate keys are stored there.
 */
public record DimObec(
    int      id,
    String   kodObce,
    String   nazevObce,
    int      okresId,            // FK → dim_okres.id (0 until resolved by PostgresLoader)
    String   kodOkresu,          // raw RUIAN okres code — used only during load, not stored
    // CSU demographics — nullable
    Integer  population,
    Double   populationDensity,  // per km²
    Double   areaKm2,
    Double   avgAge,
    Double   unemploymentPct,
    // polygon geometry for spatial join (in-memory only, not stored in PG)
    Geometry geometry,
    // centroid fallback for STRtree index
    double   centroidLat,
    double   centroidLon
) {
    public DimObec withId(int id) {
        return new DimObec(id, kodObce, nazevObce, okresId, kodOkresu,
            population, populationDensity, areaKm2, avgAge, unemploymentPct,
            geometry, centroidLat, centroidLon);
    }
}
