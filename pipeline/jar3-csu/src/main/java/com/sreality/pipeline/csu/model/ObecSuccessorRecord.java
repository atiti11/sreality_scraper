package com.sreality.pipeline.csu.model;

/**
 * One row from the CSU OD_KAM (municipality merger/succession) sheet.
 * When an old municipality is merged into a new one, its historical
 * stats rows are attributed to the new municipality's obec_id.
 */
public record ObecSuccessorRecord(
    String oldObecKod,
    String newObecKod,
    int    mergedYear) {}
