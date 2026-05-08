package com.sreality.pipeline.ruian.extract;

import com.sreality.pipeline.ruian.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.stream.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * StAX streaming parser for the RUIAN VFR 4.x full-state XML export.
 *
 * Key design: each parseXxx() method is called when the reader is positioned
 * ON the opening START_ELEMENT (e.g. <vf:Vusc>). It must consume everything
 * up to and including the matching END_ELEMENT before returning.
 *
 * We use skipElement() to consume any subtree we don't care about. This is
 * correct because XMLStreamReader.next() tracks its own depth internally —
 * we never manually track depth. getElementText() consumes text + END_ELEMENT.
 *
 * For CastObce we additionally extract the boundary polygon from
 * <gml:MultiSurface> / <gml:Polygon>, reproject every vertex from S-JTSK to
 * WGS84, and emit a MULTIPOLYGON WKT string consumable by PostGIS.
 */
public class RuianVfrParser {

    private static final Logger log = LoggerFactory.getLogger(RuianVfrParser.class);

    public record ParseResult(
        List<KrajRecord>        kraje,
        List<OkresRecord>       okresy,
        List<ObecRecord>        obce,
        List<CastObceRecord>    castiObci,
        List<MestskaCastRecord> mestskeCasti) {}

    public ParseResult parse(Path xmlFile) throws IOException, XMLStreamException {
        List<KrajRecord>        kraje         = new ArrayList<>();
        List<OkresRecord>       okresy        = new ArrayList<>();
        List<ObecRecord>        obce          = new ArrayList<>();
        List<CastObceRecord>    castiObci     = new ArrayList<>();
        List<MestskaCastRecord> mestskeCasti  = new ArrayList<>();

        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        // Coalesce character data so getElementText() returns a single string
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);

