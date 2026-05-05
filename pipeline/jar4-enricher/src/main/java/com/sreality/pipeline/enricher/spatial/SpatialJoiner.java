package com.sreality.pipeline.enricher.spatial;

import com.sreality.pipeline.shared.db.PostgresConnectionPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves GPS coordinates to RUIAN geography using bounding-box lookup.
 *
 * Algorithm:
 *   1. Find all cast_obce whose bbox contains the GPS point.
 *   2. If exactly one match → use it.
 *   3. If multiple matches (bbox overlap) → pick the one whose centroid
 *      is closest to the GPS point (Euclidean distance in degrees, sufficient
 *      for this precision level).
 *   4. If no match → return null (will be NULL in the fact table).
 *
 * The cast_obce → obec → okres → kraj chain is resolved in a single JOIN query
 * so callers get all geography IDs in one round-trip.
 */
public class SpatialJoiner {

    private static final Logger log = LoggerFactory.getLogger(SpatialJoiner.class);

    public record GeoResult(
        int    castObceId,
        int    obecId,
        int    okresId,
        int    krajId) {}

    private final PostgresConnectionPool pg;

    // Cached query string built once in constructor
    private final String sql;

    public SpatialJoiner(PostgresConnectionPool pg) {
        this.pg = pg;
        this.sql =
            "SELECT c.id, c.obec_id, c.centroid_lat, c.centroid_lon,"
            + "       r.id AS okres_id, k.id AS kraj_id"
            + " FROM " + pg.t("dim_cast_obce") + " c"
            + " JOIN " + pg.t("dim_obec")  + " o ON o.id = c.obec_id"
            + " JOIN " + pg.t("dim_okres") + " r ON r.id = o.okres_id"
            + " JOIN " + pg.t("dim_kraj")  + " k ON k.id = r.kraj_id"
            + " WHERE ? BETWEEN c.bbox_min_lat AND c.bbox_max_lat"
            + "   AND ? BETWEEN c.bbox_min_lon AND c.bbox_max_lon";
    }

    /**
     * Resolves a GPS point to RUIAN geography.
     * Returns null if no cast_obce bbox contains the point.
     *
     * @param lat WGS84 latitude
     * @param lon WGS84 longitude
     */
    public GeoResult resolve(double lat, double lon) {
        if (lat == 0 || lon == 0) return null;

        try (Connection c = pg.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setDouble(1, lat);
            ps.setDouble(2, lon);

            List<Candidate> candidates = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    candidates.add(new Candidate(
                        rs.getInt(1), rs.getInt(2),
                        rs.getDouble(3), rs.getDouble(4),
                        rs.getInt(5), rs.getInt(6)));
                }
            }

            if (candidates.isEmpty()) return null;
            if (candidates.size() == 1) {
                Candidate best = candidates.get(0);
                return new GeoResult(best.castObceId, best.obecId, best.okresId, best.krajId);
            }

            // Multiple bbox matches — pick closest centroid
            Candidate best = candidates.stream()
                .min((a, b) -> Double.compare(dist(a, lat, lon), dist(b, lat, lon)))
                .orElseThrow();
            return new GeoResult(best.castObceId, best.obecId, best.okresId, best.krajId);

        } catch (SQLException e) {
            log.error("SpatialJoiner failed for ({}, {}): {}", lat, lon, e.getMessage());
            return null;
        }
    }

    private static double dist(Candidate c, double lat, double lon) {
        double dLat = c.centroidLat - lat;
        double dLon = c.centroidLon - lon;
        return dLat * dLat + dLon * dLon; // squared — no need for sqrt
    }

    private record Candidate(
        int    castObceId,
        int    obecId,
        double centroidLat,
        double centroidLon,
        int    okresId,
        int    krajId) {}
}
