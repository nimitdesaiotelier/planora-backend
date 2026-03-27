package com.planora.web.dto;

import java.util.Map;

public record AskPlanRowDto(
        String lineKey,
        /** From plan line; may be null in edge cases. */
        String department,
        /** COA / line identifier for display; falls back to {@link #lineKey} in UI when null. */
        String coaCode,
        /** Human-readable line name; falls back to {@link #label} in UI when null. */
        String coaName,
        String label,
        /** Line item type (e.g. Revenue / Expense) — “Account type” in the Ask Plan table. */
        String type,
        String department,
        String category,
        Map<String, Integer> baseValues,
        Integer baseTotal,
        Integer periodTotal,
        Map<String, Integer> compareValues,
        Integer compareTotal,
        Integer comparePeriodTotal,
        Integer deltaVsCompare,
        Map<String, Integer> actualValues,
        Integer actualTotal,
        Integer actualPeriodTotal,
        Integer deltaVsActual) {}
