package com.sreality.etl.model;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;

/**
 * Intermediate representation of a raw estate document read from MongoDB.
 *
 * Maps the MongoDB field names (from EstateDocumentBuilder) to typed Java fields.
 * Handles missing/null fields gracefully — everything nullable is an Optional or null.
 *
 * Transformation applied here:
 *   - Date strings (ISO) → LocalDate
 *   - Numeric fields coerced to correct Java types
 *   - Missing fields mapped to null (handled downstream)
 */
public class RawEstate {

    private static final Logger log = LoggerFactory.getLogger(RawEstate.class);

    public final long    hashId;
    public final String  srealityUrl;
    public final String  propertyType;   // already decoded by scraper
    public final String  dealType;       // already decoded by scraper
    public final String  subCategory;    // nullable

    public final Long    priceCzk;
    public final Double  usableAreaM2;
    public final Double  gpsLat;
    public final Double  gpsLon;

    public final Integer floorNumber;
    public final Integer totalFloors;

    // building
    public final String  ownershipLabel;
    public final String  buildingTypeLabel;
    public final String  buildingConditionLabel;
    public final String  energyRatingLabel;

    // booleans
    public final Boolean isNewBuilding;
    public final Boolean isFurnished;
    public final Boolean hasBalcony;
    public final Boolean hasTerrace;
    public final Boolean hasLoggia;
    public final Boolean hasCellar;
    public final Boolean hasElevator;
    public final Boolean hasGarage;
    public final Boolean hasParking;
    public final Boolean hasPool;
    public final Boolean isBarrierFree;

    // metadata
    public final boolean  isActive;
    public final LocalDate firstSeenDate;
    public final Integer   advertImagesCount;
    public final Boolean   hasFloorPlan;
    public final Boolean   hasVideo;

    // agency (nested document)
    public final Integer agencySrealityId;
    public final String  agencyName;
    public final String  agencyUrl;

    private RawEstate(Document d) {
        this.hashId      = getLong(d, "hash_id", 0L);
        this.srealityUrl = getString(d, "sreality_url");
        this.propertyType = getString(d, "property_type");
        this.dealType     = getString(d, "deal_type");
        this.subCategory  = getString(d, "sub_category");

        // Transformation: price — try price_czk_value first, fall back to price_raw
        Long czk = getLong(d, "price_czk_value", null);
        this.priceCzk = czk != null && czk > 0 ? czk : getLong(d, "price_raw", null);

        // Transformation: area — try usable_area_m2 (from detail items), then area_ prefixed fields
        Double area = getDouble(d, "usable_area_m2");
        if (area == null) {
            // Detail items store area as "42 m²" strings — parse them
            area = parseAreaString(getString(d, "area_uzitna_plocha"));
        }
        this.usableAreaM2 = area;

        this.gpsLat = getDouble(d, "gps_lat");
        this.gpsLon = getDouble(d, "gps_lon");

        this.floorNumber  = getInt(d, "count_podlazi_umisteni");
        this.totalFloors  = getInt(d, "count_pocet_podlazi");

        this.ownershipLabel          = getString(d, "ownership_label");
        this.buildingTypeLabel       = getString(d, "building_type_label");
        this.buildingConditionLabel  = getString(d, "building_condition_label");
        this.energyRatingLabel       = getString(d, "energy_efficiency_label");

        this.isNewBuilding  = getBool(d, "is_new");
        this.isFurnished    = getBool(d, "is_furnished");
        this.hasBalcony     = getBool(d, "has_balcony");
        this.hasTerrace     = getBool(d, "has_terrace");
        this.hasLoggia      = getBool(d, "has_loggia");
        this.hasCellar      = getBool(d, "has_cellar");
        this.hasElevator    = getBool(d, "has_elevator");
        this.hasGarage      = getBool(d, "has_garage");
        this.hasParking     = getBool(d, "has_parking");
        this.hasPool        = getBool(d, "has_pool");
        this.isBarrierFree  = getBool(d, "is_barrier_free");

        this.isActive          = Boolean.TRUE.equals(d.getBoolean("active"));
        this.firstSeenDate     = parseDate(getString(d, "_first_seen_at"));
        this.advertImagesCount = getInt(d, "advert_images_count");
        this.hasFloorPlan      = getBool(d, "has_floor_plan");
        this.hasVideo          = getBool(d, "has_video");

        // Agency from nested embedded document
        Document agency = d.get("agency", Document.class);
        if (agency != null) {
            this.agencySrealityId = getInt(agency, "id");
            this.agencyName       = getString(agency, "name");
            this.agencyUrl        = getString(agency, "url");
        } else {
            this.agencySrealityId = null;
            this.agencyName       = null;
            this.agencyUrl        = null;
        }
    }

    public static RawEstate fromDocument(Document d) {
        return new RawEstate(d);
    }

    /**
     * An estate is usable for the warehouse if it has the minimum required fields:
     * valid hash_id, known property type, and GPS coordinates for spatial join.
     */
    public boolean isUsable() {
        return hashId > 0
            && propertyType != null
            && dealType != null
            && gpsLat != null && gpsLon != null
            && gpsLat != 0.0 && gpsLon != 0.0;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String getString(Document d, String key) {
        Object v = d.get(key);
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static Long getLong(Document d, String key, Long def) {
        Object v = d.get(key);
        if (v == null) return def;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(v.toString()); } catch (Exception e) { return def; }
    }

    private static Double getDouble(Document d, String key) {
        Object v = d.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(v.toString()); } catch (Exception e) { return null; }
    }

    private static Integer getInt(Document d, String key) {
        Object v = d.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return null; }
    }

    private static Boolean getBool(Document d, String key) {
        Object v = d.get(key);
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() == 1;
        return Boolean.parseBoolean(v.toString());
    }

    /**
     * Transformation: parse Czech area strings like "42 m²" or "42.5" → Double.
     * Returns null if the string cannot be parsed.
     */
    static Double parseAreaString(String raw) {
        if (raw == null) return null;
        String cleaned = raw.replace("m²", "").replace("m2", "")
            .replace("\u00a0", "").trim();
        if (cleaned.isEmpty()) return null;
        try { return Double.parseDouble(cleaned.replace(",", ".")); }
        catch (Exception e) { return null; }
    }

    /**
     * Transformation: parse ISO date string to LocalDate.
     * Handles both "2026-01-15" and "2026-01-15T08:23:11Z" formats.
     */
    static LocalDate parseDate(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try { return LocalDate.parse(iso.substring(0, 10)); }
        catch (Exception e) { return null; }
    }
}
