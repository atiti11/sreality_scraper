package com.sreality.pipeline.ruian.load;

import com.sreality.pipeline.ruian.model.*;
import com.sreality.pipeline.shared.db.PostgresConnectionPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.LocalDate;
import java.util.List;

/**
 * Upserts RUIAN dimension data into Postgres.
 * Uses ON CONFLICT DO UPDATE so the operation is fully idempotent.
 * Load order must be: kraj → okres → obec → cast_obce (FK chain).
 */
public class RuianLoader {

    private static final Logger log = LoggerFactory.getLogger(RuianLoader.class);
    private final PostgresConnectionPool pg;

    public RuianLoader(PostgresConnectionPool pg) {
        this.pg = pg;
    }

    public void loadKraje(List<KrajRecord> rows) throws SQLException {
        String sql = "INSERT INTO " + pg.t("dim_kraj") + " (kod_kraje, nazev_kraje) VALUES (?,?)"
                + " ON CONFLICT (kod_kraje) DO UPDATE SET nazev_kraje=EXCLUDED.nazev_kraje";
        int ok = 0, skipped = 0;
        try (Connection c = pg.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            c.setAutoCommit(false);
            for (KrajRecord r : rows) {
                if (r.kodKraje() == null) {
                    skipped++;
                    continue;
                }
                // Use kod as fallback name if Nazev is missing in the XML
                String nazev = r.nazevKraje() != null ? r.nazevKraje() : "VUSC_" + r.kodKraje();
                ps.setString(1, r.kodKraje());
                ps.setString(2, nazev);
                ps.addBatch();
                ok++;
            }
            ps.executeBatch();
            c.commit();
        }
        log.info("Upserted {} kraj, skipped {} (null kod)", ok, skipped);
    }

    public void loadOkresy(List<OkresRecord> rows) throws SQLException {
        String lookup = "SELECT id FROM " + pg.t("dim_kraj") + " WHERE kod_kraje=?";
        String upsert = "INSERT INTO " + pg.t("dim_okres") + " (kod_okresu,nazev_okresu,kraj_id) VALUES (?,?,?)"
                + " ON CONFLICT (kod_okresu) DO UPDATE SET nazev_okresu=EXCLUDED.nazev_okresu";
        int ok = 0, skip = 0;
        try (Connection c = pg.getConnection();
                PreparedStatement lps = c.prepareStatement(lookup);
                PreparedStatement ups = c.prepareStatement(upsert)) {
            for (OkresRecord r : rows) {
                if (r.kodKraje() == null) {
                    log.debug("Skipping okres {}: null kodKraje", r.kodOkresu());
                    skip++;
                    continue;
                }
                lps.setString(1, r.kodKraje());
                try (ResultSet rs = lps.executeQuery()) {
                    if (!rs.next()) {
                        skip++;
                        continue;
                    }
                    String nazev = r.nazevOkresu() != null ? r.nazevOkresu() : "OKRES_" + r.kodOkresu();
                    ups.setString(1, r.kodOkresu());
                    ups.setString(2, nazev);
                    ups.setInt(3, rs.getInt(1));
                    ups.execute();
                    ok++;
                }
            }
        }
        log.info("Upserted {} okres, skipped {}", ok, skip);
    }

    // For kraje that have no real Okres (only Praha in practice), insert a synthetic
    // Okres row using the kraj's own kod so that obcí referencing it via <Pou> can resolve.
    public void ensureSyntheticOkresy() throws SQLException {
        String sql = "INSERT INTO " + pg.t("dim_okres") + " (kod_okresu, nazev_okresu, kraj_id)"
                + " SELECT k.kod_kraje, k.nazev_kraje, k.id FROM " + pg.t("dim_kraj") + " k"
                + " WHERE NOT EXISTS ("
                + "   SELECT 1 FROM " + pg.t("dim_okres") + " o WHERE o.kraj_id = k.id"
                + " ) ON CONFLICT (kod_okresu) DO NOTHING";
        try (Connection c = pg.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            int n = ps.executeUpdate();
            if (n > 0) log.info("Inserted {} synthetic okres(y) for kraj(e) with no real okres", n);
        }
    }

    public void loadObce(List<ObecRecord> rows) throws SQLException {
        String lookup = "SELECT id FROM " + pg.t("dim_okres") + " WHERE kod_okresu=?";
        String upsert = "INSERT INTO " + pg.t("dim_obec") + " (kod_obce,nazev_obce,okres_id,is_active) VALUES (?,?,?,?)"
                + " ON CONFLICT (kod_obce) DO UPDATE SET nazev_obce=EXCLUDED.nazev_obce,is_active=EXCLUDED.is_active";
        int ok = 0, skip = 0;
        try (Connection c = pg.getConnection();
                PreparedStatement lps = c.prepareStatement(lookup);
                PreparedStatement ups = c.prepareStatement(upsert)) {
            for (ObecRecord r : rows) {
                if (r.kodOkresu() == null) {
                    log.debug("Skipping obec {}: null kodOkresu", r.kodObce());
                    skip++;
                    continue;
                }
                lps.setString(1, r.kodOkresu());
                try (ResultSet rs = lps.executeQuery()) {
                    if (!rs.next()) {
                        skip++;
                        continue;
                    }
                    String nazev = r.nazevObce() != null ? r.nazevObce() : "OBEC_" + r.kodObce();
                    ups.setString(1, r.kodObce());
                    ups.setString(2, nazev);
                    ups.setInt(3, rs.getInt(1));
                    ups.setBoolean(4, r.isActive());
                    ups.execute();
                    ok++;
                }
            }
        }
        log.info("Upserted {} obec, skipped {}", ok, skip);
    }

