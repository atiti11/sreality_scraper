package com.sreality.dashboard.sql;

/**
 * CSU socio-economics SQL templates.
 *
 * <p>CSU publishes population, births, deaths, unemployment_pct, etc. on
 * different schedules; in a fresh year, several columns can be NULL while
 * others have already been released. A naive {@code ORDER BY year DESC
 * LIMIT 1} would surface those gaps as missing stats in the UI even
 * though older years have the data. Instead, for each column we pick the
 * latest year whose value is non-NULL, using an {@code array_agg ... FILTER
 * (WHERE ... IS NOT NULL)} trick that's stable for a single obec lookup.</p>
 */
public final class CsuStats {

    private CsuStats() {}

    /**
     * Latest non-NULL value per column for a single obec. Takes one
     * parameter: the obec id (JDBC {@code ?}).
     */
    public static final String LATEST_NON_NULL_PER_OBEC = """
        SELECT
            MAX(year)                                                                                              AS year,
            (array_agg(population        ORDER BY year DESC) FILTER (WHERE population        IS NOT NULL))[1]      AS population,
            (array_agg(divorces          ORDER BY year DESC) FILTER (WHERE divorces          IS NOT NULL))[1]      AS divorces,
            (array_agg(marriages         ORDER BY year DESC) FILTER (WHERE marriages         IS NOT NULL))[1]      AS marriages,
            (array_agg(births            ORDER BY year DESC) FILTER (WHERE births            IS NOT NULL))[1]      AS births,
            (array_agg(deaths            ORDER BY year DESC) FILTER (WHERE deaths            IS NOT NULL))[1]      AS deaths,
            (array_agg(unemployment_pct  ORDER BY year DESC) FILTER (WHERE unemployment_pct  IS NOT NULL))[1]      AS unemployment_pct,
            (array_agg(migration_balance ORDER BY year DESC) FILTER (WHERE migration_balance IS NOT NULL))[1]      AS migration_balance
        FROM fact_obec_stats
        WHERE obec_id = ?
        """;

    /**
     * Aggregated per-column-latest pick across many obce, then SUM (counts)
     * or AVG (unemployment %) across them. The {@code %s} slot is the
     * {@code FROM/WHERE} fragment that restricts which obce contribute —
     * pass {@code JOIN_AND_FILTER_OKRES} or {@code JOIN_AND_FILTER_KRAJ}.
     */
    public static final String AGGREGATED_TEMPLATE = """
        SELECT
            MAX(per_obec.year)                                AS year,
            SUM(per_obec.population)                          AS population,
            SUM(per_obec.divorces)                            AS divorces,
            SUM(per_obec.marriages)                           AS marriages,
            SUM(per_obec.births)                              AS births,
            SUM(per_obec.deaths)                              AS deaths,
            AVG(per_obec.unemployment_pct)::NUMERIC(5,2)      AS unemployment_pct,
            SUM(per_obec.migration_balance)                   AS migration_balance
        FROM (
            SELECT
                s.obec_id,
                MAX(s.year)                                                                                                AS year,
                (array_agg(s.population        ORDER BY s.year DESC) FILTER (WHERE s.population        IS NOT NULL))[1]    AS population,
                (array_agg(s.divorces          ORDER BY s.year DESC) FILTER (WHERE s.divorces          IS NOT NULL))[1]    AS divorces,
                (array_agg(s.marriages         ORDER BY s.year DESC) FILTER (WHERE s.marriages         IS NOT NULL))[1]    AS marriages,
                (array_agg(s.births            ORDER BY s.year DESC) FILTER (WHERE s.births            IS NOT NULL))[1]    AS births,
                (array_agg(s.deaths            ORDER BY s.year DESC) FILTER (WHERE s.deaths            IS NOT NULL))[1]    AS deaths,
                (array_agg(s.unemployment_pct  ORDER BY s.year DESC) FILTER (WHERE s.unemployment_pct  IS NOT NULL))[1]    AS unemployment_pct,
                (array_agg(s.migration_balance ORDER BY s.year DESC) FILTER (WHERE s.migration_balance IS NOT NULL))[1]    AS migration_balance
            FROM   fact_obec_stats s
            %s
            GROUP  BY s.obec_id
        ) per_obec
        """;

    public static final String JOIN_AND_FILTER_OKRES =
        "JOIN dim_obec o ON o.id = s.obec_id WHERE o.okres_id = ?";

    public static final String JOIN_AND_FILTER_KRAJ =
        "JOIN dim_obec  o ON o.id = s.obec_id "
      + "JOIN dim_okres r ON r.id = o.okres_id "
      + "WHERE r.kraj_id = ?";
}
