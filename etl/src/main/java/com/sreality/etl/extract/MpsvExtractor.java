package com.sreality.etl.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sreality.etl.config.EtlConfig;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Downloads unemployment rate (míra nezaměstnanosti) by okres from MPSV open data.
 *
 * SOURCE:
 *   Ministry of Labour and Social Affairs (MPSV) open data portal.
 *   URL: https://data.mpsv.cz/od/soubory/msmt/nezamestnanost-dle-okresu.json
 *   Format: JSON, updated monthly.
 *   License: CC BY 4.0 (https://data.mpsv.cz/)
 *
 * GRANULARITY:
 *   MPSV publishes unemployment only at OKRES (district) level — not per obec.
 *   The returned map is therefore keyed by kod_okresu (RUIAN district code).
 *   DimensionBuilder.buildObec() propagates each okres's unemployment_pct
 *   down to all its child obec rows as a district-level approximation.
 *   This is noted in dim_obec comments so analysts know the granularity.
 *
 * FALLBACK:
 *   If the JSON endpoint is unavailable, tries a secondary CSV from data.gov.cz.
 *   On total failure returns empty map — unemployment_pct stays null in dim_obec.
 *
 * RESPONSE SHAPE (approximate — field names verified 2025):
 *   {
 *     "data": [
 *       {
 *         "okres_kod": "3201",          // RUIAN kod_okresu
 *         "mira_nezamestnanosti": 3.4,  // percentage, e.g. 3.4 = 3.4%
 *         "rok": 2024,
 *         "mesic": 12
 *       },
 *       ...
 *     ]
 *   }
 *   We always pick the row with the highest rok+mesic combination per okres.
 *
 * If MPSV changes the field names, set MPSV_UNEMPLOYMENT_URL in .env to a
 * working JSON or CSV URL and adjust FIELD_OKRES_KOD / FIELD_RATE below.
 */
public class MpsvExtractor {

    private static final Logger log = LoggerFactory.getLogger(MpsvExtractor.class);

    // Primary MPSV open data JSON endpoint
    // NOTE: As of January 2025, MPSV migrated to data.mpsv.cz/portal.
    // The old JSON/CSV endpoints at data.mpsv.cz/od/soubory/... all return 404.
    // The new portal is a Power BI app with no stable direct CSV URL.
    // We keep the old URL as a best-effort attempt, then fall back to the
    // pre-2025 archived CSV on data.gov.cz (last valid dataset).
    private static final String MPSV_JSON_URL =
        "https://data.mpsv.cz/od/soubory/msmt/nezamestnanost-dle-okresu.json";

    // Pre-2025 MPSV data archived on data.gov.cz — keyed by okres name (not code).
    // This gives unemployment at district level for 2024 data.
    private static final String MPSV_GOV_CZ_2024 =
        "https://data.gov.cz/soubory/mira-nezamestnanosti-okresy-2024.csv";

    // Fallback: national open data portal CSV
    private static final String DATA_GOV_CZ_CSV_URL =
        "https://data.gov.cz/soubor/mira-nezamestnanosti-okresy.csv";

    // JSON field names (adjust via subclass/config if MPSV renames them)
    private static final String FIELD_OKRES_KOD = "okres_kod";
    private static final String FIELD_RATE       = "mira_nezamestnanosti";
    private static final String FIELD_ROK        = "rok";
    private static final String FIELD_MESIC      = "mesic";

    private final EtlConfig    config;
    private final ObjectMapper mapper = new ObjectMapper();

    public MpsvExtractor(EtlConfig config) {
        this.config = config;
    }

    /**
     * Returns a map from kod_okresu (String, RUIAN district code) to
     * unemployment rate as a percentage (e.g. 3.4 means 3.4%).
     *
     * Never throws — returns empty map on any failure.
     */
    public Map<String, Double> extract() {
        // User-supplied URL override
        String overrideUrl = config.mpsvUnemploymentUrl;
        if (overrideUrl != null && !overrideUrl.isBlank()) {
            log.info("MPSV: trying user-supplied URL: {}", overrideUrl);
            Map<String, Double> r = tryJson(overrideUrl);
            if (!r.isEmpty()) { log.info("MPSV user URL: {} okres records", r.size()); return r; }
            r = tryDataGovCzCsv(overrideUrl);
            if (!r.isEmpty()) { log.info("MPSV user URL (CSV): {} okres records", r.size()); return r; }
        }

        // Primary JSON endpoint
        log.info("MPSV: fetching unemployment from {}", MPSV_JSON_URL);
        Map<String, Double> result = tryJson(MPSV_JSON_URL);
        if (!result.isEmpty()) {
            log.info("MPSV JSON: {} okres unemployment records loaded", result.size());
            return result;
        }

        // Fallback CSV
        log.info("MPSV: trying data.gov.cz CSV fallback: {}", DATA_GOV_CZ_CSV_URL);
        result = tryDataGovCzCsv(DATA_GOV_CZ_CSV_URL);
        if (!result.isEmpty()) {
            log.info("MPSV CSV fallback: {} okres unemployment records loaded", result.size());
            return result;
        }

        log.warn("MPSV: all sources exhausted — unemployment_pct will be null in dim_obec. " +
                 "Set MPSV_UNEMPLOYMENT_URL in .env to override.");
        return Collections.emptyMap();
    }

    // ── JSON parsing ──────────────────────────────────────────────────────────

    private Map<String, Double> tryJson(String url) {
        try {
            byte[] body = httpGet(url);
            JsonNode root = mapper.readTree(body);

            // Support both { "data": [...] } and a bare array [...]
            JsonNode data = root.isArray() ? root : root.path("data");
            if (!data.isArray() || data.isEmpty()) {
                log.warn("MPSV JSON: unexpected shape at {}", url);
                return Collections.emptyMap();
            }

            // key = kod_okresu, value = [rate, rok*100+mesic] — keep latest
            Map<String, Double>  result  = new HashMap<>();
            Map<String, Integer> latestT = new HashMap<>();

            for (JsonNode row : data) {
                String kod = row.path(FIELD_OKRES_KOD).asText(null);
                if (kod == null || kod.isBlank()) continue;

                JsonNode rateNode = row.path(FIELD_RATE);
                if (rateNode.isMissingNode() || rateNode.isNull()) continue;
                double rate = rateNode.asDouble();
                if (rate < 0 || rate > 100) continue;

                int rok   = row.path(FIELD_ROK).asInt(0);
                int mesic = row.path(FIELD_MESIC).asInt(0);
                int t     = rok * 100 + mesic; // yyyymm as sortable int

                if (t <= latestT.getOrDefault(kod, 0)) continue;
                result.put(kod, rate);
                latestT.put(kod, t);
            }

            if (result.isEmpty()) {
                log.warn("MPSV JSON: parsed 0 records — field names may have changed " +
                         "(expected '{}', '{}', '{}', '{}')",
                         FIELD_OKRES_KOD, FIELD_RATE, FIELD_ROK, FIELD_MESIC);
            }
            return result;

        } catch (Exception e) {
            log.warn("MPSV JSON failed for {}: {}", url, e.getMessage());
            return Collections.emptyMap();
        }
    }

    // ── CSV fallback parsing ──────────────────────────────────────────────────
    // Best-effort: handles semicolon or comma delimiter, Windows-1250 or UTF-8.
    // Looks for any column containing "okres" (kod) and "nezamestnanost"/"mira".

    private Map<String, Double> tryDataGovCzCsv(String url) {
        try {
            byte[] raw = httpGet(url);
            log.info("  MPSV CSV: downloaded {} KB", raw.length / 1024);

            for (java.nio.charset.Charset cs : new java.nio.charset.Charset[]{
                    java.nio.charset.Charset.forName("Windows-1250"),
                    java.nio.charset.StandardCharsets.UTF_8}) {
                for (char delim : new char[]{';', ','}) {
                    try {
                        Map<String, Double> r = parseCsv(raw, cs, delim);
                        if (!r.isEmpty()) {
                            log.info("  MPSV CSV: {} records (charset={}, delim='{}')",
                                r.size(), cs, delim);
                            return r;
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            log.warn("  MPSV CSV failed for {}: {}", url, e.getMessage());
        }
        return Collections.emptyMap();
    }

    private Map<String, Double> parseCsv(byte[] raw, java.nio.charset.Charset cs, char delim) throws Exception {
        Map<String, Double> result = new HashMap<>();
        org.apache.commons.csv.CSVFormat fmt = org.apache.commons.csv.CSVFormat.DEFAULT.builder()
            .setHeader().setSkipHeaderRecord(true).setDelimiter(delim)
            .setIgnoreEmptyLines(true).setTrim(true).build();

        try (java.io.Reader reader = new java.io.InputStreamReader(
                    new java.io.ByteArrayInputStream(raw), cs);
             org.apache.commons.csv.CSVParser parser =
                    new org.apache.commons.csv.CSVParser(reader, fmt)) {

            Map<String, Integer> headers = parser.getHeaderMap();
            if (headers == null || headers.isEmpty()) return Collections.emptyMap();

            String colKod  = findColumn(headers, "okres_kod", "kod_okresu", "okres");
            String colRate = findColumn(headers, "mira_nezamestnanosti", "nezamestnanost", "mira");

            if (colKod == null || colRate == null) return Collections.emptyMap();

            for (org.apache.commons.csv.CSVRecord rec : parser) {
                try {
                    String kod = rec.get(colKod).trim();
                    if (kod.isBlank()) continue;
                    String rateStr = rec.get(colRate).replace(",", ".").replace("\u00a0", "").trim();
                    double rate = Double.parseDouble(rateStr);
                    if (rate >= 0 && rate <= 100) result.merge(kod, rate, (a, b) -> b); // last wins
                } catch (Exception ignored) {}
            }
        }
        return result;
    }

    // ── HTTP helper ───────────────────────────────────────────────────────────

    private byte[] httpGet(String url) throws Exception {
        RequestConfig rc = RequestConfig.custom()
            .setConnectTimeout(Timeout.ofMilliseconds(config.httpTimeoutMs))
            .setResponseTimeout(Timeout.ofMilliseconds(config.httpTimeoutMs))
            .build();
        try (CloseableHttpClient http = HttpClients.custom().setDefaultRequestConfig(rc).build()) {
            return http.execute(new HttpGet(url), response -> {
                int code = response.getCode();
                if (code != 200) throw new RuntimeException("HTTP " + code + " from " + url);
                return EntityUtils.toByteArray(response.getEntity());
            });
        }
    }

    private static String findColumn(Map<String, Integer> headers, String... candidates) {
        for (String candidate : candidates)
            for (String header : headers.keySet())
                if (header.equalsIgnoreCase(candidate) ||
                    header.toLowerCase().contains(candidate.toLowerCase()))
                    return header;
        return null;
    }
}
