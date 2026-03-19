package com.sreality.scraper.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.sreality.scraper.config.CategoryConfig;
import com.sreality.scraper.config.LabelConfig;
import com.sreality.scraper.util.DateParser;
import com.sreality.scraper.util.HashUtil;
import org.bson.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a MongoDB {@link Document} from the two API responses:
 *   - listingNode  : one estate object from GET /estates  (_embedded.estates[i])
 *   - detailNode   : full response from GET /estates/{id}  (may be null if 404)
 *
 * All known codes are mapped to human-readable English values.
 * Unknown codes are stored as-is.
 */
public class EstateDocumentBuilder {

    private EstateDocumentBuilder() {}

    // -------------------------------------------------------------------------
    // Public entry point
    // -------------------------------------------------------------------------

    /**
     * Build the combined MongoDB document.
     *
     * @param listingNode  the estate object from the listing endpoint
     * @param detailNode   the full detail response (null if the detail fetch failed)
     * @return a MongoDB Document ready for upsert
     */
    public static Document build(JsonNode listingNode, JsonNode detailNode) {
        Document doc = new Document();

        // --- IDs ---
        long hashId = listingNode.path("hash_id").asLong();
        doc.append("hash_id",     hashId);
        doc.append("sreality_url", "https://www.sreality.cz/cs/v2/estates/" + hashId);

        // --- Categories (mapped) ---
        int categoryMainCb = listingNode.path("category").asInt();
        int categoryTypeCb = listingNode.path("type").asInt();
        doc.append("category_main_cb", categoryMainCb);
        doc.append("category_type_cb", categoryTypeCb);
        doc.append("property_type",
            CategoryConfig.PROPERTY_TYPE.containsKey(categoryMainCb)
                ? CategoryConfig.PROPERTY_TYPE.get(categoryMainCb).label()
                : "unknown_" + categoryMainCb);
        doc.append("deal_type",
            CategoryConfig.DEAL_TYPE.containsKey(categoryTypeCb)
                ? CategoryConfig.DEAL_TYPE.get(categoryTypeCb).label()
                : "unknown_" + categoryTypeCb);

        // Sub-category from seo node
        JsonNode seo = listingNode.path("seo");
        int subCb = seo.path("category_sub_cb").asInt(-1);
        if (subCb > 0) {
            doc.append("category_sub_cb", subCb);
            doc.append("sub_category",
                CategoryConfig.SUB_CATEGORY.getOrDefault(subCb, "unknown_" + subCb));
        }

        // --- Name ---
        doc.append("name", listingNode.path("name").asText(""));

        // --- Locality ---
        doc.append("locality", listingNode.path("locality").asText(""));
        JsonNode seoLocality = seo.path("locality");
        if (!seoLocality.isMissingNode()) {
            doc.append("seo_locality", seoLocality.asText());
        }

        // --- Price ---
        doc.append("price_raw",    listingNode.path("price").asLong());
        JsonNode priceCzk = listingNode.path("price_czk");
        doc.append("price_czk_value", priceCzk.path("value_raw").asLong());
        doc.append("price_czk_unit",  priceCzk.path("unit").asText(""));
        doc.append("price_czk_name",  priceCzk.path("name").asText(""));

        // --- GPS ---
        JsonNode gps = listingNode.path("gps");
        if (!gps.isMissingNode()) {
            doc.append("gps_lat", gps.path("lat").asDouble());
            doc.append("gps_lon", gps.path("lon").asDouble());
        }

        // --- Boolean flags from listing ---
        doc.append("is_auction",         listingNode.path("is_auction").asBoolean(false));
        doc.append("has_floor_plan",      listingNode.path("has_floor_plan").asInt(0) == 1);
        doc.append("has_panorama",        listingNode.path("has_panorama").asInt(0) == 1);
        doc.append("has_video",           listingNode.path("has_video").asBoolean(false));
        doc.append("has_matterport",      listingNode.path("has_matterport_url").asBoolean(false));
        doc.append("is_new",              listingNode.path("new").asBoolean(false));
        doc.append("is_attractive",       listingNode.path("attractive_offer").asInt(0) == 1);
        doc.append("exclusively_at_rk",   listingNode.path("exclusively_at_rk").asInt(0) == 1);
        doc.append("auction_price",       listingNode.path("auctionPrice").asDouble(0));
        doc.append("advert_images_count", listingNode.path("advert_images_count").asInt(0));

        // --- Labels (mapped) ---
        doc.append("property_features",      mapLabels(listingNode.path("labelsReleased"), 0, true));
        doc.append("nearby_poi",              mapLabels(listingNode.path("labelsReleased"), 1, false));
        doc.append("property_features_full", mapLabels(listingNode.path("labelsAll"), 0, true));
        doc.append("nearby_poi_full",         mapLabels(listingNode.path("labelsAll"), 1, false));

        // --- Company / agency from listing ---
        JsonNode company = listingNode.path("_embedded").path("company");
        if (!company.isMissingNode()) {
            doc.append("agency", new Document()
                .append("id",   company.path("id").asInt())
                .append("name", company.path("name").asText())
                .append("url",  company.path("url").asText()));
        }

        // --- Change-detection hash ---
        String labelsStr = doc.get("property_features") != null
            ? doc.get("property_features").toString() : "";
        doc.append("_content_hash", HashUtil.md5(
            hashId,
            priceCzk.path("value_raw").asLong(),
            listingNode.path("name").asText(""),
            labelsStr
        ));

        // --- Scrape metadata ---
        doc.append("_scraped_at",          Instant.now().toString());
        doc.append("_detail_available",    detailNode != null);
        // last_update_corrupted = true when the last scrape attempt stored only
        // listing data (detail failed). false = document is fully complete.
        // Used for quick queries: db.<col>.countDocuments({last_update_corrupted: true})
        doc.append("last_update_corrupted", detailNode == null);

        // =====================================================================
        // Detail endpoint fields (only if detail was successfully fetched)
        // =====================================================================
        if (detailNode != null) {
            appendDetailFields(doc, detailNode);
        }

        return doc;
    }

