package com.sreality.etl.transform;

import com.sreality.etl.model.DimCastObce;
import com.sreality.etl.model.DimObec;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.index.strtree.STRtree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Performs point-in-polygon spatial join between estate GPS coordinates
 * and RUIAN geographical units.
 *
 * Two-level strategy:
 *   1. Try cast_obce first — finer granularity (Praha MČ, Brno obvody, etc.)
 *   2. Fall back to obec if no cast_obce match found
 *
 * Uses JTS STRtree (R-tree spatial index) for efficient polygon lookup.
 * The index is built once on construction and reused for all estate queries.
 *
 * Memory: STRtree index is ~20-40MB for ~15k Czech cast_obce polygons.
 * This is acceptable within our 256MB heap budget.
 */
public class SpatialJoiner {

    private static final Logger log = LoggerFactory.getLogger(SpatialJoiner.class);

    private static final GeometryFactory GF =
        new GeometryFactory(new PrecisionModel(), 4326);

    private final STRtree         castObceIndex;
    private final STRtree         obecIndex;
    private final List<DimCastObce> castObceList;
    private final List<DimObec>     obecList;

    public SpatialJoiner(List<DimCastObce> castObceList, List<DimObec> obecList) {
        this.castObceList = castObceList;
        this.obecList     = obecList;
        this.castObceIndex = buildCastObceIndex(castObceList);
        this.obecIndex     = buildObecIndex(obecList);
        log.info("SpatialJoiner: index built ({} cast_obce, {} obec)",
            castObceList.size(), obecList.size());
    }

    /**
     * Result of a spatial lookup — both cast_obce_id (nullable) and obec_id.
     */
    public record SpatialMatch(
        Integer castObceId,  // null if no cast_obce contains this point
        int     obecId       // always set (or -1 if completely outside CZ)
    ) {}

    /**
     * Finds the cast_obce and obec containing the given GPS point.
     *
     * Step 1: query cast_obce STRtree with bounding box
     * Step 2: precise point-in-polygon test on candidates
     * Step 3: if no cast_obce match, fall back to obec STRtree
     */
    public SpatialMatch match(double lat, double lon) {
        Point point = GF.createPoint(new Coordinate(lon, lat)); // GeoJSON: lon first

        // ── Try cast_obce ──────────────────────────────────────────────────
        Integer castObceId = null;
        int     obecId     = -1;

        @SuppressWarnings("unchecked")
        List<DimCastObce> candidates = castObceIndex.query(point.getEnvelopeInternal());
        for (DimCastObce c : candidates) {
            if (c.geometry() != null && c.geometry().contains(point)) {
                castObceId = c.id();
                obecId     = c.obecId();
                break;
            }
        }

        // ── Fall back to obec ──────────────────────────────────────────────
        if (obecId == -1) {
            @SuppressWarnings("unchecked")
            List<DimObec> obecCandidates = obecIndex.query(point.getEnvelopeInternal());
            double minDist = Double.MAX_VALUE;
            for (DimObec o : obecCandidates) {
                // Obec index stores centroid-based bounding boxes — use centroid distance
                // as a heuristic for the fallback (full polygon not stored for obec)
                double dist = Math.sqrt(
                    Math.pow(lat - o.centroidLat(), 2) +
                    Math.pow(lon - o.centroidLon(), 2));
                if (dist < minDist) {
                    minDist = dist;
                    obecId  = o.id();
                }
            }
        }

        return new SpatialMatch(castObceId, obecId);
    }

    // ── Index builders ────────────────────────────────────────────────────────

    private static STRtree buildCastObceIndex(List<DimCastObce> list) {
        STRtree index = new STRtree();
        int built = 0;
        for (DimCastObce c : list) {
            if (c.geometry() != null && !c.geometry().isEmpty()) {
                index.insert(c.geometry().getEnvelopeInternal(), c);
                built++;
            }
        }
        index.build();
        log.debug("cast_obce STRtree: {} polygons indexed", built);
        return index;
    }

    private static STRtree buildObecIndex(List<DimObec> list) {
        STRtree index = new STRtree();
        double delta = 0.02; // ~2km bounding box around centroid
        for (DimObec o : list) {
            if (o.centroidLat() != 0 || o.centroidLon() != 0) {
                com.locationtech.jts.geom.Envelope env = new com.locationtech.jts.geom.Envelope(
                    o.centroidLon() - delta, o.centroidLon() + delta,
                    o.centroidLat() - delta, o.centroidLat() + delta
                );
                index.insert(env, o);
            }
        }
        index.build();
        log.debug("obec STRtree: {} centroids indexed", list.size());
        return index;
    }
}
