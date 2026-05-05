package com.sreality.pipeline.csu.extract;

import com.sreality.pipeline.csu.model.ObecStatsRecord;
import com.sreality.pipeline.csu.model.ObecSuccessorRecord;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses CSU municipality statistics XLSX files.
 *
 * Each XLSX (one per kraj, e.g. CZ020A.xlsx) contains:
 *   - Sheet "OD_KAM": municipality succession table
 *     Columns (0-based): old_obec_kod, old_name, new_obec_kod, new_name, year
 *
 *   - Data sheets (one or more): yearly statistics per obec
 *     Row 0: headers including year
 *     Column 0: obec_kod
 *     Remaining columns: stat values (population, births, deaths, ...)
 *
 * Missing values are represented as "-", ".", or empty cells → stored as null.
 *
 * Column mapping for data sheets (0-based after obec_kod at col 0):
 *   1: population, 2: births, 3: deaths, 4: migration_balance,
 *   5: marriages, 6: divorces, 7: unemployment_pct
 *
 * NOTE: column positions may differ per XLSX version — adjust COL_* constants
 * once you inspect actual files with a test run.
 */
public class CsuXlsxParser {

    private static final Logger log = LoggerFactory.getLogger(CsuXlsxParser.class);

    private static final String OD_KAM_SHEET = "OD_KAM";

    // Column indices in data sheets (0-based)
    private static final int COL_OBEC_KOD          = 0;
    private static final int COL_POPULATION         = 1;
    private static final int COL_BIRTHS             = 2;
    private static final int COL_DEATHS             = 3;
    private static final int COL_MIGRATION_BALANCE  = 4;
    private static final int COL_MARRIAGES          = 5;
    private static final int COL_DIVORCES           = 6;
    private static final int COL_UNEMPLOYMENT_PCT   = 7;

    // Column indices in OD_KAM sheet
    private static final int COL_OLD_KOD  = 0;
    private static final int COL_NEW_KOD  = 2;
    private static final int COL_YEAR     = 4;

    // First data row (skip header row)
    private static final int HEADER_ROWS = 1;

    public record ParseResult(
        List<ObecSuccessorRecord> successors,
        List<ObecStatsRecord>     stats) {}

    /**
     * Parses one CSU XLSX file.
     *
     * @param is    InputStream of the XLSX file
     * @param year  the year this file represents (used for data sheets without year column)
     */
    public ParseResult parse(InputStream is, int year) throws IOException {
        List<ObecSuccessorRecord> successors = new ArrayList<>();
        List<ObecStatsRecord>     stats      = new ArrayList<>();

        try (Workbook wb = new XSSFWorkbook(is)) {
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                Sheet sheet = wb.getSheetAt(i);
                String name = wb.getSheetName(i);

                if (OD_KAM_SHEET.equalsIgnoreCase(name)) {
                    successors.addAll(parseOdKam(sheet));
                } else {
                    // Determine the year for this sheet: try to read from sheet name,
                    // fall back to the year parameter
                    int sheetYear = extractYearFromSheetName(name, year);
                    stats.addAll(parseDataSheet(sheet, sheetYear));
                }
            }
        }
        log.info("Parsed XLSX: {} successors, {} stat rows", successors.size(), stats.size());
        return new ParseResult(successors, stats);
    }

    // -------------------------------------------------------------------------

    private List<ObecSuccessorRecord> parseOdKam(Sheet sheet) {
        List<ObecSuccessorRecord> result = new ArrayList<>();
        for (int r = HEADER_ROWS; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            String oldKod = str(row, COL_OLD_KOD);
            String newKod = str(row, COL_NEW_KOD);
            int    merged = intVal(row, COL_YEAR, 0);
            if (oldKod == null || newKod == null || merged == 0) continue;
            result.add(new ObecSuccessorRecord(oldKod, newKod, merged));
        }
        return result;
    }

    private List<ObecStatsRecord> parseDataSheet(Sheet sheet, int year) {
        List<ObecStatsRecord> result = new ArrayList<>();
        for (int r = HEADER_ROWS; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            String kodObce = str(row, COL_OBEC_KOD);
            if (kodObce == null || kodObce.isBlank()) continue;
            result.add(new ObecStatsRecord(
                kodObce,
                year,
                intOrNull(row, COL_POPULATION),
                intOrNull(row, COL_BIRTHS),
                intOrNull(row, COL_DEATHS),
                intOrNull(row, COL_MIGRATION_BALANCE),
                intOrNull(row, COL_MARRIAGES),
                intOrNull(row, COL_DIVORCES),
                doubleOrNull(row, COL_UNEMPLOYMENT_PCT)
            ));
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Cell helpers
    // -------------------------------------------------------------------------

    private static String str(Row row, int col) {
        Cell cell = row.getCell(col, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) return null;
        String v = switch (cell.getCellType()) {
            case STRING  -> cell.getStringCellValue().trim();
            case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
            default      -> null;
        };
        if (v == null || v.isBlank() || "-".equals(v) || ".".equals(v)) return null;
        return v;
    }

    private static Integer intOrNull(Row row, int col) {
        String s = str(row, col);
        if (s == null) return null;
        try { return Integer.parseInt(s.replace(" ", "")); }
        catch (NumberFormatException e) { return null; }
    }

    private static int intVal(Row row, int col, int def) {
        Integer v = intOrNull(row, col);
        return v != null ? v : def;
    }

    private static Double doubleOrNull(Row row, int col) {
        Cell cell = row.getCell(col, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) return null;
        if (cell.getCellType() == CellType.NUMERIC) return cell.getNumericCellValue();
        String s = str(row, col);
        if (s == null) return null;
        try { return Double.parseDouble(s.replace(",", ".")); }
        catch (NumberFormatException e) { return null; }
    }

    private static int extractYearFromSheetName(String name, int fallback) {
        // Sheet names like "2023", "rok_2023", "2023_data" etc.
        String digits = name.replaceAll("[^0-9]", "");
        if (digits.length() == 4) {
            try { return Integer.parseInt(digits); }
            catch (NumberFormatException ignored) {}
        }
        return fallback;
    }
}
