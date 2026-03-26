package com.planora.service;

import com.planora.web.dto.InstructionStepDto;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-function service: applies a single parsed instruction step to month values.
 * No DB access — all source data is passed in by the caller.
 */
public final class BudgetInstructionApplyService {

    private static final List<String> MONTHS = List.of(
            "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec");

    private static final Map<String, List<String>> QUARTERS = Map.of(
            "Q1", List.of("Jan", "Feb", "Mar"),
            "Q2", List.of("Apr", "May", "Jun"),
            "Q3", List.of("Jul", "Aug", "Sep"),
            "Q4", List.of("Oct", "Nov", "Dec"));

    private BudgetInstructionApplyService() {}

    /**
     * Apply a single instruction step.
     *
     * @param currentValues    current Jan–Dec month totals
     * @param sourceMonths     resolved source data for copy operations (empty map if not a copy or unavailable)
     * @param step             the parsed instruction step
     * @return new month values after applying the step
     */
    public static Map<String, Integer> apply(
            Map<String, Integer> currentValues,
            Map<String, Integer> sourceMonths,
            InstructionStepDto step) {

        Map<String, Integer> base = new LinkedHashMap<>();
        for (String m : MONTHS) {
            base.put(m, currentValues != null ? currentValues.getOrDefault(m, 0) : 0);
        }

        String action = step.action() == null ? "" : step.action().toLowerCase();
        Double value = toDouble(step.value());
        List<String> affected = affectedMonths(step.period());

        for (String month : affected) {
            int current = base.getOrDefault(month, 0);

            switch (action) {
                case "copy" -> {
                    if (sourceMonths != null && !sourceMonths.isEmpty()) {
                        base.put(month, sourceMonths.getOrDefault(month, 0));
                    }
                }
                case "increase" -> {
                    if (isPercentage(step.type()) && value != null) {
                        base.put(month, (int) Math.round(current * (1 + value / 100.0)));
                    } else if (isAbsolute(step.type()) && value != null) {
                        base.put(month, (int) Math.round(current + value));
                    }
                }
                case "decrease" -> {
                    if (isPercentage(step.type()) && value != null) {
                        base.put(month, (int) Math.round(current * (1 - value / 100.0)));
                    } else if (isAbsolute(step.type()) && value != null) {
                        base.put(month, (int) Math.round(current - value));
                    }
                }
                case "set" -> {
                    if (isAbsolute(step.type()) && value != null) {
                        base.put(month, (int) Math.round(value));
                    }
                }
                default -> { /* unknown action — leave values unchanged */ }
            }
        }
        return base;
    }

    static List<String> affectedMonths(String period) {
        if (period == null || period.isBlank() || "full_year".equalsIgnoreCase(period)) {
            return MONTHS;
        }
        String p = period.startsWith("Q") ? period.toUpperCase() : period;
        List<String> q = QUARTERS.get(p);
        if (q != null) {
            return q;
        }
        if (MONTHS.contains(period)) {
            return List.of(period);
        }
        return MONTHS;
    }

    public static Double toDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isPercentage(String type) {
        return "percentage".equalsIgnoreCase(type);
    }

    private static boolean isAbsolute(String type) {
        return "absolute".equalsIgnoreCase(type);
    }
}
