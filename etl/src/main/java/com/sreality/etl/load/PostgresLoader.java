package com.sreality.etl.load;

import com.sreality.etl.config.EtlConfig;
import com.sreality.etl.model.*;
import com.sreality.etl.transform.SpatialJoiner;
import com.sreality.etl.transform.SpatialJoiner.SpatialMatch;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads transformed data into the PostgreSQL data warehouse.
 *
 * Connection pool: HikariCP with max 2 connections.
 * All writes use INSERT ... ON CONFLICT (upsert) for idempotency.
 *
 * SCD Type 2 on fact tables — new row on price or is_active change.
 * SCD Type 1 on dimension tables — latest data always wins.
 *
 * RUIAN snapshot tracking:
 *   Table dw.ruian_snapshot stores the date of the last successfully loaded
 *   RUIAN VFR file. On each ETL run, if the new VFR snapshot is newer,
 *   all existing fact rows are re-matched to the new geography
 *   (bulkRematchSpatial). This ensures cast_obce_id and obec_id always
 *   reflect the latest RUIAN boundaries without keeping history of that.
 */
public class PostgresLoader implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PostgresLoader.class);

    private final HikariDataSource ds;
    private final String schema;

    public PostgresLoader(EtlConfig config) {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(config.pgJdbcUrl());
        hc.setUsername(config.pgUsername);
        hc.setPassword(config.pgPassword);
        hc.setMaximumPoolSize(2);
        hc.setMinimumIdle(1);
        hc.setConnectionTimeout(30_000);
        hc.setIdleTimeout(600_000);
        hc.setMaxLifetime(1_800_000);
        hc.setPoolName("etl-pool");
        this.ds     = new HikariDataSource(hc);
        this.schema = config.pgSchema;
        log.info("PostgresLoader connected to {}/{}", config.pgHost, config.pgDatabase);
    }

    // ── Schema creation ───────────────────────────────────────────────────────

    public void ensureSchema() {
        log.info("Ensuring schema '{}' and all tables exist...", schema);
        try (Connection conn = ds.getConnection();
             Statement  stmt = conn.createStatement()) {

            stmt.execute("CREATE SCHEMA IF NOT EXISTS " + schema);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS %s.dim_kraj (
                    id           SERIAL PRIMARY KEY,
                    kod_kraje    VARCHAR(20)  NOT NULL UNIQUE,
                    nazev_kraje  VARCHAR(100) NOT NULL
                )""".formatted(schema));

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS %s.dim_okres (
                    id            SERIAL PRIMARY KEY,
                    kod_okresu    VARCHAR(20)  NOT NULL UNIQUE,
                    nazev_okresu  VARCHAR(100) NOT NULL,
                    kraj_id       INT NOT NULL REFERENCES %s.dim_kraj(id)
                )""".formatted(schema, schema));

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS %s.dim_obec (
                    id                  SERIAL PRIMARY KEY,
                    kod_obce            VARCHAR(20)  NOT NULL UNIQUE,
                    nazev_obce          VARCHAR(100) NOT NULL,
                    okres_id            INT NOT NULL REFERENCES %s.dim_okres(id),
                    population          INT,
                    population_density  NUMERIC(10,2),
                    area_km2            NUMERIC(10,2),
                    avg_age             NUMERIC(5,2),
                    unemployment_pct    NUMERIC(5,2)
                )""".formatted(schema, schema));

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS %s.dim_cast_obce (
                    id                SERIAL PRIMARY KEY,
                    kod_cast_obce     VARCHAR(20)  NOT NULL UNIQUE,
                    nazev_cast_obce   VARCHAR(100) NOT NULL,
                    obec_id           INT NOT NULL REFERENCES %s.dim_obec(id)
                )""".formatted(schema, schema));

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS %s.dim_agency (
                    id           SERIAL PRIMARY KEY,
                    sreality_id  INT         NOT NULL UNIQUE,
                    name         VARCHAR(200),
                    url          VARCHAR(500)
                )""".formatted(schema));

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS %s.dim_date (
                    date_id      INT         PRIMARY KEY,
                    full_date    DATE        NOT NULL,
                    year         INT         NOT NULL,
                    quarter      INT         NOT NULL,
                    month        INT         NOT NULL,
                    month_name   VARCHAR(20) NOT NULL,
                    week         INT         NOT NULL,
                    day_of_week  INT         NOT NULL,
                    is_weekend   BOOLEAN     NOT NULL
                )""".formatted(schema));

            // Tracks the date of the last successfully applied RUIAN VFR snapshot.
            // One row, updated in place. Used to decide whether to re-match geography.
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS %s.ruian_snapshot (
                    id            INT PRIMARY KEY DEFAULT 1,
                    snapshot_date DATE NOT NULL,
                    loaded_at     TIMESTAMP NOT NULL DEFAULT now(),
                    zsj_count     INT,
                    CONSTRAINT single_row CHECK (id = 1)
                )""".formatted(schema));

            for (String dealType : List.of("sale", "rent", "auction")) {
                createFactTable(stmt, dealType);
            }

            createClosingViews(stmt);
            createIndexes(stmt);

            log.info("Schema ready.");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create schema", e);
        }
    }

    private void createFactTable(Statement stmt, String dealType) throws SQLException {
        String priceColsDdl = switch (dealType) {
            case "sale"    -> "price_asked_czk         BIGINT,\n    price_asked_per_m2        NUMERIC(12,2),";
            case "rent"    -> "price_monthly_czk        BIGINT,\n    price_monthly_per_m2      NUMERIC(10,2),";
            case "auction" -> "price_starting_bid_czk   BIGINT,";
            default        -> throw new IllegalArgumentException(dealType);
        };
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS %s.fact_%s_snapshot (
                id                       BIGSERIAL PRIMARY KEY,
                hash_id                  BIGINT       NOT NULL,
                sreality_url             VARCHAR(200),
                property_type            VARCHAR(50),
                sub_category             VARCHAR(20),
                valid_from               DATE         NOT NULL,
                valid_to                 DATE,
                cast_obce_id             INT          REFERENCES %s.dim_cast_obce(id),
                obec_id                  INT          NOT NULL REFERENCES %s.dim_obec(id),
                agency_id                INT          REFERENCES %s.dim_agency(id),
                date_id                  INT          REFERENCES %s.dim_date(date_id),
                %s
                usable_area_m2           NUMERIC(10,2),
                floor_number             INT,
                total_floors             INT,
                gps_lat                  NUMERIC(10,6),
                gps_lon                  NUMERIC(10,6),
                ownership_label          VARCHAR(50),
                building_type_label      VARCHAR(50),
                building_condition_label VARCHAR(100),
                energy_rating_label      VARCHAR(100),
                is_new_building          BOOLEAN,
                is_furnished             BOOLEAN,
                has_balcony              BOOLEAN,
                has_terrace              BOOLEAN,
                has_loggia               BOOLEAN,
                has_cellar               BOOLEAN,
                has_elevator             BOOLEAN,
                has_garage               BOOLEAN,
                has_parking              BOOLEAN,
                has_pool                 BOOLEAN,
                is_barrier_free          BOOLEAN,
                is_active                BOOLEAN      NOT NULL DEFAULT TRUE,
                first_seen_date          DATE,
                advert_images_count      INT,
                has_floor_plan           BOOLEAN,
                has_video                BOOLEAN
            )""".formatted(schema, dealType, schema, schema, schema, schema, priceColsDdl));
    }

    private void createClosingViews(Statement stmt) throws SQLException {
        stmt.execute("DROP VIEW IF EXISTS %s.v_sale_closing".formatted(schema));
        stmt.execute("""
            CREATE VIEW %s.v_sale_closing AS
            SELECT s.hash_id, s.property_type, s.sub_category, s.obec_id, s.cast_obce_id,
                MIN(s.valid_from) AS listed_date, MAX(s.valid_from) AS closed_date,
                MAX(s.valid_from) - MIN(s.valid_from) AS days_on_market,
                FIRST_VALUE(s.price_asked_czk) OVER (
                    PARTITION BY s.hash_id ORDER BY s.valid_from ASC
                    ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING) AS initial_asking_price_czk,
                LAST_VALUE(s.price_asked_czk) OVER (
                    PARTITION BY s.hash_id ORDER BY s.valid_from ASC
                    ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING) AS final_asking_price_czk,
                COUNT(DISTINCT s.price_asked_czk) - 1 AS total_price_changes,
                CASE WHEN FIRST_VALUE(s.price_asked_czk) OVER (
                        PARTITION BY s.hash_id ORDER BY s.valid_from
                        ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING) > 0
                    THEN ROUND(100.0 * (1 - LAST_VALUE(s.price_asked_czk) OVER (
                        PARTITION BY s.hash_id ORDER BY s.valid_from
                        ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING)::numeric
                        / FIRST_VALUE(s.price_asked_czk) OVER (
                        PARTITION BY s.hash_id ORDER BY s.valid_from
                        ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING)), 2)
                    ELSE NULL END AS pct_price_reduction
            FROM %s.fact_sale_snapshot s
            WHERE EXISTS (SELECT 1 FROM %s.fact_sale_snapshot x
                WHERE x.hash_id = s.hash_id AND x.is_active = FALSE)
            GROUP BY s.hash_id, s.property_type, s.sub_category, s.obec_id, s.cast_obce_id,
                     s.price_asked_czk, s.usable_area_m2, s.valid_from
            """.formatted(schema, schema, schema));

        stmt.execute("DROP VIEW IF EXISTS %s.v_rent_closing".formatted(schema));
        stmt.execute("""
            CREATE VIEW %s.v_rent_closing AS
            SELECT s.hash_id, s.property_type, s.sub_category, s.obec_id, s.cast_obce_id,
                MIN(s.valid_from) AS listed_date, MAX(s.valid_from) AS closed_date,
                MAX(s.valid_from) - MIN(s.valid_from) AS days_on_market,
                FIRST_VALUE(s.price_monthly_czk) OVER (
                    PARTITION BY s.hash_id ORDER BY s.valid_from ASC
                    ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING) AS initial_monthly_price_czk,
                LAST_VALUE(s.price_monthly_czk) OVER (
                    PARTITION BY s.hash_id ORDER BY s.valid_from ASC
                    ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING) AS final_monthly_price_czk,
                COUNT(DISTINCT s.price_monthly_czk) - 1 AS total_price_changes,
                CASE WHEN FIRST_VALUE(s.price_monthly_czk) OVER (
                        PARTITION BY s.hash_id ORDER BY s.valid_from
                        ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING) > 0
                    THEN ROUND(100.0 * (1 - LAST_VALUE(s.price_monthly_czk) OVER (
                        PARTITION BY s.hash_id ORDER BY s.valid_from
                        ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING)::numeric
                        / FIRST_VALUE(s.price_monthly_czk) OVER (
                        PARTITION BY s.hash_id ORDER BY s.valid_from
                        ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING)), 2)
                    ELSE NULL END AS pct_price_reduction
            FROM %s.fact_rent_snapshot s
            WHERE EXISTS (SELECT 1 FROM %s.fact_rent_snapshot x
                WHERE x.hash_id = s.hash_id AND x.is_active = FALSE)
            GROUP BY s.hash_id, s.property_type, s.sub_category, s.obec_id, s.cast_obce_id,
                     s.price_monthly_czk, s.valid_from
            """.formatted(schema, schema, schema));

        stmt.execute("DROP VIEW IF EXISTS %s.v_auction_closing".formatted(schema));
        stmt.execute("""
            CREATE VIEW %s.v_auction_closing AS
            SELECT s.hash_id, s.property_type, s.obec_id, s.cast_obce_id,
                MIN(s.valid_from) AS listed_date, MAX(s.valid_from) AS closed_date,
                MAX(s.valid_from) - MIN(s.valid_from) AS days_on_market,
                MIN(s.price_starting_bid_czk) AS initial_bid_czk,
                MAX(s.price_starting_bid_czk) AS final_bid_czk,
                COUNT(DISTINCT s.price_starting_bid_czk) - 1 AS total_price_changes
            FROM %s.fact_auction_snapshot s
            WHERE EXISTS (SELECT 1 FROM %s.fact_auction_snapshot x
                WHERE x.hash_id = s.hash_id AND x.is_active = FALSE)
            GROUP BY s.hash_id, s.property_type, s.obec_id, s.cast_obce_id
            """.formatted(schema, schema, schema));
    }

    private void createIndexes(Statement stmt) throws SQLException {
        String[] ddl = {
            "CREATE INDEX IF NOT EXISTS idx_fact_sale_hash    ON %s.fact_sale_snapshot    (hash_id)",
            "CREATE INDEX IF NOT EXISTS idx_fact_sale_valid   ON %s.fact_sale_snapshot    (valid_to) WHERE valid_to IS NULL",
            "CREATE INDEX IF NOT EXISTS idx_fact_sale_obec    ON %s.fact_sale_snapshot    (obec_id)",
            "CREATE INDEX IF NOT EXISTS idx_fact_sale_gps     ON %s.fact_sale_snapshot    (gps_lat, gps_lon) WHERE gps_lat IS NOT NULL",
            "CREATE INDEX IF NOT EXISTS idx_fact_rent_hash    ON %s.fact_rent_snapshot    (hash_id)",
            "CREATE INDEX IF NOT EXISTS idx_fact_rent_valid   ON %s.fact_rent_snapshot    (valid_to) WHERE valid_to IS NULL",
            "CREATE INDEX IF NOT EXISTS idx_fact_rent_obec    ON %s.fact_rent_snapshot    (obec_id)",
            "CREATE INDEX IF NOT EXISTS idx_fact_rent_gps     ON %s.fact_rent_snapshot    (gps_lat, gps_lon) WHERE gps_lat IS NOT NULL",
            "CREATE INDEX IF NOT EXISTS idx_fact_auc_hash     ON %s.fact_auction_snapshot (hash_id)",
            "CREATE INDEX IF NOT EXISTS idx_fact_auc_valid    ON %s.fact_auction_snapshot (valid_to) WHERE valid_to IS NULL",
            "CREATE INDEX IF NOT EXISTS idx_fact_auc_gps      ON %s.fact_auction_snapshot (gps_lat, gps_lon) WHERE gps_lat IS NOT NULL",
        };
        for (String s : ddl) stmt.execute(s.formatted(schema));
    }

    // ── RUIAN snapshot tracking ───────────────────────────────────────────────

    /**
     * Returns the date of the last RUIAN VFR snapshot loaded into the DB,
     * or null if no snapshot has been loaded yet.
     */
    public LocalDate getLastRuianSnapshotDate() {
        String sql = "SELECT snapshot_date FROM %s.ruian_snapshot WHERE id = 1".formatted(schema);
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return rs.getDate(1).toLocalDate();
            return null;
        } catch (SQLException e) {
            return null; // table exists but no row yet
        }
    }

    /** Records a successful RUIAN VFR snapshot load. */
    public void saveRuianSnapshotDate(LocalDate date, int zsjCount) {
        String sql = """
            INSERT INTO %s.ruian_snapshot (id, snapshot_date, loaded_at, zsj_count)
            VALUES (1, ?, now(), ?)
            ON CONFLICT (id) DO UPDATE
              SET snapshot_date = EXCLUDED.snapshot_date,
                  loaded_at     = EXCLUDED.loaded_at,
                  zsj_count     = EXCLUDED.zsj_count
            """.formatted(schema);
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDate(1, Date.valueOf(date));
            ps.setInt(2, zsjCount);
            ps.execute();
            log.info("Saved RUIAN snapshot date: {} ({} ZSJ records)", date, zsjCount);
        } catch (SQLException e) {
            throw new RuntimeException("saveRuianSnapshotDate failed", e);
        }
    }

    // ── Bulk spatial re-match ─────────────────────────────────────────────────

    /**
     * Re-matches ALL existing fact rows to the new RUIAN geography.
     *
     * Called when a newer RUIAN VFR snapshot is available. Reads GPS coordinates
     * from every fact row (all versions, not just current), re-runs the spatial
     * join, and updates cast_obce_id + obec_id in place.
     *
     * No SCD Type 2 logic here — geography is not a versioned attribute.
     * We always want every row to reflect the latest boundaries.
     *
     * Process: stream rows in pages of 1000, re-match, batch-update.
     */
    public void bulkRematchSpatial(SpatialJoiner joiner) {
        log.info("Bulk re-matching spatial geography for all fact rows...");
        int total = 0;
        for (String dealType : List.of("sale", "rent", "auction")) {
            int n = rematchTable(schema + ".fact_" + dealType + "_snapshot", joiner);
            log.info("  {} {}: {} rows re-matched", dealType, "snapshot", n);
            total += n;
        }
        log.info("Bulk spatial re-match complete: {} total rows updated", total);
    }

    private int rematchTable(String table, SpatialJoiner joiner) {
        String selectSql = "SELECT id, gps_lat, gps_lon FROM " + table
            + " WHERE gps_lat IS NOT NULL ORDER BY id LIMIT 1000 OFFSET ?";
        String updateSql = "UPDATE " + table
            + " SET cast_obce_id = ?, obec_id = ? WHERE id = ?";

        int updated = 0;
        int offset  = 0;

        try (Connection conn = ds.getConnection()) {
            conn.setAutoCommit(false);
            PreparedStatement selPs = conn.prepareStatement(selectSql);
            PreparedStatement updPs = conn.prepareStatement(updateSql);

            while (true) {
                selPs.setInt(1, offset);
                ResultSet rs = selPs.executeQuery();

                List<long[]> ids = new ArrayList<>();
                while (rs.next()) {
                    long id  = rs.getLong(1);
                    double lat = rs.getDouble(2);
                    double lon = rs.getDouble(3);
                    SpatialMatch m = joiner.match(lat, lon);
                    if (m.obecId() == -1) continue;

                    if (m.castObceId() != null) updPs.setInt(1, m.castObceId());
                    else                         updPs.setNull(1, Types.INTEGER);
                    updPs.setInt(2, m.obecId());
                    updPs.setLong(3, id);
                    updPs.addBatch();
                    ids.add(new long[]{id});
                }
                rs.close();

                if (ids.isEmpty()) break;

                updPs.executeBatch();
                conn.commit();
                updated += ids.size();
                offset  += ids.size();
            }
        } catch (SQLException e) {
            throw new RuntimeException("bulkRematchSpatial failed for " + table, e);
        }
        return updated;
    }

    // ── Dimension upserts (SCD Type 1) ────────────────────────────────────────

    public void upsertKraj(List<DimKraj> rows) {
        String sql = """
            INSERT INTO %s.dim_kraj (kod_kraje, nazev_kraje)
            VALUES (?, ?)
            ON CONFLICT (kod_kraje) DO UPDATE SET nazev_kraje = EXCLUDED.nazev_kraje
            """.formatted(schema);
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (DimKraj k : rows) { ps.setString(1, k.kodKraje()); ps.setString(2, k.nazevKraje()); ps.execute(); }
        } catch (SQLException e) { throw new RuntimeException("upsertKraj failed", e); }
        log.info("Upserted {} kraj rows", rows.size());
    }

    public void upsertOkres(List<DimOkres> rows) {
        // Two-step upsert: resolve kraj_id explicitly before inserting.
        // The old inline subquery silently produced null kraj_id when kodVusc
        // was missing or didn't match any dim_kraj row (common with the ArcGIS
        // fallback path where the "vusc" field can be 0/null for some districts),
        // causing the NOT NULL constraint violation on dim_okres.kraj_id.
        String lookupSql = "SELECT id FROM %s.dim_kraj WHERE kod_kraje = ?".formatted(schema);
        String upsertSql = """
            INSERT INTO %s.dim_okres (kod_okresu, nazev_okresu, kraj_id)
            VALUES (?, ?, ?)
            ON CONFLICT (kod_okresu) DO UPDATE SET nazev_okresu = EXCLUDED.nazev_okresu
            """.formatted(schema);
        int skipped = 0;
        try (Connection conn = ds.getConnection();
             PreparedStatement lookupPs = conn.prepareStatement(lookupSql);
             PreparedStatement upsertPs = conn.prepareStatement(upsertSql)) {
            for (DimOkres o : rows) {
                if (o.kodVusc() == null) {
                    log.warn("upsertOkres: skipping okres '{}' (kod={}) — kodVusc is null",
                        o.nazevOkresu(), o.kodOkresu());
                    skipped++;
                    continue;
                }
                lookupPs.setString(1, o.kodVusc());
                ResultSet rs = lookupPs.executeQuery();
                if (!rs.next()) {
                    log.warn("upsertOkres: skipping okres '{}' (kod={}) — no dim_kraj row for kodVusc='{}'",
                        o.nazevOkresu(), o.kodOkresu(), o.kodVusc());
                    rs.close();
                    skipped++;
                    continue;
                }
                int krajId = rs.getInt(1);
                rs.close();
                upsertPs.setString(1, o.kodOkresu());
                upsertPs.setString(2, o.nazevOkresu());
                upsertPs.setInt(3, krajId);
                upsertPs.execute();
            }
        } catch (SQLException e) { throw new RuntimeException("upsertOkres failed", e); }
        if (skipped > 0)
            log.warn("upsertOkres: {} okres rows skipped (kraj not found) — check RUIAN vusc codes", skipped);
        log.info("Upserted {} okres rows ({} skipped)", rows.size() - skipped, skipped);
    }

    public void upsertObec(List<DimObec> rows) {
        // Two-step upsert: resolve okres_id explicitly before inserting.
        // The old inline subquery silently produced null okres_id when kodOkresu
        // was missing or didn't match any dim_okres row, causing the NOT NULL
        // constraint violation on dim_obec.okres_id.
        String lookupSql = "SELECT id FROM %s.dim_okres WHERE kod_okresu = ?".formatted(schema);
        String upsertSql = """
            INSERT INTO %s.dim_obec
              (kod_obce, nazev_obce, okres_id, population, population_density, area_km2, avg_age, unemployment_pct)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (kod_obce) DO UPDATE SET
              nazev_obce = EXCLUDED.nazev_obce, okres_id = EXCLUDED.okres_id,
              population = EXCLUDED.population,
              population_density = EXCLUDED.population_density, area_km2 = EXCLUDED.area_km2,
              avg_age = EXCLUDED.avg_age, unemployment_pct = EXCLUDED.unemployment_pct
            """.formatted(schema);
        int skipped = 0;
        try (Connection conn = ds.getConnection();
             PreparedStatement lookupPs = conn.prepareStatement(lookupSql);
             PreparedStatement upsertPs = conn.prepareStatement(upsertSql)) {
            for (DimObec o : rows) {
                if (o.kodOkresu() == null) {
                    log.warn("upsertObec: skipping obec '{}' (kod={}) — kodOkresu is null",
                        o.nazevObce(), o.kodObce());
                    skipped++;
                    continue;
                }
                lookupPs.setString(1, o.kodOkresu());
                ResultSet rs = lookupPs.executeQuery();
                if (!rs.next()) {
                    log.warn("upsertObec: skipping obec '{}' (kod={}) — no dim_okres row for kodOkresu='{}'",
                        o.nazevObce(), o.kodObce(), o.kodOkresu());
                    rs.close();
                    skipped++;
                    continue;
                }
                int okresId = rs.getInt(1);
                rs.close();
                upsertPs.setString(1, o.kodObce());
                upsertPs.setString(2, o.nazevObce());
                upsertPs.setInt(3, okresId);
                setNullableInt(upsertPs, 4, o.population());
                setNullableDouble(upsertPs, 5, o.populationDensity());
                setNullableDouble(upsertPs, 6, o.areaKm2());
                setNullableDouble(upsertPs, 7, o.avgAge());
                setNullableDouble(upsertPs, 8, o.unemploymentPct());
                upsertPs.execute();
            }
        } catch (SQLException e) { throw new RuntimeException("upsertObec failed", e); }
        if (skipped > 0)
            log.warn("upsertObec: {} obec rows skipped (okres not found) — check RUIAN okres codes", skipped);
        log.info("Upserted {} obec rows ({} skipped)", rows.size() - skipped, skipped);
    }

    public void upsertCastObce(List<DimCastObce> rows) {
        // Two-step upsert: resolve obec_id explicitly before inserting.
        // The old inline subquery silently produced null obec_id when kodObce
        // was missing or didn't match any dim_obec row (e.g. the parent obec
        // was itself skipped due to a missing okres), causing the NOT NULL
        // constraint violation on dim_cast_obce.obec_id.
        String lookupSql = "SELECT id FROM %s.dim_obec WHERE kod_obce = ?".formatted(schema);
        String upsertSql = """
            INSERT INTO %s.dim_cast_obce (kod_cast_obce, nazev_cast_obce, obec_id)
            VALUES (?, ?, ?)
            ON CONFLICT (kod_cast_obce) DO UPDATE SET
              nazev_cast_obce = EXCLUDED.nazev_cast_obce,
              obec_id = EXCLUDED.obec_id
            """.formatted(schema);
        int skipped = 0;
        try (Connection conn = ds.getConnection();
             PreparedStatement lookupPs = conn.prepareStatement(lookupSql);
             PreparedStatement upsertPs = conn.prepareStatement(upsertSql)) {
            for (DimCastObce c : rows) {
                if (c.kodObce() == null) {
                    log.warn("upsertCastObce: skipping cast_obce '{}' (kod={}) — kodObce is null",
                        c.nazevCastObce(), c.kodCastObce());
                    skipped++;
                    continue;
                }
                lookupPs.setString(1, c.kodObce());
                ResultSet rs = lookupPs.executeQuery();
                if (!rs.next()) {
                    log.warn("upsertCastObce: skipping cast_obce '{}' (kod={}) — no dim_obec row for kodObce='{}'",
                        c.nazevCastObce(), c.kodCastObce(), c.kodObce());
                    rs.close();
                    skipped++;
                    continue;
                }
                int obecId = rs.getInt(1);
                rs.close();
                upsertPs.setString(1, c.kodCastObce());
                upsertPs.setString(2, c.nazevCastObce());
                upsertPs.setInt(3, obecId);
                upsertPs.execute();
            }
        } catch (SQLException e) { throw new RuntimeException("upsertCastObce failed", e); }
        if (skipped > 0)
            log.warn("upsertCastObce: {} cast_obce rows skipped (obec not found) — check RUIAN obec codes", skipped);
        log.info("Upserted {} cast_obce rows ({} skipped)", rows.size() - skipped, skipped);
    }

    public void upsertDate(int dateId, java.sql.Date fullDate, int year, int quarter,
                           int month, String monthName, int week, int dayOfWeek, boolean isWeekend) {
        String sql = """
            INSERT INTO %s.dim_date (date_id, full_date, year, quarter, month, month_name, week, day_of_week, is_weekend)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (date_id) DO NOTHING
            """.formatted(schema);
        try (Connection conn = ds.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, dateId); ps.setDate(2, fullDate); ps.setInt(3, year); ps.setInt(4, quarter);
            ps.setInt(5, month); ps.setString(6, monthName); ps.setInt(7, week); ps.setInt(8, dayOfWeek);
            ps.setBoolean(9, isWeekend); ps.execute();
        } catch (SQLException e) { throw new RuntimeException("upsertDate failed", e); }
    }

    public int upsertAgency(DimAgency agency) {
        String sql = """
            INSERT INTO %s.dim_agency (sreality_id, name, url) VALUES (?, ?, ?)
            ON CONFLICT (sreality_id) DO UPDATE SET name = EXCLUDED.name, url = EXCLUDED.url
            RETURNING id
            """.formatted(schema);
        try (Connection conn = ds.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, agency.srealityId()); ps.setString(2, agency.name()); ps.setString(3, agency.url());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt(1);
            throw new RuntimeException("upsertAgency returned no id");
        } catch (SQLException e) { throw new RuntimeException("upsertAgency failed", e); }
    }

    // ── Fact upserts (SCD Type 2) ─────────────────────────────────────────────

    public void upsertFactSnapshots(List<FactSnapshot> snapshots, String dealType, EtlReport report) {
        String table    = schema + ".fact_" + dealType + "_snapshot";
        String checkSql = "SELECT id, " + priceColumnName(dealType) + ", is_active FROM " + table
            + " WHERE hash_id = ? AND valid_to IS NULL LIMIT 1";
        String closeSql = "UPDATE " + table + " SET valid_to = ? WHERE hash_id = ? AND valid_to IS NULL";
        String insertSql = buildInsertSql(table, dealType);

        try (Connection conn = ds.getConnection()) {
            conn.setAutoCommit(false);
            try {
                PreparedStatement checkPs  = conn.prepareStatement(checkSql);
                PreparedStatement closePs  = conn.prepareStatement(closeSql);
                PreparedStatement insertPs = conn.prepareStatement(insertSql);
                LocalDate today = LocalDate.now();

                for (FactSnapshot s : snapshots) {
                    checkPs.setLong(1, s.hashId());
                    ResultSet rs = checkPs.executeQuery();
                    boolean exists = rs.next();
                    if (!exists) {
                        bindInsert(insertPs, s, today, null, dealType);
                        insertPs.execute();
                        report.estatesInserted.incrementAndGet();
                    } else {
                        long    ep = rs.getLong(2);
                        boolean ea = rs.getBoolean(3);
                        if (ep != coercePrice(s) || ea != s.isActive()) {
                            closePs.setDate(1, Date.valueOf(today)); closePs.setLong(2, s.hashId()); closePs.execute();
                            bindInsert(insertPs, s, today, null, dealType); insertPs.execute();
                            report.estatesUpdated.incrementAndGet();
                        } else {
                            report.estatesUnchanged.incrementAndGet();
                        }
                    }
                    rs.close();
                }
                conn.commit();
            } catch (Exception e) { conn.rollback(); throw e; }
        } catch (SQLException e) { throw new RuntimeException("upsertFactSnapshots failed for " + dealType, e); }
    }

    public void refreshClosingViews() {
        log.info("Closing views are live SQL — no refresh needed.");
    }

    // ── SQL helpers ───────────────────────────────────────────────────────────

    private String priceColumnName(String dealType) {
        return switch (dealType) {
            case "sale"    -> "price_asked_czk";
            case "rent"    -> "price_monthly_czk";
            case "auction" -> "price_starting_bid_czk";
            default -> throw new IllegalArgumentException(dealType);
        };
    }

    private long coercePrice(FactSnapshot s) { return s.priceCzk() != null ? s.priceCzk() : 0L; }

    private String buildInsertSql(String table, String dealType) {
        String cols = switch (dealType) {
            case "sale"    -> "price_asked_czk, price_asked_per_m2,";
            case "rent"    -> "price_monthly_czk, price_monthly_per_m2,";
            case "auction" -> "price_starting_bid_czk,";
            default -> throw new IllegalArgumentException(dealType);
        };
        String params = switch (dealType) {
            case "sale", "rent" -> "?, ?,";
            case "auction"      -> "?,";
            default -> throw new IllegalArgumentException(dealType);
        };
        return """
            INSERT INTO %s
              (hash_id, sreality_url, property_type, sub_category, valid_from, valid_to,
               cast_obce_id, obec_id, agency_id, date_id, %s
               usable_area_m2, floor_number, total_floors, gps_lat, gps_lon,
               ownership_label, building_type_label, building_condition_label, energy_rating_label,
               is_new_building, is_furnished, has_balcony, has_terrace, has_loggia,
               has_cellar, has_elevator, has_garage, has_parking, has_pool, is_barrier_free,
               is_active, first_seen_date, advert_images_count, has_floor_plan, has_video)
            VALUES (?,?,?,?,?,?,  ?,?,?,?,  %s  ?,?,?,?,?,  ?,?,?,?,  ?,?,?,?,?,  ?,?,?,?,?,?,  ?,?,?,?,?)
            """.formatted(table, cols, params);
    }

    private void bindInsert(PreparedStatement ps, FactSnapshot s,
                            LocalDate validFrom, LocalDate validTo, String dealType) throws SQLException {
        int i = 1;
        ps.setLong(i++, s.hashId()); ps.setString(i++, s.srealityUrl());
        ps.setString(i++, s.propertyType()); ps.setString(i++, s.subCategory());
        ps.setDate(i++, Date.valueOf(validFrom));
        if (validTo != null) ps.setDate(i++, Date.valueOf(validTo)); else ps.setNull(i++, Types.DATE);
        setNullableInt(ps, i++, s.castObceId()); ps.setInt(i++, s.obecId());
        setNullableInt(ps, i++, s.agencyId()); ps.setInt(i++, s.dateId());
        if (s.priceCzk() != null) ps.setLong(i++, s.priceCzk()); else ps.setNull(i++, Types.BIGINT);
        if (!dealType.equals("auction")) {
            if (s.pricePerM2() != null) ps.setDouble(i++, s.pricePerM2()); else ps.setNull(i++, Types.NUMERIC);
        }
        setNullableDouble(ps, i++, s.usableAreaM2()); setNullableInt(ps, i++, s.floorNumber());
        setNullableInt(ps, i++, s.totalFloors()); setNullableDouble(ps, i++, s.gpsLat());
        setNullableDouble(ps, i++, s.gpsLon()); ps.setString(i++, s.ownershipLabel());
        ps.setString(i++, s.buildingTypeLabel()); ps.setString(i++, s.buildingConditionLabel());
        ps.setString(i++, s.energyRatingLabel()); setNullableBool(ps, i++, s.isNewBuilding());
        setNullableBool(ps, i++, s.isFurnished()); setNullableBool(ps, i++, s.hasBalcony());
        setNullableBool(ps, i++, s.hasTerrace()); setNullableBool(ps, i++, s.hasLoggia());
        setNullableBool(ps, i++, s.hasCellar()); setNullableBool(ps, i++, s.hasElevator());
        setNullableBool(ps, i++, s.hasGarage()); setNullableBool(ps, i++, s.hasParking());
        setNullableBool(ps, i++, s.hasPool()); setNullableBool(ps, i++, s.isBarrierFree());
        ps.setBoolean(i++, s.isActive());
        if (s.firstSeenDate() != null) ps.setDate(i++, Date.valueOf(s.firstSeenDate())); else ps.setNull(i++, Types.DATE);
        setNullableInt(ps, i++, s.advertImagesCount()); setNullableBool(ps, i++, s.hasFloorPlan());
        setNullableBool(ps, i++, s.hasVideo());
    }

    private static void setNullableInt(PreparedStatement ps, int i, Integer v) throws SQLException {
        if (v != null) ps.setInt(i, v); else ps.setNull(i, Types.INTEGER);
    }
    private static void setNullableDouble(PreparedStatement ps, int i, Double v) throws SQLException {
        if (v != null) ps.setDouble(i, v); else ps.setNull(i, Types.NUMERIC);
    }
    private static void setNullableBool(PreparedStatement ps, int i, Boolean v) throws SQLException {
        if (v != null) ps.setBoolean(i, v); else ps.setNull(i, Types.BOOLEAN);
    }

    @Override
    public void close() {
        ds.close();
        log.info("PostgresLoader disconnected");
    }
}
