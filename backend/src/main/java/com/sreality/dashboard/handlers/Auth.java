package com.sreality.dashboard.handlers;

import com.sreality.dashboard.Config;

import io.javalin.http.Context;
import io.javalin.http.Handler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code GET /api/auth/me} — credential-verification endpoint used by the
 * React login screen. The actual validation happens in
 * {@link com.sreality.dashboard.AuthGate} before this handler runs, so a
 * 200 from this endpoint means the {@code Authorization} header was
 * accepted. The handler returns the configured username so the UI can
 * greet the user.
 *
 * <p>When auth is disabled (no DASHBOARD_USER set), this handler reports
 * {@code authenticated=false} but still returns 200 — the SPA treats that
 * as "auth not required" and skips the login screen entirely.</p>
 */
public final class Auth implements Handler {

    @Override
    public void handle(Context ctx) {
        String user = Config.dashboardUser();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user",          user.isEmpty() ? null : user);
        body.put("authenticated", !user.isEmpty());
        ctx.json(body);
    }
}
