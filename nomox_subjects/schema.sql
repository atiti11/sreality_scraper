-- =============================================================================
-- MFF UK predmety — Postgres schema
-- =============================================================================
-- Run once on the target database:
--   psql -U sreality -d nomox -f mff_subjects_schema.sql
-- (or whichever db you connect to nomox)
-- =============================================================================

CREATE TABLE IF NOT EXISTS mff_subject (
    kod                 VARCHAR(20)  PRIMARY KEY,
    nazev               TEXT         NOT NULL,
    fakulta_kod         VARCHAR(20)  NOT NULL DEFAULT '11320',
    akademicky_rok      VARCHAR(10)  NOT NULL,
    semestr             VARCHAR(20),         -- 'zimni' / 'letni' / 'oba' / null
    kredity             INT,
    rozsah              VARCHAR(50),         -- e.g. '2/2 Zk+Z', '4/0 Zk'
    jazyk               VARCHAR(10),         -- 'cs' / 'en'
    anotace             TEXT,
    cile                TEXT,
    sylabus             TEXT,
    pozadavky           TEXT,
    studijni_materialy  TEXT,
    url                 TEXT,
    scraped_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    raw_html            TEXT                  -- optional: keep original HTML for re-parse
);

CREATE INDEX IF NOT EXISTS idx_mff_subject_year_sem ON mff_subject(akademicky_rok, semestr);
CREATE INDEX IF NOT EXISTS idx_mff_subject_nazev    ON mff_subject USING GIN (to_tsvector('simple', nazev));
CREATE INDEX IF NOT EXISTS idx_mff_subject_anotace  ON mff_subject USING GIN (to_tsvector('simple', coalesce(anotace,'')));
CREATE INDEX IF NOT EXISTS idx_mff_subject_sylabus  ON mff_subject USING GIN (to_tsvector('simple', coalesce(sylabus,'')));

CREATE TABLE IF NOT EXISTS mff_subject_lecturer (
    id           BIGSERIAL    PRIMARY KEY,
    kod          VARCHAR(20)  NOT NULL REFERENCES mff_subject(kod) ON DELETE CASCADE,
    jmeno        TEXT         NOT NULL,
    role         VARCHAR(40),         -- 'garant' / 'prednasejici' / 'cviciaci' / 'jiny'
    UNIQUE (kod, jmeno, role)
);

CREATE INDEX IF NOT EXISTS idx_mff_lecturer_kod   ON mff_subject_lecturer(kod);
CREATE INDEX IF NOT EXISTS idx_mff_lecturer_jmeno ON mff_subject_lecturer(jmeno);
