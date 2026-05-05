package com.sreality.pipeline.enricher.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Computes field-level diffs between two estate snapshots.
 * Each changed field produces one FieldChange record written to estate_field_changes.
 */
public final class FieldDiff {

    private FieldDiff() {}

    public record FieldChange(String fieldName, String oldValue, String newValue) {}

    /**
     * Compares two maps of field values (field name → string representation).
     * Returns one FieldChange per field whose value differs.
     * Null values are compared correctly: null→value and value→null are both changes.
     */
    public static List<FieldChange> diff(
            java.util.Map<String, String> oldValues,
            java.util.Map<String, String> newValues) {

        List<FieldChange> changes = new ArrayList<>();
        // All fields in either snapshot
        java.util.Set<String> allFields = new java.util.LinkedHashSet<>();
        allFields.addAll(oldValues.keySet());
        allFields.addAll(newValues.keySet());

        for (String field : allFields) {
            String oldVal = oldValues.get(field);
            String newVal = newValues.get(field);
            boolean differs = (oldVal == null) != (newVal == null)
                || (oldVal != null && !oldVal.equals(newVal));
            if (differs) {
                changes.add(new FieldChange(field, oldVal, newVal));
            }
        }
        return changes;
    }
}
