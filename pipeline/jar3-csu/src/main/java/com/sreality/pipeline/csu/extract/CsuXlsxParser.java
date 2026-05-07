package com.sreality.pipeline.csu.extract;

import com.sreality.pipeline.csu.model.ObecStatsRecord;
import com.sreality.pipeline.csu.model.ObecSuccessorRecord;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Parses CSU municipality statistics from ZIP archives or single XLSX files.
 *
 * CSU distributes two ZIPs, each containing 77 XLSX files (one per okres).
 * The sheet type is auto-detected from the header row (col 3):
 *
 *   Demographics ZIP — columns (0-based):
 *     0:Rok  1:Číslo obce  2:Název obce  3:Vznik  4:Stav 1.1.
 *     5:Narozeni  6:Zemřelí  7:Přistěhovalí  8:Vystěhovalí
 *     9:Přírůstek přirozený  10:Přírůstek migrační  11:Přírůstek celkový
 *     12:Územní změna 1  13:Stav 31.12.  ...
 *     → population=Stav 1.1., births, deaths, migration_balance
 *
 *   Weddings ZIP — columns (0-based):
 *     0:Rok  1:Číslo obce  2:Název obce  3:Sňatky  4:Rozvody  5:Potraty
 *     → marriages, divorces
 *
 * OD_KAM sheets are skipped (succession handling deferred).
 * Both ZIPs upsert into the same fact_obec_stats table via COALESCE merge.
 */
public class CsuXlsxParser {

    private static final Logger log = LoggerFactory.getLogger(CsuXlsxParser.class);

    public record ParseResult(
        List<ObecSuccessorRecord> successors,
        List<ObecStatsRecord>     stats) {}

    // -------------------------------------------------------------------------
    // Public entry points
    // -------------------------------------------------------------------------

    /** Parses a ZIP archive containing multiple XLSX files (one per okres). */
    public ParseResult parseZip(InputStream in) throws IOException {
        List<ObecStatsRecord> all = new ArrayList<>();
        int xlsxCount = 0;
        try (ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (!name.toLowerCase().endsWith(".xlsx")) { zis.closeEntry(); continue; }
                byte[] bytes = zis.readAllBytes();
                zis.closeEntry();
                try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
                    int before = all.size();
                    parseWorkbook(wb, all);
                    log.debug("{}: {} rows", name, all.size() - before);
                    xlsxCount++;
                } catch (Exception e) {
                    log.warn("Skipping {}: {}", name, e.getMessage());
                }
            }
        }
        log.info("Parsed ZIP: {} XLSX files → {} stat rows", xlsxCount, all.size());
        return new ParseResult(List.of(), all);
    }

    /** Parses a single XLSX file. */
    public ParseResult parseXlsx(InputStream in) throws IOException {
        List<ObecStatsRecord> all = new ArrayList<>();
        try (Workbook wb = new XSSFWorkbook(in)) {
            parseWorkbook(wb, all);
        }
        log.info("Parsed XLSX: {} stat rows", all.size());
        return new ParseResult(List.of(), all);
    }

    // -------------------------------------------------------------------------

    private void parseWorkbook(Workbook wb, List<ObecStatsRecord> out) {
        for (int i = 0; i < wb.getNumberOfSheets(); i++) {
            if ("OD_KAM".equalsIgnoreCase(wb.getSheetName(i))) continue;
            parseDataSheet(wb.getSheetAt(i), out);
        }
    }

    private enum SheetType { DEMOGRAPHICS, WEDDINGS }

    private void parseDataSheet(Sheet sheet, List<ObecStatsRecord> out) {
        // Find header: first row (within first 5) where col 0 text is "Rok"
        int headerIdx = -1;
        for (int r = 0; r <= Math.min(5, sheet.getLastRowNum()); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            if ("Rok".equalsIgnoreCase(strRaw(row, 0))) { headerIdx = r; break; }
        }
        if (headerIdx < 0) return;

        // Detect type: weddings have "Sňatky" (or ascii fallback) at col 3
        String col3Header = strRaw(sheet.getRow(headerIdx), 3);
        SheetType type = (col3Header != null
                && (col3Header.toLowerCase().contains("sňatky")
                 || col3Header.toLowerCase().contains("snatky")))
                ? SheetType.WEDDINGS
                : SheetType.DEMOGRAPHICS;

        int parsed = 0;
        for (int r = headerIdx + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            Integer year    = intOrNull(row, 0);
            String  kodObce = str(row, 1);
            if (year == null || kodObce == null) continue;

            ObecStatsRecord rec = switch (type) {
                case DEMOGRAPHICS -> new ObecStatsRecord(
                    kodObce, year,
                    intOrNull(row, 4),   // Stav 1.1.           → population
                    intOrNull(row, 5),   // Narozeni             → births
                    intOrNull(row, 6),   // Zemřelí              → deaths
                    intOrNull(row, 10),  // Přírůstek migrační   → migration_balance
                    null, null, null
                );
                case WEDDINGS -> new ObecStatsRecord(
                    kodObce, year,
                    null, null, null, null,
                    intOrNull(row, 3),   // Sňatky  → marriages
                    intOrNull(row, 4),   // Rozvody → divorces
                    null
                );
            };
            out.add(rec);
            parsed++;
        }
        if (parsed > 0)
            log.debug("Sheet '{}' ({}): {} rows", sheet.getSheetName(), type, parsed);
    }

    // -------------------------------------------------------------------------
    // Cell helpers
    // -------------------------------------------------------------------------

    private static String strRaw(Row row, int col) {
        Cell cell = row.getCell(col, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING  -> cell.getStringCellValue().trim();
            case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
            default      -> null;
        };
    }

    private static String str(Row row, int col) {
        String v = strRaw(row, col);
        if (v == null || v.isBlank() || "-".equals(v) || ".".equals(v)) return null;
        return v;
    }

    private static Integer intOrNull(Row row, int col) {
        Cell cell = row.getCell(col, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) return null;
        if (cell.getCellType() == CellType.NUMERIC)
            return (int) cell.getNumericCellValue();
        String s = str(row, col);
        if (s == null) return null;
        try {
            // strip thousands separators (space or NBSP) and parse
            return Integer.parseInt(s.replace(" ", "").replace(" ", ""));
        } catch (NumberFormatException e) { return null; }
    }
}
