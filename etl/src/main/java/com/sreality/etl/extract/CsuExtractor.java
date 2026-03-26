package com.sreality.etl.extract;

import com.sreality.etl.config.EtlConfig;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;

/**
 * Downloads and parses CSU (Czech Statistical Office) demographic data.
 *
 * Source: "Počet obyvatel v obcích" — population by municipality.
 * URL: https://www.czso.cz/documents/10180/25233174/1300721903.csv
 *
 * The CSV uses Windows-1250 encoding and Czech number formatting
 * (spaces as thousands separators).
 *
 * Transformations applied here:
 *   - Windows-1250 encoding decoded to UTF-8 strings
 *   - Czech number strings (spaces as thousands sep) → integers/doubles
 *   - Municipality code (kod_obce) used as join key to RUIAN data
 *   - Rows with invalid/missing data are skipped with a warning
 */
public class CsuExtractor {

    private static final Logger log = LoggerFactory.getLogger(CsuExtractor.class);

    // CSU CSV column names (may vary by year — update if CSU changes format)
    private static final String COL_KOD_OBCE        = "Kód obce";
    private static final String COL_POPULATION      = "Počet obyvatel";
    private static final String COL_AREA_KM2        = "Výměra v ha";   // hectares — converted to km²
    private static final String COL_AVG_AGE         = "Průměrný věk";

    private final EtlConfig config;

    public CsuExtractor(EtlConfig config) {
        this.config = config;
    }

    /**
     * Downloads and parses the CSU CSV.
     * Returns a map from kod_obce → Demographics.
     */
    public Map<String, Demographics> extract() {
        log.info("Downloading CSU demographics from {}", config.csuDemographicsUrl);
        Map<String, Demographics> result = new HashMap<>();
        int skipped = 0;

        RequestConfig reqConfig = RequestConfig.custom()
            .setConnectTimeout(Timeout.ofMilliseconds(config.httpTimeoutMs))
            .setResponseTimeout(Timeout.ofMilliseconds(config.httpTimeoutMs))
            .build();

        try (CloseableHttpClient http = HttpClients.custom()
                .setDefaultRequestConfig(reqConfig).build()) {

            Reader reader = http.execute(new HttpGet(config.csuDemographicsUrl), response -> {
                // CSU CSVs use Windows-1250 encoding
                Charset charset;
                try {
                    charset = Charset.forName("Windows-1250");
                } catch (Exception e) {
                    charset = Charset.forName("UTF-8");
                }
                return new InputStreamReader(response.getEntity().getContent(), charset);
            });

            // CSU CSVs use semicolon delimiter
            CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setDelimiter(';')
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .build();

            try (CSVParser parser = new CSVParser(reader, format)) {
                for (CSVRecord record : parser) {
                    try {
                        String kodObce = record.get(COL_KOD_OBCE);
                        if (kodObce == null || kodObce.isBlank()) { skipped++; continue; }
                        kodObce = kodObce.trim();

                        Integer population     = parseIntCzech(record.get(COL_POPULATION));
                        Double  areaKm2        = parseHectaresToKm2(record.get(COL_AREA_KM2));
                        Double  avgAge         = parseDoubleCzech(record.get(COL_AVG_AGE));

                        // Derived: population density
                        Double density = null;
                        if (population != null && areaKm2 != null && areaKm2 > 0) {
                            density = population / areaKm2;
                        }

                        result.put(kodObce, new Demographics(
                            population, density, areaKm2, avgAge, null));

                    } catch (Exception e) {
                        log.warn("Skipping CSU row due to parse error: {}", e.getMessage());
                        skipped++;
                    }
                }
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed to download/parse CSU demographics", e);
        }

        log.info("CSU: {} records loaded, {} skipped", result.size(), skipped);
        return result;
    }

    // ── Number format transformations ─────────────────────────────────────────

    /**
     * Transformation: parse Czech integer string.
     * Czech numbers use non-breaking spaces as thousands separators.
     * e.g. "1 275 406" or "1\u00a0275\u00a0406" → 1275406
     */
    static Integer parseIntCzech(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String cleaned = raw.replace("\u00a0", "").replace(" ", "").replace(",", "").trim();
        if (cleaned.isEmpty()) return null;
        try { return Integer.parseInt(cleaned); }
        catch (Exception e) { return null; }
    }

    /**
     * Transformation: parse Czech decimal string.
     * Czech decimals use comma as decimal separator.
     * e.g. "42,3" → 42.3
     */
    static Double parseDoubleCzech(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String cleaned = raw.replace("\u00a0", "").replace(" ", "")
            .replace(",", ".").trim();
        if (cleaned.isEmpty()) return null;
        try { return Double.parseDouble(cleaned); }
        catch (Exception e) { return null; }
    }

    /**
     * Transformation: convert hectares to km².
     * 1 ha = 0.01 km²
     */
    static Double parseHectaresToKm2(String raw) {
        Double ha = parseDoubleCzech(raw);
        return ha != null ? ha * 0.01 : null;
    }

    /**
     * Demographic data for one municipality.
     * All fields nullable — not all municipalities have complete data.
     */
    public record Demographics(
        Integer population,
        Double  populationDensity,  // per km²
        Double  areaKm2,
        Double  avgAge,
        Double  unemploymentPct     // separate dataset — null for now
    ) {}
}
