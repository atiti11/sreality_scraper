package com.sreality.etl.model;

/**
 * Dimension: okres (district) — child of kraj.
 * Sourced from RUIAN. SCD Type 1.
 */
public record DimOkres(
    int    id,
    String kodOkresu,
    String nazevOkresu,
    int    krajId       // FK → dim_kraj.id
) {
    public DimOkres withId(int id) {
        return new DimOkres(id, kodOkresu, nazevOkresu, krajId);
    }
}
