package com.planora.service;

import com.planora.enums.PlanType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies parsed AI intent to monthly plan values (same rules as the former frontend {@code applyTransformation}).
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
     * @param priorYearBudgetMonths monthly values from prior FY {@link PlanType#BUDGET} (same property,
     *                              same line); used only for {@code copy}+{@code ly_actual}
     */
    public static Map<String, Integer> apply(
            Map<String, Integer> currentValues,
            Map<String, Integer> priorYearBudgetMonths,
            String action,
            String type,
            String period,
            Double value) {
        Map<String, Integer> base = new LinkedHashMap<>();
        for (String m : MONTHS) {
            base.put(m, currentValues != null ? currentValues.getOrDefault(m, 0) : 0);
        }
        List<String> affected = affectedMonths(period);
        String act = action == null ? "" : action.toLowerCase();
        String typ = type == null ? "" : type.toLowerCase();

        for (String month : affected) {
            int current = base.getOrDefault(month, 0);
            if ("copy".equals(act) && "ly_actual".equals(typ)) {
                int av = priorYearBudgetMonths != null ? priorYearBudgetMonths.getOrDefault(month, 0) : 0;
                base.put(month, av);
            } else if ("increase".equals(act)) {
                if ("percentage".equals(typ) && value != null) {
                    base.put(month, (int) Math.round(current * (1 + value / 100.0)));
                } else if ("absolute".equals(typ) && value != null) {
                    base.put(month, (int) Math.round(current + value));
                }
            } else if ("decrease".equals(act)) {
                if ("percentage".equals(typ) && value != null) {
                    base.put(month, (int) Math.round(current * (1 - value / 100.0)));
                } else if ("absolute".equals(typ) && value != null) {
                    base.put(month, Math.max(0, (int) Math.round(current - value)));
                }
            } else if ("set".equals(act)) {
                if ("absolute".equals(typ) && value != null) {
                    base.put(month, (int) Math.round(value));
                }
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

    /** Coerce model output to a numeric amount for math (null when not applicable). */
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
}
