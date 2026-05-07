package com.sreality.pipeline.csu;

import com.sreality.pipeline.csu.extract.CsuXlsxParser;
import com.sreality.pipeline.csu.extract.CsuXlsxParser.ParseResult;
import com.sreality.pipeline.csu.load.CsuLoader;
import com.sreality.pipeline.shared.db.PostgresConnectionPool;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.*;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;

/**
 * JAR 3 entry point — CSU statistics loader.
 *
 * Supports two input modes:
 *
 *   CSU_LOCAL_FILES=/path/a.xlsx,/path/b.xlsx
 *     Reads XLSX files from local paths (pre-downloaded by curl in Airflow).
 *     Takes priority over CSU_XLSX_URLS.
 *
 *   CSU_XLSX_URLS=https://...a.xlsx,https://...b.xlsx
 *     Downloads XLSX files via HTTP. Used as fallback if CSU_LOCAL_FILES not set.
 *
 * CSU_MODE:
 *   full   — seeds obec_successor from OD_KAM sheets + loads all years
 *   update — incremental upsert of latest year only (default)
 *
 * CSU_YEAR: year to tag rows with (default: current year)
 */
public class CsuMain {

    private static final Logger log = LoggerFactory.getLogger(CsuMain.class);

    public static void main(String[] args) {
        log.info("=== JAR 3: CSU Loader ===");

        String mode       = env("CSU_MODE",        "update");
        String localFiles = env("CSU_LOCAL_FILES",  "");
        String urls       = env("CSU_XLSX_URLS",    "");
        int    year       = Integer.parseInt(env("CSU_YEAR",
                                String.valueOf(Year.now().getValue())));

        if (localFiles.isBlank() && urls.isBlank()) {
            log.error("Neither CSU_LOCAL_FILES nor CSU_XLSX_URLS is set — nothing to load.");
            System.exit(1);
        }

        log.info("Mode: {}, year: {}", mode, year);

        try (PostgresConnectionPool pg = new PostgresConnectionPool()) {
            CsuLoader     loader       = new CsuLoader(pg);
            CsuXlsxParser parser       = new CsuXlsxParser();
            var allSuccessors          = new ArrayList<com.sreality.pipeline.csu.model.ObecSuccessorRecord>();
            var allStats               = new ArrayList<com.sreality.pipeline.csu.model.ObecStatsRecord>();

            if (!localFiles.isBlank()) {
                // ── Local file mode (pre-downloaded by curl) ──────────────────
                String[] paths = localFiles.split(",");
                log.info("Reading {} local file(s)", paths.length);
                for (String rawPath : paths) {
                    String path = rawPath.trim();
                    if (path.isBlank()) continue;
                    Path p = Path.of(path);
                    if (!Files.exists(p)) {
                        log.warn("Local file not found, skipping: {}", path);
                        continue;
                    }
                    log.info("Parsing local file: {} ({} KB)", path, Files.size(p) / 1024);
                    try (InputStream is = Files.newInputStream(p)) {
                        ParseResult result = path.toLowerCase().endsWith(".zip")
                                ? parser.parseZip(is) : parser.parseXlsx(is);
                        allSuccessors.addAll(result.successors());
                        allStats.addAll(result.stats());
                    }
                }
            } else {
                // ── URL mode (download via HTTP) ──────────────────────────────
                String[] urlList = urls.split(",");
                log.info("Downloading {} file(s) via HTTP", urlList.length);
                for (String rawUrl : urlList) {
                    String url = rawUrl.trim();
                    if (url.isBlank()) continue;
                    log.info("Downloading {}", url);
                    boolean isZip = url.toLowerCase().contains(".zip");
                    try (CloseableHttpClient http = HttpClients.createDefault()) {
                        http.execute(new HttpGet(URI.create(url)), response -> {
                            if (response.getCode() != 200)
                                throw new RuntimeException(
                                    "HTTP " + response.getCode() + " from " + url);
                            try (InputStream body = response.getEntity().getContent()) {
                                ParseResult result = isZip
                                        ? parser.parseZip(body) : parser.parseXlsx(body);
                                allSuccessors.addAll(result.successors());
                                allStats.addAll(result.stats());
                            }
                            return null;
                        });
                    }
                }
            }

            if (allSuccessors.isEmpty() && allStats.isEmpty()) {
                log.warn("No data parsed from any file — check file paths and formats.");
                return;
            }

            // On full load: seed successor table first
            if ("full".equalsIgnoreCase(mode) && !allSuccessors.isEmpty()) {
                log.info("Loading {} obec_successor rows", allSuccessors.size());
                loader.loadSuccessors(allSuccessors);
            }

            log.info("Loading {} stat rows", allStats.size());
            loader.loadStats(allStats);

        } catch (Exception e) {
            log.error("JAR 3 failed: {}", e.getMessage(), e);
            System.exit(1);
        }
        log.info("=== JAR 3 finished ===");
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return (v != null && !v.isBlank()) ? v : def;
    }
}
