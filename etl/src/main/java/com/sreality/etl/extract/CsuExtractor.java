package com.sreality.etl.extract;

import com.sreality.etl.config.EtlConfig;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Downloads and parses Czech demographic data by municipality (optional enrichment).
 *
 * The CSU CSV URL has historically broken every 1-2 years as CSU migrates
 * their file hosting (czso.cz → csu.gov.cz, new document IDs each year).
 * This extractor treats demographics as OPTIONAL:
 *   - If configured URL returns 404/error → log warning, try fallback URL
 *   - If fallback also fails → log warning, return empty map
 *   - ETL continues normally; dim_obec rows are created with null demographics
 *
 * To provide demographics: set CSU_DEMOGRAPHICS_URL in .env to a working CSV.
 * Expected CSV format: semicolon-delimited, Windows-1250 or UTF-8,
 * columns including kod_obce, pocet_obyvatel, vymera_ha, prumerny_vek
 * (column name matching is case-insensitive and partial).
 */
public class CsuExtractor {

    private static final Logger log = LoggerFactory.getLogger(CsuExtractor.class);

    // Fallback: Czech national open data portal — may have population data
    private static final String FALLBACK_URL =
        "https://data.gov.cz/soubor/pocet-obyvatel-v-obcich.csv";

    private final EtlConfig config;

    public CsuExtractor(EtlConfig config) {
        this.config = config;
    }

    /**
     * Downloads and parses demographic data.
     * Returns empty map on any failure — demographics are optional enrichment only.
     */
    public Map<String, Demographics> extract() {
        // Try configured URL first (if set)
        if (config.csuDemographicsUrl != null && !config.csuDemographicsUrl.isBlank()) {
            log.info("Downloading CSU demographics from {}", config.csuDemographicsUrl);
            try {
                Map<String, Demographics> result = tryDownload(config.csuDemographicsUrl);
                if (!result.isEmpty()) {
                    log.info("CSU demographics: {} records loaded", result.size());
                    return result;
                }
            } catch (Exception e) {
                log.warn("CSU primary URL failed ({}): {} — trying fallback",
                    config.csuDemographicsUrl, e.getMessage());
            }
        } else {
            log.info("CSU_DEMOGRAPHICS_URL not configured — trying fallback URL");
        }

        // Try fallback URL
        log.info("Trying fallback demographics URL: {}", FALLBACK_URL);
        try {
            Map<String, Demographics> result = tryDownload(FALLBACK_URL);
            if (!result.isEmpty()) {
                log.info("CSU demographics (fallback): {} records loaded", result.size());
                return result;
            }
        } catch (Exception e) {
            log.warn("CSU fallback URL also failed: {}", e.getMessage());
        }

        log.warn("CSU demographics unavailable — ETL will continue without demographic enrichment. " +
                 "dim_obec rows will have null population/area/avgAge fields. " +
                 "Set CSU_DEMOGRAPHICS_URL in .env to a working CSV URL to enable enrichment.");
        return Collections.emptyMap();
    }

    /**
     * Downloads and parses a demographics CSV from the given URL.
     * Tries both Windows-1250 and UTF-8 encodings and both ; and , delimiters.
     * Returns empty map if nothing parseable found.
     */
    private Map<String, Demographics> tryDownload(String url) throws Exception {
        RequestConfig reqConfig = RequestConfig.custom()
            .setConnectTimeout(Timeout.ofMilliseconds(config.httpTimeoutMs))
            .setResponseTimeout(Timeout.ofMilliseconds(config.httpTimeoutMs))
            .build();

        // Download full body as bytes while HTTP connection is still open.
        // EntityUtils.toByteArray() handles gzip decompression automatically.
        byte[] rawBytes;
        try (CloseableHttpClient http = HttpClients.custom()
                .setDefaultRequestConfig(reqConfig)
                .build()) {
            rawBytes = http.execute(new HttpGet(url), response -> {
                int code = response.getCode();
                if (code != 200) throw new RuntimeException("HTTP " + code);
                return EntityUtils.toByteArray(response.getEntity());
            });
        }

        log.info("Downloaded {} KB from {}", rawBytes.length / 1024, url);

        // Try charset and delimiter combinations
        for (Charset charset : new Charset[]{Charset.forName("Windows-1250"), StandardCharsets.UTF_8}) {
            for (char delimiter : new char[]{';', ','}) {
                try {
                    Map<String, Demographics> result = parse(rawBytes, charset, delimiter);
                    if (!result.isEmpty()) {
                        log.info("Parsed {} records with charset={}, delimiter='{}'",
                            result.size(), charset, delimiter);
                        return result;
                    }
                } catch (Exception ignored) {}
            }
        }
        return Collections.emptyMap();
    }

