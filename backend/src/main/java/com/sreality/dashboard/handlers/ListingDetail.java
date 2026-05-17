package com.sreality.dashboard.handlers;

import com.sreality.dashboard.DealType;
import com.sreality.dashboard.PropertyType;
import com.sreality.dashboard.sql.Queries;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.http.NotFoundResponse;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code GET /api/listing/{property_type}/{deal}/{hash_id}/detail} —
 * returns every column from the active fact-table row for one estate,
 * plus the long description from {@code estate_detail}. Used by the
 * "Details" dropdown the listings page renders under each row.
 *
 * <p>We don't store image URLs in the warehouse (the schema only carries
 * an {@code advert_images_count} integer per estate), so the detail
 * panel can show a count but has to send users back to sreality.cz for
 * the actual photos.</p>
 */
public final class ListingDetail implements Handler {

    private final DataSource ds;

    public ListingDetail(DataSource ds) {
        this.ds = ds;
    }

    @Override
    public void handle(Context ctx) throws Exception {
        PropertyType ptype;
        DealType deal;
        long hashId;
        try {
            ptype  = PropertyType.fromQueryToken(ctx.pathParam("property_type"));
            deal   = DealType.fromQueryToken(ctx.pathParam("deal"));
            hashId = Long.parseLong(ctx.pathParam("hash_id"));
        } catch (IllegalArgumentException e) {
            throw new NotFoundResponse("Unknown property/deal type or hash id.");
        }

        Queries.TableCfg cfg = Queries.TABLES.get(new Queries.TableKey(ptype, deal));
        if (cfg == null) throw new NotFoundResponse("No fact table for that combination.");

        // SELECT *. Each fact table has its own column set (apartments
        // expose ``has_balcony``, houses expose ``garden_area_m2``, etc.)
        // so we just stream every column back as JSON; the React panel
        // chooses what to show based on what's present.
        String sql =
            "SELECT f.*, ed.description AS detail_description, " +
            "       o.nazev_obce AS obec, co.nazev_cast_obce AS cast_obce, " +
            "       r.nazev_okresu AS okres, k.nazev_kraje AS kraj " +
            "FROM   " + cfg.table() + " f " +
            "LEFT JOIN estate_detail   ed ON ed.hash_id  = f.hash_id " +
            "LEFT JOIN dim_obec        o  ON o.id        = f.obec_id " +
            "LEFT JOIN dim_cast_obce   co ON co.id       = f.cast_obce_id " +
            "LEFT JOIN dim_okres       r  ON r.id        = o.okres_id " +
            "LEFT JOIN dim_kraj        k  ON k.id        = r.kraj_id " +
            "WHERE  f.hash_id = ? " +
            "  AND  f.valid_to IS NULL " +
            "  AND  f.is_active = TRUE " +
            "LIMIT 1";

        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, hashId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new NotFoundResponse("Listing not found: "
                        + ptype.token() + "/" + deal.token() + "/" + hashId);
                }
                ctx.json(rowToMap(rs));
            }
        }
    }

    /**
     * Convert every column of the current row to a {@link LinkedHashMap}
     * preserving column order. Date / numeric / boolean values are
     * normalised to JSON-friendly Java types; everything else passes
     * through {@code getObject()}.
     */
    private static Map<String, Object> rowToMap(ResultSet rs) throws Exception {
        ResultSetMetaData md = rs.getMetaData();
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            String col = md.getColumnLabel(i);
            int sqlType = md.getColumnType(i);
            Object val = switch (sqlType) {
                case Types.DATE      -> {
                    java.sql.Date d = rs.getDate(i);
                    yield d == null ? null : d.toString();
                }
                case Types.TIMESTAMP -> {
                    java.sql.Timestamp ts = rs.getTimestamp(i);
                    yield ts == null ? null : ts.toInstant().toString();
                }
                case Types.NUMERIC, Types.DECIMAL -> {
                    java.math.BigDecimal bd = rs.getBigDecimal(i);
                    yield bd == null ? null : bd.doubleValue();
                }
                case Types.BIGINT -> {
                    long v = rs.getLong(i);
                    yield rs.wasNull() ? null : v;
                }
                case Types.INTEGER -> {
                    int v = rs.getInt(i);
                    yield rs.wasNull() ? null : v;
                }
                case Types.BOOLEAN, Types.BIT -> {
                    boolean v = rs.getBoolean(i);
                    yield rs.wasNull() ? null : v;
                }
                default -> rs.getObject(i);
            };
            out.put(col, val);
        }
        return out;
    }
}
