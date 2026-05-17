package com.sreality.dashboard.util;

import com.sreality.dashboard.DealType;
import com.sreality.dashboard.PropertyType;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;

/**
 * Reconstruct working {@code sreality.cz} detail URLs.
 *
 * <p>The scraper stores {@code sreality_url} as whatever the source
 * response shipped — often just the hash with all the slug segments
 * dashed out, like {@code https://www.sreality.cz/detail/-/-/-/3679096908}.
 * Those URLs 404 because the site routes on the slug, not on the
 * trailing numeric id. We rebuild a closer match from the data we
 * actually have.</p>
 *
 * <p>Target format (as emitted by sreality.cz itself):</p>
 * <pre>
 *   /detail/{deal}/{property}/{sub_category}/{obec}-{castObce}-{street}/{hash}
 * </pre>
 *
 * <p>We have the first four segments and obec / castObce. Sreality's
 * router is forgiving about the street slug being empty as long as the
 * dash that separates it from castObce is still there — so we always
 * close the locality slug with a trailing dash. Examples:</p>
 *
 * <pre>
 *   prodej/byt/3+1/vseradice-vseradice-/238465100
 *   pronajem/byt/3+kk/harrachov-harrachov-/2337710156
 *   drazby/pozemek/-/zizkovo-pole-macourov-/2030735436
 * </pre>
 *
 * <p>Sub-category is only known for apartments today (their fact tables
 * store {@code sub_category} = "1+kk", "2+1", …). Houses and land have
 * their own taxonomies in sreality's URLs ("rodinny", "les", …) but we
 * don't have those values in a form clean enough to slugify
 * deterministically, so we emit a dash for non-apartment types and
 * accept that those URLs are "best-effort" rather than exact.</p>
 */
public final class SrealityUrl {

    private SrealityUrl() {}

    private static final Map<DealType, String> DEAL_CZ = Map.of(
        DealType.SALE,    "prodej",
        DealType.RENT,    "pronajem",
        // sreality emits the plural in URLs, even for a single auction.
        DealType.AUCTION, "drazby"
    );

    private static final Map<PropertyType, String> PROP_CZ = Map.of(
        PropertyType.APARTMENT,  "byt",
        PropertyType.HOUSE,      "dum",
        PropertyType.LAND,       "pozemek",
        PropertyType.COMMERCIAL, "nebytove-prostory"
    );

    /**
     * Build the URL. Returns {@code null} when {@code hashId} is null —
     * there's nothing to link to without an estate id.
     *
     * @param subCategory apartment dispozice ("1+kk", "2+1", …) or
     *                    {@code null} for property types that don't
     *                    expose one.
     */
    public static String build(
        DealType deal,
        String propertyTypeToken,
        String subCategory,
        String obec,
        String castObce,
        Long hashId
    ) {
        if (hashId == null) return null;

        String dealCz = deal != null ? DEAL_CZ.getOrDefault(deal, "-") : "-";

        String propCz = "-";
        if (propertyTypeToken != null) {
            try {
                propCz = PROP_CZ.getOrDefault(
                    PropertyType.fromQueryToken(propertyTypeToken), "-"
                );
            } catch (IllegalArgumentException ignored) {
                propCz = "-";
            }
        }

        // Sub-category: passed through verbatim (lowercased + trimmed).
        // sreality URLs preserve "+" in dispozice slugs like "2+kk", so
        // we don't URL-encode here. If the value is null / blank, the
        // segment falls back to a single dash to match sreality's
        // own placeholder.
        String subSlug = "-";
        if (subCategory != null && !subCategory.isBlank()) {
            subSlug = subCategory.trim().toLowerCase(Locale.ROOT);
        }

        // Locality slug. Sreality always emits {obec}-{castObce}-{street}
        // with the street part possibly empty (trailing dash kept). We
        // don't have street data in the warehouse, so we always close
        // the slug with the trailing dash.
        String obecSlug = slugifyCz(obec);
        String castSlug = slugifyCz(castObce);
        String slug;
        if (obecSlug.isEmpty() && castSlug.isEmpty()) {
            slug = "-";
        } else {
            slug = obecSlug + "-" + castSlug + "-";
        }

        return "https://www.sreality.cz/detail/" + dealCz + "/" + propCz
             + "/" + subSlug + "/" + slug + "/" + hashId;
    }

    /**
     * "Hlavní město Praha" → "hlavni-mesto-praha". NFD-normalises to
     * strip combining diacritics, lowercases, replaces everything that
     * isn't [a-z0-9] with '-', and trims stray dashes at the edges.
     */
    static String slugifyCz(String s) {
        if (s == null || s.isEmpty()) return "";
        String nfd = Normalizer.normalize(s, Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(nfd.length());
        for (int i = 0; i < nfd.length(); i++) {
            char c = nfd.charAt(i);
            if (Character.getType(c) != Character.NON_SPACING_MARK) {
                sb.append(c);
            }
        }
        String lower = sb.toString().toLowerCase(Locale.ROOT);
        String dashed = lower.replaceAll("[^a-z0-9]+", "-");
        int start = 0, end = dashed.length();
        while (start < end && dashed.charAt(start) == '-') start++;
        while (end > start && dashed.charAt(end - 1) == '-') end--;
        return dashed.substring(start, end);
    }
}
