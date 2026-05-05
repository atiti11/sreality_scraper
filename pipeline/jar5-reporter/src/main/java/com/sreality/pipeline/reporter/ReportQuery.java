package com.sreality.pipeline.reporter;

import com.sreality.pipeline.shared.db.PostgresConnectionPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.LocalDate;

/**
 * Queries Postgres for interesting pipeline statistics and formats a Telegram message.
 *
 * Queries run:
 *   1. Total active listings per type/deal
 *   2. New listings added since yesterday
 *   3. Price reductions in last 24h (top 5 largest)
 *   4. Most active kraj by new listings
 *   5. Average apartment sale price per m2 in Prague vs national
 */
public class ReportQuery {

    private static final Logger log = LoggerFactory.getLogger(ReportQuery.class);

    private final PostgresConnectionPool pg;

    public ReportQuery(PostgresConnectionPool pg) { this.pg = pg; }

    public String buildReport() {
        StringBuilder sb = new StringBuilder();
        LocalDate today     = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);

        sb.append("📊 *Sreality Pipeline Report* — ").append(today).append("\n\n");

        appendSection(sb, "🏠 Active listings",          queryActiveCounts());
        appendSection(sb, "🆕 New since yesterday",       queryNewListings(yesterday));
        appendSection(sb, "📉 Biggest price drops (24h)", queryPriceDrops(yesterday));
        appendSection(sb, "📍 Most active kraj",          queryMostActiveKraj(yesterday));
        appendSection(sb, "💰 Avg Prague vs national (apt sale/m²)", queryPriceComparison());

        return sb.toString();
    }

    // -------------------------------------------------------------------------

    private String queryActiveCounts() {
        String sql =
            "SELECT 'Apartments for sale' AS label, COUNT(*) FROM " + pg.t("fact_apartment_sale") + " WHERE valid_to IS NULL AND is_active=true UNION ALL " +
            "SELECT 'Apartments for rent',           COUNT(*) FROM " + pg.t("fact_apartment_rent") + " WHERE valid_to IS NULL AND is_active=true UNION ALL " +
            "SELECT 'Houses for sale',               COUNT(*) FROM " + pg.t("fact_house_sale")     + " WHERE valid_to IS NULL AND is_active=true UNION ALL " +
            "SELECT 'Houses for rent',               COUNT(*) FROM " + pg.t("fact_house_rent")     + " WHERE valid_to IS NULL AND is_active=true UNION ALL " +
            "SELECT 'Land for sale',                 COUNT(*) FROM " + pg.t("fact_land_sale")      + " WHERE valid_to IS NULL AND is_active=true UNION ALL " +
            "SELECT 'Commercial for sale',           COUNT(*) FROM " + pg.t("fact_commercial_sale")+ " WHERE valid_to IS NULL AND is_active=true";
        return runLabelCount(sql);
    }

    private String queryNewListings(LocalDate since) {
        String sql =
            "SELECT 'Apartments' AS label, COUNT(*) FROM " + pg.t("fact_apartment_sale") + " WHERE first_seen_date >= '" + since + "' UNION ALL " +
            "SELECT 'Houses',              COUNT(*) FROM " + pg.t("fact_house_sale")      + " WHERE first_seen_date >= '" + since + "' UNION ALL " +
            "SELECT 'Land',               COUNT(*) FROM " + pg.t("fact_land_sale")       + " WHERE first_seen_date >= '" + since + "'";
        return runLabelCount(sql);
    }

    private String queryPriceDrops(LocalDate since) {
        String sql =
            "SELECT d.hash_id, CAST(d.old_value AS bigint) - CAST(d.new_value AS bigint) AS drop_czk" +
            " FROM " + pg.t("estate_field_changes") + " d" +
            " WHERE d.field_name='price_asked_czk' AND d.changed_at >= '" + since + "'" +
            "   AND d.old_value ~ '^[0-9]+$' AND d.new_value ~ '^[0-9]+$'" +
            "   AND CAST(d.old_value AS bigint) > CAST(d.new_value AS bigint)" +
            " ORDER BY drop_czk DESC LIMIT 5";
        StringBuilder sb = new StringBuilder();
        try (Connection c = pg.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                sb.append("  hash_id=").append(rs.getLong(1))
                  .append(" ↓ ").append(String.format("%,d", rs.getLong(2))).append(" Kč\n");
            }
        } catch (SQLException e) { log.warn("queryPriceDrops failed: {}", e.getMessage()); }
        return sb.isEmpty() ? "  (none)" : sb.toString().stripTrailing();
    }

    private String queryMostActiveKraj(LocalDate since) {
        String sql =
            "SELECT k.nazev_kraje, COUNT(*) AS new_count" +
            " FROM " + pg.t("fact_apartment_sale") + " f" +
            " JOIN " + pg.t("dim_obec")  + " o ON o.id=f.obec_id" +
            " JOIN " + pg.t("dim_okres") + " r ON r.id=o.okres_id" +
            " JOIN " + pg.t("dim_kraj")  + " k ON k.id=r.kraj_id" +
            " WHERE f.first_seen_date >= '" + since + "'" +
            " GROUP BY k.nazev_kraje ORDER BY new_count DESC LIMIT 3";
        return runLabelCount(sql);
    }

    private String queryPriceComparison() {
        String sql =
            "SELECT 'Prague avg/m²' AS label," +
            "       ROUND(AVG(price_asked_per_m2))::text" +
            " FROM " + pg.t("fact_apartment_sale") + " f" +
            " JOIN " + pg.t("dim_obec") + " o ON o.id=f.obec_id" +
            " WHERE f.valid_to IS NULL AND f.is_active=true AND o.nazev_obce='Praha'" +
            "   AND f.price_asked_per_m2 > 0" +
            " UNION ALL" +
            " SELECT 'National avg/m²'," +
            "       ROUND(AVG(price_asked_per_m2))::text" +
            " FROM " + pg.t("fact_apartment_sale") +
            " WHERE valid_to IS NULL AND is_active=true AND price_asked_per_m2 > 0";
        return runLabelCount(sql);
    }

    // -------------------------------------------------------------------------

    private String runLabelCount(String sql) {
        StringBuilder sb = new StringBuilder();
        try (Connection c = pg.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                sb.append("  ").append(rs.getString(1))
                  .append(": ").append(rs.getString(2)).append("\n");
            }
        } catch (SQLException e) { log.warn("Query failed: {}", e.getMessage()); }
        return sb.isEmpty() ? "  (no data)" : sb.toString().stripTrailing();
    }

    private static void appendSection(StringBuilder sb, String title, String content) {
        sb.append("*").append(title).append("*\n").append(content).append("\n\n");
    }
}
