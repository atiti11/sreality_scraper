package com.sreality.etl.model;

/**
 * Dimension: obec (municipality) — child of okres.
 * Carries CSU demographic data. SCD Type 1 — always newest data.
 *
 * Demographics are nullable: not every municipality has full CSU coverage.
 */
public record DimObec(
    int     id,
    String  kodObce,
    String  nazevObce,
    int     okresId,            // FK → dim_okres.id
    // CSU demographics — nullable
    Integer population,
    Double  populationDensity,  // per km²
    Double  areaKm2,
    Double  avgAge,
    Double  unemploymentPct,
    // geometry centroid for spatial index (not stored in PG, used during ETL only)
    double  centroidLat,
    double  centroidLon
) {
    public DimObec withId(int id) {
        return new DimObec(id, kodObce, nazevObce, okresId,
            population, populationDensity, areaKm2, avgAge, unemploymentPct,
            centroidLat, centroidLon);
    }
}
