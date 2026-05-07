package com.sreality.pipeline.enricher.load;

import com.sreality.pipeline.enricher.model.FieldDiff;
import com.sreality.pipeline.enricher.model.FieldDiff.FieldChange;
import com.sreality.pipeline.enricher.spatial.SpatialJoiner;
import com.sreality.pipeline.enricher.spatial.SpatialJoiner.GeoResult;
import com.sreality.pipeline.shared.db.PostgresConnectionPool;
import com.sreality.pipeline.shared.db.TableRouter;
import com.sreality.pipeline.shared.model.ContentHasher;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes enriched estate data from a MongoDB staging document into Postgres.
 *
 * Designed for extension: three protected hook methods control SCD behaviour
 * and can be overridden by subclasses (e.g. InitialEnricher for the one-time
 * historical load):
 *
 * resolveValidFrom(doc) — date for valid_from column (default: today)
 * resolveValidTo(doc) — date for valid_to column (default: null = open row)
 * resolveIsActive(doc) — value for is_active column (default: true)
 */
public class EnricherLoader {

    private static final Logger log = LoggerFactory.getLogger(EnricherLoader.class);

    protected final PostgresConnectionPool pg;
    private final SpatialJoiner spatial;

    public EnricherLoader(PostgresConnectionPool pg) {
        this.pg = pg;
        this.spatial = new SpatialJoiner(pg);
    }

    // =========================================================================
    // Protected hooks — override in subclasses to change SCD behaviour
    // =========================================================================

    protected LocalDate resolveValidFrom(Document doc) {
        return LocalDate.now();
    }

    protected LocalDate resolveValidTo(Document doc) {
        return null;
    }

    protected boolean resolveIsActive(Document doc) {
        return true;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    public enum WriteResult {
        INSERTED, UPDATED, SKIPPED, ERROR
    }

    public WriteResult process(Document doc) {
        try {
            String propertyType = doc.getString("property_type");
            String dealType = doc.getString("deal_type");
            if (propertyType == null || dealType == null) {
                log.warn("Missing property_type/deal_type — hash_id={}", doc.get("hash_id"));
                return WriteResult.ERROR;
            }

            long hashId = getLong(doc, "hash_id");
            String table = TableRouter.factTable(propertyType, dealType);
            GeoResult geo = spatial.resolve(getDouble(doc, "gps_lat"), getDouble(doc, "gps_lon"));
            annotateGeoResolution(doc, geo);
            Integer agencyId = upsertAgency(doc);
            long contentHash = computeFullHash(doc);
            Long existingHash = getCurrentHash(table, hashId);

            if (existingHash != null && existingHash == contentHash)
                return WriteResult.SKIPPED;

            LocalDate validFrom = resolveValidFrom(doc);
            LocalDate validTo = resolveValidTo(doc);
            boolean isActive = resolveIsActive(doc);

            Map<String, String> oldValues = Collections.emptyMap();
            if (existingHash != null) {
                oldValues = fetchCurrentFieldValues(table, hashId);
                closeCurrentRow(table, hashId, validFrom);
            }

            insertFactRow(table, propertyType, dealType, doc,
                    hashId, contentHash, geo, agencyId, validFrom, validTo, isActive);

            if (existingHash != null) {
                List<FieldChange> changes = FieldDiff.diff(oldValues, extractTrackedFields(doc));
                if (!changes.isEmpty())
                    recordFieldChanges(hashId, table, validFrom, changes);
            }

            upsertDetail(hashId, doc);
            return existingHash == null ? WriteResult.INSERTED : WriteResult.UPDATED;

        } catch (Exception e) {
            log.error("Failed hash_id={}: {}", doc.get("hash_id"), e.getMessage(), e);
            return WriteResult.ERROR;
        }
    }

    // =========================================================================
    // Content hash
    // =========================================================================

    private static long computeFullHash(Document doc) {
        return ContentHasher.compute(
                str(doc, "price_czk_value"),
                str(doc, "usable_area_m2"),
                parseArea(str(doc, "area_plocha_pozemku")),
                parseArea(str(doc, "area_zahrada")),
                str(doc, "sub_category"),
                join(str(doc, "ownership_label"), str(doc, "building_type_label"),
                        str(doc, "building_condition_label"), energyLabel(doc)),
                join(bool(doc, "has_balcony"), bool(doc, "has_terrace"), bool(doc, "has_loggia"),
                        bool(doc, "has_cellar"), bool(doc, "has_elevator"), bool(doc, "has_parking"),
                        bool(doc, "has_garage"), bool(doc, "has_pool"), bool(doc, "is_barrier_free"),
                        bool(doc, "is_low_energy"), bool(doc, "is_furnished"), bool(doc, "is_new")));
    }

    private static String parseArea(String raw) {
        if (raw == null)
            return null;
        return raw.replace(" m²", "").replace(" m2", "").trim();
    }

    private static String energyLabel(Document doc) {
        String en = str(doc, "energy_efficiency_label");
        return en != null ? en : str(doc, "energy_rating_label");
    }

    // =========================================================================
    // Agency upsert
    // =========================================================================

    private Integer upsertAgency(Document doc) throws SQLException {
        Document agency = (Document) doc.get("agency");
        if (agency == null)
            return null;
        int sreId = intVal(agency, "id", 0);
        if (sreId == 0)
            return null;
        String sql = "INSERT INTO " + pg.t("dim_agency") + " (sreality_id,name,url) VALUES (?,?,?)"
                + " ON CONFLICT (sreality_id) DO UPDATE SET name=EXCLUDED.name RETURNING id";
        try (Connection c = pg.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, sreId);
            ps.setString(2, agency.getString("name"));
            ps.setString(3, agency.getString("url"));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        }
    }

