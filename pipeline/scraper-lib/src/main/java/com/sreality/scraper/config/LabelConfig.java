package com.sreality.scraper.config;

import java.util.Map;

/**
 * Mappings for various label / flag codes that appear in sreality API responses.
 *
 * labelsReleased / labelsAll contain two sub-arrays:
 *   [0] – property feature labels  (PROPERTY_LABELS)
 *   [1] – nearby POI labels         (POI_LABELS)
 *
 * ownership, building_type, building_condition, energy_efficiency_rating
 * appear inside codeItems / items of the detail endpoint.
 */
public class LabelConfig {

    // -------------------------------------------------------------------------
    // Property feature labels  (labelsReleased[0] / labelsAll[0])
    // -------------------------------------------------------------------------
    public static final Map<String, String> PROPERTY_LABELS = Map.ofEntries(
        Map.entry("new_building",       "New building"),
        Map.entry("personal",           "Personal ownership"),
        Map.entry("collective",         "Collective ownership"),
        Map.entry("state",              "State ownership"),
        Map.entry("terrace",            "Terrace"),
        Map.entry("balcony",            "Balcony"),
        Map.entry("loggia",             "Loggia"),
        Map.entry("cellar",             "Cellar"),
        Map.entry("elevator",           "Elevator"),
        Map.entry("garage",             "Garage"),
        Map.entry("parking_lots",       "Parking"),
        Map.entry("basin",              "Swimming pool"),
        Map.entry("garden",             "Garden"),
        Map.entry("furnished",          "Furnished"),
        Map.entry("partly_furnished",   "Partly furnished"),
        Map.entry("not_furnished",      "Not furnished"),
        Map.entry("easy_access",        "Barrier-free access"),
        Map.entry("brick",              "Brick construction"),
        Map.entry("panel",              "Panel construction"),
        Map.entry("in_construction",    "Under construction"),
        Map.entry("after_reconstruction","After reconstruction"),
        Map.entry("energy_rating_a",    "Energy class A"),
        Map.entry("energy_rating_b",    "Energy class B"),
        Map.entry("energy_rating_c",    "Energy class C"),
        Map.entry("energy_rating_d",    "Energy class D"),
        Map.entry("energy_rating_e",    "Energy class E"),
        Map.entry("energy_rating_f",    "Energy class F"),
        Map.entry("energy_rating_g",    "Energy class G")
    );

    // -------------------------------------------------------------------------
    // Nearby POI labels  (labelsReleased[1] / labelsAll[1])
    // -------------------------------------------------------------------------
    public static final Map<String, String> POI_LABELS = Map.ofEntries(
        Map.entry("metro",              "Metro nearby"),
        Map.entry("tram",               "Tram nearby"),
        Map.entry("train",              "Train nearby"),
        Map.entry("bus_public_transport","Bus nearby"),
        Map.entry("restaurant",         "Restaurant nearby"),
        Map.entry("shop",               "Shop nearby"),
        Map.entry("small_shop",         "Small shop nearby"),
        Map.entry("drugstore",          "Drugstore nearby"),
        Map.entry("school",             "School nearby"),
        Map.entry("kindergarten",       "Kindergarten nearby"),
        Map.entry("sports",             "Sports facility nearby"),
        Map.entry("playground",         "Playground nearby"),
        Map.entry("medic",              "Doctor nearby"),
        Map.entry("vet",                "Vet nearby"),
        Map.entry("post_office",        "Post office nearby"),
        Map.entry("atm",                "ATM nearby"),
        Map.entry("theater",            "Theatre nearby"),
        Map.entry("movies",             "Cinema nearby"),
        Map.entry("tavern",             "Pub nearby"),
        Map.entry("candy_shop",         "Café/confectionery nearby"),
        Map.entry("sightseeing",        "Sightseeing nearby"),
        Map.entry("natural_attraction", "Natural attraction nearby")
    );

    // -------------------------------------------------------------------------
    // ownership  (codeItems.ownership)
    // -------------------------------------------------------------------------
    public static final Map<Integer, String> OWNERSHIP = Map.of(
        1, "Personal",
        2, "Collective",
        3, "State"
    );

    // -------------------------------------------------------------------------
    // building_type_search  (codeItems.building_type_search)
    // -------------------------------------------------------------------------
    public static final Map<Integer, String> BUILDING_TYPE = Map.of(
        1, "Panel",
        2, "Brick",
        3, "Other"
    );

    // -------------------------------------------------------------------------
    // building_condition  (codeItems.building_condition  /  items value)
    // -------------------------------------------------------------------------
    public static final Map<Integer, String> BUILDING_CONDITION = Map.of(
        1, "Good condition",
        2, "Very good condition",
        3, "Poor condition",
        4, "Under construction",
        5, "In project phase",
        6, "New building",
        7, "Before reconstruction",
        8, "After reconstruction",
        9, "Developer project"
    );

    // -------------------------------------------------------------------------
    // energy_efficiency_rating_cb  (recommendations_data / items)
    // -------------------------------------------------------------------------
    public static final Map<Integer, String> ENERGY_RATING = Map.of(
        1, "A - Extremely energy efficient",
        2, "B - Very energy efficient",
        3, "C - Energy efficient",
        4, "D - Less energy efficient",
        5, "E - Barely energy efficient",
        6, "F - Energy inefficient",
        7, "G - Extremely energy inefficient"
    );

    // -------------------------------------------------------------------------
    // Helper: map a property label string, falling back to original
    // -------------------------------------------------------------------------
    public static String mapPropertyLabel(String raw) {
        return PROPERTY_LABELS.getOrDefault(raw, raw);
    }

    public static String mapPoiLabel(String raw) {
        return POI_LABELS.getOrDefault(raw, raw);
    }

    private LabelConfig() {}
}
