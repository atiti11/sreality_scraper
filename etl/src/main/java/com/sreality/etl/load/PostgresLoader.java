package com.sreality.etl.load;

import com.sreality.etl.config.EtlConfig;
import com.sreality.etl.model.*;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.LocalDate;
import java.util.List;

/**
 * Loads transformed data into the PostgreSQL data warehouse.
 *
 * Connection pool: HikariCP with max 2 connections (1 active + 1 idle).
 * This is a single-threaded loader — no concurrency needed.
 *
 * All writes use INSERT ... ON CONFLICT (upsert) for idempotency.
 * Re-running the ETL is always safe.
 *
 * SCD Type 2 on fact tables:
 *   When an estate changes, the current row's valid_to is set to today
 *   and a new row is inserted with valid_from = today, valid_to = NULL.
 *   The comparison key is hash_id; fields compared are price, is_active,
 *   and boolean features.
 *
 * SCD Type 1 on dimension tables:
 *   Dimension rows are upserted by natural key (kod_obce, kod_cast_obce etc.).
 *   The latest data always wins — no history is kept.
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

    /**
     * Creates the data warehouse schema and all tables if they don't exist.
     * Safe to call on every ETL run — uses CREATE IF NOT EXISTS throughout.
     */
    public void ensureSchema() {
        log.info("Ensuring schema '{}' and all tables exist...", schema);
        try (Connection conn = ds.getConnection();
             Statement  stmt = conn.createStatement()) {

            stmt.execute("CREATE SCHEMA IF NOT EXISTS " + schema);

            // ── Location hierarchy ────────────────────────────────────────
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

            // ── Shared dimensions ─────────────────────────────────────────
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

            // ── Fact tables ───────────────────────────────────────────────
            for (String dealType : List.of("sale", "rent", "auction")) {
                createFactTable(stmt, dealType);
            }

            // ── Closing views ─────────────────────────────────────────────
            createClosingViews(stmt);

            // ── Indexes ───────────────────────────────────────────────────
            createIndexes(stmt);

            log.info("Schema ready.");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create schema", e);
        }
    }

    private void createFactTable(Statement stmt, String dealType) throws SQLException {
        String priceCol = switch (dealType) {
            case "sale"    -> "price_asked_czk         BIGINT,\n    price_asked_per_m2        NUMERIC(12,2),";
            case "rent"    -> "price_monthly_czk        BIGINT,\n    price_monthly_per_m2      NUMERIC(10,2),";
            case "auction" -> "price_starting_bid_czk   BIGINT,";
            default        -> throw new IllegalArgumentException("Unknown deal type: " + dealType);
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
            )""".formatted(schema, dealType, schema, schema, schema, schema, priceCol));
    }

    private void createClosingViews(Statement stmt) throws SQLException {
        // Sale closing view
        stmt.execute("DROP VIEW IF EXISTS %s.v_sale_closing".formatted(schema));
        stmt.execute("""
            CREATE VIEW %s.v_sale_closing AS
            SELECT
                s.hash_id,
                s.property_type,
                s.sub_category,
                s.obec_id,
                s.cast_obce_id,
                MIN(s.valid_from)                                        AS listed_date,
                MAX(s.valid_from)                                        AS closed_date,
                MAX(s.valid_from) - MIN(s.valid_from)                    AS days_on_market,
                FIRST_VALUE(s.price_asked_czk) OVER (
                    PARTITION BY s.hash_id ORDER BY s.valid_from ASC
                    ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING
                )                                                         AS initial_asking_price_czk,
                LAST_VALUE(s.price_asked_czk) OVER (
                    PARTITION BY s.hash_id ORDER BY s.valid_from ASC
                    ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING
                )                                                         AS final_asking_price_czk,
                CASE WHEN MAX(s.usable_area_m2) > 0
                    THEN MAX(s.price_asked_czk)::numeric / MAX(s.usable_area_m2)
                    ELSE NULL END                                         AS final_asking_price_per_m2,
                COUNT(DISTINCT s.price_asked_czk) - 1                    AS total_price_changes,
                CASE WHEN FIRST_VALUE(s.price_asked_czk) OVER (
                        PARTITION BY s.hash_id ORDER BY s.valid_from
                        ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING) > 0
                    THEN ROUND(100.0 * (1 - LAST_VALUE(s.price_asked_czk) OVER (
                        PARTITION BY s.hash_id ORDER BY s.valid_from
                        ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING)::numeric
                        / FIRST_VALUE(s.price_asked_czk) OVER (
                        PARTITION BY s.hash_id ORDER BY s.valid_from
                        ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING)), 2)
                    ELSE NULL END                                         AS pct_price_reduction
            FROM %s.fact_sale_snapshot s
            WHERE EXISTS (
                SELECT 1 FROM %s.fact_sale_snapshot x
                WHERE x.hash_id = s.hash_id AND x.is_active = FALSE
            )
            GROUP BY s.hash_id, s.property_type, s.sub_category, s.obec_id, s.cast_obce_id,
                     s.price_asked_czk, s.usable_area_m2, s.valid_from
            """.formatted(schema, schema, schema));

        // Rent closing view
        stmt.execute("DROP VIEW IF EXISTS %s.v_rent_closing".formatted(schema));
        stmt.execute("""
            CREATE VIEW %s.v_rent_closing AS
            SELECT
                s.hash_id,
                s.property_type,
                s.sub_category,
                s.obec_id,
                s.cast_obce_id,
                MIN(s.valid_from)                                        AS listed_date,
                MAX(s.valid_from)                                        AS closed_date,
                MAX(s.valid_from) - MIN(s.valid_from)                    AS days_on_market,
                FIRST_VALUE(s.price_monthly_czk) OVER (
                    PARTITION BY s.hash_id ORDER BY s.valid_from ASC
                    ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING
                )                                                         AS initial_monthly_price_czk,
                LAST_VALUE(s.price_monthly_czk) OVER (
                    PARTITION BY s.hash_id ORDER BY s.valid_from ASC
                    ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING
                )                                                         AS final_monthly_price_czk,
                COUNT(DISTINCT s.price_monthly_czk) - 1                  AS total_price_changes,
                CASE WHEN FIRST_VALUE(s.price_monthly_czk) OVER (
                        PARTITION BY s.hash_id ORDER BY s.valid_from
                        ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING) > 0
                    THEN ROUND(100.0 * (1 - LAST_VALUE(s.price_monthly_czk) OVER (
                        PARTITION BY s.hash_id ORDER BY s.valid_from
                        ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING)::numeric
                        / FIRST_VALUE(s.price_monthly_czk) OVER (
                        PARTITION BY s.hash_id ORDER BY s.valid_from
                        ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING)), 2)
                    ELSE NULL END                                         AS pct_price_reduction
            FROM %s.fact_rent_snapshot s
            WHERE EXISTS (
                SELECT 1 FROM %s.fact_rent_snapshot x
                WHERE x.hash_id = s.hash_id AND x.is_active = FALSE
            )
            GROUP BY s.hash_id, s.property_type, s.sub_category, s.obec_id, s.cast_obce_id,
                     s.price_monthly_czk, s.valid_from
            """.formatted(schema, schema, schema));

        // Auction closing view
        stmt.execute("DROP VIEW IF EXISTS %s.v_auction_closing".formatted(schema));
        stmt.execute("""
            CREATE VIEW %s.v_auction_closing AS
            SELECT
                s.hash_id,
                s.property_type,
                s.obec_id,
                s.cast_obce_id,
                MIN(s.valid_from)                       AS listed_date,
                MAX(s.valid_from)                       AS closed_date,
                MAX(s.valid_from) - MIN(s.valid_from)   AS days_on_market,
                MIN(s.price_starting_bid_czk)           AS initial_bid_czk,
                MAX(s.price_starting_bid_czk)           AS final_bid_czk,
                COUNT(DISTINCT s.price_starting_bid_czk) - 1 AS total_price_changes
            FROM %s.fact_auction_snapshot s
            WHERE EXISTS (
                SELECT 1 FROM %s.fact_auction_snapshot x
                WHERE x.hash_id = s.hash_id AND x.is_active = FALSE
            )
            GROUP BY s.hash_id, s.property_type, s.obec_id, s.cast_obce_id
            """.formatted(schema, schema, schema));
    }

    private void createIndexes(Statement stmt) throws SQLException {
        String[] ddl = {
            "CREATE INDEX IF NOT EXISTS idx_fact_sale_hash    ON %s.fact_sale_snapshot    (hash_id)",
            "CREATE INDEX IF NOT EXISTS idx_fact_sale_valid   ON %s.fact_sale_snapshot    (valid_to) WHERE valid_to IS NULL",
            "CREATE INDEX IF NOT EXISTS idx_fact_sale_obec    ON %s.fact_sale_snapshot    (obec_id)",
            "CREATE INDEX IF NOT EXISTS idx_fact_sale_date    ON %s.fact_sale_snapshot    (valid_from)",
            "CREATE INDEX IF NOT EXISTS idx_fact_rent_hash    ON %s.fact_rent_snapshot    (hash_id)",
            "CREATE INDEX IF NOT EXISTS idx_fact_rent_valid   ON %s.fact_rent_snapshot    (valid_to) WHERE valid_to IS NULL",
            "CREATE INDEX IF NOT EXISTS idx_fact_rent_obec    ON %s.fact_rent_snapshot    (obec_id)",
            "CREATE INDEX IF NOT EXISTS idx_fact_auc_hash     ON %s.fact_auction_snapshot (hash_id)",
            "CREATE INDEX IF NOT EXISTS idx_fact_auc_valid    ON %s.fact_auction_snapshot (valid_to) WHERE valid_to IS NULL",
        };
        for (String s : ddl) {
            stmt.execute(s.formatted(schema));
        }
    }

    // ── Dimension upserts (SCD Type 1) ────────────────────────────────────────

    public void upsertKraj(List<DimKraj> rows) {
        String sql = """
            INSERT INTO %s.dim_kraj (kod_kraje, nazev_kraje)
            VALUES (?, ?)
            ON CONFLICT (kod_kraje) DO UPDATE
              SET nazev_kraje = EXCLUDED.nazev_kraje
            RETURNING id
            """.formatted(schema);
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (DimKraj k : rows) {
                ps.setString(1, k.kodKraje());
                ps.setString(2, k.nazevKraje());
                ps.execute();
            }
        } catch (SQLException e) {
            throw new RuntimeException("upsertKraj failed", e);
        }
        log.info("Upserted {} kraj rows", rows.size());
    }

    public void upsertOkres(List<DimOkres> rows) {
        String sql = """
            INSERT INTO %s.dim_okres (kod_okresu, nazev_okresu, kraj_id)
            VALUES (?, ?, (SELECT id FROM %s.dim_kraj WHERE kod_kraje = ? LIMIT 1))
            ON CONFLICT (kod_okresu) DO UPDATE
              SET nazev_okresu = EXCLUDED.nazev_okresu
            """.formatted(schema, schema);
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (DimOkres o : rows) {
                ps.setString(1, o.kodOkresu());
                ps.setString(2, o.nazevOkresu());
                // kraj code embedded in okres code: first 5 chars is NUTS-based — use subquery
                ps.setString(3, o.kodOkresu().length() >= 5 ? o.kodOkresu().substring(0, 5) : o.kodOkresu());
                ps.execute();
            }
        } catch (SQLException e) {
            throw new RuntimeException("upsertOkres failed", e);
        }
        log.info("Upserted {} okres rows", rows.size());
    }

    public void upsertObec(List<DimObec> rows) {
        String sql = """
            INSERT INTO %s.dim_obec
              (kod_obce, nazev_obce, okres_id, population, population_density,
               area_km2, avg_age, unemployment_pct)
            VALUES (?, ?,
              (SELECT id FROM %s.dim_okres WHERE kod_okresu = ? LIMIT 1),
              ?, ?, ?, ?, ?)
            ON CONFLICT (kod_obce) DO UPDATE SET
              nazev_obce          = EXCLUDED.nazev_obce,
              population          = EXCLUDED.population,
              population_density  = EXCLUDED.population_density,
              area_km2            = EXCLUDED.area_km2,
              avg_age             = EXCLUDED.avg_age,
              unemployment_pct    = EXCLUDED.unemployment_pct
            """.formatted(schema, schema);
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (DimObec o : rows) {
                ps.setString(1, o.kodObce());
                ps.setString(2, o.nazevObce());
                ps.setString(3, o.kodObce().length() >= 6 ? o.kodObce().substring(0, 6) : o.kodObce());
                setNullableInt(ps, 4, o.population());
                setNullableDouble(ps, 5, o.populationDensity());
                setNullableDouble(ps, 6, o.areaKm2());
                setNullableDouble(ps, 7, o.avgAge());
                setNullableDouble(ps, 8, o.unemploymentPct());
                ps.execute();
            }
        } catch (SQLException e) {
            throw new RuntimeException("upsertObec failed", e);
        }
        log.info("Upserted {} obec rows", rows.size());
    }

    public void upsertCastObce(List<DimCastObce> rows) {
        String sql = """
            INSERT INTO %s.dim_cast_obce (kod_cast_obce, nazev_cast_obce, obec_id)
            VALUES (?, ?,
              (SELECT id FROM %s.dim_obec WHERE kod_obce = ? LIMIT 1))
            ON CONFLICT (kod_cast_obce) DO UPDATE SET
              nazev_cast_obce = EXCLUDED.nazev_cast_obce
            """.formatted(schema, schema);
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (DimCastObce c : rows) {
                ps.setString(1, c.kodCastObce());
                ps.setString(2, c.nazevCastObce());
                // obec code: cast_obce code first 6 digits = obec code (RUIAN convention)
                String obceFallback = c.kodCastObce().length() >= 6
                    ? c.kodCastObce().substring(0, 6)
                    : c.kodCastObce();
                ps.setString(3, obceFallback);
                ps.execute();
            }
        } catch (SQLException e) {
            throw new RuntimeException("upsertCastObce failed", e);
        }
        log.info("Upserted {} cast_obce rows", rows.size());
    }

    public void upsertDate(int dateId, java.sql.Date fullDate, int year, int quarter,
                           int month, String monthName, int week, int dayOfWeek, boolean isWeekend) {
        String sql = """
            INSERT INTO %s.dim_date
              (date_id, full_date, year, quarter, month, month_name, week, day_of_week, is_weekend)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (date_id) DO NOTHING
            """.formatted(schema);
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1,     dateId);
            ps.setDate(2,    fullDate);
            ps.setInt(3,     year);
            ps.setInt(4,     quarter);
            ps.setInt(5,     month);
            ps.setString(6,  monthName);
            ps.setInt(7,     week);
            ps.setInt(8,     dayOfWeek);
            ps.setBoolean(9, isWeekend);
            ps.execute();
        } catch (SQLException e) {
            throw new RuntimeException("upsertDate failed", e);
        }
    }

    /** Upserts an agency by sreality_id. Returns the surrogate id. */
    public int upsertAgency(DimAgency agency) {
        String sql = """
            INSERT INTO %s.dim_agency (sreality_id, name, url)
            VALUES (?, ?, ?)
            ON CONFLICT (sreality_id) DO UPDATE
              SET name = EXCLUDED.name, url = EXCLUDED.url
            RETURNING id
            """.formatted(schema);
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1,    agency.srealityId());
            ps.setString(2, agency.name());
            ps.setString(3, agency.url());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt(1);
            throw new RuntimeException("upsertAgency returned no id");
        } catch (SQLException e) {
            throw new RuntimeException("upsertAgency failed", e);
        }
    }

    // ── Fact upserts (SCD Type 2) ─────────────────────────────────────────────

    /**
     * Upserts a batch of fact snapshots with SCD Type 2 logic.
     *
     * For each estate:
     *   - If no current row exists (valid_to IS NULL) → INSERT as new
     *   - If current row exists AND meaningful fields changed:
     *       UPDATE current row valid_to = today
     *       INSERT new row valid_from = today, valid_to = NULL
     *   - If current row exists AND nothing changed → skip (unchanged)
     *
     * Uses a single connection for the whole batch — no per-row commit overhead.
     */
    public void upsertFactSnapshots(List<FactSnapshot> snapshots, String dealType, EtlReport report) {
        String priceCol = switch (dealType) {
            case "sale"    -> "price_asked_czk, price_asked_per_m2,";
            case "rent"    -> "price_monthly_czk, price_monthly_per_m2,";
            case "auction" -> "price_starting_bid_czk,";
            default -> throw new IllegalArgumentException("Unknown deal type: " + dealType);
        };

        String table = schema + ".fact_" + dealType + "_snapshot";

        // Check query: find current row for a hash_id
        String checkSql = "SELECT id, " + priceColumnName(dealType) +
            ", is_active FROM " + table +
            " WHERE hash_id = ? AND valid_to IS NULL LIMIT 1";

        // Close current row
        String closeSql = "UPDATE " + table +
            " SET valid_to = ? WHERE hash_id = ? AND valid_to IS NULL";

        // Insert new row
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

                    boolean hasCurrentRow = rs.next();

                    if (!hasCurrentRow) {
                        // Brand new estate
                        bindInsert(insertPs, s, today, null, dealType);
                        insertPs.execute();
                        report.estatesInserted.incrementAndGet();

                    } else {
                        long   existingPrice    = rs.getLong(2);
                        boolean existingActive  = rs.getBoolean(3);

                        boolean changed = existingPrice != coercePrice(s, dealType)
                            || existingActive != s.isActive();

                        if (changed) {
                            // Close existing row
                            closePs.setDate(1, Date.valueOf(today));
                            closePs.setLong(2, s.hashId());
                            closePs.execute();

                            // Insert new version
                            bindInsert(insertPs, s, today, null, dealType);
                            insertPs.execute();
                            report.estatesUpdated.incrementAndGet();
                        } else {
                            report.estatesUnchanged.incrementAndGet();
                        }
                    }
                    rs.close();
                }

                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException("upsertFactSnapshots failed for " + dealType, e);
        }
    }

    /** No-op for views — they are live queries, nothing to refresh. */
    public void refreshClosingViews() {
        log.info("Closing views are live SQL views — no refresh needed.");
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

    private long coercePrice(FactSnapshot s, String dealType) {
        if (s.priceCzk() == null) return 0;
        return s.priceCzk();
    }

    private String buildInsertSql(String table, String dealType) {
        String priceParams = switch (dealType) {
            case "sale"    -> "?, ?,";   // price_asked_czk, price_asked_per_m2
            case "rent"    -> "?, ?,";   // price_monthly_czk, price_monthly_per_m2
            case "auction" -> "?,";      // price_starting_bid_czk only
            default -> throw new IllegalArgumentException(dealType);
        };
        String priceColsInsert = switch (dealType) {
            case "sale"    -> "price_asked_czk, price_asked_per_m2,";
            case "rent"    -> "price_monthly_czk, price_monthly_per_m2,";
            case "auction" -> "price_starting_bid_czk,";
            default -> throw new IllegalArgumentException(dealType);
        };
        return """
            INSERT INTO %s
              (hash_id, sreality_url, property_type, sub_category,
               valid_from, valid_to,
               cast_obce_id, obec_id, agency_id, date_id,
               %s
               usable_area_m2, floor_number, total_floors, gps_lat, gps_lon,
               ownership_label, building_type_label, building_condition_label, energy_rating_label,
               is_new_building, is_furnished, has_balcony, has_terrace, has_loggia,
               has_cellar, has_elevator, has_garage, has_parking, has_pool, is_barrier_free,
               is_active, first_seen_date, advert_images_count, has_floor_plan, has_video)
            VALUES (?,?,?,?,  ?,?,  ?,?,?,?,  %s  ?,?,?,?,?,  ?,?,?,?,  ?,?,?,?,?,  ?,?,?,?,?,?,  ?,?,?,?,?)
            """.formatted(table, priceColsInsert, priceParams);
    }

    private void bindInsert(PreparedStatement ps, FactSnapshot s,
                            LocalDate validFrom, LocalDate validTo, String dealType)
            throws SQLException {
        int i = 1;
        ps.setLong(i++,   s.hashId());
        ps.setString(i++, s.srealityUrl());
        ps.setString(i++, s.propertyType());
        ps.setString(i++, s.subCategory());
        ps.setDate(i++,   Date.valueOf(validFrom));
        if (validTo != null) ps.setDate(i++, Date.valueOf(validTo));
        else                 ps.setNull(i++, Types.DATE);
        setNullableInt(ps, i++, s.castObceId());
        ps.setInt(i++,    s.obecId());
        setNullableInt(ps, i++, s.agencyId());
        ps.setInt(i++,    s.dateId());
        // price — one or two columns depending on deal type
        if (s.priceCzk() != null) ps.setLong(i++, s.priceCzk());
        else                       ps.setNull(i++, Types.BIGINT);
        if (!dealType.equals("auction")) {
            if (s.pricePerM2() != null) ps.setDouble(i++, s.pricePerM2());
            else                         ps.setNull(i++, Types.NUMERIC);
        }
        setNullableDouble(ps, i++, s.usableAreaM2());
        setNullableInt(ps, i++, s.floorNumber());
        setNullableInt(ps, i++, s.totalFloors());
        setNullableDouble(ps, i++, s.gpsLat());
        setNullableDouble(ps, i++, s.gpsLon());
        ps.setString(i++, s.ownershipLabel());
        ps.setString(i++, s.buildingTypeLabel());
        ps.setString(i++, s.buildingConditionLabel());
        ps.setString(i++, s.energyRatingLabel());
        setNullableBool(ps, i++, s.isNewBuilding());
        setNullableBool(ps, i++, s.isFurnished());
        setNullableBool(ps, i++, s.hasBalcony());
        setNullableBool(ps, i++, s.hasTerrace());
        setNullableBool(ps, i++, s.hasLoggia());
        setNullableBool(ps, i++, s.hasCellar());
        setNullableBool(ps, i++, s.hasElevator());
        setNullableBool(ps, i++, s.hasGarage());
        setNullableBool(ps, i++, s.hasParking());
        setNullableBool(ps, i++, s.hasPool());
        setNullableBool(ps, i++, s.isBarrierFree());
        ps.setBoolean(i++, s.isActive());
        if (s.firstSeenDate() != null) ps.setDate(i++, Date.valueOf(s.firstSeenDate()));
        else                            ps.setNull(i++, Types.DATE);
        setNullableInt(ps, i++, s.advertImagesCount());
        setNullableBool(ps, i++, s.hasFloorPlan());
        setNullableBool(ps, i++, s.hasVideo());
    }

    // ── JDBC null-safe setters ────────────────────────────────────────────────

    private static void setNullableInt(PreparedStatement ps, int i, Integer v) throws SQLException {
        if (v != null) ps.setInt(i, v);
        else           ps.setNull(i, Types.INTEGER);
    }

    private static void setNullableDouble(PreparedStatement ps, int i, Double v) throws SQLException {
        if (v != null) ps.setDouble(i, v);
        else           ps.setNull(i, Types.NUMERIC);
    }

    private static void setNullableBool(PreparedStatement ps, int i, Boolean v) throws SQLException {
        if (v != null) ps.setBoolean(i, v);
        else           ps.setNull(i, Types.BOOLEAN);
    }

    @Override
    public void close() {
        ds.close();
        log.info("PostgresLoader disconnected");
    }
}
