package com.sreality.scraper.config;

import java.util.Map;

/**
 * Mappings for sreality.cz category codes to human-readable English names.
 *
 * category_main_cb → property type
 * category_type_cb → deal type
 * category_sub_cb → apartment layout / house size sub-category
 *
 * Each entry contains:
 * - collectionName : MongoDB collection name used for this category
 * - label : human-readable label stored in every document
 * - description : longer explanation (for reference / documentation)
 */
public class CategoryConfig {

    // -------------------------------------------------------------------------
    // category_main_cb – property type
    // -------------------------------------------------------------------------
    public record CategoryEntry(String collectionSuffix, String label, String description) {
    }

    public static final Map<Integer, CategoryEntry> PROPERTY_TYPE = Map.of(
            1, new CategoryEntry("apartments", "Apartment", "Residential flat / apartment unit"),
            2, new CategoryEntry("houses", "House", "Detached or semi-detached house"),
            3, new CategoryEntry("land", "Land", "Plot of land / building plot"),
            4, new CategoryEntry("commercial", "Commercial", "Commercial property (office, shop, warehouse…)"),
            5, new CategoryEntry("other", "Other", "Other types of property not covered above"));

    // -------------------------------------------------------------------------
    // category_type_cb – deal type
    // -------------------------------------------------------------------------
    public record DealEntry(String collectionSuffix, String label, String description) {
    }

    public static final Map<Integer, DealEntry> DEAL_TYPE = Map.of(
            1, new DealEntry("sale", "Sale", "Property listed for outright sale"),
            2, new DealEntry("rent", "Rent", "Property listed for rent / lease"),
            3, new DealEntry("auction", "Auction", "Property to be sold via auction"));

    // -------------------------------------------------------------------------
    // category_sub_cb – apartment layout / sub-type
    // -------------------------------------------------------------------------
    public static final Map<Integer, String> SUB_CATEGORY = Map.ofEntries(
            Map.entry(2, "1+kk"),
            Map.entry(3, "1+1"),
            Map.entry(4, "2+kk"),
            Map.entry(5, "2+1"),
            Map.entry(6, "3+kk"),
            Map.entry(7, "3+1"),
            Map.entry(8, "4+kk"),
            Map.entry(9, "4+1"),
            Map.entry(10, "5+kk"),
            Map.entry(11, "5+1"),
            Map.entry(12, "6 and more"),
            Map.entry(16, "Unusual layout"),
            Map.entry(37, "1 room"),
            Map.entry(38, "2 rooms"),
            Map.entry(39, "3 rooms"),
            Map.entry(40, "4 rooms"),
            Map.entry(41, "5 and more rooms"),
            Map.entry(43, "Unusual house layout"),
            Map.entry(56, "Residential housing land"),
            Map.entry(57, "Commercial land"),
            Map.entry(58, "Field"),
            Map.entry(59, "Forest"),
            Map.entry(60, "Pond"),
            Map.entry(61, "Orchard / vineyard"),
            Map.entry(62, "Meadow"),
            Map.entry(63, "Garden"),
            Map.entry(64, "Other land"));

    // -------------------------------------------------------------------------
    // Helper: derive MongoDB collection name from the two main codes
    // Pattern: <property_type>_<deal_type> e.g. apartments_sale
    // -------------------------------------------------------------------------
    public static String collectionName(int categoryMainCb, int categoryTypeCb) {
        String propertyPart = PROPERTY_TYPE.containsKey(categoryMainCb)
                ? PROPERTY_TYPE.get(categoryMainCb).collectionSuffix()
                : "property_" + categoryMainCb;

        String dealPart = DEAL_TYPE.containsKey(categoryTypeCb)
                ? DEAL_TYPE.get(categoryTypeCb).collectionSuffix()
                : "deal_" + categoryTypeCb;

        return propertyPart + "_" + dealPart;
    }

    private CategoryConfig() {
    }
}
