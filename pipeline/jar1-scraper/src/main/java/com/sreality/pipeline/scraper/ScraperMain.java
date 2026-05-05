package com.sreality.pipeline.scraper;

import com.sreality.pipeline.scraper.db.PostgresLookup;
import com.sreality.pipeline.shared.db.PostgresConnectionPool;
import com.sreality.scraper.config.AppConfig;
import com.sreality.scraper.db.MongoRepository;
import com.sreality.scraper.http.SrealityHttpClient;
import com.sreality.scraper.notify.TelegramNotifier;
import com.sreality.scraper.scraper.ScrapeRunReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JAR 1 entry point — pipeline-aware Sreality scraper.
 *
 * Differences from the original Main.java:
 *   - Opens a Postgres connection for change detection (no more touchLastSeen)
 *   - Uses PipelineEstateScraper instead of EstateScraper
 *   - MongoDB is used only as a staging queue for changed/new estates
 *
 * Additional env vars vs original scraper:
 *   PG_HOST, PG_PORT, PG_DATABASE, PG_USERNAME, PG_PASSWORD, PG_SCHEMA
 */
public class ScraperMain {

    private static final Logger log = LoggerFactory.getLogger(ScraperMain.class);

    public static void main(String[] args) {
        log.info("=== JAR 1: Sreality Scraper (pipeline mode) ===");
        AppConfig config = AppConfig.fromEnv();
        log.info("Config: {}", config);

        try (PostgresConnectionPool pg    = new PostgresConnectionPool();
             MongoRepository        mongo = new MongoRepository(config);
             SrealityHttpClient     http  = new SrealityHttpClient(config)) {

            PostgresLookup        pgLookup = new PostgresLookup(pg);
            PipelineEstateScraper scraper  = new PipelineEstateScraper(
                config, http, mongo, pgLookup);

            scraper.run();
            ScrapeRunReport report = scraper.getLastReport();

            // Save run report to MongoDB scrape_runs collection
            mongo.saveReport(report);

            // Telegram notification — matches real TelegramNotifier(token, chatId) constructor
            new TelegramNotifier(config.telegramBotToken, config.telegramChatId)
                .sendReport(report);

        } catch (Exception e) {
            log.error("JAR 1 fatal: {}", e.getMessage(), e);
            System.exit(1);
        }
        log.info("=== JAR 1 finished ===");
    }
}