    // -------------------------------------------------------------------------
    // Detail fields
    // -------------------------------------------------------------------------

    private static void appendDetailFields(Document doc, JsonNode detail) {

        // Description text
        JsonNode textNode = detail.path("text").path("value");
        if (!textNode.isMissingNode()) {
            doc.append("description", textNode.asText());
        }

        // Locality accuracy
        JsonNode locality = detail.path("locality");
        if (!locality.isMissingNode()) {
            doc.append("locality_accuracy", locality.path("accuracy").asText());
        }

        // Topped status
        doc.append("is_topped",       detail.path("is_topped").asBoolean(false));
        doc.append("is_topped_today", detail.path("is_topped_today").asBoolean(false));

        // items[] — structured key-value pairs from the detail page
        appendItems(doc, detail.path("items"));

        // Seller info
        JsonNode seller = detail.path("_embedded").path("seller");
        if (!seller.isMissingNode()) {
            Document sellerDoc = new Document()
                .append("user_id",   seller.path("user_id").asInt())
                .append("user_name", seller.path("user_name").asText())
                .append("active",    seller.path("active").asBoolean());
            JsonNode premise = seller.path("_embedded").path("premise");
            if (!premise.isMissingNode()) {
                sellerDoc
                    .append("agency_name",    premise.path("name").asText())
                    .append("agency_email",   premise.path("email").asText())
                    .append("agency_address", premise.path("address").asText())
                    .append("agency_www",     premise.path("www").asText(""));
            }
            doc.append("seller", sellerDoc);
        }

        // Full image list from detail
        JsonNode images = detail.path("_embedded").path("images");
        if (images.isArray()) {
            List<Document> imgList = new ArrayList<>();
            for (JsonNode img : images) {
                imgList.add(new Document()
                    .append("id",    img.path("id").asLong())
                    .append("order", img.path("order").asInt())
                    .append("kind",  img.path("kind").asInt())
                    .append("url",   img.path("_links").path("self").path("href").asText()));
            }
            doc.append("images", imgList);
        }

        // Map geometry
        JsonNode map = detail.path("map");
        if (!map.isMissingNode()) {
            doc.append("map_lat",  map.path("lat").asDouble());
            doc.append("map_lon",  map.path("lon").asDouble());
            doc.append("map_zoom", map.path("zoom").asInt());
        }

        // recommendations_data — rich structured fields
        JsonNode rec = detail.path("recommendations_data");
        if (!rec.isMissingNode()) {
            appendRecommendationsData(doc, rec);
        }
    }

    // -------------------------------------------------------------------------
    // items[] — structured key/value rows from the detail endpoint
    // -------------------------------------------------------------------------

