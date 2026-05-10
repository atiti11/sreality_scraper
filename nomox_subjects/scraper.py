"""
Scrape předmětů MFF UK ze SIS UK (https://is.cuni.cz/studium/predmety/).

Selektory aktualizované 2026-05-10 podle reálné struktury HTML — viz
parse_list_page() pro detaily o pořadí buněk.

Dvoufázový pipeline (idempotentní, resumable přes JSON checkpointy):
    1. List      — paginovaná search page → kódy + základní metadata
    2. Detail    — per-předmět page → anotace, sylabus, vyučující

Použití:
    pip install requests beautifulsoup4 lxml tqdm
    python scraper.py --out-dir ./data
    python scraper.py --phase list --pocet 1000          # jen list, jedna stránka
    python scraper.py --phase detail --limit 5           # jen prvních 5 detailů

POZN.: SIS modul "Předměty" nemá v search formuláři pole pro akademický rok —
zobrazuje aktuální rok podle ÚVT konfigurace. Pro starší roky by bylo třeba
buď přepínat session, nebo scrapovat Karolinka PDF.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import time
from dataclasses import dataclass, asdict, field
from pathlib import Path
from typing import Optional
from urllib.parse import urljoin

import requests
from bs4 import BeautifulSoup
from tqdm import tqdm


SIS_BASE  = "https://is.cuni.cz/studium/predmety/index.php"
FAK_MFF   = "11320"
USER_AGENT = "MFFSubjectsScraper/2.0 (student research)"


# -----------------------------------------------------------------------------
# Polite HTTP session
# -----------------------------------------------------------------------------

class PoliteSession:
    def __init__(self, request_delay_s: float = 1.0):
        self.s = requests.Session()
        self.s.headers.update({"User-Agent": USER_AGENT, "Accept-Language": "cs,en"})
        self.delay = request_delay_s
        self._last = 0.0

    def get(self, url: str, **kw) -> requests.Response:
        elapsed = time.time() - self._last
        if elapsed < self.delay:
            time.sleep(self.delay - elapsed)
        r = self.s.get(url, timeout=30, allow_redirects=True, **kw)
        self._last = time.time()
        r.raise_for_status()
        # SIS deklaruje cp1250, ale nový web posílá UTF-8 — necháme bs4 detekovat
        if r.encoding and r.encoding.lower() == "iso-8859-1":
            r.encoding = r.apparent_encoding or "utf-8"
        return r


# -----------------------------------------------------------------------------
# Data classy
# -----------------------------------------------------------------------------

@dataclass
class SubjectStub:
    kod:        str
    nazev:      str
    semestr:    Optional[str] = None      # 'oba' | 'zimní' | 'letní'
    rozsah:     Optional[str] = None      # '2/2, Z+Zk'
    katedra:    Optional[str] = None      # '32-KFNT'
    detail_url: Optional[str] = None


@dataclass
class Subject:
    kod:                str
    nazev:              str
    fakulta_kod:        str = FAK_MFF
    semestr:            Optional[str] = None
    rozsah:             Optional[str] = None
    katedra:            Optional[str] = None
    kredity:            Optional[int] = None
    jazyk:              Optional[str] = None
    anotace:            Optional[str] = None
    cile:               Optional[str] = None
    sylabus:            Optional[str] = None
    pozadavky:          Optional[str] = None
    studijni_materialy: Optional[str] = None
    url:                Optional[str] = None
    lecturers:          list[dict] = field(default_factory=list)


# -----------------------------------------------------------------------------
# Phase 1 — list page
# -----------------------------------------------------------------------------

LIST_PARAMS_BASE = {
    "do":           "search",
    "fak":          FAK_MFF,
    "match":        "substring",
    "srch_nazev":   "1",          # search in title (vrací prázdné výsledky pokud =0)
    "srch_pam_a":   "0",
    "srch_pam_s":   "0",
    "sem":          "3",          # oba semestry
    "ujmeno":       "",
    "utyp":         "3",
    "nazev":        "",
    "kod":          "",
    "ustav":        "",
    "sekce":        "",
    "trida":        "",
    "klas":         "",
    "p4euplus":     "",
    "pvirtmob":     "",
    "pvyjazyk":     "",
    "b":            "Hledej",
}


def fetch_list_page(session: PoliteSession, page: int = 1, pocet: int = 1000) -> str:
    params = dict(LIST_PARAMS_BASE)
    params["pocet"]     = str(pocet)
    params["stev_page"] = str(page)
    r = session.get(SIS_BASE, params=params)
    return r.text


def parse_list_page(html: str) -> tuple[list[SubjectStub], int, int]:
    """
    Vrací (rows, total_count, current_page).
    Skutečné pořadí buněk v table.tab1 / tr.row[12]:
       0 — detail icon
       1 — checkbox
       2 — Kód     (link)
       3 — Název   (link)
       4 — Semestr ('oba'/'zimní'/'letní')
       5 — Rozsah, examinace ('2/2, Z+Zk')
       6 — Katedra (link s '32-XXX')
       7 — Fakulta ('MFF')
       8 — Cyklická výuka
       9 — Virtuál. mob.
      10 — Poč. míst
      11 — 4EU+
    """
    soup = BeautifulSoup(html, "lxml")
    rows = soup.select("table.tab1 tr.row1, table.tab1 tr.row2")
    stubs: list[SubjectStub] = []

    for tr in rows:
        cells = tr.find_all("td", recursive=False)
        if len(cells) < 7:
            continue
        kod_link    = cells[2].find("a")
        nazev_link  = cells[3].find("a")
        if not kod_link or not nazev_link:
            continue
        kod         = kod_link.get_text(strip=True)
        nazev       = nazev_link.get_text(strip=True)
        semestr     = cells[4].get_text(" ", strip=True)
        # Rozsah: může mít více řádků pro zimní + letní semestr — beru text inline
        rozsah      = cells[5].get_text(" ", strip=True)
        # Strip [HT] tooltip suffix
        rozsah      = re.sub(r"\s*\[HT\]\s*", " ", rozsah).strip()
        katedra_a   = cells[6].find("a")
        katedra     = katedra_a.get_text(strip=True) if katedra_a else cells[6].get_text(strip=True)

        href = kod_link.get("href", "")
        detail_url = urljoin(SIS_BASE, href) if href else None

        stubs.append(SubjectStub(
            kod=kod, nazev=nazev, semestr=semestr or None,
            rozsah=rozsah or None, katedra=katedra or None,
            detail_url=detail_url,
        ))

    # Total result count z hlavičky "Výsledky 1-20 z 460"
    total = 0
    page_div = soup.find("div", class_="seznam1", id="page_div")
    if page_div:
        m = re.search(r"\bz\s+<b>(\d+)</b>", str(page_div)) or \
            re.search(r"\bz\s+(\d+)\b", page_div.get_text())
        if m:
            total = int(m.group(1))

    # Aktuální strana (z odkazu s class="akt")
    current = 1
    if page_div:
        akt = page_div.find("a", class_="akt")
        if akt:
            try:
                current = int(akt.get_text(strip=True))
            except ValueError:
                pass

    return stubs, total, current


def crawl_all_subjects(session: PoliteSession, pocet: int = 1000) -> list[SubjectStub]:
    """Iteruje stránky dokud je co stahovat. Default pocet=1000 minimalizuje paginaci."""
    all_stubs: dict[str, SubjectStub] = {}
    page = 1
    pbar = tqdm(desc="List pages", unit="page")
    while True:
        html = fetch_list_page(session, page=page, pocet=pocet)
        stubs, total, current = parse_list_page(html)
        if not stubs:
            break
        for s in stubs:
            all_stubs[s.kod] = s
        pbar.update(1)
        pbar.set_postfix(loaded=len(all_stubs), total=total)
        # Spočítáme očekávaný počet stránek z `total / pocet`
        max_pages = (total + pocet - 1) // pocet if total else (page + 1)
        if page >= max_pages:
            break
        page += 1
    pbar.close()
    return list(all_stubs.values())


# -----------------------------------------------------------------------------
# Phase 2 — detail page
# -----------------------------------------------------------------------------

def fetch_detail(session: PoliteSession, stub: SubjectStub) -> str:
    if stub.detail_url:
        r = session.get(stub.detail_url)
    else:
        r = session.get(SIS_BASE, params={"do": "predmet", "kod": stub.kod, "fak": FAK_MFF})
    return r.text


def parse_detail(html: str, stub: SubjectStub, final_url: str) -> Subject:
    """
    Detail page je polo-strukturovaný. SIS používá:
      - <table class="tab1"> s páry <th>Title</th><td>Value</td> pro metadata
      - sekce s <h3> nebo <h2> nadpisy + následujícími <div>/<p> bloky pro
        Anotace / Sylabus / Cíle / Literatura
    Defenzivní parser: zkouší obě cesty.
    """
    soup = BeautifulSoup(html, "lxml")
    subj = Subject(kod=stub.kod, nazev=stub.nazev,
                   semestr=stub.semestr, rozsah=stub.rozsah,
                   katedra=stub.katedra, url=final_url)

    # Pokud title selektor v list page selhal, zkus z detail page
    if not subj.nazev:
        h = soup.find(["h1", "h2", "h3"])
        if h:
            subj.nazev = re.sub(rf"^.*{re.escape(stub.kod)}\s*[—-]\s*", "",
                                 h.get_text(strip=True))

    # Metadata table (<th>:<td>) — kredity, rozsah, jazyk, semestr atd.
    meta = extract_meta_pairs(soup)
    subj.kredity = parse_int(meta.get("E-Kredity") or meta.get("Kredity")
                              or meta.get("Počet kreditů"))
    if not subj.semestr:
        subj.semestr = meta.get("Semestr") or meta.get("semestr")
    if not subj.rozsah:
        subj.rozsah = meta.get("Rozsah, examinace") or meta.get("Rozsah")
    jazyk_raw = meta.get("Vyučovací jazyk") or meta.get("Jazyk výuky") or ""
    subj.jazyk = (jazyk_raw or "").strip().lower()[:2] or None

    # Textové sekce
    sections = extract_text_sections(soup)
    subj.anotace            = sections.get("Anotace")
    subj.cile               = sections.get("Cíl předmětu") or sections.get("Cíle")
    subj.sylabus            = sections.get("Sylabus")
    subj.pozadavky          = (sections.get("Podmínky zakončení")
                                or sections.get("Požadavky")
                                or sections.get("Podmínky zakončení předmětu"))
    subj.studijni_materialy = sections.get("Studijní materiály") or sections.get("Literatura")

    # Vyučující
    subj.lecturers = extract_lecturers(soup)
    return subj


def extract_meta_pairs(soup: BeautifulSoup) -> dict[str, str]:
    """<table>...<tr><th>X</th><td>Y</td></tr>... → {X: Y}."""
    out: dict[str, str] = {}
    for tr in soup.select("table tr"):
        th = tr.find("th")
        td = tr.find("td")
        if not th or not td:
            continue
        k = th.get_text(" ", strip=True).rstrip(":")
        v = td.get_text(" ", strip=True)
        if k and v and k not in out:
            out[k] = v
    return out


def extract_text_sections(soup: BeautifulSoup) -> dict[str, str]:
    """
    Hledá sekce typu <h3>Anotace</h3><div>...content...</div> nebo
    <table><tr><th>Anotace</th><td>...</td></tr>.
    """
    out: dict[str, str] = {}

    # Pattern A: heading + sibling content až do dalšího heading
    for h in soup.find_all(["h2", "h3", "h4"]):
        title = h.get_text(strip=True).rstrip(":")
        if not title:
            continue
        chunks: list[str] = []
        for sib in h.find_next_siblings():
            if sib.name in ("h2", "h3", "h4"):
                break
            txt = sib.get_text("\n", strip=True)
            if txt:
                chunks.append(txt)
        if chunks and title not in out:
            out[title] = "\n".join(chunks)

    # Pattern B: <th>Title</th><td>Multi-line text</td> (často Anotace/Sylabus)
    for tr in soup.select("table tr"):
        th = tr.find("th")
        td = tr.find("td")
        if not th or not td:
            continue
        k = th.get_text(" ", strip=True).rstrip(":")
        v = td.get_text("\n", strip=True)
        if k and v and len(v) > 50 and k not in out:
            out[k] = v
    return out


def extract_lecturers(soup: BeautifulSoup) -> list[dict]:
    """
    SIS detail typicky obsahuje <th>Garant:</th><td>Mgr. Jan Novák, Ph.D.</td>
    nebo bloky 'Vyučující' / 'Přednášející' / 'Cvičící'.
    """
    label_to_role = {
        "garant":              "garant",
        "garanti":             "garant",
        "vyučující":           "prednasejici",
        "přednášející":        "prednasejici",
        "prednasejici":        "prednasejici",
        "cvičící":             "cviciaci",
        "cviciaci":            "cviciaci",
    }
    out: list[dict] = []
    seen = set()
    for tr in soup.select("table tr"):
        th = tr.find("th")
        td = tr.find("td")
        if not th or not td:
            continue
        label = th.get_text(strip=True).rstrip(":").lower()
        role = label_to_role.get(label)
        if not role:
            continue
        names = [a.get_text(strip=True) for a in td.find_all("a")]
        if not names:
            names = [n.strip() for n in td.get_text(",").split(",") if n.strip()]
        for n in names:
            key = (n, role)
            if key in seen:
                continue
            seen.add(key)
            out.append({"jmeno": n, "role": role})
    return out


def parse_int(value: Optional[str]) -> Optional[int]:
    if not value:
        return None
    m = re.search(r"\d+", value)
    return int(m.group(0)) if m else None


# -----------------------------------------------------------------------------
# JSON checkpoints
# -----------------------------------------------------------------------------

def save_json(path: Path, data) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def load_json(path: Path):
    if not path.exists():
        return None
    return json.loads(path.read_text(encoding="utf-8"))


# -----------------------------------------------------------------------------
# Main
# -----------------------------------------------------------------------------

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out-dir", default="./data", type=Path)
    ap.add_argument("--delay",   default=1.0,    type=float)
    ap.add_argument("--pocet",   default=1000,   type=int,
                    help="Records per list page (max 1000 podle SIS form options).")
    ap.add_argument("--phase",   choices=["list", "detail", "all"], default="all")
    ap.add_argument("--limit",   default=0,      type=int,
                    help="Detail phase only: process first N stubs (0 = all).")
    args = ap.parse_args()

    session    = PoliteSession(request_delay_s=args.delay)
    list_path  = args.out_dir / "subjects_index.json"
    full_path  = args.out_dir / "subjects_full.json"

    # ── List ──────────────────────────────────────────────────────────────
    if args.phase in ("list", "all"):
        if list_path.exists():
            print(f"Cached list at {list_path} — using ({len(load_json(list_path))} stubs). "
                  f"Delete to re-fetch.")
            stubs = [SubjectStub(**s) for s in load_json(list_path)]
        else:
            print(f"Fetching list of MFF subjects (pocet={args.pocet}/page)…")
            stubs = crawl_all_subjects(session, pocet=args.pocet)
            save_json(list_path, [asdict(s) for s in stubs])
            print(f"Saved {len(stubs)} stubs → {list_path}")
    else:
        stubs = [SubjectStub(**s) for s in (load_json(list_path) or [])]

    if args.phase == "list":
        return 0
    if not stubs:
        print("No stubs. Run --phase list first.")
        return 1

    # ── Detail (resumable) ────────────────────────────────────────────────
    full_data = load_json(full_path) or {}
    todo = [s for s in stubs if s.kod not in full_data]
    if args.limit:
        todo = todo[:args.limit]
    print(f"Detail phase: {len(todo)} to fetch ({len(full_data)} cached).")

    save_every = 25
    pbar = tqdm(todo, desc="Detail")
    for i, stub in enumerate(pbar, 1):
        try:
            r = session.get(stub.detail_url) if stub.detail_url else \
                session.get(SIS_BASE, params={"do": "predmet", "kod": stub.kod, "fak": FAK_MFF})
            subj = parse_detail(r.text, stub, r.url)
            full_data[stub.kod] = asdict(subj)
        except Exception as e:
            pbar.write(f"[WARN] {stub.kod} failed: {e}")
            continue
        if i % save_every == 0:
            save_json(full_path, full_data)
            pbar.set_postfix(saved=len(full_data))
    pbar.close()
    save_json(full_path, full_data)
    print(f"Saved {len(full_data)} subjects → {full_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
