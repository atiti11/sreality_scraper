package com.sreality.etl.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sreality.etl.config.EtlConfig;
import com.sreality.etl.model.DimCastObce;
import com.sreality.etl.model.DimKraj;
import com.sreality.etl.model.DimObec;
import com.sreality.etl.model.DimOkres;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.Timeout;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Downloads RUIAN geographical data from the official ČÚZK ArcGIS MapServer.
 * Server: ags.cuzk.gov.cz/arcgis/rest/services/RUIAN/Prohlizeci_sluzba_nad_daty_RUIAN/MapServer
 *
 * Layer mapping and field names (verified from live API):
 *   11 CastObce: kod (int), nazev, obec (int FK → obec.kod) — POINTS, no polygon
 *   12 Obec:     kod (int), nazev, okres (int FK → okres.kod) — POLYGONS
 *   15 Okres:    kod (int), nazev, vusc (int FK → kraj.kod) — no geometry
 *   17 VUSC:     kod (int), nazev — no geometry
 *
 * FK codes (obec, okres, vusc) are captured on each model and passed to
 * PostgresLoader, which uses them in SQL subqueries to resolve surrogate keys.
 * All codes are integers in the API but stored as Strings internally.
 */
public class RuianExtractor {

    private static final Logger log = LoggerFactory.getLogger(RuianExtractor.class);

    private static final GeometryFactory GF =
        new GeometryFactory(new PrecisionModel(), 4326);

    private static final int PAGE_SIZE = 1000;

    private final EtlConfig          config;
    private final ObjectMapper        mapper = new ObjectMapper();
    private final CloseableHttpClient http;

    public RuianExtractor(EtlConfig config) {
        this.config = config;
        this.http   = buildHttpClient();
    }

    // ── Public extraction methods ─────────────────────────────────────────────

    /** Layer 17 — VUSC (Kraj). Fields: kod, nazev. No geometry. */
    public List<DimKraj> extractKraj() {
        log.info("Fetching RUIAN kraj (layer 17)...");
        List<DimKraj> result = new ArrayList<>();
        fetchInPages(config.ruianKrajUrl, feature -> {
            JsonNode attrs = feature.path("attributes");
            String kod   = intToStr(attrs, "kod");
            String nazev = str(attrs, "nazev");
            if (kod != null && nazev != null)
                result.add(new DimKraj(0, kod, nazev));
        });
        log.info("RUIAN kraj: {} records", result.size());
        return result;
    }

    /**
     * Layer 15 — Okres. Fields: kod, nazev, vusc (FK → kraj.kod).
     * vusc is stored as kodVusc on DimOkres for FK resolution during load.
     */
    public List<DimOkres> extractOkres() {
        log.info("Fetching RUIAN okres (layer 15)...");
        List<DimOkres> result = new ArrayList<>();
        fetchInPages(config.ruianOkresUrl, feature -> {
            JsonNode attrs = feature.path("attributes");
            String kod     = intToStr(attrs, "kod");
            String nazev   = str(attrs, "nazev");
            String kodVusc = intToStr(attrs, "vusc"); // FK → kraj.kod
            if (kod != null && nazev != null)
                result.add(new DimOkres(0, kod, nazev, 0, kodVusc));
        });
        log.info("RUIAN okres: {} records", result.size());
        return result;
    }

    /**
     * Layer 12 — Obec. Fields: kod, nazev, okres (FK → okres.kod). POLYGON geometry.
     * okres code is stored as kodOkresu on DimObec for FK resolution during load.
     * Geometry stored for point-in-polygon spatial join in SpatialJoiner.
     */
    public List<DimObec> extractObec() {
        log.info("Fetching RUIAN obec (layer 12, with polygon geometry)...");
        List<DimObec> result = new ArrayList<>();
        fetchInPages(config.ruianObecUrl, feature -> {
            JsonNode attrs    = feature.path("attributes");
            String kod        = intToStr(attrs, "kod");
            String nazev      = str(attrs, "nazev");
            String kodOkresu  = intToStr(attrs, "okres"); // FK → okres.kod
            if (kod != null && nazev != null) {
                double lat = 0, lon = 0;
                Geometry geom = null;
                try {
                    geom = parseArcGisGeometry(feature.path("geometry"));
                    if (geom != null && !geom.isEmpty()) {
                        lat = geom.getCentroid().getY();
                        lon = geom.getCentroid().getX();
                    }
                } catch (Exception ignored) {}
                result.add(new DimObec(0, kod, nazev, 0, kodOkresu,
                    null, null, null, null, null,
                    geom, lat, lon));
            }
        });
        log.info("RUIAN obec: {} records ({} with geometry)",
            result.size(),
            result.stream().filter(o -> o.geometry() != null).count());
        return result;
    }

