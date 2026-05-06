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
 * XML structure (from inspecting 20260228_ST_UKSG.xml directly):
 *
 *   <vf:Vusc>
 *     <vci:Kod>35</vci:Kod>
 *     <vci:Nazev>Jihočeský kraj</vci:Nazev>
 *     ... (geometry, other elements)
 *   </vf:Vusc>
 *
 *   <vf:Okres gml:id="OK.3306">
 *     <oki:Kod>3306</oki:Kod>
 *     <oki:Nazev>Prachatice</oki:Nazev>
 *     <oki:Vusc>
 *       <vci:Kod>35</vci:Kod>    ← FK to Vusc (our dim_kraj)
 *     </oki:Vusc>
 *     ... (geometry)
 *   </vf:Okres>
 *
 *   <vf:Obec>
 *     <obi:Kod>500011</obi:Kod>
 *     <obi:Nazev>Adamov</obi:Nazev>
 *     <obi:Okres>
 *       <oki:Kod>3201</oki:Kod>  ← FK to Okres
 *     </obi:Okres>
 *   </vf:Obec>
 *
 *   <vf:CastObce>
 *     <coi:Kod>400001</coi:Kod>
 *     <coi:Nazev>Adamov</coi:Nazev>
 *     <coi:Obec>
 *       <obi:Kod>500011</obi:Kod> ← FK to Obec
 *     </coi:Obec>
 *     <coi:Geometrie>
 *       <coi:DefinicniBod>
 *         <gml:Point>
 *           <gml:pos>-123456.00 -456789.00</gml:pos>
 *         </gml:Point>
 *       </coi:DefinicniBod>
 *     </coi:Geometrie>
 *   </vf:CastObce>
 *
 * IMPORTANT: getElementText() consumes both the text and the END_ELEMENT.
 * Do NOT call depth-- after getElementText(). Only decrement on raw END_ELEMENT events.
 *
 * We use nested-element context tracking ("Vusc", "Okres", "Obec") to distinguish
 * FK Kod elements from the entity's own Kod. We exit each parser when we see
 * the matching end tag at depth 0.
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
                    case "Vusc"     -> { KrajRecord k    = parseVusc(r);      if (k  != null) kraje.add(k); }
                    case "Okres"    -> { OkresRecord o   = parseOkres(r);     if (o  != null) okresy.add(o); }
                    case "Obec"     -> { ObecRecord ob   = parseObec(r);      if (ob != null) obce.add(ob); }
                    case "CastObce" -> { CastObceRecord c = parseCastObce(r); if (c  != null) castiObci.add(c); }
                }
            }
            r.close();
        }
        log.info("Parsed: {} vusc(kraj), {} okres, {} obec, {} cast_obce",
            kraje.size(), okresy.size(), obce.size(), castiObci.size());
        return new ParseResult(kraje, okresy, obce, castiObci);
    }

    // =========================================================================
    // Helpers — read until the matching end tag, tracking nested depth
    // getElementText() consumes text + END_ELEMENT, so no depth change needed after it.
    // Only raw END_ELEMENT events (that we didn't consume via getElementText) count.
    // =========================================================================

    private KrajRecord parseVusc(XMLStreamReader r) throws XMLStreamException {
        String kod = null, nazev = null;
        int depth = 1; // already inside <Vusc>
        while (r.hasNext() && depth > 0) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                String local = r.getLocalName();
                if ("Kod".equals(local) && kod == null) {
                    kod = r.getElementText();   // consumes END_ELEMENT — no depth change
                } else if ("Nazev".equals(local) && nazev == null) {
                    nazev = r.getElementText();
                } else {
                    depth++; // opening a nested element we won't consume with getElementText
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
        if (kod == null) return null;
        return new KrajRecord(kod, nazev);
    }

    private OkresRecord parseOkres(XMLStreamReader r) throws XMLStreamException {
        String kod = null, nazev = null, kodVusc = null;
        String context = null; // tracks which container we're in: "Vusc" or null
        int depth = 1;
        while (r.hasNext() && depth > 0) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                String local = r.getLocalName();
                if ("Vusc".equals(local) && context == null) {
                    context = "Vusc";
                    depth++;
                } else if ("Kod".equals(local)) {
                    String val = r.getElementText(); // consumes END_ELEMENT
                    if ("Vusc".equals(context))      kodVusc = val;
                    else if (kod == null)             kod = val;
                } else if ("Nazev".equals(local) && context == null && nazev == null) {
                    nazev = r.getElementText();
                } else {
                    depth++;
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                depth--;
                if ("Vusc".equals(r.getLocalName())) context = null;
            }
        }
        if (kod == null) return null;
        return new OkresRecord(kod, nazev, kodVusc);
    }

    private ObecRecord parseObec(XMLStreamReader r) throws XMLStreamException {
        String kod = null, nazev = null, kodOkresu = null, zaniklo = null;
        String context = null;
        int depth = 1;
        while (r.hasNext() && depth > 0) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                String local = r.getLocalName();
                if ("Okres".equals(local) && context == null) {
                    context = "Okres";
                    depth++;
                } else if ("Kod".equals(local)) {
                    String val = r.getElementText();
                    if ("Okres".equals(context))  kodOkresu = val;
                    else if (kod == null)          kod = val;
                } else if ("Nazev".equals(local) && context == null && nazev == null) {
                    nazev = r.getElementText();
                } else if ("ZanikloOd".equals(local)) {
                    zaniklo = r.getElementText();
                } else {
                    depth++;
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                depth--;
                if ("Okres".equals(r.getLocalName())) context = null;
            }
        }
        if (kod == null) return null;
        return new ObecRecord(kod, nazev, kodOkresu, zaniklo == null || zaniklo.isBlank());
    }

    private CastObceRecord parseCastObce(XMLStreamReader r) throws XMLStreamException {
        String kod = null, nazev = null, kodObce = null;
        double[] sjtsk = null;
        String context = null;
        boolean insideDefinicniBod = false;
        int depth = 1;
        while (r.hasNext() && depth > 0) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                String local = r.getLocalName();
                if ("Obec".equals(local) && context == null) {
                    context = "Obec";
                    depth++;
                } else if ("DefinicniBod".equals(local)) {
                    insideDefinicniBod = true;
                    depth++;
                } else if ("Kod".equals(local)) {
                    String val = r.getElementText();
                    if ("Obec".equals(context)) kodObce = val;
                    else if (kod == null)        kod = val;
                } else if ("Nazev".equals(local) && context == null && nazev == null) {
                    nazev = r.getElementText();
                } else if ("pos".equals(local)) {
                    String raw = r.getElementText();
                    if (insideDefinicniBod && sjtsk == null) {
                        String[] parts = raw.trim().split("\\s+");
                        if (parts.length >= 2) {
                            try {
                                sjtsk = new double[]{
                                    Double.parseDouble(parts[0]),
                                    Double.parseDouble(parts[1])
                                };
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                } else {
                    depth++;
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                depth--;
                String local = r.getLocalName();
                if ("Obec".equals(local))         context = null;
                if ("DefinicniBod".equals(local)) insideDefinicniBod = false;
            }
        }
        if (kod == null || sjtsk == null) return null;
        double[] wgs = SjtskToWgs84.convert(sjtsk[0], sjtsk[1]);
        return CastObceRecord.fromCentroid(kod, nazev, kodObce, wgs[0], wgs[1]);
    }
}
