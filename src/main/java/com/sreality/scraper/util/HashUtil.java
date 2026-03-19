package com.sreality.scraper.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Utility for computing MD5 change-detection hashes.
 *
 * The hash covers: hash_id + price_czk.value_raw + name + labels
 * If none of these changed since the last scrape, we skip the detail fetch.
 */
public class HashUtil {

    private HashUtil() {}

    /**
     * Compute MD5 hex string of the concatenation of all provided parts,
     * separated by a pipe character to avoid accidental collisions.
     */
    public static String md5(Object... parts) {
        StringBuilder sb = new StringBuilder();
        for (Object part : parts) {
            sb.append(part == null ? "null" : part.toString());
            sb.append("|");
        }
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // MD5 is guaranteed to be present in every JVM
            throw new RuntimeException("MD5 not available", e);
        }
    }
}
