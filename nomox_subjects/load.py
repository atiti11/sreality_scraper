"""
Načte JSON výstup z mff_subjects_scraper.py do Postgresu.

Použití:
    pip install psycopg2-binary
    python mff_subjects_load.py \\
        --json ./mff_data/subjects_full_2025.json \\
        --pg "host=localhost port=5432 dbname=nomox user=nomox password=..." \\
        [--truncate]   # smaže existující data před loadem

Bez --truncate je načítání idempotentní: ON CONFLICT DO UPDATE.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import psycopg2
from psycopg2.extras import execute_values


UPSERT_SUBJECT_SQL = """
INSERT INTO mff_subject (
    kod, nazev, fakulta_kod, akademicky_rok, semestr, kredity, rozsah, jazyk,
    anotace, cile, sylabus, pozadavky, studijni_materialy, url, scraped_at
)
VALUES %s
ON CONFLICT (kod) DO UPDATE SET
    nazev               = EXCLUDED.nazev,
    fakulta_kod         = EXCLUDED.fakulta_kod,
    akademicky_rok      = EXCLUDED.akademicky_rok,
    semestr             = EXCLUDED.semestr,
    kredity             = EXCLUDED.kredity,
    rozsah              = EXCLUDED.rozsah,
    jazyk               = EXCLUDED.jazyk,
    anotace             = EXCLUDED.anotace,
    cile                = EXCLUDED.cile,
    sylabus             = EXCLUDED.sylabus,
    pozadavky           = EXCLUDED.pozadavky,
    studijni_materialy  = EXCLUDED.studijni_materialy,
    url                 = EXCLUDED.url,
    scraped_at          = EXCLUDED.scraped_at
"""


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", required=True, type=Path)
    ap.add_argument("--pg", required=True,
                    help="Postgres connection string, e.g. "
                         "'host=localhost port=5432 dbname=nomox user=... password=...'")
    ap.add_argument("--truncate", action="store_true",
                    help="Wipe mff_subject + mff_subject_lecturer before loading.")
    args = ap.parse_args()

    data = json.loads(args.json.read_text(encoding="utf-8"))
    if isinstance(data, dict):
        subjects = list(data.values())
    elif isinstance(data, list):
        subjects = data
    else:
        print(f"Unexpected JSON shape: {type(data)}", file=sys.stderr)
        return 1
    print(f"Loaded {len(subjects)} subjects from {args.json}")

    conn = psycopg2.connect(args.pg)
    conn.autocommit = False
    try:
        with conn.cursor() as cur:
            if args.truncate:
                print("TRUNCATE mff_subject_lecturer, mff_subject CASCADE …")
                cur.execute("TRUNCATE mff_subject_lecturer, mff_subject RESTART IDENTITY CASCADE;")

            # ----- mff_subject -----
            rows = []
            for s in subjects:
                rows.append((
                    s.get("kod"),
                    s.get("nazev") or "",
                    s.get("fakulta_kod") or "11320",
                    s.get("akademicky_rok") or "",
                    s.get("semestr"),
                    s.get("kredity"),
                    s.get("rozsah"),
                    s.get("jazyk"),
                    s.get("anotace"),
                    s.get("cile"),
                    s.get("sylabus"),
                    s.get("pozadavky"),
                    s.get("studijni_materialy"),
                    s.get("url"),
                    "now()",   # scraped_at handled by DEFAULT/EXCLUDED
                ))
            # Strip the literal 'now()' string and let DB use default
            stripped = [(r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7],
                         r[8], r[9], r[10], r[11], r[12], r[13], None) for r in rows]
            # We rebuild SQL to use DEFAULT for scraped_at when None
            insert_sql = """
INSERT INTO mff_subject (
    kod, nazev, fakulta_kod, akademicky_rok, semestr, kredity, rozsah, jazyk,
    anotace, cile, sylabus, pozadavky, studijni_materialy, url
)
VALUES %s
ON CONFLICT (kod) DO UPDATE SET
    nazev               = EXCLUDED.nazev,
    fakulta_kod         = EXCLUDED.fakulta_kod,
    akademicky_rok      = EXCLUDED.akademicky_rok,
    semestr             = EXCLUDED.semestr,
    kredity             = EXCLUDED.kredity,
    rozsah              = EXCLUDED.rozsah,
    jazyk               = EXCLUDED.jazyk,
    anotace             = EXCLUDED.anotace,
    cile                = EXCLUDED.cile,
    sylabus             = EXCLUDED.sylabus,
    pozadavky           = EXCLUDED.pozadavky,
    studijni_materialy  = EXCLUDED.studijni_materialy,
    url                 = EXCLUDED.url,
    scraped_at          = now()
"""
            tuples_for_db = [t[:14] for t in stripped]   # drop the trailing None
            execute_values(cur, insert_sql, tuples_for_db, page_size=200)
            print(f"Upserted {len(tuples_for_db)} rows into mff_subject")

            # ----- mff_subject_lecturer -----
            lecturer_rows = []
            for s in subjects:
                kod = s.get("kod")
                for lec in s.get("lecturers", []) or []:
                    lecturer_rows.append((kod, lec.get("jmeno"), lec.get("role") or "jiny"))
            if lecturer_rows:
                # Idempotent — UNIQUE constraint blocks duplicates, but
                # ON CONFLICT DO NOTHING is cleaner than catching IntegrityError.
                lec_sql = """
INSERT INTO mff_subject_lecturer (kod, jmeno, role)
VALUES %s
ON CONFLICT (kod, jmeno, role) DO NOTHING
"""
                execute_values(cur, lec_sql, lecturer_rows, page_size=500)
                print(f"Upserted {len(lecturer_rows)} rows into mff_subject_lecturer")

        conn.commit()
        print("Commit OK.")
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
