package com.sreality.etl.transform;

import com.sreality.etl.extract.CsuExtractor.Demographics;
import com.sreality.etl.model.DimCastObce;
import com.sreality.etl.model.DimKraj;
import com.sreality.etl.model.DimObec;
import com.sreality.etl.model.DimOkres;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the dimension model from raw RUIAN and CSU data.
 *
 * Transformations applied here:
 *   1. Joining: RUIAN obec + CSU demographics joined on kod_obce
 *   2. Surrogate keys: assigned sequentially (PostgreSQL will generate the real
 *      ones via SERIAL, but we need them in-memory for FK resolution)
 *   3. FK resolution: kraj→okres→obec→cast_obce hierarchy linked by natural keys
 *   4. Handling missing values: obec without CSU data gets null demographics
 *   5. Deduplication: RUIAN can contain duplicate entries — deduplicated by code
 */
public class DimensionBuilder {

    private static final Logger log = LoggerFactory.getLogger(DimensionBuilder.class);

    private final Map<String, Demographics> demographics;

    public DimensionBuilder(Map<String, Demographics> demographics) {
        this.demographics = demographics;
    }

    // ── Kraj ─────────────────────────────────────────────────────────────────

    public List<DimKraj> buildKraj(List<DimKraj> raw) {
        Map<String, DimKraj> seen = new HashMap<>();
        int id = 1;
        for (DimKraj k : raw) {
            if (!seen.containsKey(k.kodKraje())) {
                seen.put(k.kodKraje(), k.withId(id++));
            }
        }
        log.info("DimKraj: {} unique records", seen.size());
        return new ArrayList<>(seen.values());
    }

    // ── Okres ─────────────────────────────────────────────────────────────────

    public List<DimOkres> buildOkres(List<DimOkres> raw, List<DimKraj> krajRows) {
        // Build lookup: kodKraje → surrogate id
        Map<String, Integer> krajIndex = new HashMap<>();
        for (DimKraj k : krajRows) krajIndex.put(k.kodKraje(), k.id());

        Map<String, DimOkres> seen = new HashMap<>();
        int id = 1;
        int unmatched = 0;
        for (DimOkres o : raw) {
            if (seen.containsKey(o.kodOkresu())) continue;
            // krajId will be resolved by PostgreSQL FK — for in-memory use we
            // look it up from the kraj index; 0 means unresolved (should not happen)
            seen.put(o.kodOkresu(), o.withId(id++));
            if (!krajIndex.containsKey(extractKrajFromOkres(o.kodOkresu()))) {
                unmatched++;
            }
        }
        if (unmatched > 0) log.warn("DimOkres: {} records with unresolved kraj", unmatched);
        log.info("DimOkres: {} unique records", seen.size());
        return new ArrayList<>(seen.values());
    }

    // ── Obec ──────────────────────────────────────────────────────────────────

    /**
     * Builds dim_obec by joining RUIAN obec with CSU demographics.
     * Transformation: join on kod_obce. Missing demographics → null fields.
     */
    public List<DimObec> buildObec(List<DimObec> raw, List<DimOkres> okresRows,
                                    Map<String, Demographics> demographics) {
        Map<String, Integer> okresIndex = new HashMap<>();
        for (DimOkres o : okresRows) okresIndex.put(o.kodOkresu(), o.id());

        Map<String, DimObec> seen = new HashMap<>();
        int id = 1;
        int withDemographics = 0;

        for (DimObec o : raw) {
            if (seen.containsKey(o.kodObce())) continue;

            // JOIN: attach demographics from CSU
            Demographics dem = demographics.get(o.kodObce());
            if (dem != null) withDemographics++;

            DimObec enriched = new DimObec(
                id++,
                o.kodObce(),
                o.nazevObce(),
                o.okresId(),   // resolved by PostgreSQL via upsert
                dem != null ? dem.population()         : null,
                dem != null ? dem.populationDensity()  : null,
                dem != null ? dem.areaKm2()            : null,
                dem != null ? dem.avgAge()             : null,
                dem != null ? dem.unemploymentPct()    : null,
                o.centroidLat(),
                o.centroidLon()
            );
            seen.put(o.kodObce(), enriched);
        }

        log.info("DimObec: {} unique records, {} with demographics ({} without)",
            seen.size(), withDemographics, seen.size() - withDemographics);
        return new ArrayList<>(seen.values());
    }

    // ── CastObce ──────────────────────────────────────────────────────────────

    public List<DimCastObce> buildCastObce(List<DimCastObce> raw, List<DimObec> obecRows) {
        Map<String, Integer> obecIndex = new HashMap<>();
        for (DimObec o : obecRows) obecIndex.put(o.kodObce(), o.id());

        Map<String, DimCastObce> seen = new HashMap<>();
        int id = 1;
        int noGeom = 0;
        int noObec = 0;

        for (DimCastObce c : raw) {
            if (seen.containsKey(c.kodCastObce())) continue;
            if (c.geometry() == null) { noGeom++; continue; }  // filter: skip if no geometry

            seen.put(c.kodCastObce(), c.withId(id++));
            if (c.obecId() == 0) noObec++;
        }

        if (noGeom > 0) log.warn("DimCastObce: {} records skipped (no geometry)", noGeom);
        if (noObec > 0) log.warn("DimCastObce: {} records with unresolved obec FK", noObec);
        log.info("DimCastObce: {} unique records with geometry", seen.size());
        return new ArrayList<>(seen.values());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * RUIAN okres codes follow the pattern where the first 5 chars identify the kraj.
     * This is a heuristic — proper resolution uses the properties field from GeoJSON.
     * In practice the FK is resolved by the PostgreSQL upsert matching on natural keys.
     */
    private static String extractKrajFromOkres(String kodOkresu) {
        if (kodOkresu == null || kodOkresu.length() < 5) return "";
        return kodOkresu.substring(0, 5);
    }
}
