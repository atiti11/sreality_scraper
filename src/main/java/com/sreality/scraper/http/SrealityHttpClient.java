package com.sreality.scraper.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sreality.scraper.config.AppConfig;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Thin wrapper around Apache HttpClient 5 that:
 *  - sets a realistic browser User-Agent (required by sreality.cz)
 *  - applies connect / read timeouts from AppConfig
 *  - returns parsed Jackson JsonNode
 *  - throws SrealityHttpException on non-200 or parse errors
 */
public class SrealityHttpClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SrealityHttpClient.class);

    private static final String USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Safari/537.36";

    private final RequestConfig requestConfig;
    // ObjectMapper is thread-safe and reused, but we recreate the HTTP client
    // per request to prevent connection pool memory accumulation.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public SrealityHttpClient(AppConfig config) {
        this.requestConfig = RequestConfig.custom()
            .setConnectTimeout(Timeout.of(config.httpConnectTimeoutMs, TimeUnit.MILLISECONDS))
            .setResponseTimeout(Timeout.of(config.httpReadTimeoutMs, TimeUnit.MILLISECONDS))
            .build();
    }

    /**
     * Performs a GET request and returns the parsed JSON body.
     *
     * @param url full URL to fetch
     * @return parsed JsonNode
     * @throws SrealityHttpException if the server returns a non-200 status
     * @throws IOException           on network / parse errors
     */
    public JsonNode get(String url) throws IOException {
        HttpGet request = new HttpGet(url);
        request.setHeader("User-Agent",  USER_AGENT);
        request.setHeader("Accept",      "application/json");
        request.setHeader("Accept-Language", "cs,en;q=0.9");
        request.setHeader("Connection", "close");

        log.debug("GET {}", url);

        // Create a fresh HTTP client per request — this ensures the connection
        // pool and all internal buffers are fully released after each call.
        // The performance cost is negligible given the 500ms delay between requests.
        try (CloseableHttpClient client = HttpClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .build()) {
            return client.execute(request, response -> {
                int status = response.getCode();
                String body = EntityUtils.toString(response.getEntity(), "UTF-8");

                if (status == 404 || status == 410) {
                    throw new SrealityHttpException(status, url,
                        status == 410 ? "Gone (estate sold or removed)" : "Not found");
                }
                if (status != 200) {
                    throw new SrealityHttpException(status, url,
                        "Unexpected status " + status + ": " + body.substring(0, Math.min(200, body.length())));
                }

                return OBJECT_MAPPER.readTree(body);
            });
        }
    }

    @Override
    public void close() throws IOException {
        // Nothing to close — HTTP clients are created and closed per request
    }

    // -------------------------------------------------------------------------
    // Custom exception that carries the HTTP status code
    // -------------------------------------------------------------------------
    public static class SrealityHttpException extends IOException {
        private final int statusCode;
        private final String url;

        public SrealityHttpException(int statusCode, String url, String message) {
            super(message);
            this.statusCode = statusCode;
            this.url        = url;
        }

        public int getStatusCode() { return statusCode; }
        public String getUrl()     { return url; }

        /** Returns true for 404 (not found) and 410 (gone / sold) — both are expected and handled gracefully. */
        public boolean isNotFound() { return statusCode == 404 || statusCode == 410; }
        public boolean isGone()     { return statusCode == 410; }
    }
}
