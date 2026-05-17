package com.sreality.scraper;

import com.sreality.scraper.config.AppConfig;
import com.sreality.scraper.db.MongoRepository;
import com.sreality.scraper.http.SrealityHttpClient;
import com.sreality.scraper.notify.TelegramNotifier;
import com.sreality.scraper.scraper.EstateScraper;
import com.sreality.scraper.scraper.ScrapeRunReport;
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
        AppConfig        config   = AppConfig.fromEnv();
        TelegramNotifier telegram = new TelegramNotifier(config.telegramBotToken, config.telegramChatId);

        log.info("Starting sreality scraper with config: {}", config);

        ScrapeRunReport report = null;
        try (
            SrealityHttpClient http  = new SrealityHttpClient(config);
            MongoRepository    mongo = new MongoRepository(config)
        ) {
            EstateScraper scraper = new EstateScraper(config, http, mongo);
            scraper.run();
            report = scraper.getLastReport();

        } catch (Exception e) {
            log.error("Fatal error during scrape run", e);
            if (report != null) telegram.sendReport(report);
            System.exit(1);
        }

        if (report != null) telegram.sendReport(report);
        log.info("Scraper finished — exiting.");
    }
}
