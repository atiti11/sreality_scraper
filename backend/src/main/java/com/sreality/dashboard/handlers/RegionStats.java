package com.sreality.dashboard.handlers;

import com.sreality.dashboard.Config;
import com.sreality.dashboard.DealType;
import com.sreality.dashboard.PropertyType;
import com.sreality.dashboard.RegionLevel;
import com.sreality.dashboard.sql.CsuStats;
import com.sreality.dashboard.sql.Queries;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.http.NotFoundResponse;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/region/{level}/{region_id}/stats} — drives the right-hand
 * side panel that opens when a polygon is clicked on the map: listing
 * count, avg + median price/m², CSU socio-economics (population,
 * unemployment, births, …).
 *
 * <p>For {@code cast_obce} the surfaced CSU stats are the <em>parent
 * obec</em>'s — CSU publishes per municipality, not per locality. The
 * frontend flags that scope with a "for whole obec" label.</p>
 */
public final class RegionStats implements Handler {

    private final DataSource ds;

    public RegionStats(DataSource ds) {
        this.ds = ds;
    }

    @Override
    public void handle(Context ctx) throws Exception {
        RegionLevel level = RegionLevel.fromPath(ctx.pathParam("level"));
        int regionId = Integer.parseInt(ctx.pathParam("region_id"));
        DealType deal = DealType.fromQueryToken(ctx.queryParam("deal"));
        List<PropertyType> ptypes = Config.parsePropertyTypes(ctx.queryParam("property_types"));

        try (Connection c = ds.getConnection()) {

            // --- Region context ---------------------------------------------
            String name, parentName;
            Integer obecIdForStats;
            try (PreparedStatement ps = c.prepareStatement(Queries.regionContextQuery(level))) {
                ps.setInt(1, regionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new NotFoundResponse("Region " + level.token() + "/" + regionId + " not found.");
                    }
                    name = rs.getString("name");
                    parentName = rs.getString("parent_name");
                    int oid = rs.getInt("obec_id_for_stats");
                    obecIdForStats = rs.wasNull() ? null : oid;
                }
            }

            // --- Listing aggregations for this region -----------------------
            String rfilter = Queries.regionFilterClause(level);
            String factsCte = Queries.buildFactsCte(deal, ptypes, "AND " + rfilter);
            String aggSql = """
                WITH facts AS (%s)
                SELECT COUNT(*) AS n,
                       AVG(per_m2)::NUMERIC(12,2) AS avg_per_m2,
                       PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY per_m2)::NUMERIC(12,2) AS median_per_m2,
                       AVG(price)::NUMERIC AS avg_price,
                       PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY price)::BIGINT  AS median_price
                FROM facts WHERE per_m2 IS NOT NULL AND per_m2 > 0
                """.formatted(factsCte);

            long n = 0;
            Double avgPerM2 = null, medianPerM2 = null, avgPrice = null;
            Long medianPrice = null;
            try (PreparedStatement ps = c.prepareStatement(aggSql)) {
                // ``factsCte`` has one ? per branch for the region id —
                // build_facts_cte appends ``AND <rfilter>`` to each branch's
                // WHERE. Bind the same region id into every placeholder.
                int placeholders = countPlaceholders(aggSql);
                for (int i = 1; i <= placeholders; i++) {
                    ps.setInt(i, regionId);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        n = rs.getLong("n");
                        avgPerM2 = nullable(rs, "avg_per_m2");
                        medianPerM2 = nullable(rs, "median_per_m2");
                        avgPrice = nullable(rs, "avg_price");
                        long mp = rs.getLong("median_price");
                        medianPrice = rs.wasNull() ? null : mp;
                    }
                }
            }

            // --- CSU stats ---------------------------------------------------
            Map<String, Object> csu = null;
            switch (level) {
                case OBEC, CAST_OBCE -> {
                    if (obecIdForStats != null) {
                        csu = fetchCsuLatestPerObec(c, obecIdForStats);
                    }
                }
                case OKRES -> {
                    String sql = CsuStats.AGGREGATED_TEMPLATE.formatted(
                        CsuStats.JOIN_AND_FILTER_OKRES
                    );
                    csu = fetchCsuAggregated(c, sql, regionId);
                }
                case KRAJ -> {
                    String sql = CsuStats.AGGREGATED_TEMPLATE.formatted(
                        CsuStats.JOIN_AND_FILTER_KRAJ
                    );
                    csu = fetchCsuAggregated(c, sql, regionId);
                }
            }

            // --- Response ---------------------------------------------------
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("level",          level.token());
            body.put("id",             regionId);
            body.put("name",           name);
            body.put("parent_name",    parentName);
            body.put("n",              n);
            body.put("avg_per_m2",     avgPerM2);
            body.put("median_per_m2",  medianPerM2);
            body.put("avg_price",      avgPrice);
            body.put("median_price",   medianPrice);
            body.put("csu",            csu);
            body.put("csu_aggregated", level == RegionLevel.KRAJ || level == RegionLevel.OKRES);
            ctx.json(body);
        }
    }

    // ------------------------------------------------------------------------

    private static Map<String, Object> fetchCsuLatestPerObec(Connection c, int obecId) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(CsuStats.LATEST_NON_NULL_PER_OBEC)) {
            ps.setInt(1, obecId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return csuRow(rs);
            }
        }
    }

    private static Map<String, Object> fetchCsuAggregated(Connection c, String sql, int regionId) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, regionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return csuRow(rs);
            }
        }
    }

    /**
     * Build the {@code csu} JSON object from a row of either the
     * latest-non-null or aggregated query. Returns {@code null} when the
     * row has no year (i.e. no underlying stats at all).
     */
    private static Map<String, Object> csuRow(ResultSet rs) throws Exception {
        int year = rs.getInt("year");
        if (rs.wasNull()) return null;

        Map<String, Object> csu = new LinkedHashMap<>();
        csu.put("year",              year);
        csu.put("population",        nullableLong(rs, "population"));
        csu.put("divorces",          nullableLong(rs, "divorces"));
        csu.put("marriages",         nullableLong(rs, "marriages"));
        csu.put("births",            nullableLong(rs, "births"));
        csu.put("deaths",            nullableLong(rs, "deaths"));
        csu.put("unemployment_pct",  nullable(rs, "unemployment_pct"));
        csu.put("migration_balance", nullableLong(rs, "migration_balance"));
        return csu;
    }

    private static Double nullable(ResultSet rs, String col) throws Exception {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }

    private static Long nullableLong(ResultSet rs, String col) throws Exception {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    /**
     * Count the number of ``?`` JDBC placeholders in a SQL string. Used
     * to bind the same {@code regionId} into every per-branch filter that
     * {@link Queries#buildFactsCte(DealType, java.util.Collection, String)}
     * stamps into the CTE.
     */
    private static int countPlaceholders(String sql) {
        int count = 0;
        boolean inSingle = false;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            if (ch == '\'') {
                inSingle = !inSingle;
            } else if (ch == '?' && !inSingle) {
                count++;
            }
        }
        return count;
    }
}
