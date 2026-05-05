package com.sreality.pipeline.reporter;

import com.sreality.pipeline.shared.db.PostgresConnectionPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JAR 5 entry point — Telegram Reporter.
 *
 * Queries Postgres for interesting stats and sends a formatted summary
 * to a Telegram chat.
 *
 * Env vars:
 *   PG_*                Postgres connection
 *   TELEGRAM_BOT_TOKEN  Telegram bot token
 *   TELEGRAM_CHAT_ID    Target chat id
 */
public class ReporterMain {

    private static final Logger log = LoggerFactory.getLogger(ReporterMain.class);

    public static void main(String[] args) {
        log.info("=== JAR 5: Telegram Reporter ===");

        try (PostgresConnectionPool pg = new PostgresConnectionPool()) {
            String report = new ReportQuery(pg).buildReport();
            log.info("Report:\n{}", report);

            TelegramReporter telegram = new TelegramReporter();
            if (telegram.isConfigured()) {
                telegram.send(report);
            } else {
                log.info("Telegram not configured — report printed to log only.");
            }
        } catch (Exception e) {
            log.error("JAR 5 failed: {}", e.getMessage(), e);
            System.exit(1);
        }
        log.info("=== JAR 5 finished ===");
    }
}
