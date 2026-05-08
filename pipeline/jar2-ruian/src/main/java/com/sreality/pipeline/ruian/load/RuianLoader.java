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
        // Postgres performs the S-JTSK -> WGS84 reprojection via PostGIS
        // ST_Transform; centroid, bbox and the geom column are all derived from
        // the same EPSG:5514 inputs in one SELECT subquery.
        //
        // Bindings:
        //   1 kod_cast_obce  2 nazev  3 obec_id
        //   4 sjtsk_easting (centroid)  5 sjtsk_northing (centroid)
        //   6 wkt_sjtsk (polygon, NULL when missing)
        String upsert = "INSERT INTO " + pg.t("dim_cast_obce")
                + " (kod_cast_obce,nazev_cast_obce,obec_id,"
                + "  bbox_min_lat,bbox_min_lon,bbox_max_lat,bbox_max_lon,"
                + "  centroid_lat,centroid_lon,geom)"
                + " SELECT ?,?,?,"
                + "        ST_YMin(gw), ST_XMin(gw), ST_YMax(gw), ST_XMax(gw),"
                + "        ST_Y(cw), ST_X(cw),"
                + "        ST_Multi(gw)"
                + " FROM (SELECT"
                + "   ST_Transform(ST_SetSRID(ST_MakePoint(?, ?), 5514), 4326) AS cw,"
                + "   ST_Transform(ST_GeomFromText(?, 5514), 4326)             AS gw"
                + " ) src"
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
                if (r.sjtskEasting() == 0 && r.sjtskNorthing() == 0) {
                    log.debug("Skipping cast_obce {}: zero S-JTSK coordinates", r.kodCastObce());
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
                    ups.setDouble(4, r.sjtskEasting());
                    ups.setDouble(5, r.sjtskNorthing());
                    if (r.geomWktSjtsk() != null) {
                        ups.setString(6, r.geomWktSjtsk());
                        withGeom++;
                    } else {
                        ups.setNull(6, java.sql.Types.VARCHAR);
                    }
                    ups.execute();
                    ok++;
                }
            }
        }
        log.info("Upserted {} cast_obce ({} with polygon geometry), skipped {}",
                ok, withGeom, skip);
    }

    public void loadMestskeCasti(List<MestskaCastRecord> rows) throws SQLException {
        String lookup = "SELECT id FROM " + pg.t("dim_obec") + " WHERE kod_obce=?";
        // Same shape as loadCastiObci, just an extra typ column ('MOP'/'MOMC').
        // Bindings:
        //   1 kod  2 nazev  3 obec_id  4 typ
        //   5 sjtsk_easting  6 sjtsk_northing  7 wkt_sjtsk (NULL when missing)
        String upsert = "INSERT INTO " + pg.t("dim_mestska_cast")
                + " (kod_mestska_cast, nazev_mestska_cast, obec_id, typ,"
                + "  bbox_min_lat,bbox_min_lon,bbox_max_lat,bbox_max_lon,"
                + "  centroid_lat,centroid_lon,geom)"
                + " SELECT ?,?,?,?,"
                + "        ST_YMin(gw), ST_XMin(gw), ST_YMax(gw), ST_XMax(gw),"
                + "        ST_Y(cw), ST_X(cw),"
                + "        ST_Multi(gw)"
                + " FROM (SELECT"
                + "   ST_Transform(ST_SetSRID(ST_MakePoint(?, ?), 5514), 4326) AS cw,"
                + "   ST_Transform(ST_GeomFromText(?, 5514), 4326)             AS gw"
                + " ) src"
                + " ON CONFLICT (kod_mestska_cast) DO UPDATE SET"
                + "  nazev_mestska_cast=EXCLUDED.nazev_mestska_cast,"
                + "  obec_id=EXCLUDED.obec_id, typ=EXCLUDED.typ,"
                + "  bbox_min_lat=EXCLUDED.bbox_min_lat,bbox_min_lon=EXCLUDED.bbox_min_lon,"
                + "  bbox_max_lat=EXCLUDED.bbox_max_lat,bbox_max_lon=EXCLUDED.bbox_max_lon,"
                + "  centroid_lat=EXCLUDED.centroid_lat,centroid_lon=EXCLUDED.centroid_lon,"
                + "  geom=EXCLUDED.geom";
        int ok = 0, skip = 0, withGeom = 0, mop = 0, momc = 0;
        try (Connection c = pg.getConnection();
                PreparedStatement lps = c.prepareStatement(lookup);
                PreparedStatement ups = c.prepareStatement(upsert)) {
            for (MestskaCastRecord r : rows) {
                if (r.kodObce() == null) {
                    log.debug("Skipping mestska_cast {}: null kodObce", r.kodMestskaCast());
                    skip++;
                    continue;
                }
                if (r.sjtskEasting() == 0 && r.sjtskNorthing() == 0) {
                    log.debug("Skipping mestska_cast {}: zero S-JTSK coordinates", r.kodMestskaCast());
                    skip++;
                    continue;
                }
                lps.setString(1, r.kodObce());
                try (ResultSet rs = lps.executeQuery()) {
                    if (!rs.next()) {
                        skip++;
                        continue;
                    }
                    String nazev = r.nazevMestskaCast() != null
                            ? r.nazevMestskaCast()
                            : (r.typ() + "_" + r.kodMestskaCast());
                    ups.setString(1, r.kodMestskaCast());
                    ups.setString(2, nazev);
                    ups.setInt   (3, rs.getInt(1));
                    ups.setString(4, r.typ());
                    ups.setDouble(5, r.sjtskEasting());
                    ups.setDouble(6, r.sjtskNorthing());
                    if (r.geomWktSjtsk() != null) {
                        ups.setString(7, r.geomWktSjtsk());
                        withGeom++;
                    } else {
                        ups.setNull(7, java.sql.Types.VARCHAR);
                    }
                    ups.execute();
                    ok++;
                    if ("MOP".equals(r.typ())) mop++; else momc++;
                }
            }
        }
        log.info("Upserted {} mestska_cast ({} MOP / {} MOMC, {} with polygon), skipped {}",
                ok, mop, momc, withGeom, skip);
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