        try (InputStream is = new BufferedInputStream(Files.newInputStream(xmlFile), 131072)) {
            XMLStreamReader r = factory.createXMLStreamReader(is);
            while (r.hasNext()) {
                if (r.next() != XMLStreamConstants.START_ELEMENT) continue;
                switch (r.getLocalName()) {
                    // Known entity containers: iterate children, parse each record.
                    case "Vusc"      -> parseContainerOf(r, "Vusc",     sub -> { KrajRecord k     = parseVusc(sub);      if (k  != null) kraje.add(k); });
                    case "Okresy"    -> parseContainerOf(r, "Okres",    sub -> { OkresRecord o    = parseOkres(sub);     if (o  != null) okresy.add(o); });
                    case "Obce"      -> parseContainerOf(r, "Obec",     sub -> { ObecRecord ob    = parseObec(sub);      if (ob != null) obce.add(ob); });
                    case "CastiObci" -> parseContainerOf(r, "CastObce", sub -> { CastObceRecord c = parseCastObce(sub);  if (c  != null) castiObci.add(c); });
                    // Mestske obvody Prahy (MOP) and mestske casti / obvody (MOMC).
                    // Element names in RUIAN VFR: container == record == "Mop" / "Momc".
                    case "Mop"       -> parseContainerOf(r, "Mop",      sub -> { MestskaCastRecord m = parseMestskaCast(sub, "MOP");  if (m != null) mestskeCasti.add(m); });
                    case "Momc"      -> parseContainerOf(r, "Momc",     sub -> { MestskaCastRecord m = parseMestskaCast(sub, "MOMC"); if (m != null) mestskeCasti.add(m); });
                    // Explicitly skip containers whose children contain Obec/Okres/CastObce
                    // FK references — without this they leak into the cases above.
                    case "KatastralniUzemi", "SpravniObvody",
                         "Orp", "Pou", "Zsj",
                         "RegionySoudrznosti", "Staty", "Hlavicka" -> skipElement(r);
                    // No default: wrapper elements (VymennyFormat, Data, …) are
                    // traversed naturally by the StAX event loop.
                }
            }
            r.close();
        }
        long withGeom   = castiObci.stream().filter(c -> c.geomWktSjtsk() != null).count();
        long mcWithGeom = mestskeCasti.stream().filter(m -> m.geomWktSjtsk() != null).count();
        long mopCount   = mestskeCasti.stream().filter(m -> "MOP".equals(m.typ())).count();
        long momcCount  = mestskeCasti.size() - mopCount;
        log.info("Parsed: {} vusc(kraj), {} okres, {} obec, {} cast_obce ({} with polygon), {} mestska_cast ({} MOP / {} MOMC, {} with polygon)",
            kraje.size(), okresy.size(), obce.size(), castiObci.size(), withGeom,
            mestskeCasti.size(), mopCount, momcCount, mcWithGeom);
        return new ParseResult(kraje, okresy, obce, castiObci, mestskeCasti);
    }

    @FunctionalInterface
    private interface XmlConsumer {
        void accept(XMLStreamReader r) throws XMLStreamException;
    }

    // Reader is ON the container START_ELEMENT (e.g. <vf:Okresy>).
    // Iterates children; calls handler for each child whose local name matches
    // childLocalName; skips everything else. Consumes through the container END_ELEMENT.
    private void parseContainerOf(XMLStreamReader r, String childLocalName, XmlConsumer handler)
            throws XMLStreamException {
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.END_ELEMENT) break;
            if (ev != XMLStreamConstants.START_ELEMENT) continue;
            if (childLocalName.equals(r.getLocalName())) {
                handler.accept(r);
            } else {
                skipElement(r);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Skip the entire subtree rooted at the current START_ELEMENT.
    // Called when reader is ON a START_ELEMENT we don't want.
    // After return, reader has consumed the matching END_ELEMENT.
    // -------------------------------------------------------------------------
    private void skipElement(XMLStreamReader r) throws XMLStreamException {
        int depth = 1;
        while (r.hasNext() && depth > 0) {
            switch (r.next()) {
                case XMLStreamConstants.START_ELEMENT -> depth++;
                case XMLStreamConstants.END_ELEMENT   -> depth--;
            }
        }
    }

    // -------------------------------------------------------------------------
    // <vf:Vusc>
    //   <vci:Kod>35</vci:Kod>
    //   <vci:Nazev>Jihočeský kraj</vci:Nazev>
    //   ... (geometry etc — skipped)
    // </vf:Vusc>
    // -------------------------------------------------------------------------
    private KrajRecord parseVusc(XMLStreamReader r) throws XMLStreamException {
        String kod = null, nazev = null;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.END_ELEMENT) break;
            if (ev != XMLStreamConstants.START_ELEMENT) continue;

            switch (r.getLocalName()) {
                case "Kod"   -> { if (kod   == null) kod   = r.getElementText(); else skipElement(r); }
                case "Nazev" -> { if (nazev == null) nazev = r.getElementText(); else skipElement(r); }
                default      -> skipElement(r);
            }
        }
        if (kod == null) return null;
        String n = (nazev != null && !nazev.isBlank()) ? nazev : "VUSC_" + kod;
        return new KrajRecord(kod, n);
    }

    // -------------------------------------------------------------------------
    // <vf:Okres>
    //   <oki:Kod>3306</oki:Kod>
    //   <oki:Nazev>Prachatice</oki:Nazev>
    //   <oki:Vusc>
    //     <vci:Kod>35</vci:Kod>   ← FK
    //   </oki:Vusc>
    //   ... geometry
    // </vf:Okres>
    // -------------------------------------------------------------------------
    private OkresRecord parseOkres(XMLStreamReader r) throws XMLStreamException {
        String kod = null, nazev = null, kodVusc = null;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.END_ELEMENT) break;
            if (ev != XMLStreamConstants.START_ELEMENT) continue;

            switch (r.getLocalName()) {
                case "Kod"   -> { if (kod   == null) kod   = r.getElementText(); else skipElement(r); }
                case "Nazev" -> { if (nazev == null) nazev = r.getElementText(); else skipElement(r); }
                case "Vusc"  -> kodVusc = parseFirstKodChild(r);
                default      -> skipElement(r);
            }
        }
        if (kod == null) return null;
        String n = (nazev != null && !nazev.isBlank()) ? nazev : "OKRES_" + kod;
        return new OkresRecord(kod, n, kodVusc);
    }

    // -------------------------------------------------------------------------
    // <vf:Obec>
    //   <obi:Kod>500011</obi:Kod>
    //   <obi:Nazev>Adamov</obi:Nazev>
    //   <obi:Okres>
    //     <oki:Kod>3201</oki:Kod>  ← FK
    //   </obi:Okres>
    //   <obi:ZanikloOd>...</obi:ZanikloOd>  (optional)
    // </vf:Obec>
    // -------------------------------------------------------------------------
    private ObecRecord parseObec(XMLStreamReader r) throws XMLStreamException {
        String kod = null, nazev = null, kodOkresu = null, zaniklo = null;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.END_ELEMENT) break;
            if (ev != XMLStreamConstants.START_ELEMENT) continue;

            switch (r.getLocalName()) {
                case "Kod"      -> { if (kod      == null) kod      = r.getElementText(); else skipElement(r); }
                case "Nazev"    -> { if (nazev    == null) nazev    = r.getElementText(); else skipElement(r); }
                case "Okres"    -> kodOkresu = parseFirstKodChild(r);
                // Praha has no <Okres> — it references <Pou> whose kod equals Praha's VUSC kod.
                case "Pou"      -> { if (kodOkresu == null) kodOkresu = parseFirstKodChild(r); else skipElement(r); }
                case "ZanikloOd"-> { zaniklo = r.getElementText(); }
                default         -> skipElement(r);
            }
        }
        if (kod == null) return null;
        String n = (nazev != null && !nazev.isBlank()) ? nazev : "OBEC_" + kod;
        boolean active = (zaniklo == null || zaniklo.isBlank());
        return new ObecRecord(kod, n, kodOkresu, active);
    }

    // -------------------------------------------------------------------------
    // <vf:CastObce>
    //   <coi:Kod>400001</coi:Kod>
    //   <coi:Nazev>Adamov</coi:Nazev>
    //   <coi:Obec><obi:Kod>500011</obi:Kod></coi:Obec>
    //   <coi:Geometrie>
    //     <coi:DefinicniBod>
    //       <gml:Point><gml:pos>X Y</gml:pos></gml:Point>
    //     </coi:DefinicniBod>
    //     <coi:OriginalniHranice|GeneralizovaneHranice_Hranice>
    //       <gml:MultiSurface>
    //         <gml:surfaceMember>
    //           <gml:Polygon>
    //             <gml:exterior><gml:LinearRing><gml:posList>X1 Y1 X2 Y2 …</gml:posList></gml:LinearRing></gml:exterior>
    //             [<gml:interior>…</gml:interior>]*
    //           </gml:Polygon>
    //         </gml:surfaceMember>*
    //       </gml:MultiSurface>
    //     </…>
    //   </coi:Geometrie>
    // </vf:CastObce>
    // -------------------------------------------------------------------------
    private CastObceRecord parseCastObce(XMLStreamReader r) throws XMLStreamException {
        String kod = null, nazev = null, kodObce = null;
        double[] sjtskCentroid = null;
        // multipolygon -> polygon -> ring -> [easting, northing]_sjtsk (raw)
        List<List<List<double[]>>> sjtskPolygons = new ArrayList<>();

        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.END_ELEMENT) break;
            if (ev != XMLStreamConstants.START_ELEMENT) continue;

            switch (r.getLocalName()) {
                case "Kod"       -> { if (kod   == null) kod   = r.getElementText(); else skipElement(r); }
                case "Nazev"     -> { if (nazev == null) nazev = r.getElementText(); else skipElement(r); }
                case "Obec"      -> kodObce = parseFirstKodChild(r);
                case "Geometrie" -> {
                    GeometrieResult g = parseGeometrie(r);
                    if (g.centroid != null && sjtskCentroid == null) sjtskCentroid = g.centroid;
                    if (!g.polygons.isEmpty()) sjtskPolygons.addAll(g.polygons);
                }
                default          -> skipElement(r);
            }
        }
        if (kod == null) return null;
        String n = (nazev != null && !nazev.isBlank()) ? nazev : "CAST_" + kod;

        if (sjtskCentroid == null && sjtskPolygons.isEmpty()) return null;

        // S-JTSK values are passed through to Postgres as-is; the loader does
        // ST_Transform(... 5514, 4326) to derive WGS84 lat/lon and the geom column.
        String wkt = sjtskPolygons.isEmpty() ? null : buildMultiPolygonWktSjtsk(sjtskPolygons);

        // Centroid: prefer DefinicniBod, otherwise fall back to first polygon vertex
        double easting, northing;
        if (sjtskCentroid != null) {
            easting = sjtskCentroid[0];
            northing = sjtskCentroid[1];
        } else {
            double[] first = sjtskPolygons.get(0).get(0).get(0);
            easting = first[0];
            northing = first[1];
        }

        return wkt == null
            ? CastObceRecord.fromCentroid(kod, n, kodObce, easting, northing)
            : CastObceRecord.fromPolygon(kod, n, kodObce, easting, northing, wkt);
    }

    // -------------------------------------------------------------------------
    // <vf:Mop> or <vf:Momc> (record): Kod, Nazev, Obec FK, Geometrie
    // (other fields like StatutMesto, MluvnickeCharakteristiky etc are ignored)
    // -------------------------------------------------------------------------
    private MestskaCastRecord parseMestskaCast(XMLStreamReader r, String typ)
            throws XMLStreamException {
        String kod = null, nazev = null, kodObce = null;
        double[] sjtskCentroid = null;
        List<List<List<double[]>>> sjtskPolygons = new ArrayList<>();

        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.END_ELEMENT) break;
            if (ev != XMLStreamConstants.START_ELEMENT) continue;
            switch (r.getLocalName()) {
                case "Kod"       -> { if (kod   == null) kod   = r.getElementText(); else skipElement(r); }
                case "Nazev"     -> { if (nazev == null) nazev = r.getElementText(); else skipElement(r); }
                case "Obec"      -> kodObce = parseFirstKodChild(r);
                case "Geometrie" -> {
                    GeometrieResult g = parseGeometrie(r);
                    if (g.centroid != null && sjtskCentroid == null) sjtskCentroid = g.centroid;
                    if (!g.polygons.isEmpty()) sjtskPolygons.addAll(g.polygons);
                }
                default          -> skipElement(r);
            }
        }
        if (kod == null) return null;
        String n = (nazev != null && !nazev.isBlank()) ? nazev : (typ + "_" + kod);

        if (sjtskCentroid == null && sjtskPolygons.isEmpty()) return null;

        String wkt = sjtskPolygons.isEmpty() ? null : buildMultiPolygonWktSjtsk(sjtskPolygons);

        double easting, northing;
        if (sjtskCentroid != null) {
            easting = sjtskCentroid[0];
            northing = sjtskCentroid[1];
        } else {
            double[] first = sjtskPolygons.get(0).get(0).get(0);
            easting = first[0];
            northing = first[1];
        }

        return wkt == null
            ? MestskaCastRecord.fromCentroid(kod, n, kodObce, typ, easting, northing)
            : MestskaCastRecord.fromPolygon (kod, n, kodObce, typ, easting, northing, wkt);
    }

    // -------------------------------------------------------------------------
    // Helper: reader is ON a container START_ELEMENT (e.g. <Vusc>, <Okres>).
    // Scans children for the FIRST <Kod> and returns its text.
    // Consumes through the matching END_ELEMENT of the container.
    // -------------------------------------------------------------------------
    private String parseFirstKodChild(XMLStreamReader r) throws XMLStreamException {
        String kod = null;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.END_ELEMENT) break;
            if (ev != XMLStreamConstants.START_ELEMENT) continue;

            if ("Kod".equals(r.getLocalName()) && kod == null) {
                kod = r.getElementText();
            } else {
                skipElement(r);
            }
        }
        return kod;
    }

    // =========================================================================
    // Geometry parsing
    // =========================================================================

    private record GeometrieResult(
            double[] centroid,
            List<List<List<double[]>>> polygons) {}

    /**
     * Reader is ON {@code <Geometrie>}. Captures:
     *   - the first {@code <DefinicniBod>/<gml:Point>/<gml:pos>} as centroid
     *   - any {@code <gml:MultiSurface>} or {@code <gml:Polygon>} encountered,
     *     recursively descending through any wrapper elements
     *     (OriginalniHranice / GeneralizovaneHranice_Hranice / …).
     * Consumes through {@code </Geometrie>}.
     */
    private GeometrieResult parseGeometrie(XMLStreamReader r) throws XMLStreamException {
        double[] centroid = null;
        List<List<List<double[]>>> polygons = new ArrayList<>();
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.END_ELEMENT) break;
            if (ev != XMLStreamConstants.START_ELEMENT) continue;
            switch (r.getLocalName()) {
                case "DefinicniBod" -> {
                    if (centroid == null) centroid = parseGeomPoint(r);
                    else skipElement(r);
                }
                default -> {
                    // Any other child of <Geometrie> may contain the boundary.
                    // Recursively look for MultiSurface / Polygon inside.
                    polygons.addAll(parseAnyPolygonContainer(r));
                }
            }
        }
        return new GeometrieResult(centroid, polygons);
    }

    /**
     * Reader is ON some container element; recursively descends until it hits
     * {@code <gml:MultiSurface>} / {@code <gml:MultiPolygon>} / {@code <gml:Polygon>}
     * and parses them. Anything not on the path to a polygon is skipped.
     * Consumes through the container's END_ELEMENT.
     */
    private List<List<List<double[]>>> parseAnyPolygonContainer(XMLStreamReader r)
            throws XMLStreamException {
        List<List<List<double[]>>> out = new ArrayList<>();
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.END_ELEMENT) break;
            if (ev != XMLStreamConstants.START_ELEMENT) continue;
            switch (r.getLocalName()) {
                case "MultiSurface", "MultiPolygon" -> out.addAll(parseMultiSurface(r));
                case "Polygon" -> {
                    List<List<double[]>> poly = parsePolygon(r);
                    if (poly != null) out.add(poly);
                }
                default -> out.addAll(parseAnyPolygonContainer(r));
            }
        }
        return out;
    }

    /** Reader is ON {@code <DefinicniBod>}. Returns S-JTSK [x, y] of inner Point. */
    private double[] parseGeomPoint(XMLStreamReader r) throws XMLStreamException {
        double[] pos = null;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.END_ELEMENT) break;
            if (ev != XMLStreamConstants.START_ELEMENT) continue;
            if ("pos".equals(r.getLocalName()) && pos == null) {
                pos = parsePosPair(r.getElementText());
            } else if (pos == null) {
                // Descend into <gml:Point> looking for <gml:pos>
                double[] inner = parseGeomPoint(r);
                if (inner != null) pos = inner;
            } else {
                skipElement(r);
            }
        }
        return pos;
    }

    /** Reader is ON {@code <gml:MultiSurface>} or similar. */
    private List<List<List<double[]>>> parseMultiSurface(XMLStreamReader r)
            throws XMLStreamException {
        List<List<List<double[]>>> out = new ArrayList<>();
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.END_ELEMENT) break;
            if (ev != XMLStreamConstants.START_ELEMENT) continue;
            switch (r.getLocalName()) {
                case "surfaceMember", "polygonMember" -> {
                    List<List<double[]>> poly = parseSurfaceMember(r);
                    if (poly != null) out.add(poly);
                }
                case "Polygon" -> {
                    List<List<double[]>> poly = parsePolygon(r);
                    if (poly != null) out.add(poly);
                }
                default -> skipElement(r);
            }
        }
        return out;
    }

    /** Reader is ON {@code <gml:surfaceMember>}/{@code <gml:polygonMember>}. */
    private List<List<double[]>> parseSurfaceMember(XMLStreamReader r) throws XMLStreamException {
        List<List<double[]>> polygon = null;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.END_ELEMENT) break;
            if (ev != XMLStreamConstants.START_ELEMENT) continue;
            if ("Polygon".equals(r.getLocalName()) && polygon == null) {
                polygon = parsePolygon(r);
            } else {
                skipElement(r);
            }
        }
        return polygon;
    }

    /** Reader is ON {@code <gml:Polygon>}. Returns list of rings: [exterior, hole, hole, …]. */
    private List<List<double[]>> parsePolygon(XMLStreamReader r) throws XMLStreamException {
        List<List<double[]>> rings = new ArrayList<>();
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.END_ELEMENT) break;
            if (ev != XMLStreamConstants.START_ELEMENT) continue;
            switch (r.getLocalName()) {
                case "exterior", "interior" -> {
                    List<double[]> ring = parseRingContainer(r);
                    if (ring != null && ring.size() >= 4) rings.add(ring);
                }
                default -> skipElement(r);
            }
        }
        return rings.isEmpty() ? null : rings;
    }

    /** Reader is ON {@code <gml:exterior>} or {@code <gml:interior>}. */
    private List<double[]> parseRingContainer(XMLStreamReader r) throws XMLStreamException {
        List<double[]> ring = null;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.END_ELEMENT) break;
            if (ev != XMLStreamConstants.START_ELEMENT) continue;
            if ("LinearRing".equals(r.getLocalName())) {
                ring = parseLinearRing(r);
            } else {
                skipElement(r);
            }
        }
        return ring;
    }

    /** Reader is ON {@code <gml:LinearRing>}. */
    private List<double[]> parseLinearRing(XMLStreamReader r) throws XMLStreamException {
        List<double[]> points = new ArrayList<>();
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.END_ELEMENT) break;
            if (ev != XMLStreamConstants.START_ELEMENT) continue;
            switch (r.getLocalName()) {
                case "posList" -> points.addAll(parsePosList(r.getElementText()));
                case "pos"     -> {
                    double[] p = parsePosPair(r.getElementText());
                    if (p != null) points.add(p);
                }
                default -> skipElement(r);
            }
        }
        return points;
    }

    private double[] parsePosPair(String text) {
        String[] parts = text.trim().split("\\s+");
        if (parts.length < 2) return null;
        try {
            return new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private List<double[]> parsePosList(String text) {
        String[] parts = text.trim().split("\\s+");
        List<double[]> points = new ArrayList<>(parts.length / 2);
        for (int i = 0; i + 1 < parts.length; i += 2) {
            try {
                points.add(new double[]{
                    Double.parseDouble(parts[i]),
                    Double.parseDouble(parts[i + 1])
                });
            } catch (NumberFormatException ignored) {}
        }
        return points;
    }

    // =========================================================================
    // S-JTSK polygons → raw S-JTSK MULTIPOLYGON WKT (no reprojection here;
    // Postgres does ST_Transform(... 5514, 4326) at insert time)
    // =========================================================================

    /**
     * Builds a MULTIPOLYGON WKT string in S-JTSK (EPSG:5514). Coordinates are
     * written as {@code easting northing} pairs straight from {@code <gml:pos>}
     * / {@code <gml:posList>}, no axis swap, no reprojection.
     *
     * Returns null if no valid polygon ring (size ≥ 4) is available.
     */
    private static String buildMultiPolygonWktSjtsk(
            List<List<List<double[]>>> sjtskPolygons) {

        StringBuilder sb = new StringBuilder("MULTIPOLYGON(");
        boolean firstPoly = true;
        for (List<List<double[]>> polygon : sjtskPolygons) {
            if (polygon == null || polygon.isEmpty()) continue;
            boolean polyOk = false;
            for (List<double[]> ring : polygon) {
                if (ring != null && ring.size() >= 4) { polyOk = true; break; }
            }
            if (!polyOk) continue;

            if (!firstPoly) sb.append(",");
            firstPoly = false;
            sb.append("(");
            boolean firstRing = true;
            for (List<double[]> ring : polygon) {
                if (ring == null || ring.size() < 4) continue;
                if (!firstRing) sb.append(",");
                firstRing = false;
                sb.append("(");
                boolean firstPoint = true;
                for (double[] xy : ring) {
                    if (!firstPoint) sb.append(",");
                    firstPoint = false;
                    // EPSG:5514 axis order = (easting, northing); WKT mirrors it
                    sb.append(xy[0]).append(' ').append(xy[1]);
                }
                sb.append(")");
            }
            sb.append(")");
        }
        sb.append(")");
        return firstPoly ? null : sb.toString();
    }
}
