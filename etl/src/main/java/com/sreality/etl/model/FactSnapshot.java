package com.sreality.etl.model;

import java.time.LocalDate;

/**
 * Fact row for a snapshot fact table (sale, rent, or auction).
 *
 * One row represents one distinct observed state of an estate.
 * A new row is only created when a meaningful field changes (SCD Type 2
 * applied to the fact table). valid_to = null means current state.
 *
 * Used for all three deal types — the dealType field determines which
 * PostgreSQL table this row is loaded into.
 */
public record FactSnapshot(
    long        hashId,
    String      srealityUrl,
    String      dealType,           // "sale" | "rent" | "auction" — routing only
    String      propertyType,       // "Apartment" | "House" | "Land" | "Commercial" | "Other"
    String      subCategory,        // nullable: "1+kk", "2+1", etc.

    // versioning
    LocalDate   validFrom,
    LocalDate   validTo,            // null = current state

    // dimension FKs
    Integer     castObceId,         // nullable
    int         obecId,
    Integer     agencyId,           // nullable
    int         dateId,             // FK → dim_date

    // measures — semantics depend on dealType
    Long        priceCzk,           // asking / monthly / starting bid
    Double      pricePerM2,         // nullable — requires usable_area_m2
    Double      usableAreaM2,       // nullable

    // location
    Integer     floorNumber,        // nullable
    Integer     totalFloors,        // nullable
    Double      gpsLat,
    Double      gpsLon,

    // building characteristics (sale only — nullable on rent/auction)
    String      ownershipLabel,
    String      buildingTypeLabel,
    String      buildingConditionLabel,
    String      energyRatingLabel,

    // boolean features
    Boolean     isNewBuilding,
    Boolean     isFurnished,        // rent-specific
    Boolean     hasBalcony,
    Boolean     hasTerrace,
    Boolean     hasLoggia,
    Boolean     hasCellar,
    Boolean     hasElevator,
    Boolean     hasGarage,
    Boolean     hasParking,
    Boolean     hasPool,
    Boolean     isBarrierFree,

    // listing metadata
    boolean     isActive,
    LocalDate   firstSeenDate,
    Integer     advertImagesCount,
    Boolean     hasFloorPlan,
    Boolean     hasVideo
) {}
