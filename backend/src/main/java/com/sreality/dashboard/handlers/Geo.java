package com.sreality.dashboard.handlers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sreality.dashboard.Config;
import com.sreality.dashboard.DealType;
import com.sreality.dashboard.PropertyType;
import com.sreality.dashboard.RegionLevel;
import com.sreality.dashboard.sql.Queries;

import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.Handler;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/geo/{level}} — GeoJSON FeatureCollection of {@code kraj}
 * or {@code okres} polygons with the same aggregations the markers
 * endpoint serves. Kept around for legacy clients; the current frontend
 * uses {@link Markers} everywhere because polygon columns aren't loaded
 * into this snapshot.
 */
public final class Geo implements Handler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DataSource ds;

    public Geo(DataSource ds) {
        this.ds = ds;
    }

    @Override
    public void handle(Context ctx) throws Exception {
        RegionLevel level;
        try {
            level = RegionLevel.fromPath(ctx.pathParam("level"));
        } catch (IllegalArgumentException e) {
            throw new BadRequestResponse("level must be 'kraj' or 'okres'");
        }
        if (level != RegionLevel.KRAJ && level != RegionLevel.OKRES) {
            throw new BadRequestResponse("level must be 'kraj' or 'okres'");
        }

        DealType deal = DealType.fromQueryToken(ctx.queryParam("deal"));
        List<PropertyType> ptypes = Config.parsePropertyTypes(ctx.queryParam("property_types"));
        Integer parentId = parseIntOrNull(ctx.queryParam("parent_id"));

        // Step 1: geometries.
        record Geometry(int id, String code, String name, Integer parentId, JsonNode geom) {}
        List<Geometry> geometries = new ArrayList<>();

        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(Queries.geoQuery(level));
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int pid = rs.getInt("parent_id");
                Integer pidBoxed = rs.wasNull() ? null : pid;
                if (parentId != null && level == RegionLevel.OKRES) {
                    if (pidBoxed == null || !pidBoxed.equals(parentId)) continue;
                }
                geometries.add(new Geometry(
                    rs.getInt("id"),
                    rs.getString("code"),
                    rs.getString("name"),
                    pidBoxed,
                    parseGeoJson(rs.getString("geom"))
                ));
            }
        }

        // Step 2: aggregations keyed by region id.
        String factsCte = Queries.buildFactsCte(deal, ptypes, "");
        String aggSql   = "WITH facts AS (" + factsCte + ") " + Queries.aggregationQuery(level);
        Map<Integer, MarkersAgg> byId = new HashMap<>();
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
                byId.put(rid, new MarkersAgg(n, avgNull ? null : avg, medNull ? null : med));
            }
        }

        // Step 3: assemble FeatureCollection.
        List<Map<String, Object>> features = new ArrayList<>();
        for (Geometry g : geometries) {
            MarkersAgg agg = byId.get(g.id());
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("id",            g.id());
            props.put("code",          g.code());
            props.put("name",          g.name());
            props.put("parent_id",     g.parentId());
            props.put("n",             agg == null ? 0 : agg.n());
            props.put("avg_per_m2",    agg == null ? null : agg.avgPerM2());
            props.put("median_per_m2", agg == null ? null : agg.medianPerM2());

            Map<String, Object> feat = new LinkedHashMap<>();
            feat.put("type",       "Feature");
            feat.put("geometry",   g.geom());
            feat.put("properties", props);
            features.add(feat);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type",     "FeatureCollection");
        body.put("features", features);
        body.put("level",    level.token());
        ctx.json(body);
    }

    private static JsonNode parseGeoJson(String raw) {
        if (raw == null) return null;
        try {
            return MAPPER.readTree(raw);
        } catch (JsonProcessingException e) {
            // PostGIS shouldn't emit invalid GeoJSON, but if it does we
            // surface the raw string rather than 500ing the whole map.
            return null;
        }
    }

    private static Integer parseIntOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return null; }
    }

    private record MarkersAgg(long n, Double avgPerM2, Double medianPerM2) {}
}
