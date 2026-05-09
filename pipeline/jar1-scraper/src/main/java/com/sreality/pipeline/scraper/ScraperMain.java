package com.sreality.pipeline.scraper;

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
 * Now uses MongoDB exclusively for change detection (same as the cron scraper).
 * Postgres is intentionally NOT touched here — all SCD writes go through
 * jar4-enricher, which reads Mongo docs whose _updated_at bumped since the
 * last enrichment run.
 *
 * Env vars: standard Mongo + Sreality + Telegram block; Postgres no longer needed.
 */
public class ScraperMain {

    private static final Logger log = LoggerFactory.getLogger(ScraperMain.class);

    public static void main(String[] args) {
        log.info("=== JAR 1: Sreality Scraper (pipeline mode) ===");
        AppConfig config = AppConfig.fromEnv();
        log.info("Config: {}", config);

        try (MongoRepository    mongo = new MongoRepository(config);
             SrealityHttpClient http  = new SrealityHttpClient(config)) {

            PipelineEstateScraper scraper = new PipelineEstateScraper(config, http, mongo);

            scraper.run();
            ScrapeRunReport report = scraper.getLastReport();

            mongo.saveReport(report);

            new TelegramNotifier(config.telegramBotToken, config.telegramChatId)
                .sendReport(report);

        } catch (Exception e) {
            log.error("JAR 1 fatal: {}", e.getMessage(), e);
            System.exit(1);
        }
        log.info("=== JAR 1 finished ===");
    }
}
