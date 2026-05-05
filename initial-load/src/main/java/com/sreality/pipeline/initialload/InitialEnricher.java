package com.sreality.pipeline.initialload;

import com.sreality.pipeline.enricher.load.EnricherLoader;
import com.sreality.pipeline.shared.db.PostgresConnectionPool;
import org.bson.Document;

import java.sql.*;
import java.time.LocalDate;

/**
 * Extends EnricherLoader with one behavioural change for the initial load:
 *
 *   valid_from = _first_seen_at date (not today)
 *
 * The regular EnricherLoader uses LocalDate.now() as valid_from because it is
 * always processing documents that just arrived. For the initial load we want
 * to preserve the real first-seen date so that the SCD history starts from
 * when the estate was actually first scraped, not from when we ran this loader.
 *
 * is_active is set from the MongoDB "active" field (true/false) rather than
 * always defaulting to true — some estates in MongoDB are already marked
 * inactive (they disappeared from the API during the scraping period).
 *
 * All other logic (spatial join, agency upsert, content hash, field changes,
 * detail upsert) is inherited unchanged from EnricherLoader.
 */
public class InitialEnricher extends EnricherLoader {

    public InitialEnricher(PostgresConnectionPool pg) {
        super(pg);
    }

    /**
     * Overrides the valid_from date used in setCommon().
     * Returns the date part of _first_seen_at, falling back to today if missing.
     */
    @Override
    protected LocalDate resolveValidFrom(Document doc) {
        String ts = doc.getString("_first_seen_at");
        if (ts != null && ts.length() >= 10) {
            try {
                return LocalDate.parse(ts.substring(0, 10));
            } catch (Exception ignored) {}
        }
        return LocalDate.now();
    }

    /**
     * Overrides is_active to read from the MongoDB "active" field.
     * Inactive estates (sold/removed during the scraping period) are loaded
     * with is_active=false and valid_to = _last_seen_at date.
     */
    @Override
    protected boolean resolveIsActive(Document doc) {
        Boolean active = doc.getBoolean("active");
        return !Boolean.FALSE.equals(active);  // default true if field missing
    }

    /**
     * Overrides valid_to for inactive estates.
     * When active=false, sets valid_to = _last_seen_at date so the row is
     * immediately closed, correctly representing that the estate was last seen
     * on that date and is no longer on the market.
     */
    @Override
    protected LocalDate resolveValidTo(Document doc) {
        if (resolveIsActive(doc)) return null;  // open row for active estates
        String ts = doc.getString("_last_seen_at");
        if (ts != null && ts.length() >= 10) {
            try {
                return LocalDate.parse(ts.substring(0, 10));
            } catch (Exception ignored) {}
        }
        return LocalDate.now();
    }
}
