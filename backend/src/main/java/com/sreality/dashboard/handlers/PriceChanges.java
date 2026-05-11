package com.sreality.dashboard.handlers;

import com.sreality.dashboard.Config;
import com.sreality.dashboard.DealType;
import com.sreality.dashboard.PropertyType;
import com.sreality.dashboard.RegionLevel;
import com.sreality.dashboard.sql.Queries;
import com.sreality.dashboard.util.SrealityUrl;

import io.javalin.http.Context;
import io.javalin.http.Handler;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/region/{level}/{region_id}/price-changes} — recent
 * monetary price changes for estates inside the selected region. Joins
 * {@code estate_field_changes} against the active fact tables to pick up
 * each estate's <em>current</em> price for context.
 */
public final class PriceChanges implements Handler {

    private final DataSource ds;

    public PriceChanges(DataSource ds) {
        this.ds = ds;
    }

    @Override
    public void handle(Context ctx) throws Exception {
        RegionLevel level = RegionLevel.fromPath(ctx.pathParam("level"));
        int regionId = Integer.parseInt(ctx.pathParam("region_id"));
        DealType deal = DealType.fromQueryToken(ctx.queryParam("deal"));
        List<PropertyType> ptypes = Config.parsePropertyTypes(ctx.queryParam("property_types"));
        int limit = parsePositiveInt(ctx.queryParam("limit"), 20, 1, 100);

        // Build the UNION of fact-table branches that yield current state.
        List<String> branches = new ArrayList<>();
        for (PropertyType ptype : ptypes) {
            Queries.TableCfg cfg = Queries.TABLES.get(new Queries.TableKey(ptype, deal));
            if (cfg == null) continue;
            branches.add(
                "SELECT '" + ptype.token() + "'::TEXT AS property_type,\n" +
                "       '" + cfg.table()   + "'::TEXT AS source_table,\n" +
                "       f.hash_id, f.obec_id, f.cast_obce_id,\n" +
                "       f.gps_lat, f.gps_lon,\n" +
                "       f." + cfg.priceCol() + "::BIGINT AS price,\n" +
                "       " + cfg.perM2Expr() + "::NUMERIC AS per_m2,\n" +
                "       f." + cfg.areaCol() + "::NUMERIC AS area,\n" +
                "       f.sreality_url AS url,\n" +
                "       f.first_seen_date\n" +
                "FROM   " + cfg.table() + " f\n" +
                "WHERE  f.valid_to IS NULL AND f.is_active"
            );
        }
        if (branches.isEmpty()) {
            ctx.json(List.of());
            return;
        }
        String facts = String.join("\n        UNION ALL\n", branches);
        String rfilter = Queries.regionFilterClause(level);

        // The two regex literals check "all digits, optional .digits". In
        // the Java source ``\\.`` compiles to ``\.`` in the runtime string,
        // which PostgreSQL reads as a literal dot in a POSIX regex. The
        // anchors at start/end are plain ``^`` and ``$`` characters.
        String regex = "^[0-9]+(\\.[0-9]+)?" + "$";
        String sql =
            "WITH f AS (" + facts + ")\n" +
            "SELECT  f.property_type,\n" +
            "        f.hash_id,\n" +
            "        f.area,\n" +
            "        f.price        AS current_price,\n" +
            "        f.per_m2       AS current_per_m2,\n" +
            "        ch.changed_at,\n" +
            "        ch.field_name,\n" +
            "        ch.old_value,\n" +
            "        ch.new_value,\n" +
            "        CASE WHEN ch.old_value ~ '" + regex + "'\n" +
            "                  AND ch.new_value ~ '" + regex + "'\n" +
            "             THEN ch.new_value::NUMERIC - ch.old_value::NUMERIC\n" +
            "             ELSE NULL END AS delta,\n" +
            "        o.nazev_obce       AS obec,\n" +
            "        co.nazev_cast_obce AS cast_obce\n" +
            "FROM    estate_field_changes ch\n" +
            "JOIN    f                     ON f.hash_id  = ch.hash_id\n" +
            "LEFT JOIN dim_obec       o    ON o.id  = f.obec_id\n" +
            "LEFT JOIN dim_cast_obce  co   ON co.id = f.cast_obce_id\n" +
            "WHERE   ch.field_name IN ('price_asked_czk', 'price_monthly_czk', 'price_starting_bid_czk')\n" +
            "  AND   " + rfilter + "\n" +
            "ORDER BY ch.changed_at DESC, ch.id DESC\n" +
            "LIMIT ?";

        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, regionId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String propertyTypeToken = rs.getString("property_type");
                    long hashId = rs.getLong("hash_id");
                    String obec = rs.getString("obec");
                    String castObce = rs.getString("cast_obce");

                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("property_type", propertyTypeToken);
                    r.put("hash_id",       hashId);
                    r.put("url",           SrealityUrl.build(
                                              deal, propertyTypeToken,
                                              obec, castObce, hashId));
                    r.put("obec",          obec);
                    r.put("area",          nullable(rs, "area"));
                    r.put("current_price",  nullableLong(rs, "current_price"));
                    r.put("current_per_m2", nullable(rs, "current_per_m2"));
                    java.sql.Date changedAt = rs.getDate("changed_at");
                    r.put("changed_at",     changedAt == null ? null : changedAt.toString());
                    r.put("field",          rs.getString("field_name"));
                    r.put("old_value",      rs.getString("old_value"));
                    r.put("new_value",      rs.getString("new_value"));
                    r.put("delta",          nullable(rs, "delta"));
                    out.add(r);
                }
            }
        }
        ctx.json(out);
    }

    private static int parsePositiveInt(String s, int dflt, int min, int max) {
        if (s == null || s.isBlank()) return dflt;
        try {
            int v = Integer.parseInt(s.trim());
            if (v < min || v > max) return dflt;
            return v;
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static Double nullable(ResultSet rs, String col) throws Exception {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }

    private static Long nullableLong(ResultSet rs, String col) throws Exception {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }
}
