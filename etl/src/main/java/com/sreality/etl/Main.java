package com.sreality.etl;

import com.sreality.etl.config.EtlConfig;
import com.sreality.etl.extract.CsuExtractor;
import com.sreality.etl.extract.MongoExtractor;
import com.sreality.etl.extract.RuianExtractor;
import com.sreality.etl.extract.RuianVfrExtractor;
import com.sreality.etl.extract.RuianVfrExtractor.VfrResult;
import com.sreality.etl.load.PostgresLoader;
import com.sreality.etl.model.*;
import com.sreality.etl.transform.DimensionBuilder;
import com.sreality.etl.transform.FactBuilder;
import com.sreality.etl.transform.SpatialJoiner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ETL entry point — stateless, runs once and exits.
 *
 * RUIAN data strategy:
 *   PRIMARY:  VFR XML (ST_UKSH monthly snapshot from services.cuzk.gov.cz).
 *             Contains the complete hierarchy kraj→okres→obec→cast_obce→ZSJ
 *             with polygon geometry. One file, one source of truth.
 *             Updated monthly by ČÚZK. All dimensions are rebuilt from this.
 *
 *   FALLBACK: ArcGIS REST API (ags.cuzk.gov.cz MapServer layers 11/12/15/17).
 *             Used only when the VFR download fails. Provides hierarchy data
 *             but no ZSJ polygons so cast_obce matching is unavailable.
 *
 * Snapshot logic:
 *   - VFR snapshot date (from ZIP filename) stored in dw.ruian_snapshot.
 *   - If newer than stored: reload ALL dimensions + bulk re-match all facts.
 *   - If same: skip dimension reload and bulk re-match (run is faster).
 *   - Either way: process new/changed MongoDB estates.
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        EtlConfig config = EtlConfig.fromEnv();
        log.info("Starting ETL run. Config: {}", config);

        EtlReport report = new EtlReport();

        try (MongoExtractor mongo = new MongoExtractor(config);
             PostgresLoader pg    = new PostgresLoader(config)) {

            pg.ensureSchema();

            // ── Step 1: Download RUIAN VFR (primary source) ───────────────────
            log.info("=== EXTRACT RUIAN VFR (primary source) ===");
            VfrResult vfr = new RuianVfrExtractor(config).extract();

            LocalDate vfrDate     = vfr != null ? vfr.snapshotDate() : null;
            LocalDate lastVfrDate = pg.getLastRuianSnapshotDate();
            boolean   isNewer     = vfrDate != null
                && (lastVfrDate == null || vfrDate.isAfter(lastVfrDate));

            log.info("VFR: downloaded={}, snapshot={}, lastStored={}, isNewer={}",
                vfr != null, vfrDate, lastVfrDate, isNewer);

            // ── Step 2: Extract dimension data ────────────────────────────────
            // VFR success → use its parsed hierarchy directly (no ArcGIS calls).
            // VFR failure → fall back to ArcGIS API for hierarchy only
            //               (ZSJ/cast_obce spatial join will be unavailable).
            List<DimKraj>     krajRaw;
            List<DimOkres>    okresRaw;
            List<DimObec>     obecRaw;
            List<DimCastObce> castObceRaw;
            List<RuianVfrExtractor.ZsjRecord> zsjRecords;

            if (vfr != null) {
                log.info("Using VFR as dimension source ({} kraj, {} okres, {} obec, {} cast_obce, {} ZSJ)",
                    vfr.kraj().size(), vfr.okres().size(),
                    vfr.obec().size(), vfr.castObce().size(), vfr.zsj().size());
                krajRaw     = vfr.kraj();
                okresRaw    = vfr.okres();
                obecRaw     = vfr.obec();
                castObceRaw = vfr.castObce();
                zsjRecords  = vfr.zsj();
            } else {
                log.warn("VFR unavailable — falling back to ArcGIS REST API (no ZSJ spatial join)");
                RuianExtractor api = new RuianExtractor(config);
                krajRaw     = api.extractKraj();
                okresRaw    = api.extractOkres();
                obecRaw     = api.extractObec();
                castObceRaw = api.extractCastObce();
                zsjRecords  = List.of(); // no ZSJ without VFR
            }

            // ── Step 3: CSU demographics ──────────────────────────────────────
            log.info("=== EXTRACT CSU ===");
            Map<String, CsuExtractor.Demographics> demographics =
                new CsuExtractor(config).extract();

            // ── Step 4: Build dimensions ──────────────────────────────────────
            log.info("=== TRANSFORM DIMENSIONS ===");
            DimensionBuilder dim = new DimensionBuilder();
            List<DimKraj>     krajRows     = dim.buildKraj(krajRaw);
            List<DimOkres>    okresRows    = dim.buildOkres(okresRaw, obecRaw);
            List<DimObec>     obecRows     = dim.buildObec(obecRaw, demographics);
            List<DimCastObce> castObceRows = dim.buildCastObce(castObceRaw);

            // ── Step 5: Load dimensions (always — keeps dims fresh) ───────────
            log.info("=== LOAD DIMENSIONS ===");
            pg.upsertKraj(krajRows);
            pg.upsertOkres(okresRows);
            pg.upsertObec(obecRows);
            pg.upsertCastObce(castObceRows);
            DimDate.ensureRange(pg, 2024, 2030);

            // ── Step 6: Build spatial index ───────────────────────────────────
            log.info("=== BUILD SPATIAL INDEX ===");
            SpatialJoiner spatialJoiner = new SpatialJoiner(
                castObceRows, obecRows, zsjRecords.isEmpty() ? null : zsjRecords);

            // ── Step 7: Bulk re-match if RUIAN snapshot is newer ─────────────
            if (isNewer) {
                log.info("=== BULK SPATIAL RE-MATCH (snapshot: {} → {}) ===",
                    lastVfrDate, vfrDate);
                pg.bulkRematchSpatial(spatialJoiner);
                pg.saveRuianSnapshotDate(vfrDate, vfr.zsj().size());
                log.info("Re-match complete.");
            } else {
                log.info("RUIAN snapshot unchanged ({}) — skipping bulk re-match.", lastVfrDate);
                if (lastVfrDate == null && vfrDate != null) {
                    pg.saveRuianSnapshotDate(vfrDate, vfr.zsj().size());
                }
            }

            // ── Step 8: Process estates from MongoDB ──────────────────────────
            log.info("=== LOAD FACTS ===");
            Map<Integer, DimAgency> agencyCache = new HashMap<>();
            FactBuilder factBuilder = new FactBuilder(spatialJoiner, agencyCache, pg, config);

            for (String dealType : List.of("sale", "rent", "auction")) {
                List<String> collections = config.mongoCollectionsFor(dealType);
                log.info("Processing '{}' ({} collections)", dealType, collections.size());
                for (String collection : collections) {
                    log.info("  Streaming '{}'...", collection);
                    mongo.streamCollection(collection, batch -> {
                        List<RawEstate> estates = batch.stream()
                            .map(RawEstate::fromDocument)
                            .filter(RawEstate::isUsable)
                            .toList();
                        factBuilder.processBatch(estates, dealType, report);
                    });
                }
            }

            pg.refreshClosingViews();
            report.finish();
            log.info("=== ETL COMPLETE ===");
            log.info("{}", report.summary());

        } catch (Exception e) {
            log.error("Fatal ETL error", e);
            System.exit(1);
        }
    }
}
