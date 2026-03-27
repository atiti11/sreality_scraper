package com.sreality.etl.extract;

import com.sreality.etl.config.EtlConfig;
import com.sreality.etl.model.*;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.Timeout;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.simplify.TopologyPreservingSimplifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;
import java.io.*;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads and parses the ČÚZK RUIAN full state VFR XML file.
 *
 * File: ST_UZSZ — full state, complete copy, simplified boundaries.
 * URL:  https://services.cuzk.gov.cz/vfr/YYYYMM/YYYYMMDD_ST_UZSZ.xml.zip
 * Size: ~4.7 MB compressed. Contains the complete hierarchy:
 *   Vusc → Okres → Obec → CastObce → Zsj (with polygons)
 *
 * Parser design: depth-aware for entity/collection detection,
 * but FK wrapper detection is depth-independent (just tracks element name)
 * to avoid off-by-one depth bugs.
 */
public class RuianVfrExtractor {

    private static final Logger log = LoggerFactory.getLogger(RuianVfrExtractor.class);

    private static final String BASE_URL = "https://services.cuzk.gov.cz/vfr/";
    private static final double SIMPLIFY_TOLERANCE = 0.0005;
    private static final GeometryFactory GF = new GeometryFactory(new PrecisionModel(), 4326);

    private final EtlConfig config;

    public RuianVfrExtractor(EtlConfig config) {
        this.config = config;
    }

    public record VfrResult(
        LocalDate         snapshotDate,
        List<DimKraj>     kraj,
        List<DimOkres>    okres,
        List<DimObec>     obec,
        List<DimCastObce> castObce,
        List<ZsjRecord>   zsj
    ) {}

    public record ZsjRecord(int kod, String nazev, int castObceKod, Geometry geometry) {}

    // ── Public API ────────────────────────────────────────────────────────────

    public VfrResult extract() {
        for (int monthsBack = 1; monthsBack <= 3; monthsBack++) {
            YearMonth ym  = YearMonth.now().minusMonths(monthsBack);
            LocalDate end = ym.atEndOfMonth();
            String url = BASE_URL
                + String.format("%04d%02d", ym.getYear(), ym.getMonthValue()) + "/"
                + end.format(DateTimeFormatter.BASIC_ISO_DATE) + "_ST_UZSZ.xml.zip";

            log.info("Trying RUIAN VFR (ST_UZSZ): {}", url);

            byte[] zipBytes;
            try (CloseableHttpClient http = buildHttpClient()) {
                zipBytes = http.execute(new HttpGet(url), response -> {
                    int code = response.getCode();
                    if (code != 200) throw new RuntimeException("HTTP " + code);
                    return EntityUtils.toByteArray(response.getEntity());
                });
            } catch (Exception e) {
                log.warn("VFR download failed ({}): {}", url, e.getMessage());
                continue;
            }

            log.info("Downloaded {} MB — parsing XML...", zipBytes.length / 1024 / 1024);

            try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.getName().endsWith(".xml")) {
                        LocalDate snapshotDate = parseDateFromFilename(entry.getName());
                        log.info("Parsing VFR XML: {} (snapshot: {})", entry.getName(), snapshotDate);
                        VfrResult result = parseXml(zis, snapshotDate);
                        log.info("VFR parsed: {} kraj, {} okres, {} obec, {} cast_obce, {} ZSJ ({} with geometry)",
                            result.kraj().size(), result.okres().size(),
                            result.obec().size(), result.castObce().size(),
                            result.zsj().size(),
                            result.zsj().stream().filter(r -> r.geometry() != null).count());
                        return result;
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to parse VFR ZIP {}: {}", url, e.getMessage());
            }
        }

