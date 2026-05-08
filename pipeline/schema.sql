-- =============================================================================
-- Sreality Pipeline — Master Schema
-- =============================================================================
-- Run once on a fresh database.
-- Safe to re-run: all statements use IF NOT EXISTS / ADD COLUMN IF NOT EXISTS.
--
-- Table naming:
--   dim_*             dimension tables (geography, agency, date)
--   fact_*            fact tables (estate snapshots, obec stats)
--   estate_*          estate-level side tables (detail text, field changes)
--   obec_successor    municipality merge/split mapping
--   ruian_metadata    RUIAN snapshot freshness tracking
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS postgis;

-- ---------------------------------------------------------------------------
-- Geography dimensions (Kraj → Okres → Obec → CastObce)
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS dim_kraj (
    id           SERIAL PRIMARY KEY,
    kod_kraje    VARCHAR(20)  NOT NULL UNIQUE,
    nazev_kraje  VARCHAR(100) NOT NULL
);

CREATE TABLE IF NOT EXISTS dim_okres (
    id            SERIAL PRIMARY KEY,
    kod_okresu    VARCHAR(20)  NOT NULL UNIQUE,
    nazev_okresu  VARCHAR(100) NOT NULL,
    kraj_id       INT          NOT NULL REFERENCES dim_kraj(id)
);

