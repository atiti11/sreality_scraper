package com.sreality.pipeline.ruian;

import com.sreality.pipeline.ruian.extract.RuianDownloader;
import com.sreality.pipeline.ruian.extract.RuianVfrParser;
import com.sreality.pipeline.ruian.extract.RuianVfrParser.ParseResult;
import com.sreality.pipeline.ruian.load.RuianLoader;
import com.sreality.pipeline.shared.db.PostgresConnectionPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * JAR 2 entry point — RUIAN dimension loader.
 *
 * URL base: https://services.cuzk.gov.cz/vfr/YYYYMM/YYYYMMDD_ST_UKSG.xml.zip
 * Walks back month by month until an available snapshot is found.
 */
public class RuianMain {

    private static final Logger log = LoggerFactory.getLogger(RuianMain.class);

    public static void main(String[] args) {
        log.info("=== JAR 2: RUIAN Loader ===");

        try (PostgresConnectionPool pg = new PostgresConnectionPool()) {
            RuianLoader loader     = new RuianLoader(pg);
            LocalDate   lastLoaded = loader.getLastSnapshotDate();
            String      url        = RuianDownloader.resolveUrl();

            if (!RuianDownloader.isUpdateAvailable(url, lastLoaded)) {
                log.info("RUIAN already current — nothing to do.");
                return;
            }

            Path xmlFile = RuianDownloader.downloadAndExtract(url);
            try {
                ParseResult result = new RuianVfrParser().parse(xmlFile);
                loader.loadKraje(result.kraje());
                loader.loadOkresy(result.okresy());
                loader.loadObce(result.obce());
                loader.loadCastiObci(result.castiObci());

                // Parse snapshot date from filename: YYYYMMDD_ST_UKSG.xml.zip
                String fname   = url.substring(url.lastIndexOf('/') + 1);
                LocalDate snap = LocalDate.parse(fname.substring(0, 8),
                    DateTimeFormatter.ofPattern("yyyyMMdd"));
                loader.saveSnapshotDate(snap, result.castiObci().size());

                log.info("RUIAN load complete: {} kraj / {} okres / {} obec / {} cast_obce",
                    result.kraje().size(), result.okresy().size(),
                    result.obce().size(), result.castiObci().size());
            } finally {
                Files.deleteIfExists(xmlFile);
                log.info("Temp XML deleted.");
            }
        } catch (Exception e) {
            log.error("JAR 2 failed: {}", e.getMessage(), e);
            System.exit(1);
        }
        log.info("=== JAR 2 finished ===");
    }
}
