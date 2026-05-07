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
 * Supports two modes:
 *   1. RUIAN_LOCAL_XML=/path/to/20260228_ST_UKSG.xml
 *      Skips download entirely — parses the pre-downloaded XML file directly.
 *      Use this when the CUZK server drops the connection mid-download.
 *      The snapshot date is parsed from the filename.
 *
 *   2. Normal mode (no RUIAN_LOCAL_XML set):
 *      Resolves URL via RUIAN_OVERRIDE_URL or walks back month by month,
 *      downloads the zip, extracts, parses, then deletes the temp file.
 */
public class RuianMain {

    private static final Logger log = LoggerFactory.getLogger(RuianMain.class);

    public static void main(String[] args) {
        log.info("=== JAR 2: RUIAN Loader ===");

        try (PostgresConnectionPool pg = new PostgresConnectionPool()) {
            RuianLoader loader     = new RuianLoader(pg);
            LocalDate   lastLoaded = loader.getLastSnapshotDate();

            String localXml = System.getenv("RUIAN_LOCAL_XML");
            if (localXml != null && !localXml.isBlank()) {
                // ── Local file mode ──────────────────────────────────────────
                Path xmlFile = Path.of(localXml);
                if (!Files.exists(xmlFile)) {
                    log.error("RUIAN_LOCAL_XML file not found: {}", xmlFile);
                    System.exit(1);
                }
                log.info("Using pre-downloaded XML: {} ({} MB)",
                    xmlFile, Files.size(xmlFile) / 1_048_576);

                // Parse snapshot date from filename: 20260228_ST_UKSG.xml
                String fname = xmlFile.getFileName().toString();
                LocalDate snap;
                try {
                    snap = LocalDate.parse(fname.substring(0, 8),
                        DateTimeFormatter.ofPattern("yyyyMMdd"));
                } catch (Exception e) {
                    log.warn("Could not parse date from filename '{}' — using today", fname);
                    snap = LocalDate.now();
                }

                if (lastLoaded != null && !snap.isAfter(lastLoaded)) {
                    log.info("RUIAN {} already loaded (last: {}) — nothing to do.", snap, lastLoaded);
                    return;
                }

                loadAndSave(xmlFile, false, snap, loader, pg);

            } else {
                // ── Download mode ────────────────────────────────────────────
                String url = RuianDownloader.resolveUrl();

                if (!RuianDownloader.isUpdateAvailable(url, lastLoaded)) {
                    log.info("RUIAN already current — nothing to do.");
                    return;
                }

                Path xmlFile = RuianDownloader.downloadAndExtract(url);
                try {
                    String fname = url.substring(url.lastIndexOf('/') + 1);
                    LocalDate snap = LocalDate.parse(fname.substring(0, 8),
                        DateTimeFormatter.ofPattern("yyyyMMdd"));
                    loadAndSave(xmlFile, true, snap, loader, pg);
                } finally {
                    Files.deleteIfExists(xmlFile);
                    log.info("Temp XML deleted.");
                }
            }

        } catch (Exception e) {
            log.error("JAR 2 failed: {}", e.getMessage(), e);
            System.exit(1);
        }
        log.info("=== JAR 2 finished ===");
    }

    private static void loadAndSave(Path xmlFile, boolean deleteAfter,
                                     LocalDate snap, RuianLoader loader,
                                     PostgresConnectionPool pg) throws Exception {
        ParseResult result = new RuianVfrParser().parse(xmlFile);
        loader.loadKraje(result.kraje());
        loader.loadOkresy(result.okresy());
        loader.ensureSyntheticOkresy();
        loader.loadObce(result.obce());
        loader.loadCastiObci(result.castiObci());
        loader.saveSnapshotDate(snap, result.castiObci().size());
        log.info("RUIAN load complete: {} kraj / {} okres / {} obec / {} cast_obce",
            result.kraje().size(), result.okresy().size(),
            result.obce().size(), result.castiObci().size());
    }
}
