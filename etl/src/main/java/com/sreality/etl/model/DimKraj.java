package com.sreality.etl.model;

/**
 * Dimension: kraj (region) — top of the location hierarchy.
 * Sourced from RUIAN. SCD Type 1 — always overwritten with latest data.
 */
public record DimKraj(
    int    id,          // surrogate key (assigned by PostgreSQL SERIAL)
    String kodKraje,    // RUIAN code, e.g. "CZ010"
    String nazevKraje   // e.g. "Hlavní město Praha"
) {
    /** Used before the surrogate key is assigned (pre-load). */
    public DimKraj withId(int id) {
        return new DimKraj(id, kodKraje, nazevKraje);
    }
}
