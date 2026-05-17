package com.sreality.dashboard.handlers;

import com.sreality.dashboard.Config;
import com.sreality.dashboard.DealType;
import com.sreality.dashboard.PropertyType;
import com.sreality.dashboard.RegionLevel;
import com.sreality.dashboard.sql.Queries;
import com.sreality.dashboard.util.SrealityUrl;

import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.Handler;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code GET /api/price-movers?deal=&property_types=&window=&sort=&...}
 *
 * <p>Backs the "Price changes" page. Returns the listings whose
 * <em>asked / monthly / starting-bid</em> price moved the most within a
 * configurable window, computed from {@code estate_field_changes}.</p>
 *
 * <h2>Algorithm</h2>
 *
 * <p>For each estate that has at least one price change in the last
 * {@code N} days:</p>
 * <ol>
 *   <li>{@code price_at_window_start} = the {@code old_value} of the
 *       <em>earliest</em> price change in the window. That approximates
 *       what the estate cost when the window opened (any change before
 *       the window doesn't count).</li>
 *   <li>{@code delta} = current price &minus; price_at_window_start.</li>
 * </ol>
 *
 * <p>The result is ordered by {@code ABS(delta)} so the biggest swings
 * (either direction) surface first; the client can flip the sort sign
 * via the {@code sort} query parameter.</p>
 */
public final class PriceMovers {

    private PriceMovers() {}

    /** Default page size. */
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT     = 200;

    /** Whitelisted {@code window} values mapped to a day count. */
    private static int windowDays(String w) {
        if (w == null) return 30;
        return switch (w.toLowerCase(Locale.ROOT)) {
            case "1d", "day"   -> 1;
            case "3d"          -> 3;
            case "1w", "week"  -> 7;
            case "1m", "month" -> 30;
            default            -> 30;
        };
    }

    public static Handler handler(DataSource ds) {
        return ctx -> {
            DealType deal = DealType.fromQueryToken(ctx.queryParam("deal"));
            List<PropertyType> ptypes = Config.parsePropertyTypes(ctx.queryParam("property_types"));
            int days = windowDays(ctx.queryParam("window"));
            String sort = ctx.queryParam("sort");
            int limit  = clamp(parseIntDefault(ctx.queryParam("limit"),  DEFAULT_LIMIT), 1, MAX_LIMIT);
            int offset = Math.max(0, parseIntDefault(ctx.queryParam("offset"), 0));

            RegionLevel regionLevel = parseRegionLevel(ctx.queryParam("region_level"));
            Integer regionId = parseIntOrNull(ctx.queryParam("region_id"));

            // Direction of the sort:
            //   - "delta_desc"  → biggest rise first
            //   - "delta_asc"   → biggest drop first
            //   - default       → biggest absolute move first
            String orderBy = switch (sort == null ? "" : sort) {
                case "delta_desc"     -> "delta DESC NULLS LAST";
                case "delta_asc"      -> "delta ASC  NULLS LAST";
                case "delta_pct_desc" -> "delta_pct DESC NULLS LAST";
                case "delta_pct_asc"  -> "delta_pct ASC  NULLS LAST";
                default               -> "ABS(delta) DESC NULLS LAST";
            };

            String regionFilter = "";
            if (regionLevel != null && regionId != null) {
                regionFilter = " AND " + Queries.regionFilterClause(regionLevel);
            }

            String factsCte = Queries.buildFactsCte(deal, ptypes, "");
            // ``window_changes`` keeps one row per estate whose price moved
            // inside the window. The ``array_agg ORDER BY changed_at ASC``
            // pattern picks the OLDEST change in the window so we approximate
            // the price at the window's left edge from its old_value.
            String sql = """
                WITH facts AS (%s),
                window_changes AS (
                    SELECT
                        ch.hash_id,
                        (array_agg(ch.old_value::NUMERIC ORDER BY ch.changed_at ASC))[1]
                            AS price_at_window_start,
                        MAX(ch.changed_at) AS last_changed_at,
                        COUNT(*) AS change_count
                    FROM   estate_field_changes ch
                    WHERE  ch.field_name IN ('price_asked_czk', 'price_monthly_czk', 'price_starting_bid_czk')
                      AND  ch.changed_at >= CURRENT_DATE - INTERVAL '%d days'
                      AND  ch.old_value ~ '^[0-9]+'
                      AND  ch.new_value ~ '^[0-9]+'
                    GROUP BY ch.hash_id
                )
                SELECT
                    f.property_type,
                    f.hash_id,
                    f.price                                 AS current_price,
                    wc.price_at_window_start                AS old_price,
                    (f.price - wc.price_at_window_start)    AS delta,
                    CASE WHEN wc.price_at_window_start > 0
                         THEN (f.price - wc.price_at_window_start) * 100.0
                              / wc.price_at_window_start
                         ELSE NULL
                    END                                     AS delta_pct,
                    wc.last_changed_at,
                    wc.change_count,
                    f.area,
                    f.per_m2,
                    f.sub_category,
                    o.nazev_obce       AS obec,
                    co.nazev_cast_obce AS cast_obce,
                    r.nazev_okresu     AS okres,
                    k.nazev_kraje      AS kraj
                FROM   facts f
                JOIN   window_changes wc ON wc.hash_id = f.hash_id
                LEFT JOIN dim_obec       o  ON o.id  = f.obec_id
                LEFT JOIN dim_cast_obce  co ON co.id = f.cast_obce_id
                LEFT JOIN dim_okres      r  ON r.id  = o.okres_id
                LEFT JOIN dim_kraj       k  ON k.id  = r.kraj_id
                WHERE  TRUE %s
                ORDER  BY %s
                LIMIT  ? OFFSET ?
                """.formatted(factsCte, days, regionFilter, orderBy);

            List<Map<String, Object>> rows = new ArrayList<>();
            try (Connection c = ds.getConnection();
                 PreparedStatement ps = c.prepareStatement(sql)) {
                int idx = 1;
                if (regionLevel != null && regionId != null) {
                    ps.setInt(idx++, regionId);
                }
                ps.setInt(idx++, limit);
                ps.setInt(idx,   offset);

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String propertyTypeToken = rs.getString("property_type");
                        long hashId   = rs.getLong("hash_id");
                        String obec   = rs.getString("obec");
                        String castOb = rs.getString("cast_obce");
                        String subCat = rs.getString("sub_category");
                        Double area   = nullable(rs, "area");

                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("property_type",   propertyTypeToken);
                        r.put("hash_id",         hashId);
                        r.put("title",           buildTitle(propertyTypeToken, subCat, area));
                        r.put("sub_category",    subCat);
                        r.put("current_price",   nullableLong(rs, "current_price"));
                        r.put("old_price",       nullable(rs, "old_price"));
                        r.put("delta",           nullable(rs, "delta"));
                        r.put("delta_pct",       nullable(rs, "delta_pct"));
                        java.sql.Date last = rs.getDate("last_changed_at");
                        r.put("last_changed_at", last == null ? null : last.toString());
                        r.put("change_count",    rs.getLong("change_count"));
                        r.put("area",            area);
                        r.put("per_m2",          nullable(rs, "per_m2"));
                        r.put("obec",            obec);
                        r.put("okres",           rs.getString("okres"));
                        r.put("kraj",            rs.getString("kraj"));
                        r.put("url",             SrealityUrl.build(
                                                     deal, propertyTypeToken,
                                                     subCat, obec, castOb, hashId));
                        rows.add(r);
                    }
                }
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("window_days", days);
            body.put("count",       rows.size());
            body.put("limit",       limit);
            body.put("offset",      offset);
            body.put("rows",        rows);
            ctx.json(body);
        };
    }

    // ------------------------------------------------------------------------

    /** Same synthesised label the listings handler uses, kept in sync. */
    private static String buildTitle(String propertyType, String subCategory, Double area) {
        StringBuilder sb = new StringBuilder();
        sb.append(switch (propertyType == null ? "" : propertyType) {
            case "apartment"  -> "Apartment";
            case "house"      -> "House";
            case "land"       -> "Land";
            case "commercial" -> "Commercial space";
            default           -> "Listing";
        });
        if (subCategory != null && !subCategory.isBlank()) {
            sb.append(' ').append(subCategory.trim());
        }
        if (area != null && Double.isFinite(area) && area > 0) {
            sb.append(", ").append(Math.round(area)).append(" m²");
        }
        return sb.toString();
    }

    private static RegionLevel parseRegionLevel(String s) {
        if (s == null || s.isBlank()) return null;
        try { return RegionLevel.fromPath(s); }
        catch (IllegalArgumentException e) {
            throw new BadRequestResponse("Unknown region_level: " + s);
        }
    }

    private static Integer parseIntOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return null; }
    }

    private static int parseIntDefault(String s, int dflt) {
        if (s == null || s.isBlank()) return dflt;
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return dflt; }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
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
