package com.sreality.scraper.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * Parses the human-readable "Aktualizace" (last update) string
 * returned inside the detail endpoint's items[] array.
 *
 * Known Czech values:
 *   "Dnes"   → today
 *   "Včera"  → yesterday
 *   "d.M.yyyy" or "d. M. yyyy"  → parsed directly
 *
 * If parsing fails, the original string is stored as-is.
 */
public class DateParser {

    private static final Logger log = LoggerFactory.getLogger(DateParser.class);

    // Sreality uses Czech short date format, e.g. "15.3.2026"
    private static final DateTimeFormatter CZ_DATE = DateTimeFormatter.ofPattern("d.M.yyyy", Locale.ROOT);

    private DateParser() {}

    /**
     * Returns a ParsedDate containing either a LocalDate (if parsing succeeded)
     * or the raw original string (if parsing failed).
     */
    public static ParsedDate parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ParsedDate(null, raw);
        }

        // Remove non-breaking spaces and trim
        String cleaned = raw.replace("\u00a0", " ").trim();

        // Czech relative keywords
        if (cleaned.equalsIgnoreCase("Dnes")) {
            return new ParsedDate(LocalDate.now(), cleaned);
        }
        if (cleaned.equalsIgnoreCase("Včera") || cleaned.equalsIgnoreCase("Vчera")) {
            return new ParsedDate(LocalDate.now().minusDays(1), cleaned);
        }

        // Remove spaces around dots for flexible parsing: "15. 3. 2026" → "15.3.2026"
        String normalized = cleaned.replaceAll("\\s*\\.\\s*", ".");

        try {
            LocalDate date = LocalDate.parse(normalized, CZ_DATE);
            return new ParsedDate(date, cleaned);
        } catch (DateTimeParseException e) {
            log.warn("Could not parse 'Aktualizace' value '{}' — storing raw string", raw);
            return new ParsedDate(null, cleaned);
        }
    }

    /**
     * Holds the result of a date parse attempt.
     * If {@code date} is non-null, parsing succeeded.
     * Otherwise {@code rawValue} is the fallback string to store.
     */
    public record ParsedDate(LocalDate date, String rawValue) {
        /** Returns the date as ISO string if parsed, otherwise the raw string. */
        public String storableValue() {
            return date != null ? date.toString() : rawValue;
        }

        public boolean isParsed() {
            return date != null;
        }
    }
}
