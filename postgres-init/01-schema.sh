#!/usr/bin/env bash
# =============================================================================
# postgres-init/01-schema.sh
#
# Executed automatically by the Postgres container on first start.
# Mounted via /docker-entrypoint-initdb.d/ — runs as the postgres superuser.
# The database and user defined in POSTGRES_DB / POSTGRES_USER already exist
# at this point (created by the entrypoint before this script runs).
# =============================================================================
set -e

psql -v ON_ERROR_STOP=1 \
     --username "$POSTGRES_USER" \
     --dbname   "$POSTGRES_DB" \
<< 'ENDSQL'

-- ---------------------------------------------------------------------------
-- Geography dimensions  (Kraj → Okres → Obec → CastObce)
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS dim_kraj (
    id          SERIAL PRIMARY KEY,
    kod_kraje   VARCHAR(20)  NOT NULL UNIQUE,
    nazev_kraje VARCHAR(100) NOT NULL
);

CREATE TABLE IF NOT EXISTS dim_okres (
    id           SERIAL PRIMARY KEY,
    kod_okresu   VARCHAR(20)  NOT NULL UNIQUE,
    nazev_okresu VARCHAR(100) NOT NULL,
    kraj_id      INT          NOT NULL REFERENCES dim_kraj(id)
);

CREATE TABLE IF NOT EXISTS dim_obec (
    id         SERIAL PRIMARY KEY,
    kod_obce   VARCHAR(20)  NOT NULL UNIQUE,
    nazev_obce VARCHAR(100) NOT NULL,
    okres_id   INT          NOT NULL REFERENCES dim_okres(id),
    is_active  BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS dim_cast_obce (
    id              SERIAL PRIMARY KEY,
    kod_cast_obce   VARCHAR(20)  NOT NULL UNIQUE,
    nazev_cast_obce VARCHAR(100) NOT NULL,
    obec_id         INT          NOT NULL REFERENCES dim_obec(id),
    bbox_min_lat    NUMERIC(10,6),
    bbox_min_lon    NUMERIC(10,6),
    bbox_max_lat    NUMERIC(10,6),
    bbox_max_lon    NUMERIC(10,6),
    centroid_lat    NUMERIC(10,6),
    centroid_lon    NUMERIC(10,6)
);

-- Municipality succession (sourced from CSU OD_KAM sheet)
CREATE TABLE IF NOT EXISTS obec_successor (
    old_obec_kod VARCHAR(20) PRIMARY KEY,
    new_obec_kod VARCHAR(20) NOT NULL REFERENCES dim_obec(kod_obce),
    merged_year  INT         NOT NULL
);

-- ---------------------------------------------------------------------------
-- Agency + Date dimensions
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS dim_agency (
    id          SERIAL PRIMARY KEY,
    sreality_id INT          NOT NULL UNIQUE,
    name        VARCHAR(200),
    url         VARCHAR(500)
);

CREATE TABLE IF NOT EXISTS dim_date (
    date_id     INT         PRIMARY KEY,  -- YYYYMMDD
    full_date   DATE        NOT NULL,
    year        INT         NOT NULL,
    quarter     INT         NOT NULL,
    month       INT         NOT NULL,
    month_name  VARCHAR(20) NOT NULL,
    week        INT         NOT NULL,
    day_of_week INT         NOT NULL,     -- 1=Mon … 7=Sun
    is_weekend  BOOLEAN     NOT NULL
);

-- Pre-seed dates 2020-01-01 → 2035-12-31
INSERT INTO dim_date
    (date_id, full_date, year, quarter, month, month_name, week, day_of_week, is_weekend)
SELECT
    TO_CHAR(d,'YYYYMMDD')::INT,
    d::DATE,
    EXTRACT(YEAR    FROM d)::INT,
    EXTRACT(QUARTER FROM d)::INT,
    EXTRACT(MONTH   FROM d)::INT,
    TO_CHAR(d,'Month'),
    EXTRACT(WEEK    FROM d)::INT,
    EXTRACT(ISODOW  FROM d)::INT,
    EXTRACT(ISODOW  FROM d) IN (6,7)
FROM generate_series('2020-01-01'::DATE,'2035-12-31'::DATE,'1 day') AS d
ON CONFLICT (date_id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- CSU statistics
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS fact_obec_stats (
    id                SERIAL PRIMARY KEY,
    obec_id           INT  NOT NULL REFERENCES dim_obec(id),
    year              INT  NOT NULL,
    population        INT,
    births            INT,
    deaths            INT,
    migration_balance INT,
    marriages         INT,
    divorces          INT,
    unemployment_pct  NUMERIC(5,2),
    UNIQUE (obec_id, year)
);

-- ---------------------------------------------------------------------------
-- RUIAN freshness tracker
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS ruian_metadata (
    id              INT PRIMARY KEY DEFAULT 1,
    snapshot_date   DATE      NOT NULL,
    loaded_at       TIMESTAMP NOT NULL DEFAULT now(),
    cast_obce_count INT,
    CONSTRAINT ruian_single_row CHECK (id = 1)
);

-- ---------------------------------------------------------------------------
-- Estate side tables  (shared across all 14 fact tables)
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS estate_detail (
    hash_id       BIGINT       PRIMARY KEY,
    description   TEXT,
    locality_full VARCHAR(300),
    scraped_at    TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS estate_field_changes (
    id          BIGSERIAL   PRIMARY KEY,
    hash_id     BIGINT      NOT NULL,
    table_name  VARCHAR(50) NOT NULL,
    changed_at  DATE        NOT NULL,
    field_name  VARCHAR(60) NOT NULL,
    old_value   TEXT,
    new_value   TEXT
);

CREATE INDEX IF NOT EXISTS idx_efc_hash      ON estate_field_changes (hash_id);
CREATE INDEX IF NOT EXISTS idx_efc_field     ON estate_field_changes (field_name);
CREATE INDEX IF NOT EXISTS idx_efc_table     ON estate_field_changes (table_name);

-- ===========================================================================
-- APARTMENTS
-- ===========================================================================

CREATE TABLE IF NOT EXISTS fact_apartment_sale (
    id                       BIGSERIAL PRIMARY KEY,
    hash_id                  BIGINT        NOT NULL,
    content_hash             BIGINT        NOT NULL,
    valid_from               DATE          NOT NULL,
    valid_to                 DATE,
    obec_id                  INT           REFERENCES dim_obec(id),
    cast_obce_id             INT           REFERENCES dim_cast_obce(id),
    agency_id                INT           REFERENCES dim_agency(id),
    date_id                  INT           REFERENCES dim_date(date_id),
    price_asked_czk          BIGINT,
    price_asked_per_m2       NUMERIC(12,2),
    sub_category             VARCHAR(20),
    usable_area_m2           NUMERIC(10,2),
    floor_number             INT,
    total_floors             INT,
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
    has_parking              BOOLEAN,
    has_garage               BOOLEAN,
    is_barrier_free          BOOLEAN,
    gps_lat                  NUMERIC(10,6),
    gps_lon                  NUMERIC(10,6),
    is_active                BOOLEAN       NOT NULL DEFAULT TRUE,
    first_seen_date          DATE,
    sreality_url             VARCHAR(200),
    advert_images_count      INT,
    has_floor_plan           BOOLEAN,
    has_video                BOOLEAN
);

CREATE TABLE IF NOT EXISTS fact_apartment_rent (
    id                       BIGSERIAL PRIMARY KEY,
    hash_id                  BIGINT        NOT NULL,
    content_hash             BIGINT        NOT NULL,
    valid_from               DATE          NOT NULL,
    valid_to                 DATE,
    obec_id                  INT           REFERENCES dim_obec(id),
    cast_obce_id             INT           REFERENCES dim_cast_obce(id),
    agency_id                INT           REFERENCES dim_agency(id),
    date_id                  INT           REFERENCES dim_date(date_id),
    price_monthly_czk        BIGINT,
    price_monthly_per_m2     NUMERIC(10,2),
    sub_category             VARCHAR(20),
    usable_area_m2           NUMERIC(10,2),
    floor_number             INT,
    total_floors             INT,
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
    has_parking              BOOLEAN,
    has_garage               BOOLEAN,
    is_barrier_free          BOOLEAN,
    gps_lat                  NUMERIC(10,6),
    gps_lon                  NUMERIC(10,6),
    is_active                BOOLEAN       NOT NULL DEFAULT TRUE,
    first_seen_date          DATE,
    sreality_url             VARCHAR(200),
    advert_images_count      INT,
    has_floor_plan           BOOLEAN,
    has_video                BOOLEAN
);

CREATE TABLE IF NOT EXISTS fact_apartment_auction (
    id                       BIGSERIAL PRIMARY KEY,
    hash_id                  BIGINT        NOT NULL,
    content_hash             BIGINT        NOT NULL,
    valid_from               DATE          NOT NULL,
    valid_to                 DATE,
    obec_id                  INT           REFERENCES dim_obec(id),
    cast_obce_id             INT           REFERENCES dim_cast_obce(id),
    agency_id                INT           REFERENCES dim_agency(id),
    date_id                  INT           REFERENCES dim_date(date_id),
    price_starting_bid_czk   BIGINT,
    sub_category             VARCHAR(20),
    usable_area_m2           NUMERIC(10,2),
    floor_number             INT,
    total_floors             INT,
    ownership_label          VARCHAR(50),
    building_type_label      VARCHAR(50),
    building_condition_label VARCHAR(100),
    energy_rating_label      VARCHAR(100),
    is_new_building          BOOLEAN,
    has_balcony              BOOLEAN,
    has_terrace              BOOLEAN,
    has_loggia               BOOLEAN,
    has_cellar               BOOLEAN,
    has_elevator             BOOLEAN,
    has_parking              BOOLEAN,
    has_garage               BOOLEAN,
    is_barrier_free          BOOLEAN,
    gps_lat                  NUMERIC(10,6),
    gps_lon                  NUMERIC(10,6),
    is_active                BOOLEAN       NOT NULL DEFAULT TRUE,
    first_seen_date          DATE,
    sreality_url             VARCHAR(200),
    advert_images_count      INT,
    has_floor_plan           BOOLEAN,
    has_video                BOOLEAN
);

-- ===========================================================================
-- HOUSES
-- ===========================================================================

CREATE TABLE IF NOT EXISTS fact_house_sale (
    id                       BIGSERIAL PRIMARY KEY,
    hash_id                  BIGINT        NOT NULL,
    content_hash             BIGINT        NOT NULL,
    valid_from               DATE          NOT NULL,
    valid_to                 DATE,
    obec_id                  INT           REFERENCES dim_obec(id),
    cast_obce_id             INT           REFERENCES dim_cast_obce(id),
    agency_id                INT           REFERENCES dim_agency(id),
    date_id                  INT           REFERENCES dim_date(date_id),
    price_asked_czk          BIGINT,
    price_asked_per_m2       NUMERIC(12,2),
    usable_area_m2           NUMERIC(10,2),
    plot_area_m2             NUMERIC(10,2),
    garden_area_m2           NUMERIC(10,2),
    total_floors             INT,
    building_type_label      VARCHAR(50),
    building_condition_label VARCHAR(100),
    energy_rating_label      VARCHAR(100),
    is_new_building          BOOLEAN,
    is_low_energy            BOOLEAN,
    is_furnished             BOOLEAN,
    has_terrace              BOOLEAN,
    has_balcony              BOOLEAN,
    has_cellar               BOOLEAN,
    has_garage               BOOLEAN,
    has_parking              BOOLEAN,
    has_pool                 BOOLEAN,
    is_barrier_free          BOOLEAN,
    gps_lat                  NUMERIC(10,6),
    gps_lon                  NUMERIC(10,6),
    is_active                BOOLEAN       NOT NULL DEFAULT TRUE,
    first_seen_date          DATE,
    sreality_url             VARCHAR(200),
    advert_images_count      INT,
    has_floor_plan           BOOLEAN,
    has_video                BOOLEAN
);

CREATE TABLE IF NOT EXISTS fact_house_rent (
    id                       BIGSERIAL PRIMARY KEY,
    hash_id                  BIGINT        NOT NULL,
    content_hash             BIGINT        NOT NULL,
    valid_from               DATE          NOT NULL,
    valid_to                 DATE,
    obec_id                  INT           REFERENCES dim_obec(id),
    cast_obce_id             INT           REFERENCES dim_cast_obce(id),
    agency_id                INT           REFERENCES dim_agency(id),
    date_id                  INT           REFERENCES dim_date(date_id),
    price_monthly_czk        BIGINT,
    price_monthly_per_m2     NUMERIC(10,2),
    usable_area_m2           NUMERIC(10,2),
    plot_area_m2             NUMERIC(10,2),
    garden_area_m2           NUMERIC(10,2),
    total_floors             INT,
    building_type_label      VARCHAR(50),
    building_condition_label VARCHAR(100),
    energy_rating_label      VARCHAR(100),
    is_new_building          BOOLEAN,
    is_low_energy            BOOLEAN,
    is_furnished             BOOLEAN,
    has_terrace              BOOLEAN,
    has_balcony              BOOLEAN,
    has_cellar               BOOLEAN,
    has_garage               BOOLEAN,
    has_parking              BOOLEAN,
    has_pool                 BOOLEAN,
    is_barrier_free          BOOLEAN,
    gps_lat                  NUMERIC(10,6),
    gps_lon                  NUMERIC(10,6),
    is_active                BOOLEAN       NOT NULL DEFAULT TRUE,
    first_seen_date          DATE,
    sreality_url             VARCHAR(200),
    advert_images_count      INT,
    has_floor_plan           BOOLEAN,
    has_video                BOOLEAN
);

CREATE TABLE IF NOT EXISTS fact_house_auction (
    id                       BIGSERIAL PRIMARY KEY,
    hash_id                  BIGINT        NOT NULL,
    content_hash             BIGINT        NOT NULL,
    valid_from               DATE          NOT NULL,
    valid_to                 DATE,
    obec_id                  INT           REFERENCES dim_obec(id),
    cast_obce_id             INT           REFERENCES dim_cast_obce(id),
    agency_id                INT           REFERENCES dim_agency(id),
    date_id                  INT           REFERENCES dim_date(date_id),
    price_starting_bid_czk   BIGINT,
    usable_area_m2           NUMERIC(10,2),
    plot_area_m2             NUMERIC(10,2),
    garden_area_m2           NUMERIC(10,2),
    total_floors             INT,
    building_type_label      VARCHAR(50),
    building_condition_label VARCHAR(100),
    energy_rating_label      VARCHAR(100),
    is_new_building          BOOLEAN,
    is_low_energy            BOOLEAN,
    has_terrace              BOOLEAN,
    has_balcony              BOOLEAN,
    has_cellar               BOOLEAN,
    has_garage               BOOLEAN,
    has_parking              BOOLEAN,
    has_pool                 BOOLEAN,
    is_barrier_free          BOOLEAN,
    gps_lat                  NUMERIC(10,6),
    gps_lon                  NUMERIC(10,6),
    is_active                BOOLEAN       NOT NULL DEFAULT TRUE,
    first_seen_date          DATE,
    sreality_url             VARCHAR(200),
    advert_images_count      INT,
    has_floor_plan           BOOLEAN,
    has_video                BOOLEAN
);

-- ===========================================================================
-- LAND
-- ===========================================================================

CREATE TABLE IF NOT EXISTS fact_land_sale (
    id                  BIGSERIAL PRIMARY KEY,
    hash_id             BIGINT        NOT NULL,
    content_hash        BIGINT        NOT NULL,
    valid_from          DATE          NOT NULL,
    valid_to            DATE,
    obec_id             INT           REFERENCES dim_obec(id),
    cast_obce_id        INT           REFERENCES dim_cast_obce(id),
    agency_id           INT           REFERENCES dim_agency(id),
    date_id             INT           REFERENCES dim_date(date_id),
    price_asked_czk     BIGINT,
    price_asked_per_m2  NUMERIC(12,2),
    sub_category        VARCHAR(40),
    plot_area_m2        NUMERIC(12,2),
    gps_lat             NUMERIC(10,6),
    gps_lon             NUMERIC(10,6),
    is_active           BOOLEAN       NOT NULL DEFAULT TRUE,
    first_seen_date     DATE,
    sreality_url        VARCHAR(200),
    advert_images_count INT,
    has_floor_plan      BOOLEAN,
    has_video           BOOLEAN
);

CREATE TABLE IF NOT EXISTS fact_land_rent (
    id                  BIGSERIAL PRIMARY KEY,
    hash_id             BIGINT        NOT NULL,
    content_hash        BIGINT        NOT NULL,
    valid_from          DATE          NOT NULL,
    valid_to            DATE,
    obec_id             INT           REFERENCES dim_obec(id),
    cast_obce_id        INT           REFERENCES dim_cast_obce(id),
    agency_id           INT           REFERENCES dim_agency(id),
    date_id             INT           REFERENCES dim_date(date_id),
    price_monthly_czk   BIGINT,
    sub_category        VARCHAR(40),
    plot_area_m2        NUMERIC(12,2),
    gps_lat             NUMERIC(10,6),
    gps_lon             NUMERIC(10,6),
    is_active           BOOLEAN       NOT NULL DEFAULT TRUE,
    first_seen_date     DATE,
    sreality_url        VARCHAR(200),
    advert_images_count INT,
    has_floor_plan      BOOLEAN,
    has_video           BOOLEAN
);

CREATE TABLE IF NOT EXISTS fact_land_auction (
    id                     BIGSERIAL PRIMARY KEY,
    hash_id                BIGINT        NOT NULL,
    content_hash           BIGINT        NOT NULL,
    valid_from             DATE          NOT NULL,
    valid_to               DATE,
    obec_id                INT           REFERENCES dim_obec(id),
    cast_obce_id           INT           REFERENCES dim_cast_obce(id),
    agency_id              INT           REFERENCES dim_agency(id),
    date_id                INT           REFERENCES dim_date(date_id),
    price_starting_bid_czk BIGINT,
    sub_category           VARCHAR(40),
    plot_area_m2           NUMERIC(12,2),
    gps_lat                NUMERIC(10,6),
    gps_lon                NUMERIC(10,6),
    is_active              BOOLEAN       NOT NULL DEFAULT TRUE,
    first_seen_date        DATE,
    sreality_url           VARCHAR(200),
    advert_images_count    INT,
    has_floor_plan         BOOLEAN,
    has_video              BOOLEAN
);

-- ===========================================================================
-- COMMERCIAL
-- ===========================================================================

CREATE TABLE IF NOT EXISTS fact_commercial_sale (
    id                       BIGSERIAL PRIMARY KEY,
    hash_id                  BIGINT        NOT NULL,
    content_hash             BIGINT        NOT NULL,
    valid_from               DATE          NOT NULL,
    valid_to                 DATE,
    obec_id                  INT           REFERENCES dim_obec(id),
    cast_obce_id             INT           REFERENCES dim_cast_obce(id),
    agency_id                INT           REFERENCES dim_agency(id),
    date_id                  INT           REFERENCES dim_date(date_id),
    price_asked_czk          BIGINT,
    price_asked_per_m2       NUMERIC(12,2),
    usable_area_m2           NUMERIC(10,2),
    floor_area_m2            NUMERIC(10,2),
    building_condition_label VARCHAR(100),
    energy_rating_label      VARCHAR(100),
    has_elevator             BOOLEAN,
    has_parking              BOOLEAN,
    is_barrier_free          BOOLEAN,
    gps_lat                  NUMERIC(10,6),
    gps_lon                  NUMERIC(10,6),
    is_active                BOOLEAN       NOT NULL DEFAULT TRUE,
    first_seen_date          DATE,
    sreality_url             VARCHAR(200),
    advert_images_count      INT,
    has_floor_plan           BOOLEAN,
    has_video                BOOLEAN
);

CREATE TABLE IF NOT EXISTS fact_commercial_rent (
    id                       BIGSERIAL PRIMARY KEY,
    hash_id                  BIGINT        NOT NULL,
    content_hash             BIGINT        NOT NULL,
    valid_from               DATE          NOT NULL,
    valid_to                 DATE,
    obec_id                  INT           REFERENCES dim_obec(id),
    cast_obce_id             INT           REFERENCES dim_cast_obce(id),
    agency_id                INT           REFERENCES dim_agency(id),
    date_id                  INT           REFERENCES dim_date(date_id),
    price_monthly_czk        BIGINT,
    price_monthly_per_m2     NUMERIC(10,2),
    usable_area_m2           NUMERIC(10,2),
    floor_area_m2            NUMERIC(10,2),
    building_condition_label VARCHAR(100),
    energy_rating_label      VARCHAR(100),
    has_elevator             BOOLEAN,
    has_parking              BOOLEAN,
    is_barrier_free          BOOLEAN,
    gps_lat                  NUMERIC(10,6),
    gps_lon                  NUMERIC(10,6),
    is_active                BOOLEAN       NOT NULL DEFAULT TRUE,
    first_seen_date          DATE,
    sreality_url             VARCHAR(200),
    advert_images_count      INT,
    has_floor_plan           BOOLEAN,
    has_video                BOOLEAN
);

CREATE TABLE IF NOT EXISTS fact_commercial_auction (
    id                       BIGSERIAL PRIMARY KEY,
    hash_id                  BIGINT        NOT NULL,
    content_hash             BIGINT        NOT NULL,
    valid_from               DATE          NOT NULL,
    valid_to                 DATE,
    obec_id                  INT           REFERENCES dim_obec(id),
    cast_obce_id             INT           REFERENCES dim_cast_obce(id),
    agency_id                INT           REFERENCES dim_agency(id),
    date_id                  INT           REFERENCES dim_date(date_id),
    price_starting_bid_czk   BIGINT,
    usable_area_m2           NUMERIC(10,2),
    floor_area_m2            NUMERIC(10,2),
    building_condition_label VARCHAR(100),
    energy_rating_label      VARCHAR(100),
    has_elevator             BOOLEAN,
    has_parking              BOOLEAN,
    is_barrier_free          BOOLEAN,
    gps_lat                  NUMERIC(10,6),
    gps_lon                  NUMERIC(10,6),
    is_active                BOOLEAN       NOT NULL DEFAULT TRUE,
    first_seen_date          DATE,
    sreality_url             VARCHAR(200),
    advert_images_count      INT,
    has_floor_plan           BOOLEAN,
    has_video                BOOLEAN
);

-- ===========================================================================
-- OTHER
-- ===========================================================================

CREATE TABLE IF NOT EXISTS fact_other_sale (
    id                  BIGSERIAL PRIMARY KEY,
    hash_id             BIGINT        NOT NULL,
    content_hash        BIGINT        NOT NULL,
    valid_from          DATE          NOT NULL,
    valid_to            DATE,
    obec_id             INT           REFERENCES dim_obec(id),
    cast_obce_id        INT           REFERENCES dim_cast_obce(id),
    agency_id           INT           REFERENCES dim_agency(id),
    date_id             INT           REFERENCES dim_date(date_id),
    price_asked_czk     BIGINT,
    usable_area_m2      NUMERIC(10,2),
    plot_area_m2        NUMERIC(10,2),
    gps_lat             NUMERIC(10,6),
    gps_lon             NUMERIC(10,6),
    is_active           BOOLEAN       NOT NULL DEFAULT TRUE,
    first_seen_date     DATE,
    sreality_url        VARCHAR(200),
    advert_images_count INT,
    has_floor_plan      BOOLEAN,
    has_video           BOOLEAN
);

CREATE TABLE IF NOT EXISTS fact_other_rent (
    id                  BIGSERIAL PRIMARY KEY,
    hash_id             BIGINT        NOT NULL,
    content_hash        BIGINT        NOT NULL,
    valid_from          DATE          NOT NULL,
    valid_to            DATE,
    obec_id             INT           REFERENCES dim_obec(id),
    cast_obce_id        INT           REFERENCES dim_cast_obce(id),
    agency_id           INT           REFERENCES dim_agency(id),
    date_id             INT           REFERENCES dim_date(date_id),
    price_monthly_czk   BIGINT,
    usable_area_m2      NUMERIC(10,2),
    plot_area_m2        NUMERIC(10,2),
    gps_lat             NUMERIC(10,6),
    gps_lon             NUMERIC(10,6),
    is_active           BOOLEAN       NOT NULL DEFAULT TRUE,
    first_seen_date     DATE,
    sreality_url        VARCHAR(200),
    advert_images_count INT,
    has_floor_plan      BOOLEAN,
    has_video           BOOLEAN
);

-- ===========================================================================
-- Indexes on all 14 fact tables
-- ===========================================================================

CREATE INDEX IF NOT EXISTS idx_dim_obec_okres     ON dim_obec(okres_id);
CREATE INDEX IF NOT EXISTS idx_dim_obec_active    ON dim_obec(is_active);
CREATE INDEX IF NOT EXISTS idx_dim_cast_bbox      ON dim_cast_obce(bbox_min_lat, bbox_max_lat, bbox_min_lon, bbox_max_lon);
CREATE INDEX IF NOT EXISTS idx_dim_cast_obec      ON dim_cast_obce(obec_id);
CREATE INDEX IF NOT EXISTS idx_obec_stats_year    ON fact_obec_stats(year);

DO $$
DECLARE tbl TEXT;
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'fact_apartment_sale','fact_apartment_rent','fact_apartment_auction',
    'fact_house_sale','fact_house_rent','fact_house_auction',
    'fact_land_sale','fact_land_rent','fact_land_auction',
    'fact_commercial_sale','fact_commercial_rent','fact_commercial_auction',
    'fact_other_sale','fact_other_rent'
  ] LOOP
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_%s_hash    ON %I (hash_id)',        tbl, tbl);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_%s_current ON %I (hash_id) WHERE valid_to IS NULL', tbl, tbl);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_%s_obec    ON %I (obec_id)',         tbl, tbl);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_%s_date    ON %I (valid_from DESC)', tbl, tbl);
  END LOOP;