    // =========================================================================
    // SCD helpers
    // =========================================================================

    private Long getCurrentHash(String table, long hashId) throws SQLException {
        String sql = "SELECT content_hash FROM " + pg.t(table) + " WHERE hash_id=? AND valid_to IS NULL";
        try (Connection c = pg.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, hashId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private void closeCurrentRow(String table, long hashId, LocalDate closeDate) throws SQLException {
        String sql = "UPDATE " + pg.t(table) + " SET valid_to=? WHERE hash_id=? AND valid_to IS NULL";
        try (Connection c = pg.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setDate(1, Date.valueOf(closeDate));
            ps.setLong(2, hashId);
            ps.execute();
        }
    }

    // =========================================================================
    // Fact row insert dispatcher
    // =========================================================================

    private void insertFactRow(String table, String propertyType, String dealType,
            Document doc, long hashId, long contentHash,
            GeoResult geo, Integer agencyId,
            LocalDate validFrom, LocalDate validTo, boolean isActive)
            throws SQLException {
        switch (propertyType.toLowerCase()) {
            case "apartment" ->
                insertApartment(table, dealType, doc, hashId, contentHash, geo, agencyId, validFrom, validTo, isActive);
            case "house" ->
                insertHouse(table, dealType, doc, hashId, contentHash, geo, agencyId, validFrom, validTo, isActive);
            case "land" ->
                insertLand(table, dealType, doc, hashId, contentHash, geo, agencyId, validFrom, validTo, isActive);
            case "commercial" -> insertCommercial(table, dealType, doc, hashId, contentHash, geo, agencyId, validFrom,
                    validTo, isActive);
            default ->
                insertOther(table, dealType, doc, hashId, contentHash, geo, agencyId, validFrom, validTo, isActive);
        }
    }

    private void insertApartment(String table, String dealType, Document doc,
            long hashId, long contentHash, GeoResult geo,
            Integer agencyId, LocalDate validFrom, LocalDate validTo,
            boolean isActive) throws SQLException {
        boolean hasPerM2 = !dealType.equalsIgnoreCase("auction");
        boolean isSaleOrRent = dealType.equalsIgnoreCase("sale") || dealType.equalsIgnoreCase("rent");
        String priceCol = switch (dealType.toLowerCase()) {
            case "rent" -> "price_monthly_czk,price_monthly_per_m2";
            case "auction" -> "price_starting_bid_czk";
            default -> "price_asked_czk,price_asked_per_m2";
        };
        // Build column list dynamically: include is_furnished only for Sale/Rent
        String columns = "hash_id,content_hash,valid_from,valid_to,obec_id,cast_obce_id,agency_id,date_id,"
                + "  gps_lat,gps_lon,is_active,first_seen_date,sreality_url,advert_images_count,has_floor_plan,has_video,"
                + "  " + priceCol + ","
                + "  sub_category,usable_area_m2,floor_number,total_floors,"
                + "  ownership_label,building_type_label,building_condition_label,energy_rating_label,"
                + "  is_new_building" + (isSaleOrRent ? ",is_furnished" : "")
                + ",has_balcony,has_terrace,has_loggia,"
                + "  has_cellar,has_elevator,has_parking,has_garage,is_barrier_free";
        String sql = "INSERT INTO " + pg.t(table)
                + " (" + columns + ")"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?"
                + (hasPerM2 ? ",?,?" : ",?")
                + (isSaleOrRent ? ",?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?" : ",?,?,?,?,?,?,?,?,?,?,?,?,?,?")
                + ")";
        try (Connection c = pg.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            int i = setCommon(ps, 1, doc, hashId, contentHash, geo, agencyId, validFrom, validTo, isActive);
            i = setPrice(ps, i, doc, dealType);
            ps.setString(i++, str(doc, "sub_category"));
            setIntOrNull(ps, i++, getUsableArea(doc));
            setIntOrNull(ps, i++, getFloorNumber(doc));
            setIntOrNull(ps, i++, getTotalFloors(doc));
            ps.setString(i++, str(doc, "ownership_label"));
            ps.setString(i++, str(doc, "building_type_label"));
            ps.setString(i++, str(doc, "building_condition_label"));
            ps.setString(i++, energyLabel(doc));
            setBoolOrNull(ps, i++, doc, "is_new");
            if (isSaleOrRent)
                setBoolOrNull(ps, i++, doc, "is_furnished");
            setBoolOrNull(ps, i++, doc, "has_balcony");
            setBoolOrNull(ps, i++, doc, "has_terrace");
            setBoolOrNull(ps, i++, doc, "has_loggia");
            setBoolOrNull(ps, i++, doc, "has_cellar");
            setBoolOrNull(ps, i++, doc, "has_elevator");
            setBoolOrNull(ps, i++, doc, "has_parking");
            setBoolOrNull(ps, i++, doc, "has_garage");
            setBoolOrNull(ps, i++, doc, "is_barrier_free");
            ps.execute();
        }
    }

    private void insertHouse(String table, String dealType, Document doc,
            long hashId, long contentHash, GeoResult geo,
            Integer agencyId, LocalDate validFrom, LocalDate validTo,
            boolean isActive) throws SQLException {
        boolean hasPerM2 = !dealType.equalsIgnoreCase("auction");
        boolean isSaleOrRent = dealType.equalsIgnoreCase("sale") || dealType.equalsIgnoreCase("rent");
        String priceCol = switch (dealType.toLowerCase()) {
            case "rent" -> "price_monthly_czk,price_monthly_per_m2";
            case "auction" -> "price_starting_bid_czk";
            default -> "price_asked_czk,price_asked_per_m2";
        };
        String columns = "hash_id,content_hash,valid_from,valid_to,obec_id,cast_obce_id,agency_id,date_id,"
                + "  gps_lat,gps_lon,is_active,first_seen_date,sreality_url,advert_images_count,has_floor_plan,has_video,"
                + "  " + priceCol + ","
                + "  usable_area_m2,plot_area_m2,garden_area_m2,total_floors,"
                + "  building_type_label,building_condition_label,energy_rating_label,"
                + "  is_new_building,is_low_energy" + (isSaleOrRent ? ",is_furnished" : "")
                + ","
                + "  has_terrace,has_balcony,has_cellar,has_garage,has_parking,has_pool,is_barrier_free";
        String sql = "INSERT INTO " + pg.t(table)
                + " (" + columns + ")"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?"
                + (hasPerM2 ? ",?,?" : ",?")
                + (isSaleOrRent ? ",?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?" : ",?,?,?,?,?,?,?,?,?,?,?,?,?,?")
                + ")";
                + "  has_terrace,has_balcony,has_cellar,has_garage,has_parking,has_pool,is_barrier_free)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?"
                + (hasPerM2 ? ",?,?" : ",?")
                + ",?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection c = pg.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            int i = setCommon(ps, 1, doc, hashId, contentHash, geo, agencyId, validFrom, validTo, isActive);
            i = setPrice(ps, i, doc, dealType);
            setIntOrNull(ps, i++, getUsableArea(doc));
            setDoubleOrNull(ps, i++, parseAreaDouble(str(doc, "area_plocha_pozemku")));
            setDoubleOrNull(ps, i++, parseAreaDouble(str(doc, "area_zahrada")));
            setIntOrNull(ps, i++, getTotalFloors(doc));
            ps.setString(i++, str(doc, "building_type_label"));
            ps.setString(i++, str(doc, "building_condition_label"));
            ps.setString(i++, energyLabel(doc));
            setBoolOrNull(ps, i++, doc, "is_new");
            setBoolOrNull(ps, i++, doc, "is_low_energy");
            if (isSaleOrRent)
                setBoolOrNull(ps, i++, doc, "is_furnished");
            setBoolOrNull(ps, i++, doc, "has_terrace");
            setBoolOrNull(ps, i++, doc, "has_balcony");
            setBoolOrNull(ps, i++, doc, "has_cellar");
            setBoolOrNull(ps, i++, doc, "has_garage");
            setBoolOrNull(ps, i++, doc, "has_parking");
            setBoolOrNull(ps, i++, doc, "has_pool");
            setBoolOrNull(ps, i++, doc, "is_barrier_free");
            ps.execute();
        }
    }

    private void insertLand(String table, String dealType, Document doc,
            long hashId, long contentHash, GeoResult geo,
            Integer agencyId, LocalDate validFrom, LocalDate validTo,
            boolean isActive) throws SQLException {
        boolean hasPerM2 = dealType.equalsIgnoreCase("sale");
        String priceCol = switch (dealType.toLowerCase()) {
            case "rent" -> "price_monthly_czk";
            case "auction" -> "price_starting_bid_czk";
            default -> "price_asked_czk,price_asked_per_m2";
        };
        String sql = "INSERT INTO " + pg.t(table)
                + " (hash_id,content_hash,valid_from,valid_to,obec_id,cast_obce_id,agency_id,date_id,"
                + "  gps_lat,gps_lon,is_active,first_seen_date,sreality_url,advert_images_count,has_floor_plan,has_video,"
                + "  " + priceCol + ",sub_category,plot_area_m2)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,"
                + (hasPerM2 ? "?,?," : "?,") + "  ?,?)";
        try (Connection c = pg.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            int i = setCommon(ps, 1, doc, hashId, contentHash, geo, agencyId, validFrom, validTo, isActive);
            i = setPrice(ps, i, doc, dealType);
            ps.setString(i++, str(doc, "sub_category"));
            setDoubleOrNull(ps, i++, parseAreaDouble(str(doc, "area_plocha_pozemku")));
            ps.execute();
        }
    }

    private void insertCommercial(String table, String dealType, Document doc,
            long hashId, long contentHash, GeoResult geo,
            Integer agencyId, LocalDate validFrom, LocalDate validTo,
            boolean isActive) throws SQLException {
        boolean hasPerM2 = !dealType.equalsIgnoreCase("auction");
        String priceCol = switch (dealType.toLowerCase()) {
            case "rent" -> "price_monthly_czk,price_monthly_per_m2";
            case "auction" -> "price_starting_bid_czk";
            default -> "price_asked_czk,price_asked_per_m2";
        };
        String sql = "INSERT INTO " + pg.t(table)
                + " (hash_id,content_hash,valid_from,valid_to,obec_id,cast_obce_id,agency_id,date_id,"
                + "  gps_lat,gps_lon,is_active,first_seen_date,sreality_url,advert_images_count,has_floor_plan,has_video,"
                + "  " + priceCol + ","
                + "  usable_area_m2,floor_area_m2,building_condition_label,energy_rating_label,"
                + "  has_elevator,has_parking,is_barrier_free)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?"
                + (hasPerM2 ? ",?,?" : ",?")
                + ",?,?,?,?,?,?)";
        try (Connection c = pg.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            int i = setCommon(ps, 1, doc, hashId, contentHash, geo, agencyId, validFrom, validTo, isActive);
            i = setPrice(ps, i, doc, dealType);
            setIntOrNull(ps, i++, getUsableArea(doc));
            setDoubleOrNull(ps, i++, parseAreaDouble(str(doc, "area_podlahova_plocha")));
            ps.setString(i++, str(doc, "building_condition_label"));
            ps.setString(i++, energyLabel(doc));
            setBoolOrNull(ps, i++, doc, "has_elevator");
            setBoolOrNull(ps, i++, doc, "has_parking");
            setBoolOrNull(ps, i++, doc, "is_barrier_free");
            ps.execute();
        }
    }

    private void insertOther(String table, String dealType, Document doc,
            long hashId, long contentHash, GeoResult geo,
            Integer agencyId, LocalDate validFrom, LocalDate validTo,
            boolean isActive) throws SQLException {
        String priceCol = dealType.equalsIgnoreCase("rent") ? "price_monthly_czk" : "price_asked_czk";
        String sql = "INSERT INTO " + pg.t(table)
                + " (hash_id,content_hash,valid_from,valid_to,obec_id,cast_obce_id,agency_id,date_id,"
                + "  gps_lat,gps_lon,is_active,first_seen_date,sreality_url,advert_images_count,has_floor_plan,has_video,"
                + "  " + priceCol + ",usable_area_m2,plot_area_m2)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection c = pg.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            int i = setCommon(ps, 1, doc, hashId, contentHash, geo, agencyId, validFrom, validTo, isActive);
            i = setPrice(ps, i, doc, dealType);
            setIntOrNull(ps, i++, getUsableArea(doc));
            setDoubleOrNull(ps, i++, parseAreaDouble(str(doc, "area_plocha_pozemku")));
            ps.execute();
        }
    }

