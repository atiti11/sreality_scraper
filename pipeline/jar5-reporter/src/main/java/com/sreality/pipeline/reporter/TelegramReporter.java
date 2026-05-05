package com.sreality.pipeline.reporter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Sends a Markdown-formatted message to a Telegram chat via Bot API.
 *
 * Env vars:
 *   TELEGRAM_BOT_TOKEN  — from @BotFather
 *   TELEGRAM_CHAT_ID    — target chat/channel id
 */
public class TelegramReporter {

    private static final Logger log = LoggerFactory.getLogger(TelegramReporter.class);

    private final String botToken;
    private final String chatId;
    private final ObjectMapper json = new ObjectMapper();

    public TelegramReporter() {
        this.botToken = env("TELEGRAM_BOT_TOKEN", "");
        this.chatId   = env("TELEGRAM_CHAT_ID",   "");
    }

    public boolean isConfigured() {
        return !botToken.isBlank() && !chatId.isBlank();
    }

    /**
     * Sends message to Telegram. Returns true on success.
     * Silently returns false if not configured (allows running without Telegram).
     */
    public boolean send(String markdownText) {
        if (!isConfigured()) {
            log.info("Telegram not configured — skipping notification.");
            return false;
        }
        String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";
        try (CloseableHttpClient http = HttpClients.createDefault()) {
            String body = json.writeValueAsString(Map.of(
                "chat_id",    chatId,
                "text",       markdownText,
                "parse_mode", "Markdown"
            ));
            HttpPost post = new HttpPost(url);
            post.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
            http.execute(post, response -> {
                int code = response.getCode();
                if (code == 200) {
                    log.info("Telegram message sent successfully.");
                } else {
                    log.warn("Telegram API returned HTTP {}", code);
                }
                return null;
            });
            return true;
        } catch (Exception e) {
            log.error("Failed to send Telegram message: {}", e.getMessage());
            return false;
        }
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return (v != null && !v.isBlank()) ? v : def;
    }
}
