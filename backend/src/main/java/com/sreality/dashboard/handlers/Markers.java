package com.sreality.dashboard.handlers;

import com.sreality.dashboard.Config;
import com.sreality.dashboard.DealType;
import com.sreality.dashboard.PropertyType;
import com.sreality.dashboard.RegionLevel;
import com.sreality.dashboard.sql.Queries;

import io.javalin.http.Context;
import io.javalin.http.Handler;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/markers/{level}} — bubble-map data.
 *
 * <p>One row per region at the requested level. Each row carries a
 * centroid (lat / lon) + aggregations (n, avg/median price-per-m²). The
 * frontend draws {@code L.circleMarker} per row with radius ∝ √n and
 * fill colour driven by avg_per_m². This is the supported map endpoint
 * when {@code dim_cast_obce.geom} is not populated.</p>
 *
 * <p>{@code obec} and {@code cast_obce} levels accept a {@code bbox} query
 * parameter so the frontend can fetch only what's currently on screen.</p>
 */
public final class Markers implements Handler {

    private final DataSource ds;

    public Markers(DataSource ds) {
        this.ds = ds;
    }

    @Override
    public void handle(Context ctx) throws Exception {
        RegionLevel level = RegionLevel.fromPath(ctx.pathParam("level"));
        DealType    deal  = DealType.fromQueryToken(ctx.queryParam("deal"));
        List<PropertyType> ptypes = Config.parsePropertyTypes(ctx.queryParam("property_types"));
        double[] bbox = Config.parseBbox(ctx.queryParam("bbox"));

        // Step 1: centroids. For obec / cast_obce the SQL has 4 ? bbox
        // placeholders that we either bind to the parsed bbox or to NULL
        // (the SQL has an ``IS NULL OR ...`` guard so NULL disables the
        // bbox filter entirely).
        List<Map<String, Object>> centroids = new ArrayList<>();
        String centroidSql = Queries.markersQuery(level);
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(centroidSql)) {
            if (level == RegionLevel.OBEC || level == RegionLevel.CAST_OBCE) {
                if (bbox != null) {
                    ps.setDouble(1, bbox[0]);      // also drives the ?::FLOAT IS NULL guard
                    ps.setDouble(2, bbox[0]);
                    ps.setDouble(3, bbox[2]);
                    ps.setDouble(4, bbox[1]);
                    ps.setDouble(5, bbox[3]);
                } else {
                    for (int i = 1; i <= 5; i++) ps.setNull(i, Types.DOUBLE);
                }
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> r = new HashMap<>();
                    r.put("id",        rs.getInt("id"));
                    r.put("code",      rs.getString("code"));
                    r.put("name",      rs.getString("name"));
                    int pid = rs.getInt("parent_id");
                    r.put("parent_id", rs.wasNull() ? null : pid);
                    double lat = rs.getDouble("lat");
                    r.put("lat", rs.wasNull() ? null : lat);
                    double lon = rs.getDouble("lon");
                    r.put("lon", rs.wasNull() ? null : lon);
                    centroids.add(r);
                }
            }
        }

        // Step 2: aggregations across the facts CTE, keyed by region id.
        String factsCte = Queries.buildFactsCte(deal, ptypes, "");
        String aggSql   = "WITH facts AS (" + factsCte + ") " + Queries.aggregationQuery(level);

        Map<Integer, AggRow> byId = new HashMap<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(aggSql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int rid = rs.getInt("region_id");
                if (rs.wasNull()) continue;
                long n = rs.getLong("n");
                double avg = rs.getDouble("avg_per_m2");
                boolean avgNull = rs.wasNull();
                double med = rs.getDouble("median_per_m2");
                boolean medNull = rs.wasNull();
                byId.put(rid, new AggRow(n, avgNull ? null : avg, medNull ? null : med));
            }
        }

        // Step 3: stitch centroids + aggregations.
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> g : centroids) {
            if (g.get("lat") == null || g.get("lon") == null) continue;
            int id = (Integer) g.get("id");
            AggRow agg = byId.get(id);

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id",            id);
            r.put("code",          g.get("code"));
            r.put("name",          g.get("name"));
            r.put("parent_id",     g.get("parent_id"));
            r.put("lat",           g.get("lat"));
            r.put("lon",           g.get("lon"));
            r.put("n",             agg == null ? 0 : agg.n());
            r.put("avg_per_m2",    agg == null ? null : agg.avgPerM2());
            r.put("median_per_m2", agg == null ? null : agg.medianPerM2());
            out.add(r);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("level",   level.token());
        body.put("markers", out);
        ctx.json(body);
    }

    /** Small holder for the per-region aggregation row. */
    private record AggRow(long n, Double avgPerM2, Double medianPerM2) {}
}
