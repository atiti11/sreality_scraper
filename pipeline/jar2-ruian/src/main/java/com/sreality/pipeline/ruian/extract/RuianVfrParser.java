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
 */
public class RuianVfrParser {

    private static final Logger log = LoggerFactory.getLogger(RuianVfrParser.class);

    public record ParseResult(
        List<KrajRecord>     kraje,
        List<OkresRecord>    okresy,
        List<ObecRecord>     obce,
        List<CastObceRecord> castiObci) {}

    public ParseResult parse(Path xmlFile) throws IOException, XMLStreamException {
        List<KrajRecord>     kraje     = new ArrayList<>();
        List<OkresRecord>    okresy    = new ArrayList<>();
        List<ObecRecord>     obce      = new ArrayList<>();
        List<CastObceRecord> castiObci = new ArrayList<>();

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
                    // Explicitly skip containers whose children contain Obec/Okres/CastObce
                    // FK references — without this they leak into the cases above.
                    case "KatastralniUzemi", "SpravniObvody",
                         "Orp", "Pou", "Momc", "Mop", "Zsj",
                         "RegionySoudrznosti", "Staty", "Hlavicka" -> skipElement(r);
                    // No default: wrapper elements (VymennyFormat, Data, …) are
                    // traversed naturally by the StAX event loop.
                }
            }
            r.close();
        }
        log.info("Parsed: {} vusc(kraj), {} okres, {} obec, {} cast_obce",
            kraje.size(), okresy.size(), obce.size(), castiObci.size());
        return new ParseResult(kraje, okresy, obce, castiObci);
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
        // Reader is ON <Vusc> START_ELEMENT. Iterate children.
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.END_ELEMENT) {
                // Matched </Vusc> — done
                break;
            }
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
                case "Vusc"  -> kodVusc = parseFirstKodChild(r); // reads <Vusc> subtree, returns inner Kod
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
                // Use it as fallback so Praha obec gets a valid kodOkresu for the synthetic Okres.
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
    //   <coi:Obec>
    //     <obi:Kod>500011</obi:Kod>  ← FK
    //   </coi:Obec>
    //   <coi:Geometrie>
    //     <coi:DefinicniBod>
    //       <gml:Point><gml:pos>-123456.00 -456789.00</gml:pos></gml:Point>
    //     </coi:DefinicniBod>
    //   </coi:Geometrie>
    // </vf:CastObce>
    // -------------------------------------------------------------------------
    private CastObceRecord parseCastObce(XMLStreamReader r) throws XMLStreamException {
        String kod = null, nazev = null, kodObce = null;
        double[] sjtsk = null;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.END_ELEMENT) break;
            if (ev != XMLStreamConstants.START_ELEMENT) continue;

            switch (r.getLocalName()) {
                case "Kod"       -> { if (kod   == null) kod   = r.getElementText(); else skipElement(r); }
                case "Nazev"     -> { if (nazev == null) nazev = r.getElementText(); else skipElement(r); }
                case "Obec"      -> kodObce = parseFirstKodChild(r);
                case "Geometrie" -> { if (sjtsk == null) sjtsk = parseDefinicniBod(r); else skipElement(r); }
                default          -> skipElement(r);
            }
        }
        if (kod == null || sjtsk == null) return null;
        double[] wgs = SjtskToWgs84.convert(sjtsk[0], sjtsk[1]);
        String n = (nazev != null && !nazev.isBlank()) ? nazev : "CAST_" + kod;
        return CastObceRecord.fromCentroid(kod, n, kodObce, wgs[0], wgs[1]);
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

    // -------------------------------------------------------------------------
    // Helper: reader is ON <Geometrie> START_ELEMENT.
    // Digs for <DefinicniBod> → <Point> → <pos> and returns S-JTSK coords.
    // Consumes through </Geometrie>.
    // -------------------------------------------------------------------------
    private double[] parseDefinicniBod(XMLStreamReader r) throws XMLStreamException {
        double[] result = null;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.END_ELEMENT && "Geometrie".equals(r.getLocalName())) break;
            if (ev != XMLStreamConstants.START_ELEMENT) continue;

            if ("pos".equals(r.getLocalName())) {
                String raw = r.getElementText().trim();
                String[] parts = raw.split("\\s+");
                if (parts.length >= 2 && result == null) {
                    try {
                        result = new double[]{
                            Double.parseDouble(parts[0]),
                            Double.parseDouble(parts[1])
                        };
                    } catch (NumberFormatException ignored) {}
                }
            } else {
                // Don't skip here — we need to descend into DefinicniBod/Point
                // Just let the loop continue naturally (START_ELEMENT events accumulate)
            }
        }
        return result;
    }
}
