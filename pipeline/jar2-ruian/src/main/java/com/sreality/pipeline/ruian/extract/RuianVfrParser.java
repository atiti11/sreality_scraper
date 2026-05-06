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
 * VFR element name mapping (doc section 3.3):
 * Kraj in our schema = [Vusc] (Vyšší územně samosprávný celek)
 * Okres = [Okres] (unchanged)
 * Obec = [Obec] (unchanged)
 * CastObce = [CastObce] (unchanged)
 *
 * FK reference field names:
 * Okres → Vusc: element <VuscKod> (NOT <KrajKod>)
 * Obec → Okres: element <OkresKod> (unchanged)
 * CastObce → Obec: element <ObecKod> (unchanged)
 *
 * Coordinates in CastObce: S-JTSK <pos> element → converted to WGS84.
 */
public class RuianVfrParser {

    private static final Logger log = LoggerFactory.getLogger(RuianVfrParser.class);

    public record ParseResult(
            List<KrajRecord> kraje,
            List<OkresRecord> okresy,
            List<ObecRecord> obce,
            List<CastObceRecord> castiObci) {
    }

    public ParseResult parse(Path xmlFile) throws IOException, XMLStreamException {
        List<KrajRecord> kraje = new ArrayList<>();
        List<OkresRecord> okresy = new ArrayList<>();
        List<ObecRecord> obce = new ArrayList<>();
        List<CastObceRecord> castiObci = new ArrayList<>();

        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);

        try (InputStream is = new BufferedInputStream(Files.newInputStream(xmlFile), 65536)) {
            XMLStreamReader r = factory.createXMLStreamReader(is);
            while (r.hasNext()) {
                if (r.next() != XMLStreamConstants.START_ELEMENT)
                    continue;
                switch (r.getLocalName()) {
                    // "Vusc" is what VFR calls Vyšší územně samosprávný celek — maps to our
                    // dim_kraj
                    case "Vusc" -> {
                        KrajRecord k = parseVusc(r);
                        if (k != null)
                            kraje.add(k);
                    }
                    case "Okres" -> {
                        OkresRecord o = parseOkres(r);
                        if (o != null)
                            okresy.add(o);
                    }
                    case "Obec" -> {
                        ObecRecord ob = parseObec(r);
                        if (ob != null)
                            obce.add(ob);
                    }
                    case "CastObce" -> {
                        CastObceRecord c = parseCastObce(r);
                        if (c != null)
                            castiObci.add(c);
                    }
                }
            }
            r.close();
        }
        log.info("Parsed: {} vusc(kraj), {} okres, {} obec, {} cast_obce",
                kraje.size(), okresy.size(), obce.size(), castiObci.size());
        return new ParseResult(kraje, okresy, obce, castiObci);
    }

    // -------------------------------------------------------------------------
    // Vusc → stored as dim_kraj
    // -------------------------------------------------------------------------

    private KrajRecord parseVusc(XMLStreamReader r) throws XMLStreamException {
        String kod = null, nazev = null;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                switch (r.getLocalName()) {
                    case "Kod" -> kod = r.getElementText();
                    case "Nazev" -> nazev = r.getElementText();
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT && "Vusc".equals(r.getLocalName()))
                break;
        }
        if (kod == null)
            return null;
        return new KrajRecord(kod, nazev);
    }

    // -------------------------------------------------------------------------
    // Okres — references Vusc via <VuscKod>
    // -------------------------------------------------------------------------

    private OkresRecord parseOkres(XMLStreamReader r) throws XMLStreamException {
        String kod = null, nazev = null, kodKraje = null;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                switch (r.getLocalName()) {
                    case "Kod" -> kod = r.getElementText();
                    case "Nazev" -> nazev = r.getElementText();
                    // VFR uses VuscKod to reference the parent Vusc (our dim_kraj)
                    case "VuscKod" -> kodKraje = r.getElementText();
                    // Older VFR versions used KrajKod — keep as fallback
                    case "KrajKod" -> {
                        if (kodKraje == null)
                            kodKraje = r.getElementText();
                    }
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT && "Okres".equals(r.getLocalName()))
                break;
        }
        if (kod == null)
            return null;
        return new OkresRecord(kod, nazev, kodKraje);
    }

    // -------------------------------------------------------------------------
    // Obec — references Okres via <OkresKod>
    // -------------------------------------------------------------------------

    private ObecRecord parseObec(XMLStreamReader r) throws XMLStreamException {
        String kod = null, nazev = null, kodOkresu = null, zaniklo = null;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                switch (r.getLocalName()) {
                    case "Kod" -> kod = r.getElementText();
                    case "Nazev" -> nazev = r.getElementText();
                    case "OkresKod" -> kodOkresu = r.getElementText();
                    case "ZanikloOd" -> zaniklo = r.getElementText();
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT && "Obec".equals(r.getLocalName()))
                break;
        }
        if (kod == null)
            return null;
        return new ObecRecord(kod, nazev, kodOkresu, zaniklo == null || zaniklo.isBlank());
    }

    // -------------------------------------------------------------------------
    // CastObce — references Obec via <ObecKod>, coordinates via <pos> in S-JTSK
    // -------------------------------------------------------------------------

    private CastObceRecord parseCastObce(XMLStreamReader r) throws XMLStreamException {
        String kod = null, nazev = null, kodObce = null;
        double[] sjtsk = null;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                switch (r.getLocalName()) {
                    case "Kod" -> kod = r.getElementText();
                    case "Nazev" -> nazev = r.getElementText();
                    case "ObecKod" -> kodObce = r.getElementText();
                    case "pos" -> {
                        if (sjtsk == null) {
                            String raw = r.getElementText().trim();
                            String[] parts = raw.split("\\s+");
                            if (parts.length >= 2) {
                                try {
                                    sjtsk = new double[] {
                                            Double.parseDouble(parts[0]),
                                            Double.parseDouble(parts[1])
                                    };
                                } catch (NumberFormatException ignored) {
                                }
                            }
                        }
                    }
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT && "CastObce".equals(r.getLocalName()))
                break;
        }
        if (kod == null || sjtsk == null)
            return null;
        double[] wgs = SjtskToWgs84.convert(sjtsk[0], sjtsk[1]);
        return CastObceRecord.fromCentroid(kod, nazev, kodObce, wgs[0], wgs[1]);
    }
}
