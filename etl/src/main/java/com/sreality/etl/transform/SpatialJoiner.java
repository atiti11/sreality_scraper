package com.sreality.etl.transform;

import com.sreality.etl.extract.RuianVfrExtractor.ZsjRecord;
import com.sreality.etl.model.DimCastObce;
import com.sreality.etl.model.DimObec;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.index.strtree.STRtree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Two-level point-in-polygon spatial joiner.
 *
 * Level 1 — ZSJ (Základní sídelní jednotka) polygons from RUIAN VFR XML.
 *   Each ZSJ carries castObceKod → resolves to dim_cast_obce surrogate id.
 *   ~45,000 polygons covering all of CZ at fine granularity.
 *   Gives cast_obce precision for every estate in the country.
 *
 * Level 2 — Obec polygons from RUIAN ArcGIS API (layer 12).
 *   Used as fallback when ZSJ data is unavailable or no ZSJ polygon matches.
 *   ~6,200 polygons. Always returns a valid obec for estates in CZ.
 *
 * Match priority:
 *   1. ZSJ polygon hit → castObceId + obecId (via cast_obce.obecId)
 *   2. Obec polygon hit → obecId only, castObceId = null
 *   3. Nearest obec centroid → obecId only (GPS just outside boundary)
 */
public class SpatialJoiner {

    private static final Logger log = LoggerFactory.getLogger(SpatialJoiner.class);

    private static final GeometryFactory GF = new GeometryFactory(new PrecisionModel(), 4326);

    // ZSJ index: ZsjRecord (has castObceKod, geometry)
    private final STRtree          zsjIndex;
    private final List<ZsjRecord>  zsjList;

    // cast_obce lookup: castObceKod (String) → surrogate id in dim_cast_obce
    private final Map<String, Integer> castObceIdByKod;

    // cast_obce → obec lookup: castObceKod → obecId surrogate
    private final Map<String, Integer> obecIdByCastObceKod;

    // Obec fallback index
    private final STRtree          obecIndex;
    private final List<DimObec>    obecList;

    private final boolean hasZsj;

    public SpatialJoiner(List<DimCastObce> castObceRows, List<DimObec> obecRows,
                         List<ZsjRecord> zsjRecords) {
        this.obecList  = obecRows;
        this.obecIndex = buildObecIndex(obecRows);

        // Build cast_obce lookups from the dimension rows (which have surrogate ids)
        this.castObceIdByKod = castObceRows.stream()
            .collect(Collectors.toMap(DimCastObce::kodCastObce, DimCastObce::id, (a, b) -> a));
        this.obecIdByCastObceKod = castObceRows.stream()
            .filter(c -> c.obecId() > 0)
            .collect(Collectors.toMap(DimCastObce::kodCastObce, DimCastObce::obecId, (a, b) -> a));

        if (zsjRecords != null && !zsjRecords.isEmpty()) {
            this.zsjList  = zsjRecords;
            this.zsjIndex = buildZsjIndex(zsjRecords);
            this.hasZsj   = true;
            log.info("SpatialJoiner: ZSJ index ({} polygons) + obec fallback ({} polygons)",
                zsjRecords.stream().filter(r -> r.geometry() != null).count(), obecRows.size());
        } else {
            this.zsjList  = List.of();
            this.zsjIndex = null;
            this.hasZsj   = false;
            log.info("SpatialJoiner: obec-only mode ({} polygons) — no ZSJ data",
                obecRows.size());
        }
    }

    // Backward-compatible constructor for when ZSJ is not available
    public SpatialJoiner(List<DimCastObce> castObceRows, List<DimObec> obecRows) {
        this(castObceRows, obecRows, null);
    }

    public record SpatialMatch(
        Integer castObceId,  // null if no ZSJ match
        int     obecId       // always set (or -1 if completely outside CZ)
    ) {}

    public SpatialMatch match(double lat, double lon) {
        Point point = GF.createPoint(new Coordinate(lon, lat));

        // ── Level 1: ZSJ polygon ──────────────────────────────────────────
        if (hasZsj) {
            @SuppressWarnings("unchecked")
            List<ZsjRecord> zsjCandidates = zsjIndex.query(point.getEnvelopeInternal());
            for (ZsjRecord zsj : zsjCandidates) {
                if (zsj.geometry() != null && zsj.geometry().contains(point)) {
                    String castKod = String.valueOf(zsj.castObceKod());
                    Integer castId = castObceIdByKod.get(castKod);
                    Integer obecId = obecIdByCastObceKod.get(castKod);
                    if (castId != null && obecId != null) {
                        return new SpatialMatch(castId, obecId);
                    }
                    // ZSJ matched but cast_obce not in dim — fall through to obec
                    break;
                }
            }
        }

        // ── Level 2: Obec polygon ─────────────────────────────────────────
        @SuppressWarnings("unchecked")
        List<DimObec> obecCandidates = obecIndex.query(point.getEnvelopeInternal());
        for (DimObec o : obecCandidates) {
            if (o.geometry() != null && o.geometry().contains(point)) {
                return new SpatialMatch(null, o.id());
            }
        }

        // ── Level 3: nearest centroid fallback ────────────────────────────
        int    bestId   = -1;
        double bestDist = Double.MAX_VALUE;
        for (DimObec o : obecCandidates) {
            double d = dist(lat, lon, o.centroidLat(), o.centroidLon());
            if (d < bestDist) { bestDist = d; bestId = o.id(); }
        }
        if (bestId == -1) {
            for (DimObec o : obecList) {
                if (o.centroidLat() == 0 && o.centroidLon() == 0) continue;
                double d = dist(lat, lon, o.centroidLat(), o.centroidLon());
                if (d < bestDist) { bestDist = d; bestId = o.id(); }
            }
        }
        return new SpatialMatch(null, bestId);
    }

    // ── Index builders ────────────────────────────────────────────────────────

    private static STRtree buildZsjIndex(List<ZsjRecord> list) {
        STRtree index = new STRtree();
        int built = 0;
        for (ZsjRecord r : list) {
            if (r.geometry() != null && !r.geometry().isEmpty()) {
                index.insert(r.geometry().getEnvelopeInternal(), r);
                built++;
            }
        }
        index.build();
        log.info("ZSJ STRtree: {} polygons indexed", built);
        return index;
    }

    private static STRtree buildObecIndex(List<DimObec> list) {
        STRtree index = new STRtree();
        int geom = 0, cent = 0;
        double delta = 0.05;
        for (DimObec o : list) {
            if (o.geometry() != null && !o.geometry().isEmpty()) {
                Geometry simplified = simplify(o.geometry());
                index.insert(simplified.getEnvelopeInternal(), o);
                geom++;
            } else if (o.centroidLat() != 0 || o.centroidLon() != 0) {
                Envelope env = new Envelope(
                    o.centroidLon() - delta, o.centroidLon() + delta,
                    o.centroidLat() - delta, o.centroidLat() + delta);
                index.insert(env, o);
                cent++;
            }
        }
        index.build();
        log.info("Obec STRtree: {} polygon + {} centroid entries", geom, cent);
        return index;
    }

    private static Geometry simplify(Geometry g) {
        try {
            Geometry s = org.locationtech.jts.simplify.TopologyPreservingSimplifier
                .simplify(g, 0.0005);
            return (s != null && s.isValid() && !s.isEmpty()) ? s : g;
        } catch (Exception e) { return g; }
    }

    private static double dist(double lat1, double lon1, double lat2, double lon2) {
        return Math.sqrt(Math.pow(lat1 - lat2, 2) + Math.pow(lon1 - lon2, 2));
    }
}
