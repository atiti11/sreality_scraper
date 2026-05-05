package com.sreality.pipeline.scraper.db;

import com.sreality.pipeline.shared.db.PostgresConnectionPool;
import com.sreality.pipeline.shared.db.TableRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Loads current estate state from Postgres for change detection.
 *
 * At the start of each category run the scraper loads all current
 * (hash_id → content_hash) pairs from the matching fact table into a HashMap.
 * Each API estate is then checked against this map in O(1) — only changed or
 * new estates are written to MongoDB.
 *
 * Also handles marking estates inactive in Postgres when they disappear from
 * the API — this replaces MongoRepository.markInactiveNotSeenSince().
 */
public class PostgresLookup {

    private static final Logger log = LoggerFactory.getLogger(PostgresLookup.class);

    private final PostgresConnectionPool pg;

    public PostgresLookup(PostgresConnectionPool pg) {
        this.pg = pg;
    }

    /**
     * Loads (hash_id → content_hash) for all current rows in the fact table.
     * Returns empty map if table has no rows yet (first run).
     */
    public Map<Long, Long> loadCurrentHashes(String propertyType, String dealType) {
        String table = pg.t(TableRouter.factTable(propertyType, dealType));
        String sql   = "SELECT hash_id, content_hash FROM " + table
                     + " WHERE valid_to IS NULL";
        Map<Long, Long> result = new HashMap<>();
        try (Connection conn = pg.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) result.put(rs.getLong(1), rs.getLong(2));
            log.info("Loaded {} hashes from {} for {}/{}", result.size(), table, propertyType, dealType);
        } catch (SQLException e) {
            log.warn("Could not load hashes from {} — first run? ({})", table, e.getMessage());
        }
        return result;
    }

    /**
     * Marks estates inactive that were in Postgres but not seen in the API this run.
     * Closes their SCD Type 2 row: sets valid_to = today, is_active = false.
     *
     * @return count of estates marked inactive
     */
    public int markInactiveBatch(String propertyType, String dealType,
                                  Set<Long> seenIds, Set<Long> knownIds) {
        String table = pg.t(TableRouter.factTable(propertyType, dealType));
        String sql   = "UPDATE " + table
                     + " SET is_active = false, valid_to = CURRENT_DATE"
                     + " WHERE hash_id = ? AND valid_to IS NULL AND is_active = true";
        int count = 0;
        try (Connection conn = pg.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Long hashId : knownIds) {
                if (seenIds.contains(hashId)) continue;
                ps.setLong(1, hashId);
                ps.addBatch();
                count++;
            }
            if (count > 0) {
                ps.executeBatch();
                log.info("Marked {} estates inactive in {}/{}", count, propertyType, dealType);
            }
        } catch (SQLException e) {
            log.error("markInactiveBatch failed for {}/{}: {}", propertyType, dealType, e.getMessage());
        }
        return count;
    }
}
