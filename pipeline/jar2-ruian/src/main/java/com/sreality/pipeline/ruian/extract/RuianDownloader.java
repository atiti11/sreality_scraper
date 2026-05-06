package com.sreality.pipeline.ruian.extract;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.zip.ZipInputStream;
import java.io.InputStream;

/**
 * Downloads the RUIAN full-state VFR XML snapshot from CUZK.
 *
 * URL structure: https://services.cuzk.gov.cz/vfr/YYYYMM/YYYYMMDD_ST_UKSG.xml.zip
 * File date = last day of the month (e.g. 202602 → 20260228_ST_UKSG.xml.zip)
 *
 * Download strategy:
 *   Uses curl (via ProcessBuilder) instead of Java HTTP client.
 *   curl handles large files reliably with built-in retry and resume.
 *   Falls back to Java HTTP client if curl is not available.
 *
 * URL resolution:
 *   1. RUIAN_OVERRIDE_URL env var
 *   2. Walk back from current month up to MAX_MONTHS_BACK
 */
public class RuianDownloader {

    private static final Logger log = LoggerFactory.getLogger(RuianDownloader.class);

    private static final String BASE_URL        = "https://services.cuzk.gov.cz/vfr/";
    private static final DateTimeFormatter DIR_FMT  = DateTimeFormatter.ofPattern("yyyyMM");
    private static final DateTimeFormatter FILE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int MAX_MONTHS_BACK = 6;

    public static String buildUrl(YearMonth month) {
        LocalDate fileDate = month.atEndOfMonth();
        String dir  = month.format(DIR_FMT);
        String file = fileDate.format(FILE_FMT) + "_ST_UKSG.xml.zip";
        return BASE_URL + dir + "/" + file;
    }

    public static boolean isUpdateAvailable(String url, LocalDate lastLoaded) {
        if (lastLoaded == null) {
            log.info("No RUIAN snapshot loaded yet — will download.");
            return true;
        }
        try {
            String fname  = url.substring(url.lastIndexOf('/') + 1);
            LocalDate remote = LocalDate.parse(fname.substring(0, 8), FILE_FMT);
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

    public static String resolveUrl() throws IOException {
        String override = System.getenv("RUIAN_OVERRIDE_URL");
        if (override != null && !override.isBlank()) {
            log.info("Using RUIAN_OVERRIDE_URL: {}", override);
            return override;
        }
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
            + "Set RUIAN_OVERRIDE_URL or RUIAN_LOCAL_XML env var.");
    }

    private static boolean urlAvailable(String url) {
        try {
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(30))
                .build();
            var resp = client.send(req, HttpResponse.BodyHandlers.discarding());
            // Some servers don't support HEAD — treat 405 as available (will try GET)
            return resp.statusCode() == 200 || resp.statusCode() == 405;
        } catch (Exception e) {
            // Fall back to GET with immediate disconnect
            try {
                HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(30))
                    .build();
                var resp = client.send(req, HttpResponse.BodyHandlers.discarding());
                return resp.statusCode() == 200;
            } catch (Exception e2) {
                log.debug("URL {} unreachable: {}", url, e2.getMessage());
                return false;
            }
        }
    }

    /**
     * Downloads the zip and extracts the XML to a temp file.
     *
     * Uses curl if available (handles 300MB+ files reliably with resume support).
     * Falls back to Java HTTP client if curl is not installed.
     *
     * Caller must delete the returned path after use.
     */
    public static Path downloadAndExtract(String url) throws IOException {
        Path zipFile = Files.createTempFile("ruian_", ".zip");
        Path xmlFile = Files.createTempFile("ruian_", ".xml");

        try {
            if (curlAvailable()) {
                downloadWithCurl(url, zipFile);
            } else {
                log.warn("curl not found — using Java HTTP client (may fail on large files)");
                downloadWithJava(url, zipFile);
            }

            log.info("Download complete: {} ({} MB)", zipFile, Files.size(zipFile) / 1_048_576);

            // Extract XML from zip
            try (InputStream is = Files.newInputStream(zipFile);
                 ZipInputStream zip = new ZipInputStream(is)) {
                if (zip.getNextEntry() == null) throw new IOException("Empty zip from " + url);
                Files.copy(zip, xmlFile, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("Extracted RUIAN XML: {} ({} MB)", xmlFile, Files.size(xmlFile) / 1_048_576);
            return xmlFile;

        } finally {
            Files.deleteIfExists(zipFile);
        }
    }

    // ── curl download ─────────────────────────────────────────────────────────

    private static boolean curlAvailable() {
        try {
            Process p = new ProcessBuilder("curl", "--version")
                .redirectErrorStream(true)
                .start();
            p.waitFor();
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void downloadWithCurl(String url, Path dest) throws IOException {
        log.info("Downloading RUIAN with curl: {}", url);
        // --retry 5:          retry up to 5 times on transient errors
        // --retry-delay 10:   wait 10 seconds between retries
        // --continue-at -:    resume partial downloads
        // --location:         follow redirects
        // --fail:             exit non-zero on HTTP errors (4xx/5xx)
        ProcessBuilder pb = new ProcessBuilder(
            "curl",
            "--retry", "5",
            "--retry-delay", "10",
            "--retry-max-time", "600",
            "--continue-at", "-",
            "--location",
            "--fail",
            "--silent",
            "--show-error",
            "-o", dest.toString(),
            url
        );
        pb.redirectErrorStream(true);
        pb.inheritIO();   // stream curl output to container stdout

        try {
            Process p = pb.start();
            int exit = p.waitFor();
            if (exit != 0) {
                throw new IOException("curl exited with code " + exit + " for URL: " + url);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted", e);
        }
    }

    // ── Java HTTP client fallback ─────────────────────────────────────────────

    private static void downloadWithJava(String url, Path dest) throws IOException {
        log.info("Downloading RUIAN with Java HTTP client: {}", url);
        try {
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(30))
                .build();
            client.send(req, HttpResponse.BodyHandlers.ofFile(dest));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted", e);
        }
    }
}
