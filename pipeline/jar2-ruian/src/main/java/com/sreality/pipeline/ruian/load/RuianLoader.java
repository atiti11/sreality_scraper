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

    public RuianLoader(PostgresConnectionPool pg) { this.pg = pg; }

    public void loadKraje(List<KrajRecord> rows) throws SQLException {
        String sql = "INSERT INTO " + pg.t("dim_kraj") + " (kod_kraje, nazev_kraje) VALUES (?,?)"
                   + " ON CONFLICT (kod_kraje) DO UPDATE SET nazev_kraje=EXCLUDED.nazev_kraje";
        try (Connection c = pg.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            c.setAutoCommit(false);
            for (KrajRecord r : rows) { ps.setString(1, r.kodKraje()); ps.setString(2, r.nazevKraje()); ps.addBatch(); }
            ps.executeBatch(); c.commit();
        }
        log.info("Upserted {} kraj", rows.size());
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
                if (r.kodKraje() == null) { skip++; continue; }
                lps.setString(1, r.kodKraje());
                try (ResultSet rs = lps.executeQuery()) {
                    if (!rs.next()) { skip++; continue; }
                    ups.setString(1, r.kodOkresu()); ups.setString(2, r.nazevOkresu()); ups.setInt(3, rs.getInt(1));
                    ups.execute(); ok++;
                }
            }
        }
        log.info("Upserted {} okres, skipped {}", ok, skip);
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
                if (r.kodOkresu() == null) { skip++; continue; }
                lps.setString(1, r.kodOkresu());
                try (ResultSet rs = lps.executeQuery()) {
                    if (!rs.next()) { skip++; continue; }
                    ups.setString(1, r.kodObce()); ups.setString(2, r.nazevObce());
                    ups.setInt(3, rs.getInt(1));   ups.setBoolean(4, r.isActive());
                    ups.execute(); ok++;
                }
            }
        }
        log.info("Upserted {} obec, skipped {}", ok, skip);
    }

    public void loadCastiObci(List<CastObceRecord> rows) throws SQLException {
        String lookup = "SELECT id FROM " + pg.t("dim_obec") + " WHERE kod_obce=?";
        String upsert = "INSERT INTO " + pg.t("dim_cast_obce")
                      + " (kod_cast_obce,nazev_cast_obce,obec_id,bbox_min_lat,bbox_min_lon,bbox_max_lat,bbox_max_lon,centroid_lat,centroid_lon)"
                      + " VALUES (?,?,?,?,?,?,?,?,?)"
                      + " ON CONFLICT (kod_cast_obce) DO UPDATE SET"
                      + "  nazev_cast_obce=EXCLUDED.nazev_cast_obce,obec_id=EXCLUDED.obec_id,"
                      + "  bbox_min_lat=EXCLUDED.bbox_min_lat,bbox_min_lon=EXCLUDED.bbox_min_lon,"
                      + "  bbox_max_lat=EXCLUDED.bbox_max_lat,bbox_max_lon=EXCLUDED.bbox_max_lon,"
                      + "  centroid_lat=EXCLUDED.centroid_lat,centroid_lon=EXCLUDED.centroid_lon";
        int ok = 0, skip = 0;
        try (Connection c = pg.getConnection();
             PreparedStatement lps = c.prepareStatement(lookup);
             PreparedStatement ups = c.prepareStatement(upsert)) {
            for (CastObceRecord r : rows) {
                if (r.kodObce() == null || r.centroidLat() == 0) { skip++; continue; }
                lps.setString(1, r.kodObce());
                try (ResultSet rs = lps.executeQuery()) {
                    if (!rs.next()) { skip++; continue; }
                    ups.setString(1, r.kodCastObce()); ups.setString(2, r.nazevCastObce());
                    ups.setInt(3, rs.getInt(1));
                    ups.setDouble(4, r.bboxMinLat()); ups.setDouble(5, r.bboxMinLon());
                    ups.setDouble(6, r.bboxMaxLat()); ups.setDouble(7, r.bboxMaxLon());
                    ups.setDouble(8, r.centroidLat()); ups.setDouble(9, r.centroidLon());
                    ups.execute(); ok++;
                }
            }
        }
        log.info("Upserted {} cast_obce, skipped {}", ok, skip);
    }

    public void saveSnapshotDate(LocalDate date, int castObceCount) throws SQLException {
        String sql = "INSERT INTO " + pg.t("ruian_metadata") + " (id,snapshot_date,loaded_at,cast_obce_count)"
                   + " VALUES (1,?,now(),?) ON CONFLICT (id) DO UPDATE"
                   + " SET snapshot_date=EXCLUDED.snapshot_date,loaded_at=EXCLUDED.loaded_at,cast_obce_count=EXCLUDED.cast_obce_count";
        try (Connection c = pg.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setDate(1, Date.valueOf(date)); ps.setInt(2, castObceCount); ps.execute();
        }
        log.info("Saved RUIAN metadata: {} ({} cast_obce)", date, castObceCount);
    }

    public LocalDate getLastSnapshotDate() {
        String sql = "SELECT snapshot_date FROM " + pg.t("ruian_metadata") + " WHERE id=1";
        try (Connection c = pg.getConnection(); PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getDate(1).toLocalDate() : null;
        } catch (SQLException e) { return null; }
    }
}