    private static void appendItems(Document doc, JsonNode items) {
        if (!items.isArray()) return;

        for (JsonNode item : items) {
            String type = item.path("type").asText("");
            String name = item.path("name").asText("");

            switch (type) {
                case "edited" -> {
                    // Last-update field — parse the Czech date string
                    String rawValue = item.path("value").asText("");
                    DateParser.ParsedDate parsed = DateParser.parse(rawValue);
                    doc.append("last_updated",     parsed.storableValue());
                    doc.append("last_updated_raw", rawValue);
                    doc.append("is_topped_item",   item.path("topped").asBoolean(false));
                }
                case "price_czk" -> {
                    // Price already captured from listing; just store the note if present
                    JsonNode notesNode = item.path("notes");
                    if (notesNode.isArray() && notesNode.size() > 0) {
                        doc.append("price_note", notesNode.get(0).asText(""));
                    }
                }
                case "area" ->
                    doc.append("area_" + sanitizeKey(name),
                        item.path("value").asText() + " m²");
                case "boolean" ->
                    doc.append("feature_" + sanitizeKey(name),
                        item.path("value").asBoolean());
                case "count" ->
                    doc.append("count_" + sanitizeKey(name),
                        item.path("value").asText());
                case "energy_efficiency_rating" -> {
                    doc.append("energy_rating_label", item.path("value").asText());
                    doc.append("energy_rating_class", item.path("value_type").asText());
                }
                case "energy_performance" ->
                    doc.append("energy_performance_kwh_m2", item.path("value").asText());
                case "string" ->
                    doc.append("detail_" + sanitizeKey(name),
                        item.path("value").asText());
                case "set" -> {
                    List<String> values = new ArrayList<>();
                    JsonNode valArr = item.path("value");
                    if (valArr.isArray()) {
                        for (JsonNode v : valArr) {
                            values.add(v.path("value").asText());
                        }
                    }
                    doc.append("set_" + sanitizeKey(name), values);
                }
                default -> {
                    // Unknown item type — store raw value under a generic key
                    if (!name.isBlank()) {
                        doc.append("item_" + sanitizeKey(name),
                            item.path("value").asText());
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // recommendations_data — rich structured fields
    // -------------------------------------------------------------------------

    private static void appendRecommendationsData(Document doc, JsonNode rec) {
        int ownership = rec.path("ownership").asInt(-1);
        if (ownership > 0) {
            doc.append("ownership_cb",    ownership);
            doc.append("ownership_label",
                LabelConfig.OWNERSHIP.getOrDefault(ownership, "unknown_" + ownership));
        }

        int buildingType = rec.path("building_type").asInt(-1);
        if (buildingType > 0) {
            doc.append("building_type_cb",    buildingType);
            doc.append("building_type_label",
                LabelConfig.BUILDING_TYPE.getOrDefault(buildingType, "unknown_" + buildingType));
        }

        int buildingCondition = rec.path("building_condition").asInt(-1);
        if (buildingCondition > 0) {
            doc.append("building_condition_cb",    buildingCondition);
            doc.append("building_condition_label",
                LabelConfig.BUILDING_CONDITION.getOrDefault(buildingCondition, "unknown_" + buildingCondition));
        }

        int energyRating = rec.path("energy_efficiency_rating_cb").asInt(-1);
        if (energyRating > 0) {
            doc.append("energy_efficiency_cb",    energyRating);
            doc.append("energy_efficiency_label",
                LabelConfig.ENERGY_RATING.getOrDefault(energyRating, "unknown_" + energyRating));
        }

        int usableArea = rec.path("usable_area").asInt(-1);
        if (usableArea > 0) doc.append("usable_area_m2", usableArea);

        // Boolean features
        appendBooleanFeature(doc, rec, "terrace",      "has_terrace");
        appendBooleanFeature(doc, rec, "balcony",      "has_balcony");
        appendBooleanFeature(doc, rec, "loggia",       "has_loggia");
        appendBooleanFeature(doc, rec, "cellar",       "has_cellar");
        appendBooleanFeature(doc, rec, "elevator",     "has_elevator");
        appendBooleanFeature(doc, rec, "garage",       "has_garage");
        appendBooleanFeature(doc, rec, "parking_lots", "has_parking");
        appendBooleanFeature(doc, rec, "basin",        "has_pool");
        appendBooleanFeature(doc, rec, "easy_access",  "is_barrier_free");
        appendBooleanFeature(doc, rec, "low_energy",   "is_low_energy");
        appendBooleanFeature(doc, rec, "furnished",    "is_furnished");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void appendBooleanFeature(Document doc, JsonNode rec,
                                              String field, String docKey) {
        JsonNode node = rec.path(field);
        if (!node.isMissingNode()) {
            doc.append(docKey, node.asInt(0) == 1);
        }
    }

    /**
     * Map a labelsReleased / labelsAll sub-array at the given index.
     * Translates raw code strings to English labels via LabelConfig.
     * Unknown codes are stored as-is.
     */
    private static List<String> mapLabels(JsonNode labelsArray, int subIndex,
                                           boolean propertyLabels) {
        List<String> result = new ArrayList<>();
        if (!labelsArray.isArray() || labelsArray.size() <= subIndex) return result;
        JsonNode sub = labelsArray.get(subIndex);
        if (!sub.isArray()) return result;
        for (JsonNode label : sub) {
            String raw    = label.asText();
            String mapped = propertyLabels
                ? LabelConfig.mapPropertyLabel(raw)
                : LabelConfig.mapPoiLabel(raw);
            result.add(mapped);
        }
        return result;
    }

    /**
     * Convert a label name (possibly Czech) into a safe MongoDB field key.
     * e.g. "Užitná plocha" → "uzitna_plocha"
     */
    private static String sanitizeKey(String name) {
        return name.toLowerCase()
            .replace("á", "a").replace("č", "c").replace("ď", "d")
            .replace("é", "e").replace("ě", "e").replace("í", "i")
            .replace("ň", "n").replace("ó", "o").replace("ř", "r")
            .replace("š", "s").replace("ť", "t").replace("ú", "u")
            .replace("ů", "u").replace("ý", "y").replace("ž", "z")
            .replace(" ", "_")
            .replaceAll("[^a-z0-9_]", "_")
            .replaceAll("_+", "_")
            .replaceAll("^_|_$", "");
    }
}
