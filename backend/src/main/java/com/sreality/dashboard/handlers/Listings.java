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
 * Two routes:
 * <ul>
 *   <li>{@code GET /api/listings} — paginated, sortable listings table.</li>
 *   <li>{@code GET /api/listings/count} — total row count for the same filters.</li>
 * </ul>
 *
 * <p>The two share the same WHERE-fragment + parameter builder so a
 * filter change can't accidentally desync the count from the visible
 * rows.</p>
 */
public final class Listings {

    private Listings() {}

    /** Mounted at {@code /api/listings}. */
    public static Handler list(DataSource ds) {
        return ctx -> {
            ListingsParams p = ListingsParams.fromContext(ctx);
            Filter f = buildFilter(p);

            String factsCte = Queries.buildFactsCte(p.deal, p.propertyTypes, "");
            String sortSql = switch (p.sort) {
                case "price_asc"   -> "f.price ASC NULLS LAST";
                case "price_desc"  -> "f.price DESC NULLS LAST";
                case "per_m2_asc"  -> "f.per_m2 ASC NULLS LAST";
                case "per_m2_desc" -> "f.per_m2 DESC NULLS LAST";
                case "area_desc"   -> "f.area DESC NULLS LAST";
                default            -> "f.first_seen_date DESC NULLS LAST";
            };

            String sql =
                "WITH facts AS (" + factsCte + ")\n" +
                "SELECT f.property_type,\n" +
                "       f.hash_id,\n" +
                "       f.price, f.per_m2, f.area,\n" +
                "       f.sub_category,\n" +
                "       f.first_seen_date,\n" +
                "       f.sreality_url     AS url,\n" +
                "       o.nazev_obce       AS obec,\n" +
                "       co.nazev_cast_obce AS cast_obce,\n" +
                "       r.nazev_okresu     AS okres,\n" +
                "       k.nazev_kraje      AS kraj\n" +
                "FROM facts f\n" +
                "LEFT JOIN dim_obec       o  ON o.id  = f.obec_id\n" +
                "LEFT JOIN dim_cast_obce  co ON co.id = f.cast_obce_id\n" +
                "LEFT JOIN dim_okres      r  ON r.id  = o.okres_id\n" +
                "LEFT JOIN dim_kraj       k  ON k.id  = r.kraj_id\n" +
                "WHERE TRUE " + f.where + "\n" +
                "ORDER BY " + sortSql + "\n" +
                "LIMIT ? OFFSET ?";

            List<Map<String, Object>> out = new ArrayList<>();
            try (Connection c = ds.getConnection();
                 PreparedStatement ps = c.prepareStatement(sql)) {
                int idx = 1;
                for (Object v : f.params) idx = bind(ps, idx, v);
                ps.setInt(idx++, p.limit);
                ps.setInt(idx,   p.offset);

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String propertyTypeToken = rs.getString("property_type");
                        long hashId = rs.getLong("hash_id");
                        String obec = rs.getString("obec");
                        String castObce = rs.getString("cast_obce");
                        String subCategory = rs.getString("sub_category");
                        Double area = nullable(rs, "area");

                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("property_type", propertyTypeToken);
                        r.put("hash_id",       hashId);
                        // Human-readable primary identifier for the
                        // listings table. Format: "<Type> <subcat?>, <area> m²".
                        r.put("title",         buildTitle(propertyTypeToken, subCategory, area));
                        r.put("sub_category",  subCategory);
                        r.put("price",         nullableLong(rs, "price"));
                        r.put("per_m2",        nullable(rs, "per_m2"));
                        r.put("area",          area);
                        java.sql.Date fsd = rs.getDate("first_seen_date");
                        r.put("first_seen_date", fsd == null ? null : fsd.toString());
                        r.put("url",           SrealityUrl.build(
                                                  p.deal, propertyTypeToken,
                                                  subCategory,
                                                  obec, castObce, hashId));
                        r.put("obec",          obec);
                        r.put("okres",         rs.getString("okres"));
                        r.put("kraj",          rs.getString("kraj"));
                        out.add(r);
                    }
                }
            }
            ctx.json(out);
        };
    }

    /** Mounted at {@code /api/listings/count}. */
    public static Handler count(DataSource ds) {
        return ctx -> {
            ListingsParams p = ListingsParams.fromContext(ctx);
            Filter f = buildFilter(p);

            String factsCte = Queries.buildFactsCte(p.deal, p.propertyTypes, "");
            String sql =
                "WITH facts AS (" + factsCte + ")\n" +
                "SELECT COUNT(*) AS n FROM facts f WHERE TRUE " + f.where;

            long n = 0;
            try (Connection c = ds.getConnection();
                 PreparedStatement ps = c.prepareStatement(sql)) {
                int idx = 1;
                for (Object v : f.params) idx = bind(ps, idx, v);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) n = rs.getLong("n");
                }
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("n", n);
            ctx.json(body);
        };
    }

    // ------------------------------------------------------------------------
    // Filter parsing
    // ------------------------------------------------------------------------

    /** Bundle of parameters parsed once per request. */
    private static final class ListingsParams {
        DealType deal;
        List<PropertyType> propertyTypes;
        RegionLevel regionLevel;
        Integer regionId;
        Long priceMin, priceMax;
        Long perM2Min, perM2Max;
        Double areaMin, areaMax;
        Double unemploymentMax;
        String sort;
        int limit, offset;

        static ListingsParams fromContext(Context ctx) {
            ListingsParams p = new ListingsParams();
            p.deal = DealType.fromQueryToken(ctx.queryParam("deal"));
            p.propertyTypes = Config.parsePropertyTypes(ctx.queryParam("property_types"));
            String rl = ctx.queryParam("region_level");
            p.regionLevel = (rl == null || rl.isBlank()) ? null : RegionLevel.fromPath(rl);
            p.regionId = parseLongOrNull(ctx.queryParam("region_id")) instanceof Long l ? l.intValue() : null;
            p.priceMin  = parseLongOrNull(ctx.queryParam("price_min"));
            p.priceMax  = parseLongOrNull(ctx.queryParam("price_max"));
            p.perM2Min  = parseLongOrNull(ctx.queryParam("per_m2_min"));
            p.perM2Max  = parseLongOrNull(ctx.queryParam("per_m2_max"));
            p.areaMin   = parseDoubleOrNull(ctx.queryParam("area_min"));
            p.areaMax   = parseDoubleOrNull(ctx.queryParam("area_max"));
            p.unemploymentMax = parseDoubleOrNull(ctx.queryParam("unemployment_max"));
            p.sort      = ctx.queryParam("sort") != null ? ctx.queryParam("sort") : "newest";
            p.limit     = clamp(parseIntDefault(ctx.queryParam("limit"),  50), 1, 200);
            p.offset    = Math.max(0, parseIntDefault(ctx.queryParam("offset"), 0));
            return p;
        }
    }

    /** WHERE fragment with leading " AND ..." plus the parameter values in order. */
    private record Filter(String where, List<Object> params) {}

    private static Filter buildFilter(ListingsParams p) {
        List<String> wheres = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        if (p.regionLevel != null && p.regionId != null) {
            wheres.add(Queries.regionFilterClause(p.regionLevel));
            params.add(p.regionId);
        }
        if (p.priceMin  != null) { wheres.add("f.price >= ?");  params.add(p.priceMin);  }
        if (p.priceMax  != null) { wheres.add("f.price <= ?");  params.add(p.priceMax);  }
        if (p.perM2Min  != null) { wheres.add("f.per_m2 >= ?"); params.add(p.perM2Min);  }
        if (p.perM2Max  != null) { wheres.add("f.per_m2 <= ?"); params.add(p.perM2Max);  }
        if (p.areaMin   != null) { wheres.add("f.area >= ?");   params.add(p.areaMin);   }
        if (p.areaMax   != null) { wheres.add("f.area <= ?");   params.add(p.areaMax);   }
        if (p.unemploymentMax != null) {
            wheres.add(
                "f.obec_id IN (SELECT s.obec_id FROM fact_obec_stats s " +
                "WHERE s.unemployment_pct IS NOT NULL " +
                "AND s.unemployment_pct <= ? " +
                "AND s.year = (SELECT MAX(year) FROM fact_obec_stats WHERE obec_id = s.obec_id))"
            );
            params.add(p.unemploymentMax);
        }
        String where = wheres.isEmpty() ? "" : " AND " + String.join(" AND ", wheres);
        return new Filter(where, params);
    }

    // ------------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------------

    /**
     * Synthesise a human-readable title for a listing row. The dashboard
     * doesn't store the sreality advert headline, so we build something
     * like {@code "Apartment 2+1, 65 m²"} from the columns we do have.
     * sub_category is only populated for apartment tables.
     */
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

    private static int bind(PreparedStatement ps, int idx, Object value) throws Exception {
        if (value instanceof Integer i) ps.setInt(idx, i);
        else if (value instanceof Long l) ps.setLong(idx, l);
        else if (value instanceof Double d) ps.setDouble(idx, d);
        else if (value instanceof String s) ps.setString(idx, s);
        else ps.setObject(idx, value);
        return idx + 1;
    }

    private static Long parseLongOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Long.parseLong(s.trim()); }
        catch (NumberFormatException e) { return null; }
    }

    private static Double parseDoubleOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Double.parseDouble(s.trim()); }
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
