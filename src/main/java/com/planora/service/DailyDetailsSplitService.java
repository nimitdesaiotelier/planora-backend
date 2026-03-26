package com.planora.service;

import com.planora.domain.PlanMonthlyDetails;
import com.planora.enums.LineItemType;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class DailyDetailsSplitService {

    /**
     * Build Jan-Dec daily arrays whose sums match monthly totals.
     */
    public Map<String, List<Integer>> buildDailyFromMonthly(
            Map<String, Integer> monthTotals,
            int fiscalYear,
            LineItemType lineItemType) {
        Map<String, List<Integer>> out = new LinkedHashMap<>();
        for (String month : PlanMonthlyDetails.MONTH_KEYS) {
            int total = monthTotals.getOrDefault(month, 0);
            int days = daysInMonth(month, fiscalYear);
            List<Integer> split = lineItemType == LineItemType.Statistics
                    ? splitForStatistics(total, days)
                    : splitEvenAcrossDays(total, days);
            out.put(month, split);
        }
        return out;
    }

    /** Days in calendar month for the given fiscal year (handles leap February). */
    public int lengthOfMonth(String month, int fiscalYear) {
        return daysInMonth(month, fiscalYear);
    }

    /** Split a single month total into one list (same rules as {@link #buildDailyFromMonthly}). */
    public List<Integer> dailyListForMonthTotal(
            int total, String month, int fiscalYear, LineItemType lineItemType) {
        int days = daysInMonth(month, fiscalYear);
        return lineItemType == LineItemType.Statistics
                ? splitForStatistics(total, days)
                : splitEvenAcrossDays(total, days);
    }

    private static int daysInMonth(String month, int year) {
        int m = switch (month) {
            case "Jan" -> 1;
            case "Feb" -> 2;
            case "Mar" -> 3;
            case "Apr" -> 4;
            case "May" -> 5;
            case "Jun" -> 6;
            case "Jul" -> 7;
            case "Aug" -> 8;
            case "Sep" -> 9;
            case "Oct" -> 10;
            case "Nov" -> 11;
            case "Dec" -> 12;
            default -> 1;
        };
        return YearMonth.of(year, m).lengthOfMonth();
    }

    /**
     * Default split: distribute remainder over earliest days.
     */
    static List<Integer> splitEvenAcrossDays(int total, int days) {
        List<Integer> list = new ArrayList<>(Math.max(days, 0));
        if (days <= 0) {
            return list;
        }
        int base = total / days;
        int rem = total - base * days;
        for (int d = 0; d < days; d++) {
            list.add(base + (d < rem ? 1 : 0));
        }
        return list;
    }

    /**
     * Statistics rule: values are always integers; any fractional split is rounded up first,
     * then adjusted back to preserve exact monthly total.
     */
    static List<Integer> splitForStatistics(int total, int days) {
        List<Integer> list = new ArrayList<>(Math.max(days, 0));
        if (days <= 0) {
            return list;
        }
        if (total < 0) {
            return splitEvenAcrossDays(total, days);
        }
        if (total == 0) {
            for (int d = 0; d < days; d++) {
                list.add(0);
            }
            return list;
        }

        int roundedUp = (int) Math.ceil((double) total / days);
        for (int d = 0; d < days; d++) {
            list.add(roundedUp);
        }

        int running = roundedUp * days;
        int delta = running - total;
        int idx = days - 1;
        while (delta > 0 && idx >= 0) {
            int current = list.get(idx);
            if (current > 0) {
                list.set(idx, current - 1);
                delta--;
            }
            idx--;
            if (idx < 0 && delta > 0) {
                idx = days - 1;
            }
        }
        return list;
    }
}