    /**
     * Layer 11 — CastObce. Fields: kod, nazev, obec (FK → obec.kod). POINTS.
     * obec code is stored as kodObce on DimCastObce for FK resolution during load.
     * No polygon geometry — layer 11 returns points only.
     */
    public List<DimCastObce> extractCastObce() {
        log.info("Fetching RUIAN cast_obce (layer 11, attribute data only)...");
        List<DimCastObce> result = new ArrayList<>();
        fetchInPages(config.ruianCastObceUrl, feature -> {
            JsonNode attrs  = feature.path("attributes");
            String kod      = intToStr(attrs, "kod");
            String nazev    = str(attrs, "nazev");
            String kodObce  = intToStr(attrs, "obec"); // FK → obec.kod
            if (kod != null && nazev != null)
                result.add(new DimCastObce(0, kod, nazev, 0, kodObce, null));
        });
        log.info("RUIAN cast_obce: {} records (no geometry)", result.size());
        return result;
    }

    // ── Pagination ────────────────────────────────────────────────────────────

    private void fetchInPages(String baseUrl, Consumer<JsonNode> featureConsumer) {
        int offset = 0;
        int total  = 0;

        while (true) {
            final int currentOffset = offset;

            String url = baseUrl
                + "&resultRecordCount=" + PAGE_SIZE
                + "&resultOffset=" + currentOffset
                + "&f=json";

            log.info("  Fetching offset {}...", currentOffset);

            JsonNode root;
            try {
                root = http.execute(new HttpGet(url), response -> {
                    int code = response.getCode();
                    if (code != 200) {
                        String body = EntityUtils.toString(response.getEntity());
                        throw new RuntimeException("HTTP " + code
                            + " at offset " + currentOffset + ": " + truncate(body, 400));
                    }
                    try (InputStream is = response.getEntity().getContent()) {
                        return mapper.readTree(is);
                    }
                });
            } catch (Exception e) {
                throw new RuntimeException("Failed to fetch RUIAN at offset " + currentOffset, e);
            }

            if (root.has("error")) {
                JsonNode err = root.path("error");
                throw new RuntimeException("ArcGIS error at offset " + currentOffset
                    + ": code=" + err.path("code").asText()
                    + " message=" + err.path("message").asText()
                    + " details=" + err.path("details"));
            }

            JsonNode features = root.path("features");
            if (!features.isArray() || features.isEmpty()) {
                log.info("  No features at offset {} — done.", currentOffset);
                break;
            }

            int count = features.size();
            for (JsonNode feature : features) featureConsumer.accept(feature);
            total  += count;
            offset += count;

            boolean exceeded = root.path("exceededTransferLimit").asBoolean(false);
            log.info("  Got {} features, total: {}, exceededTransferLimit: {}",
                count, total, exceeded);

            if (!exceeded && count < PAGE_SIZE) break;
        }
    }

    // ── Geometry parsing ──────────────────────────────────────────────────────

    private static Geometry parseArcGisGeometry(JsonNode geomNode) {
        if (geomNode == null || geomNode.isMissingNode() || geomNode.isNull()) return null;
        JsonNode rings = geomNode.path("rings");
        if (!rings.isArray() || rings.isEmpty()) return null;
        try {
            LinearRing exterior = parseRing(rings.get(0));
            if (exterior == null) return null;
            LinearRing[] holes = new LinearRing[Math.max(0, rings.size() - 1)];
            for (int i = 1; i < rings.size(); i++) holes[i - 1] = parseRing(rings.get(i));
            Polygon poly = GF.createPolygon(exterior, holes);
            if (!poly.isValid()) {
                Geometry fixed = poly.buffer(0);
                return fixed.isEmpty() ? poly : fixed;
            }
            return poly;
        } catch (Exception e) {
            return null;
        }
    }

    private static LinearRing parseRing(JsonNode ringNode) {
        if (!ringNode.isArray() || ringNode.size() < 4) return null;
        Coordinate[] coords = new Coordinate[ringNode.size()];
        for (int i = 0; i < ringNode.size(); i++) {
            JsonNode pt = ringNode.get(i);
            coords[i] = new Coordinate(pt.get(0).asDouble(), pt.get(1).asDouble());
        }
        return GF.createLinearRing(coords);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String str(JsonNode attrs, String key) {
        JsonNode node = attrs.path(key);
        if (node.isMissingNode() || node.isNull()) return null;
        String val = node.asText("").trim();
        return (val.isEmpty() || val.equals("null")) ? null : val;
    }

    /** RUIAN codes come as integers from the API — convert to String for consistent keying. */
    private static String intToStr(JsonNode attrs, String key) {
        JsonNode node = attrs.path(key);
        if (node.isMissingNode() || node.isNull()) return null;
        if (node.isNumber()) {
            int v = node.asInt();
            return v == 0 ? null : String.valueOf(v);
        }
        String s = node.asText("").trim();
        return (s.isEmpty() || s.equals("0") || s.equals("null")) ? null : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private CloseableHttpClient buildHttpClient() {
        RequestConfig rc = RequestConfig.custom()
            .setConnectTimeout(Timeout.ofMilliseconds(config.httpTimeoutMs))
            .setResponseTimeout(Timeout.ofMilliseconds(config.httpTimeoutMs))
            .build();
        return HttpClients.custom().setDefaultRequestConfig(rc).build();
    }
}
