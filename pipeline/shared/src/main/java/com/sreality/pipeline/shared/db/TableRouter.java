package com.sreality.pipeline.shared.db;

/**
 * Maps (propertyType, dealType) to the correct fact table name.
 *
 * propertyType: "Apartment" | "House" | "Land" | "Commercial" | "Other"
 * dealType:     "Sale" | "Rent" | "Auction"
 *
 * fact_other_auction does not exist — those rare estates fall into fact_other_sale.
 */
public final class TableRouter {

    private TableRouter() {}

    public static String factTable(String propertyType, String dealType) {
        String pt = propertyType == null ? "" : propertyType.trim().toLowerCase();
        String dt = dealType     == null ? "" : dealType.trim().toLowerCase();
        return switch (pt) {
            case "apartment" -> switch (dt) {
                case "sale"    -> "fact_apartment_sale";
                case "rent"    -> "fact_apartment_rent";
                case "auction" -> "fact_apartment_auction";
                default -> throw new IllegalArgumentException("Unknown deal type: " + dealType);
            };
            case "house" -> switch (dt) {
                case "sale"    -> "fact_house_sale";
                case "rent"    -> "fact_house_rent";
                case "auction" -> "fact_house_auction";
                default -> throw new IllegalArgumentException("Unknown deal type: " + dealType);
            };
            case "land" -> switch (dt) {
                case "sale"    -> "fact_land_sale";
                case "rent"    -> "fact_land_rent";
                case "auction" -> "fact_land_auction";
                default -> throw new IllegalArgumentException("Unknown deal type: " + dealType);
            };
            case "commercial" -> switch (dt) {
                case "sale"    -> "fact_commercial_sale";
                case "rent"    -> "fact_commercial_rent";
                case "auction" -> "fact_commercial_auction";
                default -> throw new IllegalArgumentException("Unknown deal type: " + dealType);
            };
            case "other" -> switch (dt) {
                case "sale", "auction" -> "fact_other_sale";
                case "rent"            -> "fact_other_rent";
                default -> throw new IllegalArgumentException("Unknown deal type: " + dealType);
            };
            default -> throw new IllegalArgumentException("Unknown property type: " + propertyType);
        };
    }

    public static String[] allFactTables() {
        return new String[]{
            "fact_apartment_sale",  "fact_apartment_rent",  "fact_apartment_auction",
            "fact_house_sale",      "fact_house_rent",      "fact_house_auction",
            "fact_land_sale",       "fact_land_rent",       "fact_land_auction",
            "fact_commercial_sale", "fact_commercial_rent", "fact_commercial_auction",
            "fact_other_sale",      "fact_other_rent"
        };
    }
}