END;
$$;

-- ===========================================================================
-- Views
-- ===========================================================================

CREATE OR REPLACE VIEW v_apartment_sale_current AS
SELECT f.*,
       o.nazev_obce, r.nazev_okresu, k.nazev_kraje, c.nazev_cast_obce,
       s.unemployment_pct, s.population
FROM   fact_apartment_sale f
LEFT JOIN dim_obec      o ON o.id = f.obec_id
LEFT JOIN dim_okres     r ON r.id = o.okres_id
LEFT JOIN dim_kraj      k ON k.id = r.kraj_id
LEFT JOIN dim_cast_obce    c ON c.id = f.cast_obce_id
LEFT JOIN fact_obec_stats  s ON s.obec_id = f.obec_id
    AND s.year = (SELECT MAX(year) FROM fact_obec_stats WHERE obec_id = f.obec_id)
WHERE  f.valid_to IS NULL AND f.is_active = TRUE;

CREATE OR REPLACE VIEW v_price_history AS
SELECT 'apartment_sale' AS source_table, hash_id, valid_from, valid_to,
       price_asked_czk   AS price_czk, usable_area_m2, obec_id, is_active
FROM   fact_apartment_sale
UNION ALL
SELECT 'apartment_rent',  hash_id, valid_from, valid_to,
       price_monthly_czk, usable_area_m2, obec_id, is_active
FROM   fact_apartment_rent
UNION ALL
SELECT 'house_sale',      hash_id, valid_from, valid_to,
       price_asked_czk,   usable_area_m2, obec_id, is_active
FROM   fact_house_sale
UNION ALL
SELECT 'house_rent',      hash_id, valid_from, valid_to,
       price_monthly_czk, usable_area_m2, obec_id, is_active
FROM   fact_house_rent;

ENDSQL

echo "=== Postgres schema applied successfully ==="
