package com.sreality.pipeline.enricher.spatial;

import com.sreality.pipeline.shared.db.PostgresConnectionPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;

/**
 * Resolves GPS coordinates to RUIAN geography using polygon containment.
 *
 * Primary strategy is exact point-in-polygon via PostGIS:
 *   ST_Contains(c.geom, ST_SetSRID(ST_MakePoint(lon, lat), 4326))
 * with a GIST index on c.geom — typically &lt; 1 ms per lookup, no overlap
 * ambiguity.
 *
 * For cast_obce that have no polygon stored (geom IS NULL — happens when the
 * RUIAN record only included the DefinicniBod centroid), we fall back to the
 * nearest centroid within ~50 km. Keeps coverage high without compromising
 * accuracy for the 99 %+ of cast_obce that have a real polygon.
 *
 * The cast_obce → obec → okres → kraj chain is resolved in a single JOIN
 * query so callers get all geography IDs in one round-trip.
 */
public class SpatialJoiner {

    private static final Logger log = LoggerFactory.getLogger(SpatialJoiner.class);

    public record GeoResult(
        int castObceId,
        int obecId,
        int okresId,
        int krajId) {}

    // Czech-territory sanity check: ignore points clearly outside CZ.
    private static final double CZ_LAT_MIN = 48.5, CZ_LAT_MAX = 51.1;
    private static final double CZ_LON_MIN = 12.0, CZ_LON_MAX = 19.0;

    // Hard cap for nearest-centroid fallback (degrees-squared, ~50 km).
    private static final double MAX_FALLBACK_DIST_SQ = 0.25;

    private final PostgresConnectionPool pg;
    private final String containsSql;
    private final String nearestSql;

    public SpatialJoiner(PostgresConnectionPool pg) {
        this.pg = pg;
        // Primary: exact point-in-polygon. ST_Contains with GIST index uses
        // bbox prefilter automatically and then runs the precise predicate.
        this.containsSql =
            "SELECT c.id, c.obec_id, r.id AS okres_id, k.id AS kraj_id"
            + " FROM " + pg.t("dim_cast_obce") + " c"
            + " JOIN " + pg.t("dim_obec")  + " o ON o.id = c.obec_id"
            + " JOIN " + pg.t("dim_okres") + " r ON r.id = o.okres_id"
            + " JOIN " + pg.t("dim_kraj")  + " k ON k.id = r.kraj_id"
            + " WHERE c.geom IS NOT NULL"
            + "   AND ST_Contains(c.geom, ST_SetSRID(ST_MakePoint(?, ?), 4326))"
            + " LIMIT 1";

        // Fallback: nearest centroid for cast_obce without a polygon. Uses a
        // generous bbox prefilter (~30 km) so Postgres can use the centroid
        // bbox index, then sorts by squared distance.
        this.nearestSql =
            "SELECT c.id, c.obec_id, c.centroid_lat, c.centroid_lon,"
            + "       r.id AS okres_id, k.id AS kraj_id"
            + " FROM " + pg.t("dim_cast_obce") + " c"
            + " JOIN " + pg.t("dim_obec")  + " o ON o.id = c.obec_id"
            + " JOIN " + pg.t("dim_okres") + " r ON r.id = o.okres_id"
            + " JOIN " + pg.t("dim_kraj")  + " k ON k.id = r.kraj_id"
            + " WHERE c.centroid_lat IS NOT NULL"
            + "   AND c.centroid_lat BETWEEN ? - 0.3 AND ? + 0.3"
            + "   AND c.centroid_lon BETWEEN ? - 0.45 AND ? + 0.45"
            + " ORDER BY (c.centroid_lat - ?) * (c.centroid_lat - ?)"
            + "        + (c.centroid_lon - ?) * (c.centroid_lon - ?) ASC"
            + " LIMIT 1";
    }

    /**
     * Resolves a GPS point to RUIAN geography.
     *
     * @param lat WGS84 latitude
     * @param lon WGS84 longitude
     * @return resolved GeoResult or null when:
     *           - lat or lon is 0 (sreality "missing GPS"),
     *           - the point is outside Czech bounds,
     *           - no polygon contains the point AND the nearest centroid is
     *             further than {@link #MAX_FALLBACK_DIST_SQ} (~50 km).
     */
    public GeoResult resolve(double lat, double lon) {
        if (lat == 0 || lon == 0) return null;
        if (lat < CZ_LAT_MIN || lat > CZ_LAT_MAX
            || lon < CZ_LON_MIN || lon > CZ_LON_MAX) return null;

        try (Connection c = pg.getConnection()) {
            // ---- 1. exact polygon containment ----
            try (PreparedStatement ps = c.prepareStatement(containsSql)) {
                // ST_MakePoint takes (x, y) = (lon, lat) for geographic coords
                ps.setDouble(1, lon);
                ps.setDouble(2, lat);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return new GeoResult(
                            rs.getInt(1), rs.getInt(2),
                            rs.getInt(3), rs.getInt(4));
                    }
                }
            }

            // ---- 2. nearest-centroid fallback (only if no polygon matched) ----
            try (PreparedStatement ps = c.prepareStatement(nearestSql)) {
                ps.setDouble(1, lat);
                ps.setDouble(2, lat);
                ps.setDouble(3, lon);
                ps.setDouble(4, lon);
                ps.setDouble(5, lat);
                ps.setDouble(6, lat);
                ps.setDouble(7, lon);
                ps.setDouble(8, lon);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    int castObceId = rs.getInt(1);
                    int obecId     = rs.getInt(2);
                    double cLat    = rs.getDouble(3);
                    double cLon    = rs.getDouble(4);
                    int okresId    = rs.getInt(5);
                    int krajId     = rs.getInt(6);
                    double dLat = cLat - lat;
                    double dLon = cLon - lon;
                    if (dLat * dLat + dLon * dLon > MAX_FALLBACK_DIST_SQ) return null;
                    return new GeoResult(castObceId, obecId, okresId, krajId);
                }
            }
        } catch (SQLException e) {
            log.error("SpatialJoiner failed for ({}, {}): {}", lat, lon, e.getMessage());
            return null;
        }
    }
}