    // =========================================================================
    // Common setter
    // =========================================================================

    private int setCommon(PreparedStatement ps, int start, Document doc,
            long hashId, long contentHash, GeoResult geo, Integer agencyId,
            LocalDate validFrom, LocalDate validTo, boolean isActive)
            throws SQLException {
        int i = start;
        ps.setLong(i++, hashId);
        ps.setLong(i++, contentHash);
        ps.setDate(i++, Date.valueOf(validFrom));
        if (validTo == null)
            ps.setNull(i++, Types.DATE);
        else
            ps.setDate(i++, Date.valueOf(validTo));
        if (geo == null) {
            ps.setNull(i++, Types.INTEGER);
            ps.setNull(i++, Types.INTEGER);
        } else {
            ps.setInt(i++, geo.obecId());
            setIntOrNull(ps, i++, geo.castObceId());
        }
        setIntOrNull(ps, i++, agencyId);
        int dateId = validFrom.getYear() * 10000 + validFrom.getMonthValue() * 100 + validFrom.getDayOfMonth();
        ps.setInt(i++, dateId);
        double lat = getDouble(doc, "gps_lat");
        double lon = getDouble(doc, "gps_lon");
        if (lat == 0)
            ps.setNull(i++, Types.NUMERIC);
        else
            ps.setDouble(i++, lat);
        if (lon == 0)
            ps.setNull(i++, Types.NUMERIC);
        else
            ps.setDouble(i++, lon);
        ps.setBoolean(i++, isActive);
        setFirstSeenDate(ps, i++, doc);
        ps.setString(i++, "https://www.sreality.cz/detail/-/-/-/" + hashId);
        setIntOrNull(ps, i++, doc.getInteger("advert_images_count"));
        setBoolOrNull(ps, i++, doc, "has_floor_plan");
        setBoolOrNull(ps, i++, doc, "has_video");
        return i;
    }

