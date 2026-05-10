# MFF UK Subjects → Postgres → Nomox

Tří-souborový pipeline: HTML scrape SIS UK → JSON → Postgres → Nomox / Discord bot.

## Soubory

| Soubor | Co dělá |
|---|---|
| `scraper.py` | Stáhne všechny předměty MFF UK ze SIS Předměty modulu. Dvoufázový (list → detail) a resumable (JSON checkpoint) |
| `schema.sql` | Tabulky `mff_subject` + `mff_subject_lecturer` v Postgresu, plus full-text indexy |
| `load.py` | JSON → Postgres (UPSERT) |

`api_scraper_legacy.py` (pokud zůstal v kořeni projektu) je první broken pokus s halucinovaným `is.mff.cuni.cz/webapi`. Smaž ho, neaktivní.

## Workflow

### 1. Závislosti

```bash
pip install requests beautifulsoup4 lxml tqdm psycopg2-binary
```

### 2. Schema do cílové DB (jednorázově)

```bash
psql "host=localhost port=5432 dbname=nomox user=nomox password=..." \
     -f schema.sql
```

### 3. Scrape

```bash
cd nomox_subjects
python scraper.py --out-dir ./data
```

Výstup:
- `data/subjects_index.json` — list (~460 předmětů, MFF UK aktuální rok)
- `data/subjects_full.json` — full data po doběhu Phase 2 (~7-15 min při default delay)

Volitelné argumenty:
- `--phase list` — jen list
- `--phase detail` — jen detail (musí existovat list checkpoint)
- `--limit 5` — detail jen prvních 5 (smoke test)
- `--pocet 1000` — records per list page (default 1000, max podle SIS form options)
- `--delay 0.5` — rate limit (default 1 s/req)

Smoke test:

```bash
python scraper.py --phase list                  # ~30 s, jeden request s pocet=1000
head -100 ./data/subjects_index.json            # ověř kódy/názvy

python scraper.py --phase detail --limit 5      # ~10 s
cat ./data/subjects_full.json | python -m json.tool | head -200
```

### 4. Load do Postgresu

```bash
python load.py \
    --json ./data/subjects_full.json \
    --pg "host=localhost port=5432 dbname=nomox user=nomox password=..." \
    --truncate
```

`--truncate` vyčistí tabulky před loadem. Bez něj je load idempotentní (UPSERT podle `kod`).

## Co SIS reálně poskytuje

SIS modul "Předměty" (https://is.cuni.cz/studium/predmety/?do=search) **nemá v search formuláři pole pro akademický rok**. Vrací aktuální „nabízené předměty" (status zachycený v ÚVT konfiguraci, typicky aktuální akademický rok). Pro starší roky:
- Buď manipulovat session přes `do=setup` (komplikované, vyžaduje cookies)
- Nebo scrapovat MFF Karolinka PDF (https://www.mff.cuni.cz/karolinka), který má strukturovaný studijní plán

460 výsledků je očekávaný počet pro jeden akademický rok MFF UK (filtruje se na non-cyclic, currently-offered subjects).

## Schema

```
mff_subject              — 1 řádek na předmět
  kod (PK)
  nazev
  fakulta_kod, semestr, kredity, rozsah, jazyk
  anotace, cile, sylabus, pozadavky, studijni_materialy
  url, scraped_at

mff_subject_lecturer     — 1 řádek na (předmět, vyučující, role)
  kod (FK), jmeno, role  (garant / prednasejici / cviciaci)
```

Full-text indexy (`gin tsvector`) na `nazev`, `anotace`, `sylabus` — Postgresové full-text hledání bez vector store.

## Pro Nomox / Discord bot

Po loadu do Postgresu napojíš nomox:

1. **Vector embeddings** sylabů — pgvector v Postgresu nebo nomox vlastní vector store
2. **RAG retrieval** — pro otázku „doporučte mi 30 kreditů z informatiky v ZS pro 2. ročník bakaláře" provedete:
   - Vector similarity nad embeddingy `nazev + anotace + sylabus`
   - Plus filtr `WHERE semestr LIKE '%zimní%' OR semestr = 'oba'`
   - Plus filtr na `kredity BETWEEN 3 AND 5`
   - Plus filtr na `katedra LIKE '32-K%'` (informatika sekce)
3. **LLM completion** — výsledky zabalíte do kontextu odpovědi

Pokud nomox nemá embedding pipeline, můžu sepsat `embed.py` který:
- Přidá sloupec `embedding vector(1536)` do `mff_subject`
- Pro každý záznam zavolá OpenAI / sentence-transformers
- Naplní vector pro RAG

## Známé limity

- **Selektory jsou křehké**. SIS občas přepracovává layout. Pokud výstup začne být prázdný, podívej se na ne-prázdnou detail page v prohlížeči, srovnej s `parse_detail()` selectory v `scraper.py`. Aktualizováno 2026-05-10 podle reálné struktury.
- **Rozvrh** (časové sloty učeben) **není v SIS Předměty**. Pro „kdy a kde" by bylo třeba scrapovat zvlášť `is.cuni.cz/studium/rozvrhng/`.
- **Prerekvizity** SIS uvádí v `pozadavky` často jen volným textem.
- **Rate limit**: ÚVT SIS rate-limituje, default 1 req/s je bezpečný. Plný scrape ~460 předmětů trvá ~15 min.
