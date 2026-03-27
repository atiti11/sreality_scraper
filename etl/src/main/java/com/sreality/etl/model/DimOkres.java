package com.sreality.etl.model;

/**
 * Dimension: okres (district) — child of kraj.
 * Sourced from RUIAN layer 15. SCD Type 1.
 *
 * kodVusc carries the raw RUIAN "vusc" integer FK (→ kraj.kod) as a String.
 * It is used by PostgresLoader.upsertOkres() to resolve the kraj_id FK
 * via a subquery: SELECT id FROM dim_kraj WHERE kod_kraje = kodVusc.
 * It is NOT stored as a column in dim_okres — only the resolved kraj_id is.
 */
public record DimOkres(
    int    id,
    String kodOkresu,
    String nazevOkresu,
    int    krajId,      // FK → dim_kraj.id (0 until resolved by PostgresLoader)
    String kodVusc      // raw RUIAN vusc code — used only during load, not stored
) {
    public DimOkres withId(int id) {
        return new DimOkres(id, kodOkresu, nazevOkresu, krajId, kodVusc);
    }
}
