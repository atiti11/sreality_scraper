package com.sreality.etl.transform;

import com.sreality.etl.config.EtlConfig;
import com.sreality.etl.load.PostgresLoader;
import com.sreality.etl.model.DimAgency;
import com.sreality.etl.model.DimDate;
import com.sreality.etl.model.EtlReport;
import com.sreality.etl.model.FactSnapshot;
import com.sreality.etl.model.RawEstate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Transforms a batch of RawEstate objects into FactSnapshot rows and
 * applies the SCD Type 2 versioning logic before loading.
 *
 * Transformations applied here:
 *   1. Spatial join: GPS → cast_obce_id + obec_id
 *   2. Derived attribute: price_per_m2 = price / usable_area_m2
 *   3. Agency deduplication: one dim_agency row per unique sreality agency ID
 *   4. SCD Type 2: compare with current PostgreSQL row — only insert new version
 *      if a meaningful field changed; otherwise mark as unchanged
 *   5. Date FK: firstSeenDate → dim_date.date_id
 *   6. valid_from / valid_to management
 */
public class FactBuilder {

    private static final Logger log = LoggerFactory.getLogger(FactBuilder.class);

    private final SpatialJoiner          spatialJoiner;
    private final Map<Integer, DimAgency> agencyCache;  // srealityId → DimAgency
    private final PostgresLoader          pg;
    private final LocalDate               today = LocalDate.now();

    public FactBuilder(SpatialJoiner spatialJoiner, Map<Integer, DimAgency> agencyCache,
                       PostgresLoader pg, EtlConfig config) {
        this.spatialJoiner = spatialJoiner;
        this.agencyCache   = agencyCache;
        this.pg            = pg;
    }

    /**
     * Processes one batch of raw estates for a given deal type.
     * Each estate goes through the full transform pipeline and is upserted
     * into the appropriate PostgreSQL fact table.
     */
    public void processBatch(List<RawEstate> estates, String dealType, EtlReport report) {
        List<FactSnapshot> toUpsert = new ArrayList<>(estates.size());

        for (RawEstate raw : estates) {
            report.estatesRead.incrementAndGet();

            if (!raw.isUsable()) {
                report.estatesSkipped.incrementAndGet();
                continue;
            }

            // ── 1. Spatial join ───────────────────────────────────────────
            SpatialJoiner.SpatialMatch spatial =
                spatialJoiner.match(raw.gpsLat, raw.gpsLon);

            if (spatial.obecId() == -1) {
                report.spatialNoMatch.incrementAndGet();
                report.estatesSkipped.incrementAndGet();
                log.debug("Estate {} skipped: no spatial match (lat={}, lon={})",
                    raw.hashId, raw.gpsLat, raw.gpsLon);
                continue;
            }

            if (spatial.castObceId() != null) {
                report.spatialMatchCast.incrementAndGet();
            } else {
                report.spatialMatchObec.incrementAndGet();
            }

            // ── 2. Derived attribute: price_per_m2 ───────────────────────
            Double pricePerM2 = null;
            if (raw.priceCzk != null && raw.usableAreaM2 != null && raw.usableAreaM2 > 0) {
                pricePerM2 = raw.priceCzk / raw.usableAreaM2;
            }

            // ── 3. Agency deduplication ───────────────────────────────────
            Integer agencyId = null;
            if (raw.agencySrealityId != null && raw.agencySrealityId > 0) {
                agencyId = resolveAgency(raw, report);
            }

            // ── 4. Date FK ────────────────────────────────────────────────
            int dateId = raw.firstSeenDate != null
                ? DimDate.dateId(raw.firstSeenDate)
                : DimDate.dateId(today);

            // ── 5. Build fact snapshot ────────────────────────────────────
            FactSnapshot snap = new FactSnapshot(
                raw.hashId,
                raw.srealityUrl != null ? raw.srealityUrl
                    : "https://www.sreality.cz/cs/v2/estates/" + raw.hashId,
                dealType,
                raw.propertyType,
                raw.subCategory,
                today,        // valid_from = today (set by upsert logic in loader)
                null,         // valid_to = null (current state)
                spatial.castObceId(),
                spatial.obecId(),
                agencyId,
                dateId,
                raw.priceCzk,
                pricePerM2,
                raw.usableAreaM2,
                raw.floorNumber,
                raw.totalFloors,
                raw.gpsLat,
                raw.gpsLon,
                raw.ownershipLabel,
                raw.buildingTypeLabel,
                raw.buildingConditionLabel,
                raw.energyRatingLabel,
                raw.isNewBuilding,
                raw.isFurnished,
                raw.hasBalcony,
                raw.hasTerrace,
                raw.hasLoggia,
                raw.hasCellar,
                raw.hasElevator,
                raw.hasGarage,
                raw.hasParking,
                raw.hasPool,
                raw.isBarrierFree,
                raw.isActive,
                raw.firstSeenDate,
                raw.advertImagesCount,
                raw.hasFloorPlan,
                raw.hasVideo
            );

            toUpsert.add(snap);
        }

        // ── 6. SCD Type 2 upsert (batch) ──────────────────────────────────
        if (!toUpsert.isEmpty()) {
            pg.upsertFactSnapshots(toUpsert, dealType, report);
        }
    }

    /**
     * Resolves or creates a dim_agency row for the given estate's agency.
     * Uses in-memory cache keyed by sreality agency ID to avoid duplicate
     * DB lookups — one DB call per new agency, then cached for all subsequent estates.
     */
    private Integer resolveAgency(RawEstate raw, EtlReport report) {
        if (agencyCache.containsKey(raw.agencySrealityId)) {
            return agencyCache.get(raw.agencySrealityId).id();
        }

        DimAgency agency = new DimAgency(
            0, raw.agencySrealityId,
            raw.agencyName != null ? raw.agencyName : "",
            raw.agencyUrl  != null ? raw.agencyUrl  : ""
        );

        int id = pg.upsertAgency(agency);
        DimAgency withId = agency.withId(id);
        agencyCache.put(raw.agencySrealityId, withId);
        report.agenciesCreated.incrementAndGet();
        return id;
    }
}
