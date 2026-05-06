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
 * The XML uses namespaced, nested elements for FK references. Example:
 *
 *   <vf:Okres gml:id="OK.3306">
 *     <oki:Kod>3306</oki:Kod>
 *     <oki:Nazev>Prachatice</oki:Nazev>
 *     <oki:Vusc>
 *       <vci:Kod>35</vci:Kod>     ← Vusc FK nested one level deep
 *     </oki:Vusc>
 *     ...
 *   </vf:Okres>
 *
 * Strategy: when parsing an entity, track which "container" element we are
 * currently inside (e.g. "Vusc", "Okres", "Obec"), and when we see a <Kod>
 * element, assign it to the right field based on that context.
 *
 * All comparisons use getLocalName() (no namespace prefix).
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

        try (InputStream is = new BufferedInputStream(Files.newInputStream(xmlFile), 65536)) {
            XMLStreamReader r = factory.createXMLStreamReader(is);
            while (r.hasNext()) {
                if (r.next() != XMLStreamConstants.START_ELEMENT) continue;
                switch (r.getLocalName()) {
                    case "Vusc"     -> { KrajRecord k     = parseVusc(r);     if (k  != null) kraje.add(k); }
                    case "Okres"    -> { OkresRecord o     = parseOkres(r);    if (o  != null) okresy.add(o); }
                    case "Obec"     -> { ObecRecord ob     = parseObec(r);     if (ob != null) obce.add(ob); }
                    case "CastObce" -> { CastObceRecord c  = parseCastObce(r); if (c  != null) castiObci.add(c); }
                }
            }
            r.close();
        }
        log.info("Parsed: {} vusc(kraj), {} okres, {} obec, {} cast_obce",
            kraje.size(), okresy.size(), obce.size(), castiObci.size());
        return new ParseResult(kraje, okresy, obce, castiObci);
    }

    // =========================================================================
    // Vusc (= our dim_kraj)
    // =========================================================================

    private KrajRecord parseVusc(XMLStreamReader r) throws XMLStreamException {
        String kod = null, nazev = null;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT && "Kod".equals(r.getLocalName()) && kod == null)
                kod = r.getElementText();
            else if (ev == XMLStreamConstants.START_ELEMENT && "Nazev".equals(r.getLocalName()) && nazev == null)
                nazev = r.getElementText();
            else if (ev == XMLStreamConstants.END_ELEMENT && "Vusc".equals(r.getLocalName())) break;
        }
        if (kod == null) return null;
        return new KrajRecord(kod, nazev);
    }

    // =========================================================================
    // Okres — FK to Vusc nested inside <oki:Vusc><vci:Kod>...</vci:Kod></oki:Vusc>
    // =========================================================================

    private OkresRecord parseOkres(XMLStreamReader r) throws XMLStreamException {
        String kod = null, nazev = null, kodVusc = null;
        boolean insideVusc = false;

        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                switch (r.getLocalName()) {
                    case "Vusc"  -> insideVusc = true;
                    case "Kod"   -> {
                        String val = r.getElementText();
                        if (insideVusc) kodVusc = val;      // FK to Vusc
                        else if (kod == null) kod = val;    // own Kod
                    }
                    case "Nazev" -> { if (nazev == null) nazev = r.getElementText(); }
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                switch (r.getLocalName()) {
                    case "Vusc"  -> insideVusc = false;
                    case "Okres" -> { break; }
                }
                if ("Okres".equals(r.getLocalName())) break;
            }
        }
        if (kod == null) return null;
        return new OkresRecord(kod, nazev, kodVusc);
    }

    // =========================================================================
    // Obec — FK to Okres nested inside <obi:Okres><oki:Kod>...</oki:Kod></obi:Okres>
    // =========================================================================

    private ObecRecord parseObec(XMLStreamReader r) throws XMLStreamException {
        String kod = null, nazev = null, kodOkresu = null, zaniklo = null;
        boolean insideOkres = false;

        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                switch (r.getLocalName()) {
                    case "Okres"    -> insideOkres = true;
                    case "Kod"      -> {
                        String val = r.getElementText();
                        if (insideOkres) kodOkresu = val;
                        else if (kod == null) kod = val;
                    }
                    case "Nazev"    -> { if (nazev == null) nazev = r.getElementText(); }
                    case "ZanikloOd"-> zaniklo = r.getElementText();
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                if ("Okres".equals(r.getLocalName())) insideOkres = false;
                if ("Obec".equals(r.getLocalName())) break;
            }
        }
        if (kod == null) return null;
        return new ObecRecord(kod, nazev, kodOkresu, zaniklo == null || zaniklo.isBlank());
    }

    // =========================================================================
    // CastObce — FK to Obec nested inside <coi:Obec><obi:Kod>...</obi:Kod></coi:Obec>
    // Coordinates in DefinicniBod/Point/pos in S-JTSK
    // =========================================================================

    private CastObceRecord parseCastObce(XMLStreamReader r) throws XMLStreamException {
        String kod = null, nazev = null, kodObce = null;
        double[] sjtsk = null;
        boolean insideObec = false;
        boolean insideDefinicniBod = false;

        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                switch (r.getLocalName()) {
                    case "Obec"         -> insideObec = true;
                    case "DefinicniBod" -> insideDefinicniBod = true;
                    case "Kod"          -> {
                        String val = r.getElementText();
                        if (insideObec) kodObce = val;
                        else if (kod == null) kod = val;
                    }
                    case "Nazev"        -> { if (nazev == null) nazev = r.getElementText(); }
                    case "pos"          -> {
                        // Only take the DefinicniBod point, not polygon coordinates
                        if (insideDefinicniBod && sjtsk == null) {
                            String raw = r.getElementText().trim();
                            String[] parts = raw.split("\\s+");
                            if (parts.length >= 2) {
                                try {
                                    sjtsk = new double[]{
                                        Double.parseDouble(parts[0]),
                                        Double.parseDouble(parts[1])
                                    };
                                } catch (NumberFormatException ignored) {}
                            }
                        } else {
                            // consume other pos elements to keep parser state clean
                            r.getElementText();
                        }
                    }
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                switch (r.getLocalName()) {
                    case "Obec"         -> insideObec = false;
                    case "DefinicniBod" -> insideDefinicniBod = false;
                    case "CastObce"     -> { break; }
                }
                if ("CastObce".equals(r.getLocalName())) break;
            }
        }
        if (kod == null || sjtsk == null) return null;
        double[] wgs = SjtskToWgs84.convert(sjtsk[0], sjtsk[1]);
        return CastObceRecord.fromCentroid(kod, nazev, kodObce, wgs[0], wgs[1]);
    }
}
