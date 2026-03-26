package com.planora.service;

import com.planora.domain.PlanMonthlyDetails;
import com.planora.enums.LineItemType;
import com.planora.web.dto.InstructionStepDto;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies parsed steps directly to per-day amounts, then month totals are the sum of each month’s list.
 */
public final class DailyBudgetInstructionApplyService {

    private DailyBudgetInstructionApplyService() {}

    /**
     * Start from stored dailies when list length matches the month length in {@code fiscalYear}; otherwise split
     * from {@code values} for that month.
     */
    public static Map<String, List<Integer>> baseline(
            Map<String, Integer> values,
            Map<String, List<Integer>> existingDaily,
            int fiscalYear,
            LineItemType lineItemType,
            DailyDetailsSplitService splitService) {
        Map<String, List<Integer>> fromSplit =
                splitService.buildDailyFromMonthly(values != null ? values : Map.of(), fiscalYear, lineItemType);
        Map<String, List<Integer>> out = new LinkedHashMap<>();
        for (String month : PlanMonthlyDetails.MONTH_KEYS) {
            int dim = splitService.lengthOfMonth(month, fiscalYear);
            List<Integer> custom = existingDaily != null ? existingDaily.get(month) : null;
            if (custom != null && custom.size() == dim) {
                out.put(month, new ArrayList<>(custom));
            } else {
                out.put(month, new ArrayList<>(fromSplit.getOrDefault(month, List.of())));
            }
        }
        return out;
    }

    public static Map<String, Integer> aggregateMonthsFromDaily(Map<String, List<Integer>> daily) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String month : PlanMonthlyDetails.MONTH_KEYS) {
            List<Integer> list = daily.get(month);
            int sum = 0;
            if (list != null) {
                for (Integer v : list) {
                    sum += v != null ? v : 0;
                }
            }
            m.put(month, sum);
        }
        return m;
    }

    public static void applyStep(
            Map<String, List<Integer>> daily,
            InstructionStepDto step,
            Map<String, Integer> sourceMonths,
            int fiscalYear,
            LineItemType lineItemType,
            DailyDetailsSplitService splitService) {

        String action = step.action() == null ? "" : step.action().toLowerCase();
        if ("copy".equals(action)) {
            List<String> affected = BudgetInstructionApplyService.affectedMonths(step.period());
            for (String month : affected) {
                int total = sourceMonths != null ? sourceMonths.getOrDefault(month, 0) : 0;
                daily.put(
                        month,
                        new ArrayList<>(splitService.dailyListForMonthTotal(
                                total, month, fiscalYear, lineItemType)));
            }
            return;
        }

        applyNumericToDaily(daily, step, lineItemType, fiscalYear);
    }

    private static final Map<String, Integer> MONTH_NUMBER = Map.ofEntries(
            Map.entry("Jan", 1), Map.entry("Feb", 2), Map.entry("Mar", 3),
            Map.entry("Apr", 4), Map.entry("May", 5), Map.entry("Jun", 6),
            Map.entry("Jul", 7), Map.entry("Aug", 8), Map.entry("Sep", 9),
            Map.entry("Oct", 10), Map.entry("Nov", 11), Map.entry("Dec", 12));

    private static void applyNumericToDaily(
            Map<String, List<Integer>> daily,
            InstructionStepDto step,
            LineItemType lineItemType,
            int fiscalYear) {

        String action = step.action() == null ? "" : step.action().toLowerCase();
        Double value = BudgetInstructionApplyService.toDouble(step.value());
        List<String> affected = BudgetInstructionApplyService.affectedMonths(step.period());

        Integer df = step.dayFrom();
        Integer dt = step.dayTo();
        String dayFilter = step.dayFilter();
        boolean hasDayFilter = dayFilter != null && !dayFilter.isBlank();
        boolean wholeMonth = df == null && dt == null;

        boolean pct = "percentage".equalsIgnoreCase(step.type());
        boolean abs = "absolute".equalsIgnoreCase(step.type());

        for (String month : affected) {
            List<Integer> list = daily.get(month);
            if (list == null || list.isEmpty()) {
                continue;
            }
            int dim = list.size();
            int fromDay = wholeMonth ? 1 : (df != null ? df : 1);
            int toDay = wholeMonth ? dim : (dt != null ? dt : fromDay);
            fromDay = Math.max(1, Math.min(dim, fromDay));
            toDay = Math.max(1, Math.min(dim, toDay));
            if (fromDay > toDay) {
                int tmp = fromDay;
                fromDay = toDay;
                toDay = tmp;
            }

            int monthNum = MONTH_NUMBER.getOrDefault(month, 1);

            for (int day = fromDay; day <= toDay; day++) {
                if (hasDayFilter) {
                    DayOfWeek dow = LocalDate.of(fiscalYear, monthNum, day).getDayOfWeek();
                    boolean isWeekend = dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
                    if ("weekends".equalsIgnoreCase(dayFilter) && !isWeekend) {
                        continue;
                    }
                    if ("weekdays".equalsIgnoreCase(dayFilter) && isWeekend) {
                        continue;
                    }
                }

                int idx = day - 1;
                int current = list.get(idx);
                int next = switch (action) {
                    case "increase" -> {
                        if (pct && value != null) {
                            yield roundForLineType(current * (1 + value / 100.0), lineItemType);
                        }
                        if (abs && value != null) {
                            yield roundForLineType(current + value, lineItemType);
                        }
                        yield current;
                    }
                    case "decrease" -> {
                        if (pct && value != null) {
                            yield roundForLineType(current * (1 - value / 100.0), lineItemType);
                        }
                        if (abs && value != null) {
                            yield roundForLineType(current - value, lineItemType);
                        }
                        yield current;
                    }
                    case "set" -> {
                        if (abs && value != null) {
                            yield roundForLineType(value, lineItemType);
                        }
                        yield current;
                    }
                    default -> current;
                };
                list.set(idx, next);
            }
        }
    }

    private static int roundForLineType(double x, LineItemType lineItemType) {
        if (lineItemType == LineItemType.Statistics) {
            return x >= 0 ? (int) Math.ceil(x) : (int) Math.floor(x);
        }
        return (int) Math.round(x);
    }

    public static boolean anyStepTargetsSpecificDays(List<InstructionStepDto> steps) {
        for (InstructionStepDto s : steps) {
            if (s.dayFrom() != null || s.dayTo() != null) {
                return true;
            }
            if (s.dayFilter() != null && !s.dayFilter().isBlank()) {
                return true;
            }
        }
        return false;
    }
}
