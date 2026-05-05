package com.sreality.pipeline.shared.model;

/**
 * FNV-1a 64-bit content hash for estate change detection.
 *
 * Used by JAR 1 (scraper) to compare API data against Postgres,
 * and by JAR 4 (enricher) to store the full hash in the fact table.
 *
 * Fields included: price, areas, sub_category, all labels, all boolean features.
 * Fields excluded: image count, description text (cosmetic).
 */
public final class ContentHasher {

    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME  = 0x00000100000001b3L;

    private ContentHasher() {}

    /**
     * Computes hash from resolved string values. Pass null for absent fields —
     * null hashes as the literal "null" so null→value transitions are detected.
     */
    public static long compute(
            String price,
            String usableArea,
            String plotArea,
            String gardenArea,
            String subCategory,
            String labels,
            String booleanFeatures) {
        long h = FNV_OFFSET;
        h = mix(h, price);
        h = mix(h, usableArea);
        h = mix(h, plotArea);
        h = mix(h, gardenArea);
        h = mix(h, subCategory);
        h = mix(h, labels);
        h = mix(h, booleanFeatures);
        return h;
    }

    private static long mix(long hash, String value) {
        String s = value == null ? "null" : value;
        for (int i = 0; i < s.length(); i++) {
            hash ^= s.charAt(i);
            hash *= FNV_PRIME;
        }
        hash ^= '|';
        hash *= FNV_PRIME;
        return hash;
    }
}
