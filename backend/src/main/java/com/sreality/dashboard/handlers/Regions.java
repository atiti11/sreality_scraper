package com.sreality.dashboard.handlers;

import io.javalin.http.Context;
import io.javalin.http.Handler;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/regions/tree} — full geography hierarchy
 * ({@code kraje}, {@code okresy}, {@code obce}) for the listings page's
 * cascading region picker. Cached client-side, so we don't bother caching
 * server-side.
 */
public final class Regions implements Handler {

    private final DataSource ds;

    public Regions(DataSource ds) {
        this.ds = ds;
    }

    @Override
    public void handle(Context ctx) throws Exception {
        List<Map<String, Object>> kraje  = new ArrayList<>();
        List<Map<String, Object>> okresy = new ArrayList<>();
        List<Map<String, Object>> obce   = new ArrayList<>();

        try (Connection c = ds.getConnection();
             Statement st = c.createStatement()) {

            try (ResultSet rs = st.executeQuery(
                "SELECT id, kod_kraje AS code, nazev_kraje AS name "
              + "FROM dim_kraj ORDER BY nazev_kraje")) {
                while (rs.next()) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("id",   rs.getInt("id"));
                    r.put("code", rs.getString("code"));
                    r.put("name", rs.getString("name"));
                    kraje.add(r);
                }
            }

            try (ResultSet rs = st.executeQuery(
                "SELECT id, kod_okresu AS code, nazev_okresu AS name, kraj_id "
              + "FROM dim_okres ORDER BY nazev_okresu")) {
                while (rs.next()) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("id",      rs.getInt("id"));
                    r.put("code",    rs.getString("code"));
                    r.put("name",    rs.getString("name"));
                    r.put("kraj_id", rs.getInt("kraj_id"));
                    okresy.add(r);
                }
            }

            try (ResultSet rs = st.executeQuery(
                "SELECT id, kod_obce AS code, nazev_obce AS name, okres_id "
              + "FROM dim_obec WHERE is_active ORDER BY nazev_obce")) {
                while (rs.next()) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("id",       rs.getInt("id"));
                    r.put("code",     rs.getString("code"));
                    r.put("name",     rs.getString("name"));
                    r.put("okres_id", rs.getInt("okres_id"));
                    obce.add(r);
                }
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kraje",  kraje);
        body.put("okresy", okresy);
        body.put("obce",   obce);
        ctx.json(body);
    }
}
