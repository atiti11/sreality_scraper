package com.sreality.etl.model;

/**
 * Dimension: agency (real estate agency).
 * Built incrementally as estates are processed.
 * Deduplication key: sreality_id.
 */
public record DimAgency(
    int    id,          // surrogate key
    int    srealityId,  // agency ID from Sreality API
    String name,
    String url
) {
    public DimAgency withId(int id) {
        return new DimAgency(id, srealityId, name, url);
    }
}
