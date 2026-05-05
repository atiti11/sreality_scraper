package com.sreality.pipeline.csu.model;

/**
 * One row of CSU statistics for a municipality and year.
 * Null values represent missing data (CSU uses "-" or "." as missing markers).
 */
public record ObecStatsRecord(
    String  kodObce,
    int     year,
    Integer population,
    Integer births,
    Integer deaths,
    Integer migrationBalance,
    Integer marriages,
    Integer divorces,
    Double  unemploymentPct) {}
