package com.sreality.dashboard.util;

import com.sreality.dashboard.DealType;
import com.sreality.dashboard.PropertyType;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reconstruct working {@code sreality.cz} detail URLs.
 *
 * <p>The scraper stores {@code sreality_url} as whatever the source
 * response shipped — often just the hash with all the slug segments
 * dashed out, like {@code https://www.sreality.cz/detail/-/-/-/3679096908}.
 * Those URLs 404 on sreality.cz because the site routes on the slug, not
 * on the trailing numeric id. We rebuild them from the data we already
 * have: deal type, property type, and the obec / cast_obce names that
 * the listings query joins anyway.</p>
 *
 * <p>Sub-category is per-table (apartments expose "1+kk", "2+1" …; other
 * property types use different vocabularies and the column isn't always
 * populated). We pass a dash for the sub segment — sreality's URL parser
 * only really needs deal/property type + the trailing id to redirect to
 * the right detail page.</p>
 */
public final class SrealityUrl {

    private SrealityUrl() {}

    private static final Map<DealType, String> DEAL_CZ = Map.of(
        DealType.SALE,    "prodej",
        DealType.RENT,    "pronajem",
        DealType.AUCTION, "drazba"
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
     */
    public static String build(
        DealType deal,
        String propertyTypeToken,
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

        // Locality slug: "praha-holesovice" assembled from obec + cast_obce.
        // Drop empty parts so a missing cast_obce doesn't leak as a stray
        // trailing dash.
        List<String> parts = new ArrayList<>();
        String obecSlug = slugifyCz(obec);
        if (!obecSlug.isEmpty()) parts.add(obecSlug);
        String castSlug = slugifyCz(castObce);
        if (!castSlug.isEmpty()) parts.add(castSlug);
        String slug = parts.isEmpty() ? "-" : String.join("-", parts);

        return "https://www.sreality.cz/detail/" + dealCz + "/" + propCz
             + "/-/" + slug + "/" + hashId;
    }

    /**
     * "Hlavní město Praha" → "hlavni-mesto-praha". NFD-normalises to
     * strip combining diacritics, lowercases, replaces everything that
     * isn't [a-z0-9] with '-', and trims stray dashes at the edges.
     */
    static String slugifyCz(String s) {
        if (s == null || s.isEmpty()) return "";
        String nfd = Normalizer.normalize(s, Normalizer.Form.NFD);
        // Drop combining diacritic marks.
        StringBuilder sb = new StringBuilder(nfd.length());
        for (int i = 0; i < nfd.length(); i++) {
            char c = nfd.charAt(i);
            if (Character.getType(c) != Character.NON_SPACING_MARK) {
                sb.append(c);
            }
        }
        String lower = sb.toString().toLowerCase(Locale.ROOT);
        // Replace runs of non-alphanumerics with a single dash.
        String dashed = lower.replaceAll("[^a-z0-9]+", "-");
        // Trim leading / trailing dashes.
        int start = 0, end = dashed.length();
        while (start < end && dashed.charAt(start) == '-') start++;
        while (end > start && dashed.charAt(end - 1) == '-') end--;
        return dashed.substring(start, end);
    }
}
