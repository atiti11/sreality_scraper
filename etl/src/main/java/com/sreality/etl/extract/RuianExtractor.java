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
import org.apache.hc.core5.util.Timeout;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.io.geojson.GeoJsonReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Downloads RUIAN geographical data (cast_obce, obec, okres, kraj) from
 * the Czech geodata.gov.cz ArcGIS FeatureServer as GeoJSON.
 *
 * Transformation applied here:
 *   - GeoJSON polygon features → JTS Geometry objects (for spatial join)
 *   - RUIAN attribute names → Java model fields
 *   - Centroid computed for obec (used as fallback spatial index)
 *
 * The ArcGIS endpoint paginates at 10,000 features per request.
 * Czech Republic has ~6,200 obec and ~15,000 cast_obce — two requests needed
 * for cast_obce, one for everything else. Pagination is handled automatically.
 */
public class RuianExtractor {

    private static final Logger log = LoggerFactory.getLogger(RuianExtractor.class);

    private static final GeometryFactory GF =
        new GeometryFactory(new PrecisionModel(), 4326); // WGS84

    private final EtlConfig    config;
    private final ObjectMapper mapper = new ObjectMapper();
    private final GeoJsonReader geoJsonReader = new GeoJsonReader(GF);

    public RuianExtractor(EtlConfig config) {
        this.config = config;
    }

    // ── Public extraction methods ─────────────────────────────────────────────

    public List<DimKraj> extractKraj() {
        log.info("Fetching RUIAN kraj...");
        List<JsonNode> features = fetchAllFeatures(config.ruianKrajUrl);
        List<DimKraj> result = new ArrayList<>();
        for (JsonNode f : features) {
            JsonNode props = f.path("properties");
            String kod  = props.path("KOD_KRAJE").asText(null);
            String nazev = props.path("NAZEV_KRAJE").asText(null);
            if (kod == null || nazev == null) continue;
            result.add(new DimKraj(0, kod, nazev));
        }
        log.info("RUIAN kraj: {} records", result.size());
        return result;
    }

    public List<DimOkres> extractOkres() {
        log.info("Fetching RUIAN okres...");
        List<JsonNode> features = fetchAllFeatures(config.ruianOkresUrl);
        List<DimOkres> result = new ArrayList<>();
        for (JsonNode f : features) {
            JsonNode props = f.path("properties");
            String kodOkresu = props.path("KOD_OKRESU").asText(null);
            String nazev     = props.path("NAZEV_OKRESU").asText(null);
            String kodKraje  = props.path("KOD_KRAJE").asText(null);
            if (kodOkresu == null || nazev == null) continue;
            // krajId resolved in DimensionBuilder after kraj is loaded
            result.add(new DimOkres(0, kodOkresu, nazev, 0));
        }
        log.info("RUIAN okres: {} records", result.size());
        return result;
    }

    public List<DimObec> extractObec() {
        log.info("Fetching RUIAN obec...");
        List<JsonNode> features = fetchAllFeatures(config.ruianObecUrl);
        List<DimObec> result = new ArrayList<>();
        for (JsonNode f : features) {
            JsonNode props = f.path("properties");
            String kodObce   = props.path("KOD_OBCE").asText(null);
            String nazev     = props.path("NAZEV_OBCE").asText(null);
            String kodOkresu = props.path("KOD_OKRESU").asText(null);
            if (kodObce == null || nazev == null) continue;

            // Compute centroid for spatial fallback
            double lat = 0, lon = 0;
            try {
                Geometry geom = parseGeometry(f.path("geometry").toString());
                if (geom != null) {
                    lat = geom.getCentroid().getY();
                    lon = geom.getCentroid().getX();
                }
            } catch (Exception e) {
                // centroid not critical — use 0,0 as sentinel
            }
            // okresId resolved in DimensionBuilder
            result.add(new DimObec(0, kodObce, nazev, 0,
                null, null, null, null, null, lat, lon));
        }
        log.info("RUIAN obec: {} records", result.size());
        return result;
    }

    public List<DimCastObce> extractCastObce() {
        log.info("Fetching RUIAN cast_obce (may take a moment — ~15k features)...");
        List<JsonNode> features = fetchAllFeatures(config.ruianCastObceUrl);
        List<DimCastObce> result = new ArrayList<>();
        for (JsonNode f : features) {
            JsonNode props = f.path("properties");
            String kodCast = props.path("KOD_CASTI_OBCE").asText(null);
            String nazev   = props.path("NAZEV_CASTI_OBCE").asText(null);
            String kodObce = props.path("KOD_OBCE").asText(null);
            if (kodCast == null || nazev == null) continue;

            Geometry geom = null;
            try {
                geom = parseGeometry(f.path("geometry").toString());
            } catch (Exception e) {
                log.warn("Could not parse geometry for cast_obce {}: {}", kodCast, e.getMessage());
            }
            // obecId resolved in DimensionBuilder
            result.add(new DimCastObce(0, kodCast, nazev, 0, geom));
        }
        log.info("RUIAN cast_obce: {} records", result.size());
        return result;
    }

    // ── HTTP + pagination ─────────────────────────────────────────────────────

    /**
     * Fetches all GeoJSON features from an ArcGIS FeatureServer endpoint,
     * handling pagination automatically (resultOffset parameter).
     */
    private List<JsonNode> fetchAllFeatures(String baseUrl) {
        List<JsonNode> all = new ArrayList<>();
        int offset = 0;
        int pageSize = 10000;

        try (CloseableHttpClient http = buildHttpClient()) {
            while (true) {
                String url = baseUrl + "&resultOffset=" + offset;
                log.debug("Fetching: {}", url);

                JsonNode root = http.execute(new HttpGet(url), response -> {
                    try (InputStream is = response.getEntity().getContent()) {
                        return mapper.readTree(is);
                    }
                });

                JsonNode features = root.path("features");
                if (!features.isArray() || features.size() == 0) break;

                for (JsonNode feature : features) {
                    all.add(feature);
                }

                if (features.size() < pageSize) break; // last page
                offset += pageSize;
                log.debug("Paginating — fetched {} so far", all.size());
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch RUIAN data from " + baseUrl, e);
        }

        return all;
    }

    private Geometry parseGeometry(String geoJsonStr) {
        if (geoJsonStr == null || geoJsonStr.equals("null")) return null;
        try {
            return geoJsonReader.read(geoJsonStr);
        } catch (Exception e) {
            return null;
        }
    }

    private CloseableHttpClient buildHttpClient() {
        RequestConfig reqConfig = RequestConfig.custom()
            .setConnectTimeout(Timeout.ofMilliseconds(config.httpTimeoutMs))
            .setResponseTimeout(Timeout.ofMilliseconds(config.httpTimeoutMs))
            .build();
        return HttpClients.custom()
            .setDefaultRequestConfig(reqConfig)
            .build();
    }
}
