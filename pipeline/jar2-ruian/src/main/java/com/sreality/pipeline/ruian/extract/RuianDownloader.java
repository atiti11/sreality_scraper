package com.sreality.pipeline.ruian.extract;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.zip.ZipInputStream;

/**
 * Downloads the RUIAN full-state VFR XML snapshot from CUZK.
 *
 * Correct URL structure (verified May 2026):
 * Base: https://services.cuzk.gov.cz/vfr/YYYYMM/
 * File: YYYYMMDD_ST_UKSG.xml.zip
 * Date: last day of the previous month (e.g. 20260228 in directory 202602)
 *
 * The old vdp.cuzk.gov.cz URL is no longer used.
 *
 * Resolution strategy:
 * 1. RUIAN_OVERRIDE_URL env var — use exactly this URL.
 * 2. Walk back from current month, trying up to MAX_MONTHS_BACK months,
 * until a directory listing returns HTTP 200 and the ST_UKSG file is found.
 */
public class RuianDownloader {

    private static final Logger log = LoggerFactory.getLogger(RuianDownloader.class);

    private static final String BASE_URL = "https://services.cuzk.gov.cz/vfr/";
    private static final DateTimeFormatter DIR_FMT = DateTimeFormatter.ofPattern("yyyyMM");
    private static final DateTimeFormatter FILE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int MAX_MONTHS_BACK = 6;

    /**
     * Builds the URL for the ST_UKSG file in a given month directory.
     * The file is dated the last day of the previous month.
     * e.g. directory 202602 → file 20260228_ST_UKSG.xml.zip
     */
    public static String buildUrl(YearMonth month) {
        // File date = last day of the month
        LocalDate fileDate = month.atEndOfMonth();
        String dir = month.format(DIR_FMT);
        String file = fileDate.format(FILE_FMT) + "_ST_UKSG.xml.zip";
        return BASE_URL + dir + "/" + file;
    }

    /** Returns true if the snapshot date from URL is newer than lastLoaded. */
    public static boolean isUpdateAvailable(String url, LocalDate lastLoaded) {
        if (lastLoaded == null) {
            log.info("No RUIAN snapshot loaded yet — will download.");
            return true;
        }
        try {
            // Extract date from filename: .../202602/20260228_ST_UKSG.xml.zip
            String filename = url.substring(url.lastIndexOf('/') + 1);
            LocalDate remote = LocalDate.parse(filename.substring(0, 8), FILE_FMT);
            if (remote.isAfter(lastLoaded)) {
                log.info("Remote RUIAN {} newer than loaded {} — will download.", remote, lastLoaded);
                return true;
            }
            log.info("RUIAN {} already current (loaded: {}).", remote, lastLoaded);
            return false;
        } catch (Exception e) {
            log.warn("Could not parse date from URL {} — downloading anyway.", url);
            return true;
        }
    }

    /**
     * Resolves the best available RUIAN URL.
     * Checks RUIAN_OVERRIDE_URL env var first, then walks back month by month.
     */
    public static String resolveUrl() throws IOException {
        String override = System.getenv("RUIAN_OVERRIDE_URL");
        if (override != null && !override.isBlank()) {
            override = override.trim();
            if (!override.startsWith("http")) {
                throw new IOException("RUIAN_OVERRIDE_URL must be absolute HTTP(S): " + override);
            }
            log.info("Using RUIAN_OVERRIDE_URL: {}", override);
            return override;
        }

        // Start from current month and walk backwards
        YearMonth month = YearMonth.now();
        for (int i = 0; i < MAX_MONTHS_BACK; i++) {
            String url = buildUrl(month);
            log.info("Trying RUIAN ({} month(s) back): {}", i, url);
            if (urlAvailable(url)) {
                log.info("Found available RUIAN snapshot: {}", url);
                return url;
            }
            month = month.minusMonths(1);
        }

        throw new IOException(
                "No RUIAN snapshot found in the last " + MAX_MONTHS_BACK + " months. "
                        + "Check " + BASE_URL + " manually, then set RUIAN_OVERRIDE_URL env var.");
    }

