package com.sreality.dashboard.handlers;

import com.sreality.dashboard.Config;
import com.sreality.dashboard.DealType;
import com.sreality.dashboard.PropertyType;
import com.sreality.dashboard.sql.Queries;
import com.sreality.dashboard.util.Pearson;

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
import java.util.Map;

/**
 * Two routes powering the correlation page:
 *
 * <ul>
 *   <li>{@code GET /api/scatter/csu-metrics} — static catalog of metric
 *       names the scatter endpoint accepts (so the frontend dropdown is
 *       sourced from the backend's whitelist).</li>
 *   <li>{@code GET /api/scatter/price-vs-csu} — one point per obec:
 *       latest non-NULL value of the chosen metric vs avg price/m².</li>
 * </ul>
 */
public final class Scatter {

    private Scatter() {}

    /** Allowed CSU columns (hard-coded so callers can't SQL-inject via ``metric``). */
    private static final Map<String, MetricInfo> METRICS;
    static {
        Map<String, MetricInfo> m = new LinkedHashMap<>();
        m.put("population",         new MetricInfo("Population",        "people"));
        m.put("unemployment_pct",   new MetricInfo("Unemployment",      "%"));
        m.put("marriages",          new MetricInfo("Marriages",         "per year"));
        m.put("divorces",           new MetricInfo("Divorces",          "per year"));
        m.put("births",             new MetricInfo("Births",            "per year"));
        m.put("deaths",             new MetricInfo("Deaths",            "per year"));
        m.put("migration_balance",  new MetricInfo("Migration balance", "per year"));
        METRICS = Map.copyOf(m);
    }

    private record MetricInfo(String label, String unit) {}

    /** Mounted at {@code /api/scatter/csu-metrics}. */
    public static Handler catalog() {
        return ctx -> {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map.Entry<String, MetricInfo> e : METRICS.entrySet()) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("key",   e.getKey());
                r.put("label", e.getValue().label());
                r.put("unit",  e.getValue().unit());
                out.add(r);
            }
            ctx.json(out);
        };
    }

    /** Mounted at {@code /api/scatter/price-vs-csu}. */
    public static Handler priceVsCsu(DataSource ds) {
        return ctx -> {
            String metric = ctx.queryParam("metric");
            if (metric == null || metric.isBlank()) metric = "unemployment_pct";
            if (!METRICS.containsKey(metric)) {
                throw new BadRequestResponse(
                    "Unknown metric " + metric + ". Allowed: " + METRICS.keySet()
                );
            }

            DealType deal = DealType.fromQueryToken(ctx.queryParam("deal"));
            List<PropertyType> ptypes = Config.parsePropertyTypes(ctx.queryParam("property_types"));
            int minListings = clamp(parseIntDefault(ctx.queryParam("min_listings"), 3), 1, 1000);

            String factsCte = Queries.buildFactsCte(deal, ptypes, "");
            // ``metric`` is safe to interpolate — validated against the
            // METRICS whitelist above and is a column name, not user data.
            String sql =
                "WITH facts AS (" + factsCte + "),\n" +
                "agg AS (\n" +
                "    SELECT f.obec_id,\n" +
                "           COUNT(*) AS n,\n" +
                "           AVG(f.per_m2)::NUMERIC(12,2) AS avg_per_m2\n" +
                "    FROM   facts f\n" +
                "    WHERE  f.per_m2 IS NOT NULL AND f.per_m2 > 0\n" +
                "    GROUP  BY f.obec_id\n" +
                "    HAVING COUNT(*) >= ?\n" +
                "),\n" +
                "latest_metric AS (\n" +
                "    SELECT DISTINCT ON (obec_id)\n" +
                "           obec_id, year, " + metric + " AS metric_value\n" +
                "    FROM   fact_obec_stats\n" +
                "    WHERE  " + metric + " IS NOT NULL\n" +
                "    ORDER  BY obec_id, year DESC\n" +
                ")\n" +
                "SELECT  o.id            AS obec_id,\n" +
                "        o.nazev_obce    AS obec_name,\n" +
                "        r.nazev_okresu  AS okres,\n" +
                "        k.nazev_kraje   AS kraj,\n" +
                "        agg.n           AS n,\n" +
                "        agg.avg_per_m2  AS avg_per_m2,\n" +
                "        lm.metric_value AS metric_value,\n" +
                "        lm.year         AS metric_year\n" +
                "FROM    agg\n" +
                "JOIN    dim_obec  o ON o.id = agg.obec_id\n" +
                "JOIN    dim_okres r ON r.id = o.okres_id\n" +
                "JOIN    dim_kraj  k ON k.id = r.kraj_id\n" +
                "JOIN    latest_metric lm ON lm.obec_id = agg.obec_id";

            List<Map<String, Object>> points = new ArrayList<>();
            List<Double> xs = new ArrayList<>();
            List<Double> ys = new ArrayList<>();

            try (Connection c = ds.getConnection();
                 PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setInt(1, minListings);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        double avg = rs.getDouble("avg_per_m2");
                        boolean avgNull = rs.wasNull();
                        double mv = rs.getDouble("metric_value");
                        boolean mvNull = rs.wasNull();
                        if (avgNull || mvNull) continue;

                        Map<String, Object> p = new LinkedHashMap<>();
                        p.put("obec_id",      rs.getInt("obec_id"));
                        p.put("obec_name",    rs.getString("obec_name"));
                        p.put("okres",        rs.getString("okres"));
                        p.put("kraj",         rs.getString("kraj"));
                        p.put("n",            rs.getLong("n"));
                        p.put("avg_per_m2",   avg);
                        p.put("metric_value", mv);
                        int yr = rs.getInt("metric_year");
                        p.put("metric_year",  rs.wasNull() ? null : yr);
                        points.add(p);
                        xs.add(mv);
                        ys.add(avg);
                    }
                }
            }

            Double correlation = Pearson.correlation(xs, ys);
            MetricInfo info = METRICS.get(metric);

            List<String> ptypeTokens = new ArrayList<>();
            for (PropertyType pt : ptypes) ptypeTokens.add(pt.token());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("metric",         metric);
            body.put("metric_label",   info.label());
            body.put("metric_unit",    info.unit());
            body.put("deal",           deal.token());
            body.put("property_types", ptypeTokens);
            body.put("min_listings",   minListings);
            body.put("n_points",       points.size());
            body.put("correlation",    correlation);
            body.put("points",         points);
            ctx.json(body);
        };
    }

    private static int parseIntDefault(String s, int dflt) {
        if (s == null || s.isBlank()) return dflt;
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return dflt; }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
