package com.sreality.scraper;

import com.sreality.scraper.config.AppConfig;
import com.sreality.scraper.db.MongoRepository;
import com.sreality.scraper.http.SrealityHttpClient;
import com.sreality.scraper.scraper.EstateScraper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point.
 *
 * The scraper is intentionally stateless — it runs once and exits.
 * Periodic execution is managed externally (cron / docker restart policy).
 *
 * Environment variables (see AppConfig / .env.example for full list):
 *   PER_PAGE     — number of estates per listing API call  (default: 100)
 *   MAX_ESTATES  — dev limiter: stop after N estates       (default: 0 = unlimited)
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        AppConfig config = AppConfig.fromEnv();
        log.info("Starting sreality scraper with config: {}", config);

        try (
            SrealityHttpClient http  = new SrealityHttpClient(config);
            MongoRepository    mongo = new MongoRepository(config)
        ) {
            EstateScraper scraper = new EstateScraper(config, http, mongo);
            scraper.run();

        } catch (Exception e) {
            log.error("Fatal error during scrape run", e);
            System.exit(1);
        }

        log.info("Scraper finished — exiting.");
    }
}
