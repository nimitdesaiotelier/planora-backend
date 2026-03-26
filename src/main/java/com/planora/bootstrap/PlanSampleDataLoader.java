package com.planora.bootstrap;

import com.planora.domain.PlanMonthlyDetails;
import com.planora.enums.LineItemType;
import com.planora.domain.Plan;
import com.planora.enums.PlanType;
import com.planora.domain.Property;
import com.planora.repo.PlanRepository;
import com.planora.repo.PropertyRepository;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(2)
@RequiredArgsConstructor
public class PlanSampleDataLoader implements ApplicationRunner {

    private final PlanRepository planRepository;
    private final PropertyRepository propertyRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (planRepository.count() > 0) {
            return;
        }

        Property property = propertyRepository.findAll().stream().findFirst().orElse(null);
        if (property == null) {
            return;
        }

        List<LineTemplate> templates = baseLineTemplates();

        Plan budget = new Plan();
        budget.setName("FY 2026 Operating Budget — " + property.getName());
        budget.setPlanType(PlanType.BUDGET);
        budget.setFiscalYear(2026);
        budget.setProperty(property);
        addLines(budget, templates, 1.0);

        Plan forecast = new Plan();
        forecast.setName("FY 2026 Rolling Forecast — " + property.getName());
        forecast.setPlanType(PlanType.FORECAST);
        forecast.setFiscalYear(2026);
        forecast.setProperty(property);
        addLines(forecast, templates, 1.05);

        Plan whatIf = new Plan();
        whatIf.setName("FY 2026 What-If: Rate Softening — " + property.getName());
        whatIf.setPlanType(PlanType.WHAT_IF);
        whatIf.setFiscalYear(2026);
        whatIf.setProperty(property);
        addLines(whatIf, templates, 0.97);

        planRepository.saveAll(List.of(budget, forecast, whatIf));
    }

    private void addLines(Plan plan, List<LineTemplate> templates, double scale) {
        int year = plan.getFiscalYear();
        for (LineTemplate t : templates) {
            PlanMonthlyDetails item = new PlanMonthlyDetails();
            item.setPlan(plan);
            item.setLineKey(t.lineKey());
            item.setDepartment(t.department());
            item.setType(t.lineType());
            item.setCategory(t.category());
            item.setLabel(t.label());
            Map<String, Integer> scaled = scaleMap(t.periodValues(), scale);
            item.applyValuesMap(scaled);
            item.setDailyDetails(buildDailyFromMonthly(scaled, year));
            plan.getLineItems().add(item);
        }
    }

    private static Map<String, Integer> scaleMap(Map<String, Integer> src, double scale) {
        Map<String, Integer> out = new HashMap<>();
        src.forEach((k, v) -> out.put(k, (int) Math.round(v * scale)));
        return out;
    }

    private static Map<String, List<Integer>> buildDailyFromMonthly(Map<String, Integer> monthTotals, int year) {
        Map<String, List<Integer>> out = new LinkedHashMap<>();
        for (String month : PlanMonthlyDetails.MONTH_KEYS) {
            int total = monthTotals.getOrDefault(month, 0);
            int days = daysInMonth(month, year);
            out.put(month, splitEvenAcrossDays(total, days));
        }
        return out;
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

    /** Distributes {@code total} into {@code days} integers that sum to {@code total}. */
    private static List<Integer> splitEvenAcrossDays(int total, int days) {
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

    private static List<LineTemplate> baseLineTemplates() {
        List<LineTemplate> list = new ArrayList<>();

        list.add(new LineTemplate(
                "transient-revenue",
                "Rooms",
                LineItemType.Revenue,
                "Revenue",
                "Transient Revenue",
                months(
                        42_000, 38_000, 45_000, 51_000, 55_000, 60_000,
                        63_000, 65_000, 58_000, 52_000, 48_000, 44_000
                )
        ));

        list.add(new LineTemplate(
                "group-revenue",
                "Sales",
                LineItemType.Revenue,
                "Revenue",
                "Group Revenue",
                months(
                        18_000, 16_000, 22_000, 25_000, 28_000, 30_000,
                        27_000, 26_000, 24_000, 21_000, 19_000, 17_000
                )
        ));

        list.add(new LineTemplate(
                "fb-revenue",
                "F&B",
                LineItemType.Revenue,
                "Revenue",
                "F&B Revenue",
                months(
                        12_000, 11_000, 13_000, 15_000, 16_000, 18_000,
                        19_000, 20_000, 17_000, 14_000, 13_000, 11_000
                )
        ));

        list.add(new LineTemplate(
                "rooms-available",
                "Rooms",
                LineItemType.Statistics,
                "Statistics",
                "Rooms Available",
                months(
                        3_100, 3_100, 3_100, 3_100, 3_100, 3_100,
                        3_100, 3_100, 3_100, 3_100, 3_100, 3_100
                )
        ));

        list.add(new LineTemplate(
                "occupied-room-nights",
                "Rooms",
                LineItemType.Statistics,
                "Statistics",
                "Occupied Room Nights",
                months(
                        2_420, 2_190, 2_580, 2_880, 3_110, 3_350,
                        3_520, 3_600, 3_280, 2_880, 2_650, 2_410
                )
        ));

        list.add(new LineTemplate(
                "labor-expense",
                "Operations",
                LineItemType.Expense,
                "Expense",
                "Labor Expense",
                months(
                        28_000, 26_000, 30_000, 32_000, 34_000, 36_000,
                        38_000, 39_000, 35_000, 31_000, 29_000, 27_000
                )
        ));

        list.add(new LineTemplate(
                "utilities-expense",
                "Property",
                LineItemType.Expense,
                "Expense",
                "Utilities Expense",
                months(
                        8_000, 7_500, 7_000, 6_500, 6_000, 6_500,
                        7_000, 7_500, 7_000, 7_500, 8_000, 8_500
                )
        ));

        list.add(new LineTemplate(
                "marketing-expense",
                "Sales & Marketing",
                LineItemType.Expense,
                "Expense",
                "Marketing Expense",
                months(
                        5_000, 5_000, 6_000, 7_000, 7_000, 8_000,
                        8_000, 8_000, 7_000, 6_000, 5_000, 5_000
                )
        ));

        return list;
    }

    private static Map<String, Integer> months(int... vals) {
        String[] keys = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < keys.length; i++) {
            m.put(keys[i], vals[i]);
        }
        return m;
    }

    private record LineTemplate(
            String lineKey,
            String department,
            LineItemType lineType,
            String category,
            String label,
            Map<String, Integer> periodValues
    ) {}
}