    public void loadCastiObci(List<CastObceRecord> rows) throws SQLException {
        String lookup = "SELECT id FROM " + pg.t("dim_obec") + " WHERE kod_obce=?";
        // geom is loaded via ST_Multi(ST_GeomFromText(?, 4326)). When the WKT
        // bind is NULL, ST_GeomFromText returns NULL (it's STRICT) so the row
        // simply ends up with geom = NULL.
        String upsert = "INSERT INTO " + pg.t("dim_cast_obce")
                + " (kod_cast_obce,nazev_cast_obce,obec_id,"
                + "  bbox_min_lat,bbox_min_lon,bbox_max_lat,bbox_max_lon,"
                + "  centroid_lat,centroid_lon,geom)"
                + " VALUES (?,?,?,?,?,?,?,?,?, ST_Multi(ST_GeomFromText(?, 4326)))"
                + " ON CONFLICT (kod_cast_obce) DO UPDATE SET"
                + "  nazev_cast_obce=EXCLUDED.nazev_cast_obce,obec_id=EXCLUDED.obec_id,"
                + "  bbox_min_lat=EXCLUDED.bbox_min_lat,bbox_min_lon=EXCLUDED.bbox_min_lon,"
                + "  bbox_max_lat=EXCLUDED.bbox_max_lat,bbox_max_lon=EXCLUDED.bbox_max_lon,"
                + "  centroid_lat=EXCLUDED.centroid_lat,centroid_lon=EXCLUDED.centroid_lon,"
                + "  geom=EXCLUDED.geom";
        int ok = 0, skip = 0, withGeom = 0;
        try (Connection c = pg.getConnection();
                PreparedStatement lps = c.prepareStatement(lookup);
                PreparedStatement ups = c.prepareStatement(upsert)) {
            for (CastObceRecord r : rows) {
                if (r.kodObce() == null) {
                    log.debug("Skipping cast_obce {}: null kodObce", r.kodCastObce());
                    skip++;
                    continue;
                }
                if (r.centroidLat() == 0 && r.centroidLon() == 0) {
                    log.debug("Skipping cast_obce {}: zero coordinates", r.kodCastObce());
                    skip++;
                    continue;
                }
                lps.setString(1, r.kodObce());
                try (ResultSet rs = lps.executeQuery()) {
                    if (!rs.next()) {
                        skip++;
                        continue;
                    }
                    String nazev = r.nazevCastObce() != null ? r.nazevCastObce() : "CAST_" + r.kodCastObce();
                    ups.setString(1, r.kodCastObce());
                    ups.setString(2, nazev);
                    ups.setInt(3, rs.getInt(1));
                    ups.setDouble(4, r.bboxMinLat());
                    ups.setDouble(5, r.bboxMinLon());
                    ups.setDouble(6, r.bboxMaxLat());
                    ups.setDouble(7, r.bboxMaxLon());
                    ups.setDouble(8, r.centroidLat());
                    ups.setDouble(9, r.centroidLon());
                    if (r.geomWkt() != null) {
                        ups.setString(10, r.geomWkt());
                        withGeom++;
                    } else {
                        ups.setNull(10, java.sql.Types.VARCHAR);
                    }
                    ups.execute();
                    ok++;
                }
            }
        }
        log.info("Upserted {} cast_obce ({} with polygon geometry), skipped {}",
                ok, withGeom, skip);
    }

    public void saveSnapshotDate(LocalDate date, int castObceCount) throws SQLException {
        String sql = "INSERT INTO " + pg.t("ruian_metadata") + " (id,snapshot_date,loaded_at,cast_obce_count)"
                + " VALUES (1,?,now(),?) ON CONFLICT (id) DO UPDATE"
                + " SET snapshot_date=EXCLUDED.snapshot_date,loaded_at=EXCLUDED.loaded_at,cast_obce_count=EXCLUDED.cast_obce_count";
        try (Connection c = pg.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setDate(1, Date.valueOf(date));
            ps.setInt(2, castObceCount);
            ps.execute();
        }
        log.info("Saved RUIAN metadata: {} ({} cast_obce)", date, castObceCount);
    }

    public LocalDate getLastSnapshotDate() {
        String sql = "SELECT snapshot_date FROM " + pg.t("ruian_metadata") + " WHERE id=1";
        try (Connection c = pg.getConnection();
                PreparedStatement ps = c.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getDate(1).toLocalDate() : null;
        } catch (SQLException e) {
            return null;
        }
    }
}
