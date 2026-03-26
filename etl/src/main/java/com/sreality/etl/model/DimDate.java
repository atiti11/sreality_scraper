package com.sreality.etl.model;

import com.sreality.etl.load.PostgresLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Date;
import java.time.LocalDate;

/**
 * Dimension: date.
 *
 * Standard data warehouse date dimension — one row per calendar day.
 * Pre-generated for a range of years; never changes once generated.
 * Populated once in ensureRange() and never touched again.
 */
public class DimDate {

    private static final Logger log = LoggerFactory.getLogger(DimDate.class);

    /**
     * Ensures all dates from Jan 1 of startYear to Dec 31 of endYear exist
     * in dim_date. Safe to call repeatedly — INSERT ... ON CONFLICT DO NOTHING.
     */
    public static void ensureRange(PostgresLoader pg, int startYear, int endYear) {
        log.info("Ensuring dim_date populated for {}-{}", startYear, endYear);
        LocalDate start = LocalDate.of(startYear, 1, 1);
        LocalDate end   = LocalDate.of(endYear,   12, 31);
        int count = 0;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            pg.upsertDate(
                dateId(d),
                Date.valueOf(d),
                d.getYear(),
                (d.getMonthValue() - 1) / 3 + 1,
                d.getMonthValue(),
                d.getMonth().name(),
                d.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear()),
                d.getDayOfWeek().getValue(),
                d.getDayOfWeek().getValue() >= 6
            );
            count++;
        }
        log.info("dim_date: {} rows ensured", count);
    }

    /** Converts a LocalDate to the integer PK format YYYYMMDD. */
    public static int dateId(LocalDate d) {
        return d.getYear() * 10000 + d.getMonthValue() * 100 + d.getDayOfMonth();
    }

    /** Converts a date string (ISO: 2026-01-15) to date_id integer. */
    public static int dateId(String isoDate) {
        if (isoDate == null || isoDate.isBlank()) return 0;
        try {
            LocalDate d = LocalDate.parse(isoDate.substring(0, 10));
            return dateId(d);
        } catch (Exception e) {
            return 0;
        }
    }

    private DimDate() {}
}