CREATE TABLE IF NOT EXISTS dim_obec (
    id           SERIAL PRIMARY KEY,
    kod_obce     VARCHAR(20)  NOT NULL UNIQUE,
    nazev_obce   VARCHAR(100) NOT NULL,
    okres_id     INT          NOT NULL REFERENCES dim_okres(id),
    -- NULL for municipalities that have been merged/abolished.
    -- They stay in this table permanently so historical fact rows keep their FK.
    is_active    BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS dim_cast_obce (
    id                SERIAL PRIMARY KEY,
    kod_cast_obce     VARCHAR(20)  NOT NULL UNIQUE,
    nazev_cast_obce   VARCHAR(100) NOT NULL,
    obec_id           INT          NOT NULL REFERENCES dim_obec(id),
    -- Bounding box in WGS84 (envelope of polygon, kept for human queries)
    bbox_min_lat      NUMERIC(10,6),
    bbox_min_lon      NUMERIC(10,6),
    bbox_max_lat      NUMERIC(10,6),
    bbox_max_lon      NUMERIC(10,6),
    -- Centroid (DefinicniBod from RUIAN)
    centroid_lat      NUMERIC(10,6),
    centroid_lon      NUMERIC(10,6),
    -- Authoritative boundary polygon (RUIAN <gml:MultiSurface> reprojected to WGS84).
    -- This is what SpatialJoiner uses via ST_Contains.
    geom              geometry(MultiPolygon, 4326)
);

-- Municipality succession: when obec A is merged into obec B,
-- insert (old_obec_kod=A, new_obec_kod=B, merged_year=YYYY).
-- Source: CSU OD_KAM sheet, loaded once during initial CSU load.
CREATE TABLE IF NOT EXISTS obec_successor (
    old_obec_kod  VARCHAR(20) PRIMARY KEY,
    new_obec_kod  VARCHAR(20) NOT NULL REFERENCES dim_obec(kod_obce),
    merged_year   INT         NOT NULL
);

-- ---------------------------------------------------------------------------
-- Agency dimension
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS dim_agency (
    id           SERIAL PRIMARY KEY,
    sreality_id  INT          NOT NULL UNIQUE,
    name         VARCHAR(200),
    url          VARCHAR(500)
);

-- ---------------------------------------------------------------------------
-- Date dimension
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS dim_date (
    date_id      INT         PRIMARY KEY,   -- YYYYMMDD integer key
    full_date    DATE        NOT NULL,
    year         INT         NOT NULL,
    quarter      INT         NOT NULL,
    month        INT         NOT NULL,
    month_name   VARCHAR(20) NOT NULL,
    week         INT         NOT NULL,
    day_of_week  INT         NOT NULL,      -- 1=Monday … 7=Sunday
    is_weekend   BOOLEAN     NOT NULL
);

-- ---------------------------------------------------------------------------
-- CSU statistics — one row per (obec, year)
-- Includes both current and historically-extinct municipalities.
-- Extinct municipalities keep their own obec_id; use obec_successor at
-- query time to roll them up to current boundaries.
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS fact_obec_stats (
    id                 SERIAL PRIMARY KEY,
    obec_id            INT  NOT NULL REFERENCES dim_obec(id),
    year               INT  NOT NULL,
    population         INT,
    births             INT,
    deaths             INT,
    migration_balance  INT,
    marriages          INT,
    divorces           INT,
    unemployment_pct   NUMERIC(5,2),
    UNIQUE (obec_id, year)
);

-- ---------------------------------------------------------------------------
-- RUIAN metadata — single-row freshness tracker
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS ruian_metadata (
    id                INT  PRIMARY KEY DEFAULT 1,
    snapshot_date     DATE NOT NULL,
    loaded_at         TIMESTAMP NOT NULL DEFAULT now(),
    cast_obce_count   INT,
    CONSTRAINT ruian_metadata_single_row CHECK (id = 1)
);

-- ---------------------------------------------------------------------------
-- Estate side tables (shared across all fact tables)
-- ---------------------------------------------------------------------------

-- Latest text/description per estate. Overwritten on every enrichment — not versioned.
CREATE TABLE IF NOT EXISTS estate_detail (
    hash_id        BIGINT       PRIMARY KEY,
    description    TEXT,
    locality_full  VARCHAR(300),
    scraped_at     TIMESTAMP    NOT NULL DEFAULT now()
);

-- One row per field that changed value between two consecutive snapshots.
-- table_name identifies which of the 14 fact tables the estate lives in.
-- field_name is the Postgres column name, e.g. 'price_asked_czk'.
CREATE TABLE IF NOT EXISTS estate_field_changes (
    id          BIGSERIAL    PRIMARY KEY,
    hash_id     BIGINT       NOT NULL,
    table_name  VARCHAR(50)  NOT NULL,
    changed_at  DATE         NOT NULL,
    field_name  VARCHAR(60)  NOT NULL,
    old_value   TEXT,
    new_value   TEXT
);

CREATE INDEX IF NOT EXISTS idx_efc_hash_id    ON estate_field_changes (hash_id);
CREATE INDEX IF NOT EXISTS idx_efc_field_name ON estate_field_changes (field_name);
CREATE INDEX IF NOT EXISTS idx_efc_table_name ON estate_field_changes (table_name);

-- ---------------------------------------------------------------------------
-- Shared columns macro (documented here, applied per table below)
--
-- Every fact table has:
--   id              BIGSERIAL PK
--   hash_id         BIGINT NOT NULL          — sreality estate identifier
--   content_hash    BIGINT NOT NULL          — hash of all tracked fields; change detection
--   valid_from      DATE NOT NULL            — SCD Type 2 open date
--   valid_to        DATE                     — SCD Type 2 close date (NULL = current row)
--   obec_id         INT NOT NULL → dim_obec
--   cast_obce_id    INT          → dim_cast_obce  (nullable: spatial join may miss)
--   agency_id       INT          → dim_agency      (nullable: private sellers have no agency)
--   date_id         INT          → dim_date
--   gps_lat/lon     NUMERIC(10,6)
--   is_active       BOOLEAN NOT NULL DEFAULT TRUE
--   first_seen_date DATE
--   sreality_url    VARCHAR(200)
--   advert_images_count INT
--   has_floor_plan  BOOLEAN
--   has_video       BOOLEAN
-- ---------------------------------------------------------------------------

-- ===========================================================================
-- APARTMENTS  (property_type = 'Apartment', category_main_cb = 1)
-- ===========================================================================

CREATE TABLE IF NOT EXISTS fact_apartment_sale (
    id                       BIGSERIAL PRIMARY KEY,
    hash_id                  BIGINT       NOT NULL,
    content_hash             BIGINT       NOT NULL,
    valid_from               DATE         NOT NULL,
    valid_to                 DATE,
    obec_id                  INT          NOT NULL REFERENCES dim_obec(id),
    cast_obce_id             INT          REFERENCES dim_cast_obce(id),
    agency_id                INT          REFERENCES dim_agency(id),
    date_id                  INT          REFERENCES dim_date(date_id),
    -- price
    price_asked_czk          BIGINT,
    price_asked_per_m2       NUMERIC(12,2),
    -- apartment specifics
    sub_category             VARCHAR(20),          -- 1+kk, 2+1, etc.
    usable_area_m2           NUMERIC(10,2),
    floor_number             INT,
    total_floors             INT,
    ownership_label          VARCHAR(50),
    building_type_label      VARCHAR(50),
    building_condition_label VARCHAR(100),
    energy_rating_label      VARCHAR(50),
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
    -- common
    gps_lat                  NUMERIC(10,6),
    gps_lon                  NUMERIC(10,6),
    is_active                BOOLEAN      NOT NULL DEFAULT TRUE,
    first_seen_date          DATE,
    sreality_url             VARCHAR(200),
    advert_images_count      INT,
    has_floor_plan           BOOLEAN,
    has_video                BOOLEAN
);

CREATE TABLE IF NOT EXISTS fact_apartment_rent (
    id                       BIGSERIAL PRIMARY KEY,
    hash_id                  BIGINT       NOT NULL,
    content_hash             BIGINT       NOT NULL,
    valid_from               DATE         NOT NULL,
    valid_to                 DATE,
    obec_id                  INT          NOT NULL REFERENCES dim_obec(id),
    cast_obce_id             INT          REFERENCES dim_cast_obce(id),
    agency_id                INT          REFERENCES dim_agency(id),
    date_id                  INT          REFERENCES dim_date(date_id),
    -- price
    price_monthly_czk        BIGINT,
    price_monthly_per_m2     NUMERIC(10,2),
    -- apartment specifics
    sub_category             VARCHAR(20),
    usable_area_m2           NUMERIC(10,2),
    floor_number             INT,
    total_floors             INT,
    ownership_label          VARCHAR(50),
    building_type_label      VARCHAR(50),
    building_condition_label VARCHAR(100),
    energy_rating_label      VARCHAR(50),
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
    -- common
    gps_lat                  NUMERIC(10,6),
    gps_lon                  NUMERIC(10,6),
    is_active                BOOLEAN      NOT NULL DEFAULT TRUE,
    first_seen_date          DATE,
    sreality_url             VARCHAR(200),
    advert_images_count      INT,
    has_floor_plan           BOOLEAN,
    has_video                BOOLEAN
);

CREATE TABLE IF NOT EXISTS fact_apartment_auction (
    id                       BIGSERIAL PRIMARY KEY,
    hash_id                  BIGINT       NOT NULL,
    content_hash             BIGINT       NOT NULL,
    valid_from               DATE         NOT NULL,
    valid_to                 DATE,
    obec_id                  INT          NOT NULL REFERENCES dim_obec(id),
    cast_obce_id             INT          REFERENCES dim_cast_obce(id),
    agency_id                INT          REFERENCES dim_agency(id),
    date_id                  INT          REFERENCES dim_date(date_id),
    -- price
    price_starting_bid_czk   BIGINT,
    -- apartment specifics
    sub_category             VARCHAR(20),
    usable_area_m2           NUMERIC(10,2),
    floor_number             INT,
    total_floors             INT,
    ownership_label          VARCHAR(50),
    building_type_label      VARCHAR(50),
    building_condition_label VARCHAR(100),
    energy_rating_label      VARCHAR(50),
    is_new_building          BOOLEAN,
    has_balcony              BOOLEAN,
    has_terrace              BOOLEAN,
    has_loggia               BOOLEAN,
    has_cellar               BOOLEAN,
    has_elevator             BOOLEAN,
    has_parking              BOOLEAN,
    has_garage               BOOLEAN,
    is_barrier_free          BOOLEAN,
    -- common
    gps_lat                  NUMERIC(10,6),
    gps_lon                  NUMERIC(10,6),
    is_active                BOOLEAN      NOT NULL DEFAULT TRUE,
    first_seen_date          DATE,
    sreality_url             VARCHAR(200),
    advert_images_count      INT,
    has_floor_plan           BOOLEAN,
    has_video                BOOLEAN
);

-- ===========================================================================
-- HOUSES  (property_type = 'House', category_main_cb = 2)
-- ===========================================================================

CREATE TABLE IF NOT EXISTS fact_house_sale (
    id                       BIGSERIAL PRIMARY KEY,
    hash_id                  BIGINT       NOT NULL,
    content_hash             BIGINT       NOT NULL,
    valid_from               DATE         NOT NULL,
    valid_to                 DATE,
    obec_id                  INT          NOT NULL REFERENCES dim_obec(id),
    cast_obce_id             INT          REFERENCES dim_cast_obce(id),
    agency_id                INT          REFERENCES dim_agency(id),
    date_id                  INT          REFERENCES dim_date(date_id),
    -- price
    price_asked_czk          BIGINT,
    price_asked_per_m2       NUMERIC(12,2),
    -- house specifics
    usable_area_m2           NUMERIC(10,2),
    plot_area_m2             NUMERIC(10,2),
    garden_area_m2           NUMERIC(10,2),
    total_floors             INT,
    building_type_label      VARCHAR(50),
    building_condition_label VARCHAR(100),
    energy_rating_label      VARCHAR(50),
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
    -- common
    gps_lat                  NUMERIC(10,6),
    gps_lon                  NUMERIC(10,6),
    is_active                BOOLEAN      NOT NULL DEFAULT TRUE,
    first_seen_date          DATE,
    sreality_url             VARCHAR(200),
    advert_images_count      INT,
    has_floor_plan           BOOLEAN,
    has_video                BOOLEAN
);

CREATE TABLE IF NOT EXISTS fact_house_rent (
    id                       BIGSERIAL PRIMARY KEY,
    hash_id                  BIGINT       NOT NULL,
    content_hash             BIGINT       NOT NULL,
    valid_from               DATE         NOT NULL,
    valid_to                 DATE,
    obec_id                  INT          NOT NULL REFERENCES dim_obec(id),
    cast_obce_id             INT          REFERENCES dim_cast_obce(id),
    agency_id                INT          REFERENCES dim_agency(id),
    date_id                  INT          REFERENCES dim_date(date_id),
    -- price
    price_monthly_czk        BIGINT,
    price_monthly_per_m2     NUMERIC(10,2),
    -- house specifics
    usable_area_m2           NUMERIC(10,2),
    plot_area_m2             NUMERIC(10,2),
    garden_area_m2           NUMERIC(10,2),
    total_floors             INT,
    building_type_label      VARCHAR(50),
    building_condition_label VARCHAR(100),
    energy_rating_label      VARCHAR(50),
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
    -- common
    gps_lat                  NUMERIC(10,6),
    gps_lon                  NUMERIC(10,6),
    is_active                BOOLEAN      NOT NULL DEFAULT TRUE,
    first_seen_date          DATE,
    sreality_url             VARCHAR(200),
    advert_images_count      INT,
    has_floor_plan           BOOLEAN,
    has_video                BOOLEAN
);

CREATE TABLE IF NOT EXISTS fact_house_auction (
    id                       BIGSERIAL PRIMARY KEY,
    hash_id                  BIGINT       NOT NULL,
    content_hash             BIGINT       NOT NULL,
    valid_from               DATE         NOT NULL,
    valid_to                 DATE,
    obec_id                  INT          NOT NULL REFERENCES dim_obec(id),
    cast_obce_id             INT          REFERENCES dim_cast_obce(id),
    agency_id                INT          REFERENCES dim_agency(id),
    date_id                  INT          REFERENCES dim_date(date_id),
    -- price
    price_starting_bid_czk   BIGINT,
    -- house specifics
    usable_area_m2           NUMERIC(10,2),
    plot_area_m2             NUMERIC(10,2),
    garden_area_m2           NUMERIC(10,2),
    total_floors             INT,
    building_type_label      VARCHAR(50),
    building_condition_label VARCHAR(100),
    energy_rating_label      VARCHAR(50),
    is_new_building          BOOLEAN,
    is_low_energy            BOOLEAN,
    has_terrace              BOOLEAN,
    has_balcony              BOOLEAN,
    has_cellar               BOOLEAN,
    has_garage               BOOLEAN,
    has_parking              BOOLEAN,
    has_pool                 BOOLEAN,
    is_barrier_free          BOOLEAN,
    -- common
    gps_lat                  NUMERIC(10,6),
    gps_lon                  NUMERIC(10,6),
    is_active                BOOLEAN      NOT NULL DEFAULT TRUE,
    first_seen_date          DATE,
    sreality_url             VARCHAR(200),
    advert_images_count      INT,
    has_floor_plan           BOOLEAN,
    has_video                BOOLEAN
);

-- ===========================================================================
-- LAND  (property_type = 'Land', category_main_cb = 3)
-- Very sparse — only the fields Sreality reliably provides for land listings.
-- ===========================================================================

CREATE TABLE IF NOT EXISTS fact_land_sale (
    id                  BIGSERIAL PRIMARY KEY,
    hash_id             BIGINT       NOT NULL,
    content_hash        BIGINT       NOT NULL,
    valid_from          DATE         NOT NULL,
    valid_to            DATE,
    obec_id             INT          NOT NULL REFERENCES dim_obec(id),
    cast_obce_id        INT          REFERENCES dim_cast_obce(id),
    agency_id           INT          REFERENCES dim_agency(id),
    date_id             INT          REFERENCES dim_date(date_id),
    -- price
    price_asked_czk     BIGINT,
    price_asked_per_m2  NUMERIC(12,2),
    -- land specifics
    sub_category        VARCHAR(40),   -- Residential, Field, Forest, Orchard, etc.
    plot_area_m2        NUMERIC(12,2),
    -- common
    gps_lat             NUMERIC(10,6),
    gps_lon             NUMERIC(10,6),
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,
    first_seen_date     DATE,
    sreality_url        VARCHAR(200),
    advert_images_count INT,
    has_floor_plan      BOOLEAN,
    has_video           BOOLEAN
);

CREATE TABLE IF NOT EXISTS fact_land_rent (
    id                  BIGSERIAL PRIMARY KEY,
    hash_id             BIGINT       NOT NULL,
    content_hash        BIGINT       NOT NULL,
    valid_from          DATE         NOT NULL,
    valid_to            DATE,
    obec_id             INT          NOT NULL REFERENCES dim_obec(id),
    cast_obce_id        INT          REFERENCES dim_cast_obce(id),
    agency_id           INT          REFERENCES dim_agency(id),
    date_id             INT          REFERENCES dim_date(date_id),
    -- price
    price_monthly_czk   BIGINT,
    -- land specifics
    sub_category        VARCHAR(40),
    plot_area_m2        NUMERIC(12,2),
    -- common
    gps_lat             NUMERIC(10,6),
    gps_lon             NUMERIC(10,6),
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,
    first_seen_date     DATE,
    sreality_url        VARCHAR(200),
    advert_images_count INT,
    has_floor_plan      BOOLEAN,
    has_video           BOOLEAN
);

CREATE TABLE IF NOT EXISTS fact_land_auction (
    id                      BIGSERIAL PRIMARY KEY,
    hash_id                 BIGINT       NOT NULL,
    content_hash            BIGINT       NOT NULL,
    valid_from              DATE         NOT NULL,
    valid_to                DATE,
    obec_id                 INT          NOT NULL REFERENCES dim_obec(id),
    cast_obce_id            INT          REFERENCES dim_cast_obce(id),
    agency_id               INT          REFERENCES dim_agency(id),
    date_id                 INT          REFERENCES dim_date(date_id),
    -- price
    price_starting_bid_czk  BIGINT,
    -- land specifics
    sub_category            VARCHAR(40),
    plot_area_m2            NUMERIC(12,2),
    -- common
    gps_lat                 NUMERIC(10,6),
    gps_lon                 NUMERIC(10,6),
    is_active               BOOLEAN      NOT NULL DEFAULT TRUE,
    first_seen_date         DATE,
    sreality_url            VARCHAR(200),
    advert_images_count     INT,
    has_floor_plan          BOOLEAN,
    has_video               BOOLEAN
);

-- ===========================================================================
-- COMMERCIAL  (property_type = 'Commercial', category_main_cb = 4)
-- ===========================================================================

CREATE TABLE IF NOT EXISTS fact_commercial_sale (
    id                       BIGSERIAL PRIMARY KEY,
    hash_id                  BIGINT       NOT NULL,
    content_hash             BIGINT       NOT NULL,
    valid_from               DATE         NOT NULL,
    valid_to                 DATE,
    obec_id                  INT          NOT NULL REFERENCES dim_obec(id),
    cast_obce_id             INT          REFERENCES dim_cast_obce(id),
    agency_id                INT          REFERENCES dim_agency(id),
    date_id                  INT          REFERENCES dim_date(date_id),
    -- price
    price_asked_czk          BIGINT,
    price_asked_per_m2       NUMERIC(12,2),
    -- commercial specifics
    usable_area_m2           NUMERIC(10,2),
    floor_area_m2            NUMERIC(10,2),
    building_condition_label VARCHAR(100),
    energy_rating_label      VARCHAR(50),
    has_elevator             BOOLEAN,
    has_parking              BOOLEAN,
    is_barrier_free          BOOLEAN,
    -- common
    gps_lat                  NUMERIC(10,6),
    gps_lon                  NUMERIC(10,6),
    is_active                BOOLEAN      NOT NULL DEFAULT TRUE,
    first_seen_date          DATE,
    sreality_url             VARCHAR(200),
    advert_images_count      INT,
    has_floor_plan           BOOLEAN,
    has_video                BOOLEAN
);

CREATE TABLE IF NOT EXISTS fact_commercial_rent (
    id                       BIGSERIAL PRIMARY KEY,
    hash_id                  BIGINT       NOT NULL,
    content_hash             BIGINT       NOT NULL,
    valid_from               DATE         NOT NULL,
    valid_to                 DATE,
    obec_id                  INT          NOT NULL REFERENCES dim_obec(id),
    cast_obce_id             INT          REFERENCES dim_cast_obce(id),
    agency_id                INT          REFERENCES dim_agency(id),
    date_id                  INT          REFERENCES dim_date(date_id),
    -- price
    price_monthly_czk        BIGINT,
    price_monthly_per_m2     NUMERIC(10,2),
    -- commercial specifics
    usable_area_m2           NUMERIC(10,2),
    floor_area_m2            NUMERIC(10,2),
    building_condition_label VARCHAR(100),
    energy_rating_label      VARCHAR(50),
    has_elevator             BOOLEAN,
    has_parking              BOOLEAN,
    is_barrier_free          BOOLEAN,
    -- common
    gps_lat                  NUMERIC(10,6),
    gps_lon                  NUMERIC(10,6),
    is_active                BOOLEAN      NOT NULL DEFAULT TRUE,
    first_seen_date          DATE,
    sreality_url             VARCHAR(200),
    advert_images_count      INT,
    has_floor_plan           BOOLEAN,
    has_video                BOOLEAN
);

CREATE TABLE IF NOT EXISTS fact_commercial_auction (
    id                       BIGSERIAL PRIMARY KEY,
    hash_id                  BIGINT       NOT NULL,
    content_hash             BIGINT       NOT NULL,
    valid_from               DATE         NOT NULL,
    valid_to                 DATE,
    obec_id                  INT          NOT NULL REFERENCES dim_obec(id),
    cast_obce_id             INT          REFERENCES dim_cast_obce(id),
    agency_id                INT          REFERENCES dim_agency(id),
    date_id                  INT          REFERENCES dim_date(date_id),
    -- price
    price_starting_bid_czk   BIGINT,
    -- commercial specifics
    usable_area_m2           NUMERIC(10,2),
    floor_area_m2            NUMERIC(10,2),
    building_condition_label VARCHAR(100),
    energy_rating_label      VARCHAR(50),
    has_elevator             BOOLEAN,
    has_parking              BOOLEAN,
    is_barrier_free          BOOLEAN,
    -- common
    gps_lat                  NUMERIC(10,6),
    gps_lon                  NUMERIC(10,6),
    is_active                BOOLEAN      NOT NULL DEFAULT TRUE,
    first_seen_date          DATE,
    sreality_url             VARCHAR(200),
    advert_images_count      INT,
    has_floor_plan           BOOLEAN,
    has_video                BOOLEAN
);

-- ===========================================================================
-- OTHER  (property_type = 'Other', category_main_cb = 5)
-- Catch-all. Minimal columns — Sreality data is too inconsistent here.
-- ===========================================================================

CREATE TABLE IF NOT EXISTS fact_other_sale (
    id                  BIGSERIAL PRIMARY KEY,
    hash_id             BIGINT       NOT NULL,
    content_hash        BIGINT       NOT NULL,
    valid_from          DATE         NOT NULL,
    valid_to            DATE,
    obec_id             INT          NOT NULL REFERENCES dim_obec(id),
    cast_obce_id        INT          REFERENCES dim_cast_obce(id),
    agency_id           INT          REFERENCES dim_agency(id),
    date_id             INT          REFERENCES dim_date(date_id),
    -- price
    price_asked_czk     BIGINT,
    -- other specifics
    usable_area_m2      NUMERIC(10,2),
    plot_area_m2        NUMERIC(10,2),
    -- common
    gps_lat             NUMERIC(10,6),
    gps_lon             NUMERIC(10,6),
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,
    first_seen_date     DATE,
    sreality_url        VARCHAR(200),
    advert_images_count INT,
    has_floor_plan      BOOLEAN,
    has_video           BOOLEAN
);

CREATE TABLE IF NOT EXISTS fact_other_rent (
    id                  BIGSERIAL PRIMARY KEY,
    hash_id             BIGINT       NOT NULL,
    content_hash        BIGINT       NOT NULL,
    valid_from          DATE         NOT NULL,
    valid_to            DATE,
    obec_id             INT          NOT NULL REFERENCES dim_obec(id),
    cast_obce_id        INT          REFERENCES dim_cast_obce(id),
    agency_id           INT          REFERENCES dim_agency(id),
    date_id             INT          REFERENCES dim_date(date_id),
    -- price
    price_monthly_czk   BIGINT,
    -- other specifics
    usable_area_m2      NUMERIC(10,2),
    plot_area_m2        NUMERIC(10,2),
    -- common
    gps_lat             NUMERIC(10,6),
    gps_lon             NUMERIC(10,6),
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,
    first_seen_date     DATE,
    sreality_url        VARCHAR(200),
    advert_images_count INT,
    has_floor_plan      BOOLEAN,
    has_video           BOOLEAN
);

-- ===========================================================================
-- Indexes — created after all tables so failures don't block table creation
-- ===========================================================================

-- Geography lookups
CREATE INDEX IF NOT EXISTS idx_dim_obec_okres       ON dim_obec(okres_id);
CREATE INDEX IF NOT EXISTS idx_dim_obec_active      ON dim_obec(is_active);
CREATE INDEX IF NOT EXISTS idx_dim_cast_obec_bbox   ON dim_cast_obce(bbox_min_lat, bbox_max_lat, bbox_min_lon, bbox_max_lon);
CREATE INDEX IF NOT EXISTS idx_dim_cast_obec_obec   ON dim_cast_obce(obec_id);
CREATE INDEX IF NOT EXISTS idx_dim_cast_obec_geom   ON dim_cast_obce USING GIST (geom);

-- CSU stats
CREATE INDEX IF NOT EXISTS idx_fact_obec_stats_year ON fact_obec_stats(year);

-- All fact tables: hash_id (for change detection lookup) and valid_to (current row filter)
DO $$
DECLARE
    tbl TEXT;
BEGIN
    FOREACH tbl IN ARRAY ARRAY[
        'fact_apartment_sale', 'fact_apartment_rent', 'fact_apartment_auction',
        'fact_house_sale',     'fact_house_rent',     'fact_house_auction',
        'fact_land_sale',      'fact_land_rent',      'fact_land_auction',
        'fact_commercial_sale','fact_commercial_rent', 'fact_commercial_auction',
        'fact_other_sale',     'fact_other_rent'
    ]
    LOOP
        EXECUTE format('CREATE INDEX IF NOT EXISTS idx_%s_hash    ON %I (hash_id)',         tbl, tbl);
        EXECUTE format('CREATE INDEX IF NOT EXISTS idx_%s_current ON %I (hash_id) WHERE valid_to IS NULL', tbl, tbl);
        EXECUTE format('CREATE INDEX IF NOT EXISTS idx_%s_obec    ON %I (obec_id)',          tbl, tbl);
        EXECUTE format('CREATE INDEX IF NOT EXISTS idx_%s_date    ON %I (valid_from DESC)',  tbl, tbl);
    END LOOP;
END;
$$;

-- ===========================================================================
-- Useful views
-- ===========================================================================

-- Current active apartment sales (most common query target)
CREATE OR REPLACE VIEW v_apartment_sale_current AS
SELECT f.*, o.nazev_obce, k.nazev_kraje, c.nazev_cast_obce,
       s.unemployment_pct, s.population
FROM   fact_apartment_sale f
JOIN   dim_obec      o ON o.id = f.obec_id
JOIN   dim_okres     r ON r.id = o.okres_id
JOIN   dim_kraj      k ON k.id = r.kraj_id
LEFT JOIN dim_cast_obce c ON c.id = f.cast_obce_id
LEFT JOIN fact_obec_stats s ON s.obec_id = f.obec_id
    AND s.year = (SELECT MAX(year) FROM fact_obec_stats WHERE obec_id = f.obec_id)
WHERE  f.valid_to IS NULL AND f.is_active = TRUE;

-- Price history for any estate (use WHERE hash_id = ? to filter)
CREATE OR REPLACE VIEW v_price_history AS
SELECT 'apartment_sale'  AS source_table, hash_id, valid_from, valid_to,
       price_asked_czk   AS price, usable_area_m2, obec_id
FROM   fact_apartment_sale
UNION ALL
SELECT 'apartment_rent',  hash_id, valid_from, valid_to,
       price_monthly_czk, usable_area_m2, obec_id
FROM   fact_apartment_rent
UNION ALL
SELECT 'house_sale',      hash_id, valid_from, valid_to,
       price_asked_czk,   usable_area_m2, obec_id
FROM   fact_house_sale
UNION ALL
SELECT 'house_rent',      hash_id, valid_from, valid_to,
       price_monthly_czk, usable_area_m2, obec_id
FROM   fact_house_rent;
