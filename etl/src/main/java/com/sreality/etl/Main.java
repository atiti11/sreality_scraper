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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ETL entry point — stateless, runs once and exits.
 *
 * Pipeline:
 *   1. Extract   — MongoDB estates + RUIAN + CSU demographics
 *   2. Transform — dimension building (with synthetic rows for Praha etc.)
 *   3. Load      — upsert into PostgreSQL (idempotent)
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        EtlConfig config = EtlConfig.fromEnv();
        log.info("Starting ETL run. Config: {}", config);

        EtlReport report = new EtlReport();

        try (
            MongoExtractor mongo = new MongoExtractor(config);
            PostgresLoader pg    = new PostgresLoader(config)
        ) {
            // ── Step 1: Extract reference data ───────────────────────────────
            log.info("=== EXTRACT ===");

            RuianExtractor ruian = new RuianExtractor(config);

            log.info("Downloading RUIAN cast_obce (layer 11)...");
            List<DimCastObce> castObceRaw = ruian.extractCastObce();

            log.info("Downloading RUIAN obec (layer 12)...");
            List<DimObec> obecRaw = ruian.extractObec();

            log.info("Downloading RUIAN okres (layer 15)...");
            List<DimOkres> okresRaw = ruian.extractOkres();

            log.info("Downloading RUIAN kraj (layer 17)...");
            List<DimKraj> krajRaw = ruian.extractKraj();

            log.info("RUIAN: {} cast_obce, {} obec, {} okres, {} kraj",
                castObceRaw.size(), obecRaw.size(), okresRaw.size(), krajRaw.size());

            log.info("Downloading CSU demographics...");
            Map<String, CsuExtractor.Demographics> demographics =
                new CsuExtractor(config).extract();
            log.info("CSU: {} municipality records", demographics.size());

            // ── Step 2: Build dimensions ──────────────────────────────────────
            // buildOkres receives the raw obec list so it can synthesize okres
            // rows for statutory cities (Praha etc.) that have okres=null in RUIAN.
            // buildKraj also synthesizes Praha kraj if absent from layer 17.
            log.info("=== TRANSFORM DIMENSIONS ===");

            DimensionBuilder dim = new DimensionBuilder();

            List<DimKraj>     krajRows     = dim.buildKraj(krajRaw);
            List<DimOkres>    okresRows    = dim.buildOkres(okresRaw, obecRaw);
            List<DimObec>     obecRows     = dim.buildObec(obecRaw, demographics);
            List<DimCastObce> castObceRows = dim.buildCastObce(castObceRaw);

            // ── Step 3: Load dimensions ───────────────────────────────────────
            log.info("=== LOAD DIMENSIONS ===");

            pg.ensureSchema();

            // Load in FK order: kraj → okres → obec → cast_obce
            pg.upsertKraj(krajRows);
            pg.upsertOkres(okresRows);
            pg.upsertObec(obecRows);
            pg.upsertCastObce(castObceRows);

            // Build spatial index after dimensions are loaded
            SpatialJoiner spatialJoiner = new SpatialJoiner(castObceRows, obecRows);

            // Date dimension — pre-populate 2024–2030
            DimDate.ensureRange(pg, 2024, 2030);

            // ── Step 4: Stream estates → transform → load facts ──────────────
            log.info("=== LOAD FACTS ===");

            Map<Integer, DimAgency> agencyCache = new HashMap<>();
            FactBuilder factBuilder = new FactBuilder(spatialJoiner, agencyCache, pg, config);

            for (String dealType : List.of("sale", "rent", "auction")) {
                List<String> collections = config.mongoCollectionsFor(dealType);
                log.info("Processing deal type '{}' ({} collections)", dealType, collections.size());

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

            // ── Step 5: Closing views (live SQL — nothing to refresh) ─────────
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
