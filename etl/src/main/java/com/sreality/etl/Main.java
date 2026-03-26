package com.sreality.etl;

import com.sreality.etl.config.EtlConfig;
import com.sreality.etl.extract.CsuExtractor;
import com.sreality.etl.extract.MongoExtractor;
import com.sreality.etl.extract.RuianExtractor;
import com.sreality.etl.load.PostgresLoader;
import com.sreality.etl.model.DimAgency;
import com.sreality.etl.model.DimCastObce;
import com.sreality.etl.model.DimDate;
import com.sreality.etl.model.DimKraj;
import com.sreality.etl.model.DimObec;
import com.sreality.etl.model.DimOkres;
import com.sreality.etl.model.EtlReport;
import com.sreality.etl.model.RawEstate;
import com.sreality.etl.transform.DimensionBuilder;
import com.sreality.etl.transform.FactBuilder;
import com.sreality.etl.transform.SpatialJoiner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * ETL entry point.
 *
 * Stateless — runs once and exits. Designed to be triggered by cron or
 * docker restart policy. Safe to run repeatedly; uses upsert semantics
 * throughout so re-runs are idempotent.
 *
 * Pipeline:
 *   1. Extract   — MongoDB estates, RUIAN GeoJSON, CSU demographics CSV
 *   2. Transform — spatial join, deduplication, derived attributes, surrogate keys
 *   3. Load      — upsert into PostgreSQL data warehouse
 *
 * Memory budget: ~256 MB heap (-Xmx256m). Data is streamed or processed
 * in small batches. Reference data (RUIAN polygons, demographics) is loaded
 * fully into memory since it is small (~50 MB total). Estate data is
 * processed in batches of 500 to avoid large in-memory lists.
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        EtlConfig config = EtlConfig.fromEnv();
        log.info("Starting ETL run. Config: {}", config);

        EtlReport report = new EtlReport();

        try (
            MongoExtractor    mongo  = new MongoExtractor(config);
            PostgresLoader    pg     = new PostgresLoader(config)
        ) {
            // ── Step 1: Extract reference data ───────────────────────────────
            log.info("=== EXTRACT ===");

            log.info("Downloading RUIAN cast_obce boundaries...");
            RuianExtractor ruian = new RuianExtractor(config);
            List<DimCastObce> castObceList = ruian.extractCastObce();
            List<DimObec>     obecList     = ruian.extractObec();
            List<DimOkres>    okresList    = ruian.extractOkres();
            List<DimKraj>     krajList     = ruian.extractKraj();
            log.info("RUIAN: {} cast_obce, {} obec, {} okres, {} kraj",
                castObceList.size(), obecList.size(), okresList.size(), krajList.size());

            log.info("Downloading CSU demographic data...");
            CsuExtractor csu = new CsuExtractor(config);
            Map<String, CsuExtractor.Demographics> demographics = csu.extract();
            log.info("CSU: {} municipality demographic records", demographics.size());

            // ── Step 2: Build and load dimension tables ───────────────────────
            log.info("=== TRANSFORM + LOAD DIMENSIONS ===");

            pg.ensureSchema();

            DimensionBuilder dimBuilder = new DimensionBuilder(demographics);

            // Location hierarchy — load bottom-up so FKs resolve
            List<DimKraj>     krajRows   = dimBuilder.buildKraj(krajList);
            List<DimOkres>    okresRows  = dimBuilder.buildOkres(okresList, krajRows);
            List<DimObec>     obecRows   = dimBuilder.buildObec(obecList, okresRows, demographics);
            List<DimCastObce> castRows   = dimBuilder.buildCastObce(castObceList, obecRows);

            pg.upsertKraj(krajRows);
            pg.upsertOkres(okresRows);
            pg.upsertObec(obecRows);
            pg.upsertCastObce(castRows);

            // Build spatial index for point-in-polygon matching
            SpatialJoiner spatialJoiner = new SpatialJoiner(castRows, obecRows);

            // Agency and date dimensions are built incrementally during fact loading
            Map<Integer, DimAgency> agencyCache = new java.util.HashMap<>();
            DimDate.ensureRange(pg, 2024, 2030);

            // ── Step 3: Stream estates → transform → load ────────────────────
            log.info("=== TRANSFORM + LOAD FACTS ===");

            FactBuilder factBuilder = new FactBuilder(spatialJoiner, agencyCache, pg, config);

            // Process each deal type from its MongoDB collection(s)
            // Each collection is streamed in batches — no full list in memory
            for (String dealType : List.of("sale", "rent", "auction")) {
                List<String> collections = config.mongoCollectionsFor(dealType);
                log.info("Processing deal type '{}' from {} collections", dealType, collections.size());

                for (String collection : collections) {
                    log.info("  Streaming collection '{}'...", collection);
                    mongo.streamCollection(collection, batch -> {
                        List<RawEstate> estates = batch.stream()
                            .map(RawEstate::fromDocument)
                            .filter(RawEstate::isUsable)
                            .toList();
                        factBuilder.processBatch(estates, dealType, report);
                    });
                }
            }

            // ── Step 4: Refresh closing views ────────────────────────────────
            log.info("=== REFRESH VIEWS ===");
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
