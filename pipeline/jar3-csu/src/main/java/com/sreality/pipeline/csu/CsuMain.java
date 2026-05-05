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
 * Modes (set via env var CSU_MODE):
 *   full   — initial full load: downloads all available XLSX files,
 *             reads OD_KAM sheets to seed obec_successor, loads all years.
 *   update — incremental: downloads only the latest year's XLSX files
 *             and upserts new rows. No changes to obec_successor.
 *
 * CSU XLSX files are hosted at:
 *   https://www.czso.cz/documents/10180/... (stable URLs per year)
 *
 * For simplicity, the URLs are read from env var CSU_XLSX_URLS (comma-separated).
 * In practice, the Airflow DAG sets this variable pointing to the correct URLs.
 *
 * Env vars:
 *   CSU_MODE          full | update  (default: update)
 *   CSU_XLSX_URLS     comma-separated list of XLSX download URLs
 *   CSU_YEAR          year to tag update rows with (default: current year)
 *   PG_*              Postgres connection
 */
public class CsuMain {

    private static final Logger log = LoggerFactory.getLogger(CsuMain.class);

    public static void main(String[] args) {
        log.info("=== JAR 3: CSU Loader ===");

        String mode  = env("CSU_MODE", "update");
        String urls  = env("CSU_XLSX_URLS", "");
        int    year  = Integer.parseInt(env("CSU_YEAR", String.valueOf(Year.now().getValue())));

        if (urls.isBlank()) {
            log.error("CSU_XLSX_URLS is not set — nothing to download.");
            System.exit(1);
        }

        String[] urlList = urls.split(",");
        log.info("Mode: {}, year: {}, files: {}", mode, year, urlList.length);

        try (PostgresConnectionPool pg = new PostgresConnectionPool()) {
            CsuLoader     loader = new CsuLoader(pg);
            CsuXlsxParser parser = new CsuXlsxParser();

            var allSuccessors = new ArrayList<com.sreality.pipeline.csu.model.ObecSuccessorRecord>();
            var allStats      = new ArrayList<com.sreality.pipeline.csu.model.ObecStatsRecord>();

            for (String rawUrl : urlList) {
                String url = rawUrl.trim();
                if (url.isBlank()) continue;
                log.info("Downloading {}", url);

                try (CloseableHttpClient http = HttpClients.createDefault()) {
                    http.execute(new HttpGet(URI.create(url)), response -> {
                        if (response.getCode() != 200)
                            throw new RuntimeException("HTTP " + response.getCode() + " from " + url);
                        try (InputStream body = response.getEntity().getContent()) {
                            ParseResult result = parser.parse(body, year);
                            allSuccessors.addAll(result.successors());
                            allStats.addAll(result.stats());
                        }
                        return null;
                    });
                }
            }

            // On full load: seed successor table first
            if ("full".equalsIgnoreCase(mode) && !allSuccessors.isEmpty()) {
                log.info("Loading {} obec_successor rows", allSuccessors.size());
                loader.loadSuccessors(allSuccessors);
            }

            // Load stats (always)
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
