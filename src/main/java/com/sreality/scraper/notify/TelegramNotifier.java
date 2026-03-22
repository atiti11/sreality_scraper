package com.sreality.scraper.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sreality.scraper.scraper.ScrapeRunReport;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;

/**
 * Sends a plain-text summary message to a Telegram chat after each scrape run.
 *
 * Required env vars (both must be non-empty for notifications to be sent):
 *   TELEGRAM_BOT_TOKEN  — obtained from @BotFather
 *   TELEGRAM_CHAT_ID    — your personal chat ID (message @userinfobot to get it)
 *
 * If either is missing the notifier silently does nothing.
 */
public class TelegramNotifier {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotifier.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TELEGRAM_API = "https://api.telegram.org/bot%s/sendMessage";

    private final String  botToken;
    private final String  chatId;
    private final boolean enabled;

    public TelegramNotifier(String botToken, String chatId) {
        this.botToken = botToken;
        this.chatId   = chatId;
        this.enabled  = botToken != null && !botToken.isBlank()
                     && chatId  != null && !chatId.isBlank();

        if (!enabled) {
            log.info("TelegramNotifier disabled -- TELEGRAM_BOT_TOKEN or TELEGRAM_CHAT_ID not set");
        }
    }

    /**
     * Sends a scrape-run summary to Telegram.
     * Silently swallows all errors so a notification failure never crashes the scraper.
     */
    public void sendReport(ScrapeRunReport report) {
        if (!enabled) return;

        String message = buildMessage(report);
        try {
            send(message);
            log.info("Telegram notification sent");
        } catch (Exception e) {
            log.warn("Failed to send Telegram notification: {}", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Message formatting — plain text, no Markdown to avoid parse errors
    // -------------------------------------------------------------------------

    private String buildMessage(ScrapeRunReport report) {
        String duration = formatDuration(report.startedAt, report.finishedAt);

        String statusIcon = switch (report.status) {
            case "completed" -> report.totalErrors() == 0 ? "[OK]" : "[WARN]";
            case "partial"   -> "[PARTIAL]";
            default          -> "[FAIL]";
        };

        StringBuilder sb = new StringBuilder();
        sb.append(statusIcon).append(" Sreality scraper -- ").append(report.status.toUpperCase()).append("\n");
        sb.append("\n");
        sb.append("Started:   ").append(report.startedAt).append("\n");
        sb.append("Finished:  ").append(report.finishedAt).append("\n");
        sb.append("Duration:  ").append(duration).append("\n");
        sb.append("\n");
        sb.append("--- Stats ---\n");
        sb.append(String.format("  Processed:      %6d%n", report.totalProcessed));
        sb.append(String.format("  Upserted:       %6d%n", report.totalUpserted));
        sb.append(String.format("  Skipped:        %6d%n", report.totalSkipped));
        sb.append(String.format("  Gone (410):     %6d%n", report.totalGone));
        sb.append(String.format("  Half-success:   %6d%n", report.totalHalfSuccess));
        sb.append(String.format("  Repaired:       %6d%n", report.totalRepaired));
        sb.append(String.format("  Listing errors: %6d%n", report.totalListingErrors));
        sb.append(String.format("  Total errors:   %6d%n", report.totalErrors()));

        if (!report.incompleteEstates.isEmpty()) {
            sb.append("\n--- Incomplete estates (").append(report.incompleteEstates.size()).append(") ---\n");
            int shown = Math.min(report.incompleteEstates.size(), 10);
            for (int i = 0; i < shown; i++) {
                ScrapeRunReport.IncompleteEstate ie = report.incompleteEstates.get(i);
                sb.append(String.format("  %2d. id=%-12d  %-15s  HTTP %d%n",
                    i + 1, ie.hashId, ie.reason, ie.httpStatus));
            }
            if (report.incompleteEstates.size() > 10) {
                sb.append(String.format("  ... and %d more%n",
                    report.incompleteEstates.size() - 10));
            }
        }

        return sb.toString();
    }

    private static String formatDuration(String startedAt, String finishedAt) {
        try {
            Instant  start  = Instant.parse(startedAt);
            Instant  finish = Instant.parse(finishedAt);
            Duration d      = Duration.between(start, finish);
            long h = d.toHours();
            long m = d.toMinutesPart();
            long s = d.toSecondsPart();
            if (h > 0) return String.format("%dh %02dm %02ds", h, m, s);
            if (m > 0) return String.format("%dm %02ds", m, s);
            return String.format("%ds", s);
        } catch (Exception e) {
            return "unknown";
        }
    }

    // -------------------------------------------------------------------------
    // HTTP POST to Telegram API — JSON built via Jackson, no manual escaping
    // -------------------------------------------------------------------------

    private void send(String text) throws Exception {
        String url = String.format(TELEGRAM_API, botToken);

        ObjectNode body = MAPPER.createObjectNode();
        body.put("chat_id", chatId);
        body.put("text",    text);
        // No parse_mode — plain text is safest and requires no escaping rules
        String json = MAPPER.writeValueAsString(body);

        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpPost post = new HttpPost(url);
            post.setHeader("Content-Type", "application/json");
            post.setEntity(new StringEntity(json, ContentType.APPLICATION_JSON));

            client.execute(post, response -> {
                int    status   = response.getCode();
                String respBody = EntityUtils.toString(response.getEntity(), "UTF-8");
                if (status != 200) {
                    log.warn("Telegram API returned HTTP {}: {}", status, respBody);
                } else {
                    log.debug("Telegram API response: {}", respBody);
                }
                return null;
            });
        }
    }
}