        log.warn("All VFR attempts failed — falling back to ArcGIS API.");
        return null;
    }

    // ── XML parsing ───────────────────────────────────────────────────────────

    /**
     * Single-pass StAX parser.
     *
     * Strategy:
     * - Use depth to detect collection wrappers (depth=3) and entity records (depth=4).
     * - Use element NAME (not depth) to detect FK wrappers and Kod/Nazev/Hranice fields.
     *   This avoids off-by-one depth errors when parsing nested structures.
     * - Track a small state machine: entityType → inFkElement → inKod/inNazev/inPosList
     */
    private VfrResult parseXml(InputStream xmlStream, LocalDate snapshotDate) throws Exception {
        List<DimKraj>     krajList     = new ArrayList<>();
        List<DimOkres>    okresList    = new ArrayList<>();
        List<DimObec>     obecList     = new ArrayList<>();
        List<DimCastObce> castObceList = new ArrayList<>();
        List<ZsjRecord>   zsjList      = new ArrayList<>();

        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        XMLStreamReader reader = factory.createXMLStreamReader(xmlStream);

        int     depth        = 0;
        int     entityDepth  = -1;   // depth at which current entity started
        String  collection   = null; // collection wrapper name (set at depth=3)
        String  entityType   = null; // current record type (set at depth=4)
        int     currentKod   = 0;
        String  currentNazev = null;
        int     fkKod        = 0;
        String  currentPosList = null;
        boolean inHranice    = false;
        boolean inFkElement  = false;
        boolean inKod        = false;
        boolean inNazev      = false;
        boolean inPosList    = false;
        StringBuilder posListBuf = new StringBuilder();

        while (reader.hasNext()) {
            int event = reader.next();

            if (event == XMLStreamReader.START_ELEMENT) {
                depth++;
                String local = reader.getLocalName();

                // ── Collection wrapper at depth=3 ─────────────────────────────
                if (depth == 3) {
                    collection = local;

                // ── Entity record starts one level below collection ────────────
                } else if (entityType == null && collection != null
                           && depth == 4 && isRecordInCollection(collection, local)) {
                    entityType  = local;
                    entityDepth = depth;
                    currentKod = 0; currentNazev = null; fkKod = 0;
                    currentPosList = null; inFkElement = false; inHranice = false;

                // ── Inside an entity record ────────────────────────────────────
                } else if (entityType != null) {

                    if (!inHranice && !inFkElement && isFkWrapper(entityType, local)) {
                        // FK wrapper element (e.g. <oki:Vusc> inside Okres)
                        inFkElement = true;

                    } else if ("Hranice".equals(local) && !inFkElement) {
                        inHranice = true;

                    } else if ("posList".equals(local) && inHranice) {
                        inPosList = true;
                        posListBuf.setLength(0);

                    } else if ("Kod".equals(local)) {
                        inKod = true;

                    } else if ("Nazev".equals(local) && !inFkElement && !inHranice) {
                        inNazev = true;
                    }
                }

            } else if (event == XMLStreamReader.CHARACTERS) {
                if (entityType == null) continue;
                String text = reader.getText().trim();
                if (text.isEmpty()) continue;
                if      (inKod && inFkElement) { try { fkKod      = Integer.parseInt(text); } catch (Exception ignored) {} }
                else if (inKod)                { try { currentKod = Integer.parseInt(text); } catch (Exception ignored) {} }
                else if (inNazev)              currentNazev = text;
                else if (inPosList)            posListBuf.append(text).append(' ');

            } else if (event == XMLStreamReader.END_ELEMENT) {
                String local = reader.getLocalName();

                if (entityType != null) {
                    if ("Kod".equals(local)) {
                        inKod = false;
                    } else if ("Nazev".equals(local)) {
                        inNazev = false;
                    } else if ("posList".equals(local) && inPosList) {
                        inPosList = false;
                        currentPosList = posListBuf.toString().trim();
                    } else if ("Hranice".equals(local)) {
                        inHranice = false;
                    } else if (inFkElement && isFkWrapper(entityType, local)) {
                        inFkElement = false;
                    } else if (depth == entityDepth && local.equals(entityType)) {
                        // End of entity record — emit
                        emitRecord(entityType, currentKod, currentNazev, fkKod, currentPosList,
                            krajList, okresList, obecList, castObceList, zsjList);
                        entityType  = null;
                        entityDepth = -1;
                    }
                } else if (depth == 3) {
                    collection = null;
                }

                depth--;
            }
        }
        reader.close();
        return new VfrResult(snapshotDate, krajList, okresList, obecList, castObceList, zsjList);
    }

    private static boolean isRecordInCollection(String collection, String local) {
        return switch (collection) {
            case "Vusc"      -> "Vusc".equals(local);
            case "Okresy"    -> "Okres".equals(local);
            case "Obce"      -> "Obec".equals(local);
            case "CastiObci" -> "CastObce".equals(local);
            case "Zsj"       -> "Zsj".equals(local);
            default -> false;
        };
    }

    private static boolean isFkWrapper(String entityType, String child) {
        return switch (entityType) {
            case "Okres"    -> "Vusc".equals(child);
            case "Obec"     -> "Okres".equals(child);
            case "CastObce" -> "Obec".equals(child);
            case "Zsj"      -> "CastObce".equals(child);
            default -> false;
        };
    }

    private static void emitRecord(String type, int kod, String nazev, int fkKod, String posList,
                                   List<DimKraj> kraj, List<DimOkres> okres,
                                   List<DimObec> obec, List<DimCastObce> castObce,
                                   List<ZsjRecord> zsj) {
        if (kod == 0 || nazev == null) return;
        String kodStr = String.valueOf(kod);
        String fkStr  = fkKod > 0 ? String.valueOf(fkKod) : null;
        switch (type) {
            case "Vusc" ->
                kraj.add(new DimKraj(0, kodStr, nazev));
            case "Okres" ->
                okres.add(new DimOkres(0, kodStr, nazev, 0, fkStr));
            case "Obec" -> {
                Geometry geom = parseGeometry(posList);
                double lat = 0, lon = 0;
                if (geom != null) { lat = geom.getCentroid().getY(); lon = geom.getCentroid().getX(); }
                obec.add(new DimObec(0, kodStr, nazev, 0, fkStr, null, null, null, null, null, geom, lat, lon));
            }
            case "CastObce" ->
                castObce.add(new DimCastObce(0, kodStr, nazev, 0, fkStr, null));
            case "Zsj" -> {
                if (fkKod > 0) zsj.add(new ZsjRecord(kod, nazev, fkKod, parseGeometry(posList)));
            }
        }
    }

    // ── Geometry ──────────────────────────────────────────────────────────────

    private static Geometry parseGeometry(String posList) {
        if (posList == null || posList.isBlank()) return null;
        String[] tokens = posList.trim().split("\\s+");
        if (tokens.length < 6) return null;
        int n = tokens.length / 2;
        Coordinate[] coords = new Coordinate[n];
        try {
            for (int i = 0; i < n; i++) {
                double[] wgs84 = sjtskToWgs84(
                    Double.parseDouble(tokens[i * 2]),
                    Double.parseDouble(tokens[i * 2 + 1]));
                coords[i] = new Coordinate(wgs84[1], wgs84[0]);
            }
        } catch (Exception e) { return null; }
        if (!coords[0].equals2D(coords[n - 1])) {
            coords = Arrays.copyOf(coords, n + 1);
            coords[n] = coords[0];
        }
        if (coords.length < 4) return null;
        try {
            Polygon poly = GF.createPolygon(GF.createLinearRing(coords));
            if (!poly.isValid()) { Geometry f = poly.buffer(0); poly = f.isEmpty() ? poly : (Polygon) f; }
            Geometry s = TopologyPreservingSimplifier.simplify(poly, SIMPLIFY_TOLERANCE);
            return (s != null && s.isValid() && !s.isEmpty()) ? s : poly;
        } catch (Exception e) { return null; }
    }

    // ── S-JTSK → WGS84 ───────────────────────────────────────────────────────

    static double[] sjtskToWgs84(double y, double x) {
        double Y = -y, X = -x;
        double a = 6377397.155, f = 1.0/299.1528128, e2 = 2*f-f*f, e = Math.sqrt(e2);
        double lat0 = Math.toRadians(49.5), lon0 = Math.toRadians(42.5-17.66666666667);
        double k2   = Math.sqrt(1+e2*Math.pow(Math.cos(lat0),4)/(1-e2));
        double lats = Math.asin(Math.sin(lat0)/k2);
        double rho0 = 0.9999*a*Math.sqrt(1-e2)/((1-e2*Math.sin(lat0)*Math.sin(lat0))*Math.cos(lats));
        double rho  = Math.sqrt(X*X+Y*Y), D = Math.atan2(Y,X)/k2;
        double alpha = Math.toRadians(30.28813972222222);
        double lat1 = 2*Math.atan(Math.pow(rho0/rho,1.0/k2)*Math.tan(Math.PI/4+lats/2))-Math.PI/2;
        double lon1 = alpha-D, lb = lat1;
        for (int i=0;i<10;i++){double s=Math.sin(lb),nv=2*Math.atan(Math.tan(Math.PI/4+lat1/2)*Math.pow((1+e*s)/(1-e*s),e/2))-Math.PI/2;if(Math.abs(nv-lb)<1e-12){lb=nv;break;}lb=nv;}
        double lonb=lon0+lon1, Nb=a/Math.sqrt(1-e2*Math.sin(lb)*Math.sin(lb));
        double Xc=Nb*Math.cos(lb)*Math.cos(lonb),Yc=Nb*Math.cos(lb)*Math.sin(lonb),Zc=Nb*(1-e2)*Math.sin(lb);
        double ds=3.5378e-6,rx=Math.toRadians(4.9732/3600),ry=Math.toRadians(1.5108/3600),rz=Math.toRadians(5.2536/3600);
        double Xw=(1+ds)*(Xc+rz*Yc-ry*Zc)+572.213, Yw=(1+ds)*(-rz*Xc+Yc+rx*Zc)+85.334, Zw=(1+ds)*(ry*Xc-rx*Yc+Zc)+461.940;
        double aw=6378137.0,fw=1.0/298.257223563,e2w=2*fw-fw*fw;
        double lonw=Math.atan2(Yw,Xw),p=Math.sqrt(Xw*Xw+Yw*Yw),latw=Math.atan2(Zw,p*(1-e2w));
        for (int i=0;i<10;i++){double Nw=aw/Math.sqrt(1-e2w*Math.sin(latw)*Math.sin(latw)),nv=Math.atan2(Zw+e2w*Nw*Math.sin(latw),p);if(Math.abs(nv-latw)<1e-12){latw=nv;break;}latw=nv;}
        return new double[]{Math.toDegrees(latw),Math.toDegrees(lonw)};
    }

    static LocalDate parseDateFromFilename(String filename) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{8})").matcher(filename);
            if (m.find()) return LocalDate.parse(m.group(1), DateTimeFormatter.BASIC_ISO_DATE);
        } catch (Exception ignored) {}
        return LocalDate.now();
    }

    private CloseableHttpClient buildHttpClient() {
        RequestConfig rc = RequestConfig.custom()
            .setConnectTimeout(Timeout.ofMilliseconds(config.httpTimeoutMs))
            .setResponseTimeout(Timeout.ofMilliseconds(180_000))
            .build();
        return HttpClients.custom().setDefaultRequestConfig(rc).build();
    }
}
