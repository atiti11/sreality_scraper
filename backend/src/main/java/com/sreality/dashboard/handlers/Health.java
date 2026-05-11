package com.sreality.dashboard.handlers;

import io.javalin.http.Context;
import io.javalin.http.Handler;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/** {@code GET /api/health} — liveness + DB ping. Public, no auth. */
public final class Health implements Handler {

    private final DataSource ds;

    public Health(DataSource ds) {
        this.ds = ds;
    }

    @Override
    public void handle(Context ctx) throws Exception {
        boolean dbOk;
        try (Connection c = ds.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1")) {
            dbOk = rs.next() && rs.getInt(1) == 1;
        } catch (Exception e) {
            dbOk = false;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("db", dbOk);
        ctx.json(body);
    }
}