    private static boolean urlAvailable(String url) {
        try (CloseableHttpClient http = HttpClients.createDefault()) {
            final int[] code = { 0 };
            URI uri = URI.create(url);
            http.execute(new HttpGet(uri), response -> {
                code[0] = response.getCode();
                EntityUtils.consume(response.getEntity());
                return null;
            });
            log.debug("URL {} → HTTP {}", url, code[0]);
            return code[0] == 200;
        } catch (IllegalArgumentException e) {
            log.debug("URL {} is malformed: {}", url, e.getMessage());
            return false;
        } catch (Exception e) {
            log.debug("URL {} unreachable: {}", url, e.getMessage());
            return false;
        }
    }

    /**
     * Downloads the zip and extracts the XML to a temp file.
     * Supports both HTTP(S) URLs and file:// URLs (for local testing).
     * Caller must delete the returned path after use.
     */
    public static Path downloadAndExtract(String url) throws IOException {
        // Validate URL before attempting to use it
        if (url == null || url.isBlank()) {
            throw new IOException("RUIAN URL is null or empty");
        }

        url = url.trim();
        boolean isFile = url.startsWith("file://");
        boolean isHttp = url.startsWith("http://") || url.startsWith("https://");

        if (!isHttp && !isFile) {
            throw new IOException("RUIAN URL must be absolute (http/https or file://): " + url);
        }

        log.info("Downloading RUIAN from: {}", url);
        log.debug("URL length: {}, first 100 chars: {}", url.length(),
                url.substring(0, Math.min(100, url.length())));

        Path tmp = Files.createTempFile("ruian_", ".xml");
        final String finalUrl = url; // For use in lambda expressions

        if (isFile) {
            // Handle file:// URL (for local testing with mounted volumes)
            URI fileUri;
            try {
                fileUri = URI.create(finalUrl);
            } catch (IllegalArgumentException e) {
                throw new IOException("Invalid local RUIAN file URL: " + finalUrl, e);
            }
            Path srcFile = Paths.get(fileUri);
            log.info("Reading local file: {}", srcFile);
            if (!Files.exists(srcFile)) {
                throw new IOException("Local RUIAN file not found: " + srcFile);
            }
            try (InputStream fileStream = Files.newInputStream(srcFile);
                    ZipInputStream zip = new ZipInputStream(fileStream)) {
                if (zip.getNextEntry() == null)
                    throw new IOException("Empty zip from " + finalUrl);
                Files.copy(zip, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
        } else {
            // Handle HTTP(S) URL
            RequestConfig requestConfig = RequestConfig.custom()
                    .setConnectTimeout(Timeout.ofSeconds(30))
                    .setResponseTimeout(Timeout.ofSeconds(120))
                    .build();

            try (CloseableHttpClient http = HttpClients.custom()
                    .setDefaultRequestConfig(requestConfig)
                    .build()) {
                URI uri;
                try {
                    uri = URI.create(finalUrl);
                } catch (IllegalArgumentException e) {
                    throw new IOException("Invalid RUIAN URL format: " + finalUrl, e);
                }

                HttpGet request = new HttpGet(uri);
                request.setHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");

                http.execute(request, response -> {
                    int code = response.getCode();
                    if (code != 200)
                        throw new IOException("HTTP " + code + " from " + finalUrl);
                    try (InputStream body = response.getEntity().getContent();
                            ZipInputStream zip = new ZipInputStream(body)) {
                        if (zip.getNextEntry() == null)
                            throw new IOException("Empty zip from " + finalUrl);
                        Files.copy(zip, tmp, StandardCopyOption.REPLACE_EXISTING);
                    }
                    EntityUtils.consume(response.getEntity());
                    return null;
                });
            }
        }
        log.info("Extracted RUIAN XML to {} ({} MB)", tmp, Files.size(tmp) / 1_048_576);
        return tmp;
    }
}
