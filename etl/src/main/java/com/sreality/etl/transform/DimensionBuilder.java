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
 * Transformations applied:
 *   1. Deduplication by RUIAN code
 *   2. JOIN: RUIAN obec + CSU demographics on kod_obce
 *   3. Geometry carried through on DimObec for SpatialJoiner
 *   4. FK codes (kodVusc, kodOkresu, kodObce) preserved for PostgresLoader subqueries
 *   5. Synthetic okres rows for statutory cities (Praha etc.) that have
 *      okres=null in RUIAN because they are their own administrative unit
 *
 * Praha special case:
 *   Praha (kod_obce=554782) returns okres=null in RUIAN layer 12 because
 *   it has no parent okres — it IS its own okres and kraj simultaneously.
 *   Praha does not appear in layer 15 (Okres) or layer 17 (VUSC/Kraj) as
 *   a separate entry. Its VUSC code is 19 (Hlavní město Praha).
 *   We synthesize a virtual "Praha" okres row (kod="SYNT_PRAHA", vusc="19")
 *   and a virtual "Hlavní město Praha" kraj row (kod="19") if missing,
 *   so the hierarchy is complete for analytics.
 *
 *   The same logic applies to any other obec with null okres — each gets
 *   a synthetic okres named after itself, linked to kraj "19" (Praha) by
 *   default since Praha is the only known Czech statutory city without an
 *   explicit okres in RUIAN.
 */
public class DimensionBuilder {

    private static final Logger log = LoggerFactory.getLogger(DimensionBuilder.class);

    // Praha's VUSC (kraj) code in RUIAN — constant, never changes
    private static final String PRAHA_VUSC_KOD = "19";
    private static final String PRAHA_KRAJ_NAZEV = "Hlavní město Praha";

    public List<DimKraj> buildKraj(List<DimKraj> raw) {
        Map<String, DimKraj> seen = new HashMap<>();
        int id = 1;
        for (DimKraj k : raw) {
            if (!seen.containsKey(k.kodKraje()))
                seen.put(k.kodKraje(), k.withId(id++));
        }

        // Ensure Praha kraj exists — it may be absent from layer 17
        // since Praha is its own VUSC and may not be listed as a regular kraj
        if (!seen.containsKey(PRAHA_VUSC_KOD)) {
            seen.put(PRAHA_VUSC_KOD, new DimKraj(id++, PRAHA_VUSC_KOD, PRAHA_KRAJ_NAZEV));
            log.info("Synthesized kraj row: kod={}, nazev={}", PRAHA_VUSC_KOD, PRAHA_KRAJ_NAZEV);
        }

        log.info("DimKraj: {} records ({} from RUIAN + synthetic)", seen.size(), raw.size());
        return new ArrayList<>(seen.values());
    }

    /**
     * Builds dim_okres.
     * Obec rows with null kodOkresu (e.g. Praha) get a synthetic okres row
     * named after the obec itself, linked to Praha's kraj (kod=19).
     * The synthetic okres code is the obec's own kod prefixed with "SYNT_"
     * to avoid collisions with real RUIAN codes.
     */
    public List<DimOkres> buildOkres(List<DimOkres> raw, List<DimObec> rawObec) {
        Map<String, DimOkres> seen = new HashMap<>();
        int id = 1;
        for (DimOkres o : raw) {
            if (!seen.containsKey(o.kodOkresu()))
                seen.put(o.kodOkresu(), o.withId(id++));
        }

        // Synthesize okres for every obec that has no parent okres in RUIAN
        int synthesized = 0;
        for (DimObec o : rawObec) {
            if (o.kodOkresu() != null) continue; // has a real parent okres — skip

            String syntheticKod = "SYNT_" + o.kodObce();
            if (!seen.containsKey(syntheticKod)) {
                seen.put(syntheticKod, new DimOkres(id++, syntheticKod, o.nazevObce(), 0, PRAHA_VUSC_KOD));
                log.info("Synthesized okres row for obec with null okres: kod={}, nazev={}",
                    syntheticKod, o.nazevObce());
                synthesized++;
            }
        }

        log.info("DimOkres: {} records ({} from RUIAN, {} synthetic)",
            seen.size(), raw.size(), synthesized);
        return new ArrayList<>(seen.values());
    }

    /**
     * Joins RUIAN obec with CSU demographics.
     * Obec rows with null kodOkresu get their kodOkresu set to the synthetic
     * okres code ("SYNT_" + kodObce) so the PostgresLoader subquery resolves correctly.
     */
    public List<DimObec> buildObec(List<DimObec> raw, Map<String, Demographics> demographics) {
        Map<String, DimObec> seen = new HashMap<>();
        int id = 1;
        int withDemographics = 0;
        int withSyntheticOkres = 0;

        for (DimObec o : raw) {
            if (seen.containsKey(o.kodObce())) continue;

            Demographics dem = demographics.get(o.kodObce());
            if (dem != null) withDemographics++;

            // If this obec has no parent okres in RUIAN, point it to the
            // synthetic okres we created in buildOkres()
            String kodOkresu = o.kodOkresu();
            if (kodOkresu == null) {
                kodOkresu = "SYNT_" + o.kodObce();
                withSyntheticOkres++;
            }

            seen.put(o.kodObce(), new DimObec(
                id++,
                o.kodObce(),
                o.nazevObce(),
                0,          // okresId — resolved by PostgresLoader via subquery
                kodOkresu,  // real or synthetic RUIAN FK
                dem != null ? dem.population()        : null,
                dem != null ? dem.populationDensity() : null,
                dem != null ? dem.areaKm2()           : null,
                dem != null ? dem.avgAge()            : null,
                dem != null ? dem.unemploymentPct()   : null,
                o.geometry(),
                o.centroidLat(),
                o.centroidLon()
            ));
        }

        log.info("DimObec: {} unique records ({} with demographics, {} with synthetic okres)",
            seen.size(), withDemographics, withSyntheticOkres);
        return new ArrayList<>(seen.values());
    }

    /** Deduplicates cast_obce. kodObce FK is preserved for PostgresLoader subquery. */
    public List<DimCastObce> buildCastObce(List<DimCastObce> raw) {
        Map<String, DimCastObce> seen = new HashMap<>();
        int id = 1;
        for (DimCastObce c : raw) {
            if (!seen.containsKey(c.kodCastObce()))
                seen.put(c.kodCastObce(), c.withId(id++));
        }
        log.info("DimCastObce: {} unique records", seen.size());
        return new ArrayList<>(seen.values());
    }
}
