package com.sreality.etl.transform;

import com.sreality.etl.extract.CsuExtractor;
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

    /**
     * Builds dim_kraj from VFR Vusc records.
     *
     * The VFR ST_UZSZ file stores Vusc as NUTS 2 cohesion regions, not the
     * 14 administrative kraj units. Multiple administrative kraje share the
     * same NUTS2 Vusc code (e.g. code=35 covers both Jihočeský and Plzeňský).
     * Okres records use those same NUTS2 codes as their kraj FK.
     *
     * Strategy: store all 14 raw Vusc entries under their own NUTS2 code.
     * Since multiple kraje share the same code, deduplicate by taking the
     * FIRST occurrence (preserves order from VFR which is alphabetical within
     * each NUTS2 group). upsertKraj uses ON CONFLICT DO UPDATE so duplicates
     * are harmless at the DB level.
     *
     * The key insight: the Okres→Vusc FK also uses the NUTS2 code, so as long
     * as at least ONE kraj row exists for each NUTS2 code in dim_kraj, all
     * Okresy will resolve correctly. The kraj name will be whichever kraj
     * happened to be first in the VFR for that NUTS2 group — imperfect for
     * display but functionally correct for FK resolution.
     */
    public List<DimKraj> buildKraj(List<DimKraj> raw) {
        // First-entry wins per NUTS2 code (preserves VFR ordering).
        Map<String, DimKraj> seen = new HashMap<>();
        int id = 1;
        for (DimKraj k : raw) {
            if (!seen.containsKey(k.kodKraje()))
                seen.put(k.kodKraje(), k.withId(id++));
        }

        // Ensure Praha kraj exists (code=19, it IS its own NUTS2 region)
        if (!seen.containsKey(PRAHA_VUSC_KOD)) {
            seen.put(PRAHA_VUSC_KOD, new DimKraj(id++, PRAHA_VUSC_KOD, PRAHA_KRAJ_NAZEV));
            log.info("Synthesized kraj row: kod={}, nazev={}", PRAHA_VUSC_KOD, PRAHA_KRAJ_NAZEV);
        }

        log.info("DimKraj: {} NUTS2 region records from {} VFR Vusc entries. Codes: {}",
            seen.size(), raw.size(),
            seen.keySet().stream().sorted().toList());
        return new ArrayList<>(seen.values());
    }

    /**
     * Maps the Okres→Vusc FK codes used in the VFR ST_UZSZ file to the
     * actual Vusc entity codes stored in dim_kraj.
     *
     * The VFR uses two different code systems simultaneously:
     *   - Vusc entities are stored with NUTS2 cohesion region codes (19,27,35...)
     *   - Okres records reference their parent Vusc with a DIFFERENT internal
     *     RUIAN administrative code (94,108,116,124,132,141...)
     *
     * This mapping is the Czech administrative geography — fully stable.
     * Source: RUIAN code lists + verified against VFR output.
     */
    private static final Map<String, String> VUSC_FK_TO_NUTS2 = Map.ofEntries(
        Map.entry("19",  "19"),  // Praha → Praha (same code)
        Map.entry("27",  "27"),  // Středočeský → Střední Čechy
        Map.entry("35",  "35"),  // Jihočeský → Jihozápad (first entry)
        Map.entry("43",  "43"),  // Karlovarský → Severozápad (first entry)
        Map.entry("51",  "51"),  // Liberecký → Severovýchod (first entry)
        Map.entry("60",  "60"),  // Kraj Vysočina → Jihovýchod (first entry)
        Map.entry("78",  "78"),  // Olomoucký → Střední Morava (first entry)
        Map.entry("86",  "86"),  // Moravskoslezský → Moravskoslezsko
        // Okres FK codes that differ from Vusc entity codes:
        Map.entry("94",  "51"),  // Pardubický kraj → Severovýchod NUTS2
        Map.entry("108", "60"),  // Kraj Vysočina → Jihovýchod NUTS2
        Map.entry("116", "60"),  // Jihomoravský kraj → Jihovýchod NUTS2
        Map.entry("124", "78"),  // Olomoucký kraj → Střední Morava NUTS2
        Map.entry("132", "86"),  // Moravskoslezský → Moravskoslezsko NUTS2
        Map.entry("141", "78")   // Zlínský kraj → Střední Morava NUTS2
    );

    /**
     * Builds dim_okres.
     * Remaps the Okres→Vusc FK code to the NUTS2 code used in dim_kraj,
     * so every Okres can resolve its parent kraj.
     * Obec rows with null kodOkresu (e.g. Praha) get a synthetic okres row.
     */
    public List<DimOkres> buildOkres(List<DimOkres> raw, List<DimObec> rawObec) {
        Map<String, DimOkres> seen = new HashMap<>();
        int id = 1;
        for (DimOkres o : raw) {
            if (!seen.containsKey(o.kodOkresu())) {
                // Remap the kodVusc FK to the NUTS2 code actually stored in dim_kraj
                String mappedVusc = VUSC_FK_TO_NUTS2.getOrDefault(o.kodVusc(), o.kodVusc());
                DimOkres remapped = new DimOkres(0, o.kodOkresu(), o.nazevOkresu(), 0, mappedVusc);
                seen.put(o.kodOkresu(), remapped.withId(id++));
            }
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
     * Joins RUIAN obec with CSU demographics and MPSV unemployment (okres-level).
     *
     * unemployment_pct is sourced from MPSV at okres granularity and propagated
     * to every child obec within that okres. This is noted so analysts know it
     * is a district-level approximation, not a municipality-specific figure.
     *
     * Obec rows with null kodOkresu get their kodOkresu set to the synthetic
     * okres code ("SYNT_" + kodObce) so the PostgresLoader subquery resolves correctly.
     */
    public List<DimObec> buildObec(List<DimObec> raw,
                                   Map<String, CsuExtractor.Demographics> demographics,
                                   Map<String, Double> unemploymentByOkres) {
        Map<String, DimObec> seen = new HashMap<>();
        int id = 1;
        int withDemographics = 0;
        int withUnemployment = 0;
        int withSyntheticOkres = 0;

        for (DimObec o : raw) {
            if (seen.containsKey(o.kodObce())) continue;

            CsuExtractor.Demographics dem = demographics.get(o.kodObce());
            if (dem != null) withDemographics++;

            // If this obec has no parent okres in RUIAN, point it to the
            // synthetic okres we created in buildOkres()
            String kodOkresu = o.kodOkresu();
            if (kodOkresu == null) {
                kodOkresu = "SYNT_" + o.kodObce();
                withSyntheticOkres++;
            }

            // Unemployment: propagated from okres level down to obec
            Double unemploymentPct = unemploymentByOkres.get(kodOkresu);
            if (unemploymentPct == null && dem != null) unemploymentPct = dem.unemploymentPct();
            if (unemploymentPct != null) withUnemployment++;

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
                unemploymentPct,
                o.geometry(),
                o.centroidLat(),
                o.centroidLon()
            ));
        }

        long nullOkres = raw.stream().filter(o -> o.kodOkresu() == null).count();
        long distinctKods = raw.stream().map(DimObec::kodObce).distinct().count();
        log.info("DimObec: {} unique records from {} raw ({} distinct kodObce, {} raw had null kodOkresu)",
            seen.size(), raw.size(), distinctKods, nullOkres);
        if (seen.size() < distinctKods)
            log.warn("DimObec: {} of {} distinct-kod obec dropped — they have null kodOkresu that wasn't resolved",
                distinctKods - seen.size(), distinctKods);
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
