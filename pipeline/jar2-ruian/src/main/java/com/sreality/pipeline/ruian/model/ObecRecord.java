package com.sreality.pipeline.ruian.model;

/**
 * Obec (municipality). is_active=false means ZanikloOd is in the past.
 * Extinct obce are still stored in dim_obec so historical fact FKs remain valid.
 */
public record ObecRecord(String kodObce, String nazevObce, String kodOkresu, boolean isActive) {}
