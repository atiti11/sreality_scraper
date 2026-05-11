package com.sreality.dashboard;

import com.sreality.dashboard.handlers.*;

import io.javalin.Javalin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sreality Dashboard API — Javalin entry point.
 *
 * <p>Read-only aggregation API over the Sreality data warehouse.
 * Endpoints power two views in the SPA:</p>
 *
 * <ul>
 *   <li><b>Map</b> — choropleth over RUIAN admin boundaries (kraj →
 *       cast_obce) coloured by avg price/m². Side panel shows region
 *       stats + recent price changes when the user clicks a polygon.</li>
 *   <li><b>Listings</b> — paginated, filterable list of properties.</li>
 *   <li><b>Correlation</b> — scatter of avg price/m² (per obec) vs a
 *       chosen CSU metric, with a Pearson coefficient.</li>
 * </ul>
 *
 * <p>Stack: Javalin (HTTP, on Jetty), Jackson (JSON), JDBC (PostgreSQL,
 * pooled via HikariCP). Auth: HTTP Basic against
 * {@code DASHBOARD_USER}/{@code DASHBOARD_PASSWORD} env vars; disabled
 * when either is unset.</p>
 */
public final class App {

    public static void main(String[] args) {
        Db db = new Db();
        AuthGate auth = new AuthGate();

        Javalin app = Javalin.create(cfg -> {
            // ---- CORS -----------------------------------------------------
            // The frontend is served same-origin via nginx in prod, so CORS
            // is mostly cosmetic. Local dev's Vite server runs on a
            // different port though, hence the explicit allow list.
            cfg.bundledPlugins.enableCors(cors -> {
                cors.addRule(it -> {
                    for (String origin : Config.corsOrigins()) {
                        it.allowHost(origin);
                    }
                    // Browser Basic Auth credentials are only sent on
                    // requests flagged ``credentials: include``; tell CORS
                    // to honour that.
                    it.allowCredentials = true;
                });
            });

            cfg.showJavalinBanner = false;
        });

        // ---- Pre-routing Basic Auth gate ----------------------------------
        app.before(auth);

        // ---- Routes -------------------------------------------------------
        // Public — no auth, no DB if the gate didn't even consult one.
        app.get("/",            App::root);
        app.get("/api/health",  new Health(db.dataSource()));

        // Auth-verification probe used by the React login screen. The
        // AuthGate above already validates the inbound Authorization
        // header; the handler just returns the configured username so the
        // SPA can render "signed in as …" in the header.
        app.get("/api/auth/me",                                   new Auth());

        app.get("/api/regions/tree",                              new Regions(db.dataSource()));
        app.get("/api/markers/{level}",                           new Markers(db.dataSource()));
        app.get("/api/geo/{level}",                               new Geo(db.dataSource()));
        app.get("/api/region/{level}/{region_id}/stats",          new RegionStats(db.dataSource()));
        app.get("/api/region/{level}/{region_id}/price-changes",  new PriceChanges(db.dataSource()));
        app.get("/api/listings",                                  Listings.list(db.dataSource()));
        app.get("/api/listings/count",                            Listings.count(db.dataSource()));
        app.get("/api/scatter/csu-metrics",                       Scatter.catalog());
        app.get("/api/scatter/price-vs-csu",                      Scatter.priceVsCsu(db.dataSource()));

        // ---- Lifecycle ----------------------------------------------------
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            app.stop();
            db.close();
        }));

        app.start(Config.apiHost(), Config.apiPort());
    }

    /** Root endpoint — service info, mirrors the Python version. */
    private static void root(io.javalin.http.Context ctx) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", "Sreality Dashboard API");
        body.put("version", "0.3.0 (Java/Javalin)");
        body.put("docs",    "/api/health");
        body.put("endpoints", List.of(
            "/api/health",
            "/api/auth/me",
            "/api/regions/tree",
            "/api/markers/{level}?deal=&property_types=&bbox=",
            "/api/geo/{level}?deal=&property_types=&parent_id= (legacy, requires polygons)",
            "/api/region/{level}/{id}/stats?deal=&property_types=",
            "/api/region/{level}/{id}/price-changes?deal=&property_types=&limit=",
            "/api/listings?...filters",
            "/api/listings/count?...filters",
            "/api/scatter/csu-metrics",
            "/api/scatter/price-vs-csu?metric=&deal=&property_types=&min_listings="
        ));
        ctx.json(body);
    }
}