    private Map<String, Demographics> parse(byte[] rawBytes, Charset charset, char delimiter) throws Exception {
        Map<String, Demographics> result = new HashMap<>();
        int skipped = 0;

        CSVFormat format = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setDelimiter(delimiter)
            .setIgnoreEmptyLines(true)
            .setTrim(true)
            .build();

        try (Reader reader = new InputStreamReader(new ByteArrayInputStream(rawBytes), charset);
             CSVParser parser = new CSVParser(reader, format)) {

            Map<String, Integer> headerMap = parser.getHeaderMap();
            if (headerMap == null || headerMap.isEmpty()) return Collections.emptyMap();

            // Find columns by partial case-insensitive name matching
            String colKod  = findColumn(headerMap, "kod_obce", "Kód obce",    "kod",      "KOD");
            String colPop  = findColumn(headerMap, "pocet_obyvatel", "Počet obyvatel", "obyvatel", "OBYV");
            String colArea = findColumn(headerMap, "vymera_ha", "Výměra v ha","vymera",   "VYMERA");
            String colAge  = findColumn(headerMap, "prumerny_vek", "Průměrný věk", "vek","VEK");

            if (colKod == null) return Collections.emptyMap(); // wrong format, skip

            for (CSVRecord record : parser) {
                try {
                    String kodObce = record.get(colKod);
                    if (kodObce == null || kodObce.isBlank()) { skipped++; continue; }
                    kodObce = kodObce.trim();

                    Integer population = colPop  != null ? parseIntCzech(record.get(colPop))      : null;
                    Double  areaKm2    = colArea != null ? parseHectaresToKm2(record.get(colArea)) : null;
                    Double  avgAge     = colAge  != null ? parseDoubleCzech(record.get(colAge))    : null;
                    Double  density    = (population != null && areaKm2 != null && areaKm2 > 0)
                        ? population / areaKm2 : null;

                    result.put(kodObce, new Demographics(population, density, areaKm2, avgAge, null));

                } catch (Exception e) {
                    skipped++;
                }
            }
        }

        if (skipped > 0) log.debug("Skipped {} rows during CSU parse", skipped);
        return result;
    }

    /** Finds first matching column header (case-insensitive, partial match). */
    private static String findColumn(Map<String, Integer> headers, String... candidates) {
        for (String candidate : candidates) {
            for (String header : headers.keySet()) {
                if (header.equalsIgnoreCase(candidate) ||
                    header.toLowerCase().contains(candidate.toLowerCase())) {
                    return header;
                }
            }
        }
        return null;
    }

    // ── Number format transformations ─────────────────────────────────────────

    static Integer parseIntCzech(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String cleaned = raw.replace("\u00a0", "").replace(" ", "").replace(",", "").trim();
        if (cleaned.isEmpty()) return null;
        try { return Integer.parseInt(cleaned); }
        catch (Exception e) { return null; }
    }

    static Double parseDoubleCzech(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String cleaned = raw.replace("\u00a0", "").replace(" ", "").replace(",", ".").trim();
        if (cleaned.isEmpty()) return null;
        try { return Double.parseDouble(cleaned); }
        catch (Exception e) { return null; }
    }

    static Double parseHectaresToKm2(String raw) {
        Double ha = parseDoubleCzech(raw);
        return ha != null ? ha * 0.01 : null;
    }

    public record Demographics(
        Integer population,
        Double  populationDensity,
        Double  areaKm2,
        Double  avgAge,
        Double  unemploymentPct
    ) {}
}
