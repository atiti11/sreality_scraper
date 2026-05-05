package com.sreality.pipeline.ruian.extract;

import com.sreality.pipeline.ruian.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.stream.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * StAX streaming parser for the RUIAN VFR full-state XML export.
 * Handles files of several hundred MB without loading into memory.
 *
 * Extracts: Kraj, Okres, Obec, CastObce.
 * CastObce coordinates are in S-JTSK → converted to WGS84 via SjtskToWgs84.
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
                    case "Kraj"     -> kraje.add(parseKraj(r));
                    case "Okres"    -> okresy.add(parseOkres(r));
                    case "Obec"     -> { ObecRecord o = parseObec(r); if (o != null) obce.add(o); }
                    case "CastObce" -> { CastObceRecord c = parseCastObce(r); if (c != null) castiObci.add(c); }
                }
            }
            r.close();
        }
        log.info("Parsed: {} kraj, {} okres, {} obec, {} cast_obce",
            kraje.size(), okresy.size(), obce.size(), castiObci.size());
        return new ParseResult(kraje, okresy, obce, castiObci);
    }

    // -------------------------------------------------------------------------

    private KrajRecord parseKraj(XMLStreamReader r) throws XMLStreamException {
        String kod = null, nazev = null;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                if ("Kod".equals(r.getLocalName()))   kod   = r.getElementText();
                if ("Nazev".equals(r.getLocalName())) nazev = r.getElementText();
            } else if (ev == XMLStreamConstants.END_ELEMENT && "Kraj".equals(r.getLocalName())) break;
        }
        return new KrajRecord(kod, nazev);
    }

    private OkresRecord parseOkres(XMLStreamReader r) throws XMLStreamException {
        String kod = null, nazev = null, kodKraje = null;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                switch (r.getLocalName()) {
                    case "Kod"     -> kod      = r.getElementText();
                    case "Nazev"   -> nazev    = r.getElementText();
                    case "KrajKod" -> kodKraje = r.getElementText();
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT && "Okres".equals(r.getLocalName())) break;
        }
        return new OkresRecord(kod, nazev, kodKraje);
    }

    private ObecRecord parseObec(XMLStreamReader r) throws XMLStreamException {
        String kod = null, nazev = null, kodOkresu = null, zaniklo = null;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                switch (r.getLocalName()) {
                    case "Kod"       -> kod       = r.getElementText();
                    case "Nazev"     -> nazev     = r.getElementText();
                    case "OkresKod"  -> kodOkresu = r.getElementText();
                    case "ZanikloOd" -> zaniklo   = r.getElementText();
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT && "Obec".equals(r.getLocalName())) break;
        }
        if (kod == null) return null;
        return new ObecRecord(kod, nazev, kodOkresu, zaniklo == null || zaniklo.isBlank());
    }

    private CastObceRecord parseCastObce(XMLStreamReader r) throws XMLStreamException {
        String kod = null, nazev = null, kodObce = null;
        double[] sjtsk = null;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                switch (r.getLocalName()) {
                    case "Kod"     -> kod     = r.getElementText();
                    case "Nazev"   -> nazev   = r.getElementText();
                    case "ObecKod" -> kodObce = r.getElementText();
                    case "pos"     -> {
                        if (sjtsk == null) {
                            String[] parts = r.getElementText().trim().split("\\s+");
                            if (parts.length >= 2) {
                                try { sjtsk = new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1])}; }
                                catch (NumberFormatException ignored) {}
                            }
                        }
                    }
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT && "CastObce".equals(r.getLocalName())) break;
        }
        if (kod == null || sjtsk == null) return null;
        double[] wgs = SjtskToWgs84.convert(sjtsk[0], sjtsk[1]);
        return CastObceRecord.fromCentroid(kod, nazev, kodObce, wgs[0], wgs[1]);
    }
}
