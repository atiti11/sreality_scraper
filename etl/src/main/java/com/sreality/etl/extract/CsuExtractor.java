package com.sreality.etl.extract;

import com.sreality.etl.config.EtlConfig;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.DefaultRedirectStrategy;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Downloads and parses CSU MOS open data CSV to get population per municipality.
 *
 * SOURCE: CSU MOS open data (opendata.csu.gov.cz)
 *   URL pattern: https://opendata.csu.gov.cz/soubory/od/od_mos01/mos_data_YYYY.csv
 *   Format: UTF-8, comma-delimited, quoted fields
 *   Columns: rok, "kodukaz", "koduzemi", hodnota
 *
 *   kodukaz = "010000" → Počet obyvatel celkem (total population)
 *   koduzemi = 6-digit RUIAN kod_obce (matches dim_obec.kod_obce directly)
 *
 * Tried in order: 2025 file, 2024 file, user-supplied CSU_DEMOGRAPHICS_URL override.
 * The user override is tried last so it acts as an emergency fallback if CSU
 * changes the URL structure again.
 *
 * Only population is extracted — the MOS dataset does not include area or avg age
 * at this granularity. Those fields remain null in dim_obec.
 */
public class CsuExtractor {

    private static final Logger log = LoggerFactory.getLogger(CsuExtractor.class);

    // MOS total-population indicator code (verified from actual CSV data)
    private static final String MOS_INDICATOR_POPULATION = "010000";

    private static final String MOS_URL_2025 =
        "https://opendata.csu.gov.cz/soubory/od/od_mos01/mos_data_2025.csv";
    private static final String MOS_URL_2024 =
        "https://opendata.csu.gov.cz/soubory/od/od_mos01/mos_data_2024.csv";

    private final EtlConfig config;

    public CsuExtractor(EtlConfig config) {
        this.config = config;
    }

    // ── Public entry point ────────────────────────────────────────────────────

    /**
     * Returns a map of kod_obce (String) → Demographics.
     * Only population is populated; all other fields are null.
     * Returns an empty map if all sources fail — ETL continues without demographics.
     */
    public Map<String, Demographics> extract() {
        for (String url : new String[]{MOS_URL_2025, MOS_URL_2024}) {
            log.info("CSU: trying MOS CSV: {}", url);
            Map<String, Demographics> result = downloadAndParse(url);
            if (!result.isEmpty()) {
                log.info("CSU: loaded {} municipality population records from {}", result.size(), url);
                return result;
            }
        }

        // User-supplied URL override (emergency fallback)
        if (config.csuDemographicsUrl != null && !config.csuDemographicsUrl.isBlank()) {
            log.info("CSU: trying user-supplied URL: {}", config.csuDemographicsUrl);
            Map<String, Demographics> result = downloadAndParse(config.csuDemographicsUrl);
            if (!result.isEmpty()) {
                log.info("CSU: loaded {} records from user URL", result.size());
                return result;
            }
        }

        log.warn("CSU: all sources failed — ETL continues without demographics. " +
                 "Set CSU_DEMOGRAPHICS_URL in .env to override.");
        return Collections.emptyMap();
    }

    // ── CSV download and parse ────────────────────────────────────────────────

    /**
     * Downloads the MOS CSV and extracts the latest population value per obec.
     *
     * MOS CSV format (UTF-8, comma-delimited):
     *   rok,"kodukaz","koduzemi",hodnota
     *   2024,"010000","500011",1234
     *
     * Multiple years may be present in a single file. We keep the highest rok
     * per koduzemi so we always use the most recent data.
     */
    private Map<String, Demographics> downloadAndParse(String url) {
        try {
            byte[] raw = httpGet(url);
            if (raw.length < 100) {
                log.warn("  CSU: response too small ({} bytes) from {}", raw.length, url);
                return Collections.emptyMap();
            }
            log.info("  CSU: {} KB downloaded", raw.length / 1024);

            Map<String, Integer> popByKod  = new HashMap<>();
            Map<String, Integer> yearByKod = new HashMap<>();

            CSVFormat fmt = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setDelimiter(',')
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .build();

            try (Reader reader = new InputStreamReader(new ByteArrayInputStream(raw), StandardCharsets.UTF_8);
                 CSVParser parser = new CSVParser(reader, fmt)) {

                for (CSVRecord rec : parser) {
                    try {
                        // Filter: only total-population indicator
                        String kodukaz = rec.get("kodukaz").replace("\"", "").trim();
                        if (!MOS_INDICATOR_POPULATION.equals(kodukaz)) continue;

                        // MOS pads koduzemi to 6 digits (e.g. "002020")
                        // RUIAN stores kod_obce without leading zeros (e.g. "2020")
                        // Strip leading zeros so the join key matches.
                        String koduzemi = rec.get("koduzemi").replace("\"", "").trim();
                        if (koduzemi.isBlank()) continue;
                        koduzemi = koduzemi.replaceFirst("^0+(?!$)", ""); // strip leading zeros

                        String hodnotaStr = rec.get("hodnota").replace("\"", "").trim();
                        if (hodnotaStr.isBlank() || "i.d.".equals(hodnotaStr)) continue;

                        int rok     = Integer.parseInt(rec.get("rok").trim());
                        int hodnota = Integer.parseInt(hodnotaStr);
                        if (hodnota <= 0) continue;

                        // Keep the most recent year's value
                        if (rok > yearByKod.getOrDefault(koduzemi, 0)) {
                            popByKod.put(koduzemi, hodnota);
                            yearByKod.put(koduzemi, rok);
                        }
                    } catch (Exception ignored) {
                        // Skip malformed rows silently
                    }
                }
            }

            if (popByKod.isEmpty()) {
                log.warn("  CSU: parsed 0 records — check indicator code or column names in {}", url);
                return Collections.emptyMap();
            }

            Map<String, Demographics> result = new HashMap<>();
            popByKod.forEach((kod, pop) -> result.put(kod, new Demographics(pop)));
            log.info("  CSU: {} records parsed (latest year per obec)", result.size());
            return result;

        } catch (Exception e) {
            log.warn("  CSU: download/parse failed for {}: {}", url, e.getMessage());
            return Collections.emptyMap();
        }
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    private byte[] httpGet(String url) throws Exception {
        RequestConfig rc = RequestConfig.custom()
            .setConnectTimeout(Timeout.ofMilliseconds(config.httpTimeoutMs))
            .setResponseTimeout(Timeout.ofMilliseconds(120_000))
            .build();
        try (CloseableHttpClient http = HttpClients.custom()
                .setDefaultRequestConfig(rc)
                .setRedirectStrategy(DefaultRedirectStrategy.INSTANCE)
                .build()) {
            return http.execute(new HttpGet(url), response -> {
                int code = response.getCode();
                if (code != 200) throw new RuntimeException("HTTP " + code + " from " + url);
                return EntityUtils.toByteArray(response.getEntity());
            });
        }
    }

    // ── Result model ──────────────────────────────────────────────────────────

    /**
     * Demographic data for one municipality.
     * Only population is sourced from MOS; other fields remain null.
     * The unemploymentPct field is kept for API compatibility with DimensionBuilder
     * (populated separately by MpsvExtractor at okres level).
     */
    public record Demographics(
        Integer population,
        Double  populationDensity,
        Double  areaKm2,
        Double  avgAge,
        Double  unemploymentPct
    ) {
        /** Convenience constructor: population only, all other fields null. */
        public Demographics(Integer population) {
            this(population, null, null, null, null);
        }
    }
}
