package com.sreality.scraper;

/**
 * Factory for realistic test JSON payloads that mirror the actual sreality API responses.
 *
 * All methods return raw JSON strings that can be parsed with ScraperIntegrationTestBase.json().
 *
 * Default estate used throughout tests:
 *   hash_id  = 123456789
 *   name     = "Prodej bytu 3+kk 83 m²"
 *   price    = 9_700_000
 *   category = 1 (apartments), type = 1 (sale)
 */
public class TestFixtures {

    public static final long   DEFAULT_HASH_ID = 123456789L;
    public static final long   DEFAULT_PRICE   = 9_700_000L;
    public static final String COLLECTION      = "apartments_sale";

    // -------------------------------------------------------------------------
    // Listing node (from GET /estates)
    // -------------------------------------------------------------------------

    public static String listingJson(long hashId, long price, String name) {
        return """
            {
              "hash_id": %d,
              "name": "%s",
              "locality": "Testovací, Praha 9",
              "price": %d,
              "price_czk": { "value_raw": %d, "unit": "", "name": "Celková cena" },
              "category": 1,
              "type": 1,
              "seo": { "category_main_cb": 1, "category_sub_cb": 6, "category_type_cb": 1, "locality": "test-locality" },
              "gps": { "lat": 50.142227, "lon": 14.507899 },
              "is_auction": false,
              "has_floor_plan": false,
              "has_panorama": false,
              "has_video": false,
              "has_matterport_url": false,
              "new": false,
              "attractive_offer": 0,
              "exclusively_at_rk": 0,
              "auctionPrice": 0.0,
              "advert_images_count": 5,
              "labelsReleased": [["personal", "elevator"], ["tram", "metro"]],
              "labelsAll": [["personal", "elevator", "cellar"], ["tram", "metro", "school"]],
              "_embedded": {
                "company": { "id": 42, "name": "Test Agency", "url": "test-agency" },
                "favourite": { "is_favourite": false, "_links": { "self": { "href": "/cs/v2/favourite/%d" } } },
                "note": { "note": "", "has_note": false, "_links": { "self": { "href": "/cs/v2/note/%d" } } }
              },
              "_links": {
                "self": { "href": "/cs/v2/estates/%d" },
                "images": [{ "href": "https://example.com/img1.jpg" }],
                "image_middle2": [{ "href": "https://example.com/img1.jpg" }],
                "dynamicDown": [{ "href": "https://example.com/img1.jpg" }],
                "dynamicUp": [{ "href": "https://example.com/img1.jpg" }],
                "iterator": { "href": "/cs/v2/estate-iterator/0" }
              },
              "rus": false,
              "region_tip": 0,
              "paid_logo": 0,
              "is_topped": false,
              "is_topped_today": false
            }
            """.formatted(hashId, name, price, price, hashId, hashId, hashId);
    }

    /** Listing with default values. */
    public static String defaultListing() {
        return listingJson(DEFAULT_HASH_ID, DEFAULT_PRICE, "Prodej bytu 3+kk 83 m²");
    }

    /** Listing with a different price (simulates price change). */
    public static String listingWithPrice(long price) {
        return listingJson(DEFAULT_HASH_ID, price, "Prodej bytu 3+kk 83 m²");
    }

    /** Listing with a different name (simulates name change). */
    public static String listingWithName(String name) {
        return listingJson(DEFAULT_HASH_ID, DEFAULT_PRICE, name);
    }

    // -------------------------------------------------------------------------
    // Detail node (from GET /estates/{id})
    // -------------------------------------------------------------------------

    public static String detailJson(long hashId, String description, String sellerName) {
        return """
            {
              "text": { "name": "Popis", "value": "%s" },
              "locality": { "name": "Adresa", "value": "Testovací, Praha 9", "accuracy": "not_address" },
              "is_topped": false,
              "is_topped_today": false,
              "panorama": 0,
              "rus": false,
              "name": { "name": "Název", "value": "Prodej bytu 3+kk 83 m²" },
              "price_czk": { "unit": "", "name": "Celková cena", "value": "9 700 000", "value_raw": 9700000 },
              "items": [
                { "type": "edited", "name": "Aktualizace", "value": "Dnes", "topped": false },
                { "type": "string", "name": "Stavba", "value": "Cihlová" },
                { "type": "area",   "name": "Užitná plocha", "value": "83", "unit": "m2" },
                { "type": "boolean","name": "Výtah", "value": true }
              ],
              "seo": { "category_main_cb": 1, "category_sub_cb": 6, "category_type_cb": 1, "locality": "test-locality" },
              "map": { "lat": 50.142227, "lon": 14.507899, "zoom": 15, "type": "point", "bounding_box": {} },
              "recommendations_data": {
                "hash_id": %d,
                "category_main_cb": 1,
                "category_type_cb": 1,
                "ownership": 1,
                "building_type": 2,
                "building_condition": 8,
                "energy_efficiency_rating_cb": 3,
                "usable_area": 83,
                "terrace": 0,
                "balcony": 0,
                "loggia": 0,
                "cellar": 1,
                "elevator": 1,
                "garage": 0,
                "parking_lots": 0,
                "basin": 0,
                "easy_access": 0,
                "low_energy": 0,
                "furnished": 0,
                "price_summary_czk": 9700000
              },
              "_links": {
                "self": { "href": "/cs/v2/estates/%d", "profile": "/estates/1/doc", "title": "Detail" }
              },
              "_embedded": {
                "seller": {
                  "user_id": 999,
                  "user_name": "%s",
                  "active": true,
                  "_links": { "self": { "href": "/cs/v2/seller/999" } },
                  "_embedded": {
                    "premise": {
                      "id": 42,
                      "name": "Test Agency",
                      "email": "test@agency.cz",
                      "address": "Testovací 1, Praha",
                      "www": "https://testagency.cz"
                    }
                  }
                },
                "images": [
                  {
                    "id": 111,
                    "order": 1,
                    "kind": 2,
                    "_links": {
                      "self": { "href": "https://example.com/img1.jpg" },
                      "gallery": { "href": "https://example.com/img1_gallery.jpg" },
                      "view": { "href": "https://example.com/img1_view.jpg" },
                      "dynamicDown": { "href": "https://example.com/img1_down.jpg" },
                      "dynamicUp": { "href": "https://example.com/img1_up.jpg" }
                    }
                  }
                ],
                "favourite": { "is_favourite": false, "_links": { "self": { "href": "/cs/v2/favourite/%d" } } },
                "note": { "note": "", "has_note": false, "_links": { "self": { "href": "/cs/v2/note/%d" } } },
                "calculator": null,
                "matterport_url": ""
              },
              "logged_in": false,
              "locality_district_id": 22
            }
            """.formatted(description, hashId, hashId, sellerName, hashId, hashId);
    }

    /** Detail with default values. */
    public static String defaultDetail() {
        return detailJson(DEFAULT_HASH_ID, "Krásný byt v centru.", "Jan Novák");
    }

    /** Detail with a different description (simulates description change). */
    public static String detailWithDescription(String description) {
        return detailJson(DEFAULT_HASH_ID, description, "Jan Novák");
    }

    /** Detail with a different seller name. */
    public static String detailWithSeller(String sellerName) {
        return detailJson(DEFAULT_HASH_ID, "Krásný byt v centru.", sellerName);
    }

    private TestFixtures() {}
}
