package com.sreality.pipeline.ruian.extract;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.zip.ZipInputStream;

/**
 * Downloads the RUIAN full-state VFR XML snapshot from CUZK.
 *
 * URL pattern: https://vdp.cuzk.gov.cz/vymenny_format/soucasnost/YYYYMMDD_ST_UKSG.xml.zip
 * Published on the 1st of each month (or next business day).
 */
public class RuianDownloader {

    private static final Logger log = LoggerFactory.getLogger(RuianDownloader.class);
    private static final String URL_TEMPLATE =
        "https://vdp.cuzk.gov.cz/vymenny_format/soucasnost/%s_ST_UKSG.xml.zip";
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    public static String buildUrl(LocalDate month) {
        return String.format(URL_TEMPLATE, month.withDayOfMonth(1).format(FMT));
    }

    /** Returns true if the remote snapshot date (from URL) is newer than lastLoaded. */
    public static boolean isUpdateAvailable(String url, LocalDate lastLoaded) {
        if (lastLoaded == null) { log.info("No RUIAN snapshot loaded yet — will download."); return true; }
        try {
            String filename = url.substring(url.lastIndexOf('/') + 1);
            LocalDate remote = LocalDate.parse(filename.substring(0, 8), FMT);
            if (remote.isAfter(lastLoaded)) {
                log.info("Remote RUIAN {} newer than loaded {} — will download.", remote, lastLoaded);
                return true;
            }
            log.info("RUIAN {} already current (loaded: {}).", remote, lastLoaded);
            return false;
        } catch (Exception e) {
            log.warn("Could not parse date from URL {} — attempting download anyway.", url);
            return true;
        }
    }

    /**
     * Downloads the zip and extracts the XML to a temp file.
     * Caller must delete the returned path after use.
     */
    public static Path downloadAndExtract(String url) throws IOException {
        log.info("Downloading RUIAN from {}", url);
        Path tmp = Files.createTempFile("ruian_", ".xml");
        try (CloseableHttpClient http = HttpClients.createDefault()) {
            http.execute(new HttpGet(URI.create(url)), response -> {
                int code = response.getCode();
                if (code == 404) throw new IOException("RUIAN not yet available (404): " + url);
                if (code != 200) throw new IOException("HTTP " + code + " from " + url);
                try (InputStream body = response.getEntity().getContent();
                     ZipInputStream zip = new ZipInputStream(body)) {
                    if (zip.getNextEntry() == null) throw new IOException("Empty zip from " + url);
                    Files.copy(zip, tmp, StandardCopyOption.REPLACE_EXISTING);
                }
                EntityUtils.consume(response.getEntity());
                return null;
            });
        }
        log.info("Extracted RUIAN XML to {} ({} MB)", tmp, Files.size(tmp) / 1_048_576);
        return tmp;
    }
}