    protected void annotateGeoResolution(Document doc, GeoResult geo) {
        doc.put("_geo_resolved", geo != null);
        doc.put("_geo_cast_resolved", geo != null && geo.castObceId() != 0);
    }

    // =========================================================================
    // Price setter
    // =========================================================================

    private int setPrice(PreparedStatement ps, int i, Document doc, String dealType)
            throws SQLException {
        long price = getLong(doc, "price_czk_value");
        Integer areaSqm = getUsableArea(doc);
        if (dealType.equalsIgnoreCase("auction")) {
            if (price == 0)
                ps.setNull(i++, Types.BIGINT);
            else
                ps.setLong(i++, price);
        } else {
            if (price == 0)
                ps.setNull(i++, Types.BIGINT);
            else
                ps.setLong(i++, price);
            setDoubleOrNull(ps, i++, computePricePerM2(price, areaSqm));
        }
        return i;
    }

    private static Double computePricePerM2(long price, Integer areaSqm) {
        if (price == 0 || areaSqm == null || areaSqm == 0)
            return null;
        return Math.round((double) price / areaSqm * 100.0) / 100.0;
    }

    // =========================================================================
    // Field changes
    // =========================================================================

    private Map<String, String> fetchCurrentFieldValues(String table, long hashId) {
        Map<String, String> values = new LinkedHashMap<>();
        String sql = "SELECT * FROM " + pg.t(table) + " WHERE hash_id=? AND valid_to IS NULL";
        try (Connection c = pg.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, hashId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next())
                    return values;
                ResultSetMetaData meta = rs.getMetaData();
                for (int col = 1; col <= meta.getColumnCount(); col++) {
                    String colName = meta.getColumnName(col);
                    if (isInfraColumn(colName))
                        continue;
                    Object val = rs.getObject(col);
                    values.put(colName, val == null ? null : val.toString());
                }
            }
        } catch (SQLException e) {
            log.warn("fetchCurrentFieldValues failed {}/{}: {}", table, hashId, e.getMessage());
        }
        return values;
    }

    private Map<String, String> extractTrackedFields(Document doc) {
        Map<String, String> m = new LinkedHashMap<>();
        putStr(m, "price_asked_czk", getLong(doc, "price_czk_value"));
        putStr(m, "price_monthly_czk", getLong(doc, "price_czk_value"));
        putStr(m, "price_starting_bid_czk", getLong(doc, "price_czk_value"));
        putStr(m, "usable_area_m2", getUsableArea(doc));
        putStr(m, "plot_area_m2", parseAreaDouble(str(doc, "area_plocha_pozemku")));
        putStr(m, "garden_area_m2", parseAreaDouble(str(doc, "area_zahrada")));
        m.put("sub_category", str(doc, "sub_category"));
        m.put("ownership_label", str(doc, "ownership_label"));
        m.put("building_type_label", str(doc, "building_type_label"));
        m.put("building_condition_label", str(doc, "building_condition_label"));
        m.put("energy_rating_label", energyLabel(doc));
        m.put("is_new_building", bool(doc, "is_new"));
        m.put("is_low_energy", bool(doc, "is_low_energy"));
        m.put("is_furnished", bool(doc, "is_furnished"));
        m.put("is_barrier_free", bool(doc, "is_barrier_free"));
        m.put("has_balcony", bool(doc, "has_balcony"));
        m.put("has_terrace", bool(doc, "has_terrace"));
        m.put("has_loggia", bool(doc, "has_loggia"));
        m.put("has_cellar", bool(doc, "has_cellar"));
        m.put("has_elevator", bool(doc, "has_elevator"));
        m.put("has_parking", bool(doc, "has_parking"));
        m.put("has_garage", bool(doc, "has_garage"));
        m.put("has_pool", bool(doc, "has_pool"));
        putStr(m, "floor_number", getFloorNumber(doc));
        putStr(m, "total_floors", getTotalFloors(doc));
        return m;
    }

    private void recordFieldChanges(long hashId, String tableName, LocalDate changedAt,
            List<FieldChange> changes) throws SQLException {
        String sql = "INSERT INTO " + pg.t("estate_field_changes")
                + " (hash_id,table_name,changed_at,field_name,old_value,new_value)"
                + " VALUES (?,?,?,?,?,?)";
        try (Connection c = pg.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            c.setAutoCommit(false);
            for (FieldChange ch : changes) {
                ps.setLong(1, hashId);
                ps.setString(2, tableName);
                ps.setDate(3, Date.valueOf(changedAt));
                ps.setString(4, ch.fieldName());
                ps.setString(5, ch.oldValue());
                ps.setString(6, ch.newValue());
                ps.addBatch();
            }
            ps.executeBatch();
            c.commit();
        }
    }

    // =========================================================================
    // Detail text
    // =========================================================================

    private void upsertDetail(long hashId, Document doc) throws SQLException {
        String sql = "INSERT INTO " + pg.t("estate_detail")
                + " (hash_id,description,locality_full,scraped_at) VALUES (?,?,?,now())"
                + " ON CONFLICT (hash_id) DO UPDATE SET"
                + "   description=EXCLUDED.description,"
                + "   locality_full=EXCLUDED.locality_full,"
                + "   scraped_at=EXCLUDED.scraped_at";
        try (Connection c = pg.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, hashId);
            ps.setString(2, str(doc, "description"));
            ps.setString(3, str(doc, "locality"));
            ps.execute();
        }
    }

    // =========================================================================
    // Field extraction helpers
    // =========================================================================

    private static Integer getUsableArea(Document doc) {
        Object v = doc.get("usable_area_m2");
        if (v instanceof Integer i)
            return i;
        if (v instanceof Long l)
            return l.intValue();
        return parseAreaInt(str(doc, "area_uzitna_plocha"));
    }

    private static Integer getFloorNumber(Document doc) {
        String fc = str(doc, "count_podlazi_z_celkem");
        if (fc != null) {
            try {
                return Integer.parseInt(fc.split("/")[0].trim());
            } catch (NumberFormatException ignored) {
            }
        }
        String fd = str(doc, "detail_podlazi");
        if (fd != null) {
            String d = fd.replaceAll("[^0-9]", "");
            if (!d.isEmpty()) {
                try {
                    return Integer.parseInt(d.substring(0, 1));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return null;
    }

    private static Integer getTotalFloors(Document doc) {
        String fc = str(doc, "count_podlazi_z_celkem");
        if (fc != null && fc.contains("/")) {
            try {
                return Integer.parseInt(fc.split("/")[1].trim());
            } catch (NumberFormatException ignored) {
            }
        }
        String fd = str(doc, "detail_pocet_podlazi");
        if (fd != null) {
            try {
                return Integer.parseInt(fd.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private static Double parseAreaDouble(String raw) {
        if (raw == null)
            return null;
        try {
            return Double.parseDouble(raw.replace(" m²", "").replace(" m2", "").replace(",", ".").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseAreaInt(String raw) {
        Double d = parseAreaDouble(raw);
        return d == null ? null : d.intValue();
    }

    // =========================================================================
    // PreparedStatement helpers
    // =========================================================================

    private static void setIntOrNull(PreparedStatement ps, int col, Integer v) throws SQLException {
        if (v == null)
            ps.setNull(col, Types.INTEGER);
        else
            ps.setInt(col, v);
    }

    private static void setDoubleOrNull(PreparedStatement ps, int col, Double v) throws SQLException {
        if (v == null)
            ps.setNull(col, Types.NUMERIC);
        else
            ps.setDouble(col, v);
    }

    private static void setBoolOrNull(PreparedStatement ps, int col, Document doc, String field)
            throws SQLException {
        Object v = doc.get(field);
        if (v == null)
            ps.setNull(col, Types.BOOLEAN);
        else if (v instanceof Boolean b)
            ps.setBoolean(col, b);
        else
            ps.setBoolean(col, Boolean.parseBoolean(v.toString()));
    }

    private static void setFirstSeenDate(PreparedStatement ps, int col, Document doc)
            throws SQLException {
        String ts = str(doc, "_first_seen_at");
        if (ts == null || ts.length() < 10) {
            ps.setNull(col, Types.DATE);
            return;
        }
        try {
            ps.setDate(col, Date.valueOf(ts.substring(0, 10)));
        } catch (IllegalArgumentException e) {
            ps.setNull(col, Types.DATE);
        }
    }

    // =========================================================================
    // Document extractors
    // =========================================================================

    protected static String str(Document doc, String key) {
        Object v = doc.get(key);
        return v == null ? null : v.toString();
    }

    private static String bool(Document doc, String key) {
        Object v = doc.get(key);
        return v == null ? null : v.toString();
    }

    protected static long getLong(Document doc, String key) {
        Object v = doc.get(key);
        if (v instanceof Long l)
            return l;
        if (v instanceof Integer i)
            return i;
        if (v instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                return 0L;
            }
        }
        return 0L;
    }

    protected static double getDouble(Document doc, String key) {
        Object v = doc.get(key);
        if (v instanceof Double d)
            return d;
        if (v instanceof Float f)
            return f;
        if (v instanceof Integer i)
            return i;
        if (v instanceof Long l)
            return l;
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    private static int intVal(Document doc, String key, int def) {
        Object v = doc.get(key);
        if (v instanceof Integer i)
            return i;
        if (v instanceof Long l)
            return l.intValue();
        return def;
    }

    private static String join(String... parts) {
        return String.join("|", Arrays.stream(parts)
                .map(s -> s == null ? "null" : s)
                .toArray(String[]::new));
    }

    private static void putStr(Map<String, String> m, String key, Object v) {
        m.put(key, v == null ? null : v.toString());
    }

    private static boolean isInfraColumn(String col) {
        return switch (col) {
            case "id", "hash_id", "content_hash", "valid_from", "valid_to",
                    "obec_id", "cast_obce_id", "agency_id", "date_id",
                    "is_active", "first_seen_date", "sreality_url",
                    "advert_images_count", "has_floor_plan", "has_video",
                    "gps_lat", "gps_lon" ->
                true;
            default -> false;
        };
    }
}
