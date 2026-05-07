package com.sreality.pipeline.csu.load;

import com.sreality.pipeline.csu.model.ObecStatsRecord;
import com.sreality.pipeline.csu.model.ObecSuccessorRecord;
import com.sreality.pipeline.shared.db.PostgresConnectionPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.List;

/**
 * Loads CSU data into Postgres.
 *
 * Successor table: seeded once during initial load from OD_KAM sheets.
 * Stats table: INSERT ... ON CONFLICT DO UPDATE so re-runs are safe.
 *
 * Extinct municipality handling:
 *   When a stat row's obec_kod is not in dim_obec (municipality no longer exists),
 *   we look it up in obec_successor and redirect to the successor's obec_id.
 *   This correctly rolls up historical data to current municipality boundaries.
 */
public class CsuLoader {

    private static final Logger log = LoggerFactory.getLogger(CsuLoader.class);
    private final PostgresConnectionPool pg;

    public CsuLoader(PostgresConnectionPool pg) { this.pg = pg; }

    // -------------------------------------------------------------------------
    // Successors
    // -------------------------------------------------------------------------

    public void loadSuccessors(List<ObecSuccessorRecord> rows) throws SQLException {
        String sql = "INSERT INTO " + pg.t("obec_successor")
                   + " (old_obec_kod, new_obec_kod, merged_year) VALUES (?,?,?)"
                   + " ON CONFLICT (old_obec_kod) DO UPDATE"
                   + "   SET new_obec_kod=EXCLUDED.new_obec_kod, merged_year=EXCLUDED.merged_year";
        try (Connection c = pg.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            c.setAutoCommit(false);
            for (ObecSuccessorRecord r : rows) {
                ps.setString(1, r.oldObecKod());
                ps.setString(2, r.newObecKod());
                ps.setInt(3, r.mergedYear());
                ps.addBatch();
            }
            ps.executeBatch();
            c.commit();
        }
        log.info("Upserted {} obec_successor rows", rows.size());
    }

    // -------------------------------------------------------------------------
    // Stats
    // -------------------------------------------------------------------------

    /**
     * Loads a list of ObecStatsRecord into fact_obec_stats.
     * Resolves obec_id via dim_obec, falling back to obec_successor for extinct codes.
     */
    public void loadStats(List<ObecStatsRecord> rows) throws SQLException {
        String resolveDirectSql  = "SELECT id FROM " + pg.t("dim_obec") + " WHERE kod_obce=?";
        String resolveSuccSql    = "SELECT o.id FROM " + pg.t("dim_obec") + " o"
                                 + " JOIN " + pg.t("obec_successor") + " s ON s.new_obec_kod=o.kod_obce"
                                 + " WHERE s.old_obec_kod=?";
        // COALESCE keeps existing non-null values so the two CSU ZIPs
        // (demographics + weddings) can be loaded in either order without
        // one overwriting the other's columns with NULLs.
        String upsertSql         = "INSERT INTO " + pg.t("fact_obec_stats")
                                 + " (obec_id,year,population,births,deaths,migration_balance,marriages,divorces,unemployment_pct)"
                                 + " VALUES (?,?,?,?,?,?,?,?,?)"
                                 + " ON CONFLICT (obec_id,year) DO UPDATE SET"
                                 + "   population=COALESCE(EXCLUDED.population,fact_obec_stats.population),"
                                 + "   births=COALESCE(EXCLUDED.births,fact_obec_stats.births),"
                                 + "   deaths=COALESCE(EXCLUDED.deaths,fact_obec_stats.deaths),"
                                 + "   migration_balance=COALESCE(EXCLUDED.migration_balance,fact_obec_stats.migration_balance),"
                                 + "   marriages=COALESCE(EXCLUDED.marriages,fact_obec_stats.marriages),"
                                 + "   divorces=COALESCE(EXCLUDED.divorces,fact_obec_stats.divorces),"
                                 + "   unemployment_pct=COALESCE(EXCLUDED.unemployment_pct,fact_obec_stats.unemployment_pct)";

        int ok = 0, resolved = 0, skipped = 0;
        try (Connection c = pg.getConnection();
             PreparedStatement direct = c.prepareStatement(resolveDirectSql);
             PreparedStatement succ   = c.prepareStatement(resolveSuccSql);
             PreparedStatement ups    = c.prepareStatement(upsertSql)) {

            c.setAutoCommit(false);
            int batch = 0;

            for (ObecStatsRecord r : rows) {
                Integer obecId = resolveObecId(r.kodObce(), direct, succ);
                if (obecId == null) { skipped++; continue; }

                boolean wasResolved = obecId < 0;  // flag from resolveObecId
                if (wasResolved) { obecId = -obecId; resolved++; }

                ups.setInt(1, obecId);
                ups.setInt(2, r.year());
                setIntOrNull(ups, 3, r.population());
                setIntOrNull(ups, 4, r.births());
                setIntOrNull(ups, 5, r.deaths());
                setIntOrNull(ups, 6, r.migrationBalance());
                setIntOrNull(ups, 7, r.marriages());
                setIntOrNull(ups, 8, r.divorces());
                setDoubleOrNull(ups, 9, r.unemploymentPct());
                ups.addBatch();
                ok++;

                if (++batch % 500 == 0) { ups.executeBatch(); c.commit(); }
            }
            ups.executeBatch();
            c.commit();
        }
        log.info("Loaded {} stat rows ({} via successor, {} skipped)", ok, resolved, skipped);
    }

    /**
     * Returns the obec_id for the given kod_obce.
     * Returns null if not found even via successor.
     * Returns a negative value if the id was found via successor (for counting).
     */
    private static Integer resolveObecId(String kodObce,
                                          PreparedStatement direct,
                                          PreparedStatement succ) throws SQLException {
        direct.setString(1, kodObce);
        try (ResultSet rs = direct.executeQuery()) {
            if (rs.next()) return rs.getInt(1);
        }
        succ.setString(1, kodObce);
        try (ResultSet rs = succ.executeQuery()) {
            if (rs.next()) return -rs.getInt(1); // negative = via successor
        }
        return null;
    }

    private static void setIntOrNull(PreparedStatement ps, int col, Integer v) throws SQLException {
        if (v == null) ps.setNull(col, Types.INTEGER); else ps.setInt(col, v);
    }

    private static void setDoubleOrNull(PreparedStatement ps, int col, Double v) throws SQLException {
        if (v == null) ps.setNull(col, Types.NUMERIC); else ps.setDouble(col, v);
    }
}
