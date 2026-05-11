package com.sreality.dashboard;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.http.HandlerType;
import io.javalin.http.UnauthorizedResponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

/**
 * Pre-routing Basic Auth gate. Same credentials the frontend nginx checks
 * against {@code /etc/nginx/.htpasswd}; validating them here too keeps the
 * API protected if someone ever reaches port 8000 directly.
 *
 * <p>Public paths: only the healthcheck and the root info endpoint.
 * Everything else under {@code /api/*} requires authentication.</p>
 *
 * <p>If either {@code DASHBOARD_USER} or {@code DASHBOARD_PASSWORD} is
 * unset, the gate is a no-op (handy for {@code mvn exec} in dev).</p>
 */
public final class AuthGate implements Handler {

    private static final Set<String> PUBLIC_PATHS = Set.of("/", "/api/health");

    private final String user;
    private final String pass;
    private final boolean enabled;

    public AuthGate() {
        this.user = Config.dashboardUser();
        this.pass = Config.dashboardPassword();
        this.enabled = !user.isEmpty() && !pass.isEmpty();
    }

    @Override
    public void handle(Context ctx) {
        if (!enabled) return;

        // CORS preflights ship without credentials; let them through so the
        // browser can complete its handshake before prompting for a password.
        if (ctx.method() == HandlerType.OPTIONS) return;

        if (PUBLIC_PATHS.contains(ctx.path())) return;

        String header = ctx.header("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
            throw unauthorized();
        }

        String decoded;
        try {
            byte[] raw = Base64.getDecoder().decode(header.substring(6).trim());
            decoded = new String(raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw unauthorized();
        }

        int colon = decoded.indexOf(':');
        if (colon < 0) throw unauthorized();

        String presentedUser = decoded.substring(0, colon);
        String presentedPass = decoded.substring(colon + 1);

        // Constant-time compare to keep the verifier from leaking the
        // expected length / prefix via response timing.
        if (!constantTimeEquals(presentedUser, user)
            || !constantTimeEquals(presentedPass, pass)) {
            throw unauthorized();
        }
    }

    private static UnauthorizedResponse unauthorized() {
        // The WWW-Authenticate header is what convinces curl / browsers to
        // surface a credential prompt. Javalin will merge it with the 401
        // response below.
        return new UnauthorizedResponse(
            "Authentication required.",
            Map.of("WWW-Authenticate", "Basic realm=\"Sreality Dashboard API\"")
        );
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] ab = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(ab, bb);
    }
}
