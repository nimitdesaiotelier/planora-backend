package com.planora.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.planora.domain.ActualsDetails;
import com.planora.domain.Plan;
import com.planora.domain.PlanMonthlyDetails;
import com.planora.enums.PlanStatus;
import com.planora.enums.PlanType;
import com.planora.repo.ActualsDetailRepository;
import com.planora.repo.LineItemRepository;
import com.planora.repo.PlanRepository;
import com.planora.web.dto.AskPlanRequest;
import com.planora.web.dto.AskPlanResponse;
import com.planora.web.dto.AskPlanRowDto;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

@Service
@RequiredArgsConstructor
public class AiAskPlanService {

    private static final long DEFAULT_ORG_ID = 1L;
    private static final int DEFAULT_TOP_N = 25;
    private static final int MAX_TOP_N = 100;

    private static final Pattern COMPARE_WORDS = Pattern.compile(
            "\\b(compare|vs\\.?|versus|against)\\b", Pattern.CASE_INSENSITIVE);

    private static String buildSystemPrompt() {
        int now = java.time.Year.now().getValue();
        return "You are a financial analytics intent parser.\n"
            + "Read a user question and return ONLY JSON using this exact shape:\n"
            + "{\n"
            + "  \"intent\":\"within_plan|compare_plan|compare_actuals|top_n|filter|plan_exists|unknown\",\n"
            + "  \"lineType\":\"Revenue|Expense|Statistics|null\",\n"
            + "  \"lineTypes\":[\"Revenue|Expense|Statistics\", \"...\"],\n"
            + "  \"category\":\"string or null\",\n"
            + "  \"department\":\"string or null\",\n"
            + "  \"coaCode\":\"string or null\",\n"
            + "  \"coaName\":\"string or null\",\n"
            + "  \"period\":\"Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec|Q1|Q2|Q3|Q4|full_year|null\",\n"
            + "  \"topN\":number or null,\n"
            + "  \"includeActuals\":true|false|null,\n"
            + "  \"compareMode\":\"none|plan|actuals\",\n"
            + "  \"actualYear\":number or null,\n"
            + "  \"comparePlanYear\":number or null,\n"
            + "  \"comparePlanType\":\"BUDGET|FORECAST|WHAT_IF|null\",\n"
            + "  \"rankMode\":\"max|min|avg|null\",\n"
            + "  \"searchText\":\"string or null\",\n"
            + "  \"queryPlanYear\":number or null,\n"
            + "  \"queryPlanType\":\"BUDGET|FORECAST|WHAT_IF|null\",\n"
            + "  \"totalFilter\":\"any|zero|non_zero|null\",\n"
            + "  \"groupBy\":\"department|category|type|null\"\n"
            + "}\n"
            + "Rules:\n"
            + "- Extract department, coaCode, coaName when user mentions department name, GL/COA code, or account name.\n"
            + "- The current calendar year is " + now + ". Resolve relative years: 'this year'/'current year' = " + now + ", 'last year'/'previous year' = " + (now - 1) + ", 'next year' = " + (now + 1) + ". Output the resolved numeric year.\n"
            + "- For filters (department, coaCode, coaName, category), use prefixes to express advanced matching:\n"
            + "  \"!Rooms\" = exclude Rooms, \"Rooms|F&B\" = either Rooms or F&B,\n"
            + "  \"^41\" = starts with 41, \"=4100\" = exact match.\n"
            + "  Default (no prefix) = case-insensitive contains.\n"
            + "- Use plan_exists when the user asks whether a budget/forecast/plan exists for a year (do we have, is there, any plan).\n"
            + "- Use queryPlanYear and queryPlanType when the user wants analytics for a specific fiscal plan year without comparing (e.g. show statistics for Budget 2025); keep compareMode none.\n"
            + "- Use compare_plan only when the user explicitly compares plans (compare, vs, versus, against).\n"
            + "- Use compare_actuals for plan-vs-actual requests.\n"
            + "- Use top_n when user asks for top/bottom style ranking.\n"
            + "- Use filter when user asks for constrained subsets (by category/type/text).\n"
            + "- Use groupBy when user asks for totals/breakdown by department, category, or account type.\n"
            + "- If unclear, use unknown.\n"
            + "Respond with valid JSON only.";
    }

    private final ObjectMapper objectMapper;
    private final PlanRepository planRepository;
    private final LineItemRepository lineItemRepository;
    private final ActualsDetailRepository actualsDetailRepository;
    private final RestClient restClient = RestClient.create();

    @Value("${planora.ai.openai.api-key:}")
    private String openaiApiKey;

    @Value("${planora.ai.gemini.api-key:}")
    private String geminiApiKey;

    @Transactional(readOnly = true)
    public AskPlanResponse ask(AskPlanRequest req) {
        ParsedAskIntent aiIntent = parseIntent(req.provider(), req.question());
        ParsedAskIntent intent = resolveRelativeYearsIfMissing(
                mergeWithOverrides(aiIntent, req), req.question());

        Plan anchorPlan = planRepository.findById(req.basePlanId())
                .orElseThrow(() -> new EntityNotFoundException("Plan not found: " + req.basePlanId()));

        if ("plan_exists".equals(intent.intent())) {
            return buildPlanExistsResponse(req, intent, anchorPlan);
        }

        if ("unknown".equals(intent.intent())
                && intent.topN() == null
                && (intent.lineTypes() == null || intent.lineTypes().isEmpty())
                && intent.category() == null
                && intent.searchText() == null
                && intent.actualYear() == null
                && intent.queryPlanYear() == null
                && !"plan".equals(intent.compareMode())
                && !"actuals".equals(intent.compareMode())) {
            throw new IllegalArgumentException("Could not understand query intent. Try asking with clearer compare/filter terms.");
        }

        Plan basePlan = anchorPlan;
        if (intent.queryPlanYear() != null) {
            PlanType type = parsePlanType(intent.queryPlanType()).orElse(PlanType.BUDGET);
            Optional<Plan> found = planRepository.findFirstByProperty_IdAndFiscalYearAndPlanTypeOrderByIdAsc(
                    anchorPlan.getProperty().getId(), intent.queryPlanYear(), type);
            if (found.isEmpty()) {
                return buildMissingPlanResponse(req, anchorPlan, intent.queryPlanYear(), type, "query");
            }
            basePlan = found.get();
        }

        List<PlanMonthlyDetails> baseRows = lineItemRepository.findByPlan_Id(basePlan.getId());

        Optional<Long> comparePlanIdOpt = resolveComparePlanIdOptional(req, intent, basePlan);
        if ("plan".equals(intent.compareMode())
                && intent.comparePlanYear() != null
                && comparePlanIdOpt.isEmpty()) {
            PlanType type = parsePlanType(intent.comparePlanType()).orElse(PlanType.BUDGET);
            return buildMissingPlanResponse(req, anchorPlan, intent.comparePlanYear(), type, "compare");
        }
        Long comparePlanId = comparePlanIdOpt.orElse(null);
        if ("plan".equals(intent.compareMode()) && comparePlanId == null && req.comparePlanId() == null) {
            throw new IllegalArgumentException("comparePlanId is required (or provide comparePlanYear/comparePlanType).");
        }

        Map<String, PlanMonthlyDetails> compareByKey = comparePlanId == null
                ? Map.of()
                : lineItemRepository.findByPlan_Id(comparePlanId).stream()
                        .collect(LinkedHashMap::new, (m, li) -> m.put(li.getLineKey(), li), Map::putAll);

        Integer actualYear = intent.actualYear() != null ? intent.actualYear() : basePlan.getFiscalYear();
        boolean includeActuals = Boolean.TRUE.equals(intent.includeActuals())
                || "actuals".equals(intent.compareMode())
                || intent.actualYear() != null;
        Map<String, Map<String, Integer>> actualsByKey = includeActuals
                ? loadActualsByLineKey(actualYear, basePlan.getProperty().getId(), DEFAULT_ORG_ID)
                : Map.of();

        List<String> periodMonths = monthsForPeriod(normalizePeriod(intent.period()));
        boolean isFullYear = periodMonths.equals(PlanMonthlyDetails.MONTH_KEYS);

        List<AskPlanRowDto> rows = baseRows.stream()
                .filter(li -> matchesLineTypes(li, intent.lineTypes()))
                .filter(li -> matchesCategory(li, intent.category()))
                .filter(li -> matchesDepartment(li, intent.department()))
                .filter(li -> matchesCoaCode(li, intent.coaCode()))
                .filter(li -> matchesCoaName(li, intent.coaName()))
                .filter(li -> matchesSearchText(li, intent.searchText()))
                .map(li -> toResultRow(li, compareByKey.get(li.getLineKey()),
                        actualsByKey.get(li.getLineKey()), isFullYear ? null : periodMonths))
                .filter(row -> matchesTotalFilter(row, intent.totalFilter(),
                        intent.totalFilterMonths(), intent.period()))
                .sorted(sorter(intent))
                .toList();

        int effectiveTopN = clampTopN(intent.topN());
        List<AskPlanRowDto> limited = rows.stream().limit(effectiveTopN).toList();

        Map<String, Object> appliedFilters = new LinkedHashMap<>();
        appliedFilters.put("period", normalizePeriod(intent.period()));
        appliedFilters.put("lineTypes", intent.lineTypes());
        appliedFilters.put("category", intent.category());
        appliedFilters.put("department", intent.department());
        appliedFilters.put("coaCode", intent.coaCode());
        appliedFilters.put("coaName", intent.coaName());
        appliedFilters.put("searchText", intent.searchText());
        appliedFilters.put("topN", effectiveTopN);
        appliedFilters.put("compareMode", intent.compareMode());
        appliedFilters.put("includeActuals", includeActuals);
        appliedFilters.put("actualYear", includeActuals ? actualYear : null);
        appliedFilters.put("comparePlanYear", intent.comparePlanYear());
        appliedFilters.put("comparePlanType", intent.comparePlanType());
        appliedFilters.put("rankMode", normalizeRankMode(intent.rankMode()));
        appliedFilters.put("queryPlanYear", intent.queryPlanYear());
        appliedFilters.put("queryPlanType", intent.queryPlanType());
        appliedFilters.put("totalFilter", intent.totalFilter());
        appliedFilters.put("totalFilterMonths", intent.totalFilterMonths());
        appliedFilters.put("groupBy", intent.groupBy());

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("basePlanId", basePlan.getId());
        meta.put("anchorPlanId", anchorPlan.getId());
        meta.put("comparePlanId", comparePlanId);
        meta.put("actualYear", includeActuals ? actualYear : null);
        meta.put("resultCount", limited.size());
        meta.put("totalMatchedBeforeLimit", rows.size());

        if (intent.groupBy() != null) {
            Map<String, Long> grouped = new LinkedHashMap<>();
            for (AskPlanRowDto row : rows) {
                String key = resolveGroupKey(row, intent.groupBy());
                grouped.merge(key, (long) sumMonths(row.baseValues(), periodMonths), Long::sum);
            }
            meta.put("groupedTotals", grouped);
            meta.put("groupBy", intent.groupBy());
        }

        String summary = buildSummary(intent, limited.size(), effectiveTopN);
        return new AskPlanResponse(summary, intent.intent(), appliedFilters, limited, meta);
    }

    // ── plan_exists response ────────────────────────────────────────────

    private AskPlanResponse buildPlanExistsResponse(AskPlanRequest req, ParsedAskIntent intent, Plan anchorPlan) {
        PlanType filterType = parsePlanType(intent.queryPlanType()).orElse(null);
        Integer year = intent.queryPlanYear();
        List<Plan> all = planRepository.findByProperty_IdAndStatusOrderByIdAsc(
                anchorPlan.getProperty().getId(), PlanStatus.ACTIVE);

        List<Plan> matches = new ArrayList<>();
        for (Plan p : all) {
            if (filterType != null && p.getPlanType() != filterType) {
                continue;
            }
            if (year != null && !year.equals(p.getFiscalYear())) {
                continue;
            }
            matches.add(p);
        }

        String summary;
        if (matches.isEmpty()) {
            String yPart = year != null ? " for " + year : "";
            String tPart = filterType != null ? filterType.name().toLowerCase(Locale.ROOT) : "plan";
            summary = "No, there is no " + tPart + yPart + " on file for this property.";
        } else {
            StringBuilder sb = new StringBuilder("Yes! We have ");
            for (int i = 0; i < matches.size(); i++) {
                Plan p = matches.get(i);
                if (i > 0) {
                    sb.append(i == matches.size() - 1 ? " and " : ", ");
                }
                sb.append("\"").append(p.getName()).append("\" (")
                        .append(p.getPlanType().name())
                        .append(" ")
                        .append(p.getFiscalYear())
                        .append(")");
            }
            sb.append(".");
            summary = sb.toString();
        }

        Map<String, Object> appliedFilters = baseAppliedFilters(intent);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("basePlanId", anchorPlan.getId());
        meta.put("resultCount", 0);
        meta.put("totalMatchedBeforeLimit", 0);
        meta.put("suggestedPlans", planSummaries(all));
        return new AskPlanResponse(summary, "plan_exists", appliedFilters, List.of(), meta);
    }

    // ── missing plan response (for both query and compare) ──────────────

    private AskPlanResponse buildMissingPlanResponse(
            AskPlanRequest req,
            Plan anchorPlan,
            int year,
            PlanType planType,
            String role) {
        List<Plan> all = planRepository.findByProperty_IdAndStatusOrderByIdAsc(
                anchorPlan.getProperty().getId(), PlanStatus.ACTIVE);
        String typeLabel = planType.name().toLowerCase(Locale.ROOT);

        String summary;
        if ("compare".equals(role)) {
            summary = "We don't have a "
                    + typeLabel
                    + " plan for "
                    + year
                    + " to compare with. You can try with one of the available plans below.";
        } else {
            summary = "We don't have a "
                    + typeLabel
                    + " plan for "
                    + year
                    + ". You can try with one of the available plans below.";
        }

        Map<String, Object> appliedFilters = new LinkedHashMap<>();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("basePlanId", anchorPlan.getId());
        meta.put("resultCount", 0);
        meta.put("totalMatchedBeforeLimit", 0);
        meta.put("missingPlanYear", year);
        meta.put("missingPlanType", planType.name());
        meta.put("missingRole", role);
        meta.put("suggestedPlans", planSummaries(all));
        return new AskPlanResponse(summary, "plan_not_found", appliedFilters, List.of(), meta);
    }

    // ── shared helpers ──────────────────────────────────────────────────

    private static Map<String, Object> baseAppliedFilters(ParsedAskIntent intent) {
        Map<String, Object> af = new LinkedHashMap<>();
        af.put("period", normalizePeriod(intent.period()));
        af.put("lineTypes", intent.lineTypes());
        af.put("category", intent.category());
        af.put("department", intent.department());
        af.put("coaCode", intent.coaCode());
        af.put("coaName", intent.coaName());
        af.put("searchText", intent.searchText());
        af.put("topN", intent.topN());
        af.put("compareMode", intent.compareMode());
        af.put("includeActuals", intent.includeActuals());
        af.put("actualYear", intent.actualYear());
        af.put("comparePlanYear", intent.comparePlanYear());
        af.put("comparePlanType", intent.comparePlanType());
        af.put("rankMode", normalizeRankMode(intent.rankMode()));
        af.put("queryPlanYear", intent.queryPlanYear());
        af.put("queryPlanType", intent.queryPlanType());
        af.put("totalFilter", intent.totalFilter());
        af.put("totalFilterMonths", intent.totalFilterMonths());
        af.put("groupBy", intent.groupBy());
        return af;
    }

    private static List<Map<String, Object>> planSummaries(List<Plan> plans) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Plan p : plans) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("name", p.getName());
            m.put("fiscalYear", p.getFiscalYear());
            m.put("planType", p.getPlanType().name());
            out.add(m);
        }
        return out;
    }

    // ── resolve base/compare plans ──────────────────────────────────────

    private Optional<Long> resolveComparePlanIdOptional(AskPlanRequest req, ParsedAskIntent intent, Plan basePlan) {
        if (req.comparePlanId() != null) {
            return Optional.of(req.comparePlanId());
        }
        if (!"plan".equals(intent.compareMode())) {
            return Optional.empty();
        }
        if (intent.comparePlanYear() == null) {
            return Optional.empty();
        }
        PlanType type = parsePlanType(intent.comparePlanType()).orElse(PlanType.BUDGET);
        return planRepository
                .findFirstByProperty_IdAndFiscalYearAndPlanTypeOrderByIdAsc(
                        basePlan.getProperty().getId(), intent.comparePlanYear(), type)
                .map(Plan::getId);
    }

    // ── sorter / row building / actuals ─────────────────────────────────

    private Comparator<AskPlanRowDto> sorter(ParsedAskIntent intent) {
        List<String> months = monthsForPeriod(normalizePeriod(intent.period()));
        Comparator<AskPlanRowDto> byMetric = Comparator.comparingInt((AskPlanRowDto r) -> sumMonths(r.baseValues(), months))
                .reversed();
        String rankMode = normalizeRankMode(intent.rankMode());
        if ("plan".equals(intent.compareMode())) {
            byMetric = Comparator.comparingInt((AskPlanRowDto r) -> Math.abs(nullToZero(r.deltaVsCompare())))
                    .reversed();
        } else if ("actuals".equals(intent.compareMode())) {
            byMetric = Comparator.comparingInt((AskPlanRowDto r) -> Math.abs(nullToZero(r.deltaVsActual())))
                    .reversed();
        } else if ("avg".equals(rankMode)) {
            byMetric = Comparator.comparingDouble((AskPlanRowDto r) -> averageMonths(r.baseValues(), months))
                    .reversed();
        }
        if ("min".equals(rankMode)) {
            byMetric = byMetric.reversed();
        }
        return byMetric.thenComparing(AskPlanRowDto::label, String.CASE_INSENSITIVE_ORDER);
    }

    private AskPlanRowDto toResultRow(
            PlanMonthlyDetails base,
            PlanMonthlyDetails compare,
            Map<String, Integer> actualValues,
            List<String> periodMonths) {
        Map<String, Integer> baseValues = base.toValuesMap();
        Map<String, Integer> compareValues = compare != null ? compare.toValuesMap() : null;
        Integer baseTotal = sumMonths(baseValues, PlanMonthlyDetails.MONTH_KEYS);
        Integer compareTotal = compareValues == null ? null : sumMonths(compareValues, PlanMonthlyDetails.MONTH_KEYS);
        Integer actualTotal = actualValues == null ? null : sumMonths(actualValues, PlanMonthlyDetails.MONTH_KEYS);
        Integer deltaVsCompare = compareTotal == null ? null : (baseTotal - compareTotal);
        Integer deltaVsActual = actualTotal == null ? null : (baseTotal - actualTotal);

        Integer periodTotal = periodMonths != null ? sumMonths(baseValues, periodMonths) : null;
        Integer comparePeriodTotal = periodMonths != null && compareValues != null
                ? sumMonths(compareValues, periodMonths) : null;
        Integer actualPeriodTotal = periodMonths != null && actualValues != null
                ? sumMonths(actualValues, periodMonths) : null;

        return new AskPlanRowDto(
                base.getLineKey(),
                base.getLabel(),
                base.getType().name(),
                base.getDepartment(),
                base.getCategory(),
                baseValues,
                baseTotal,
                periodTotal,
                compareValues,
                compareTotal,
                comparePeriodTotal,
                deltaVsCompare,
                actualValues,
                actualTotal,
                actualPeriodTotal,
                deltaVsActual);
    }

    private Map<String, Map<String, Integer>> loadActualsByLineKey(int year, long propertyId, long orgId) {
        Map<String, Map<String, Integer>> out = new LinkedHashMap<>();
        List<ActualsDetails> rows = actualsDetailRepository
                .findByYearAndProperty_IdAndOrganizationIdOrderByCoaCodeAsc(year, propertyId, orgId);
        for (ActualsDetails row : rows) {
            out.put(row.getCoaCode(), actualToMonthMap(row));
        }
        return out;
    }

    private static Map<String, Integer> actualToMonthMap(ActualsDetails a) {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("Jan", bdToInt(a.getJanValue()));
        m.put("Feb", bdToInt(a.getFebValue()));
        m.put("Mar", bdToInt(a.getMarValue()));
        m.put("Apr", bdToInt(a.getAprValue()));
        m.put("May", bdToInt(a.getMayValue()));
        m.put("Jun", bdToInt(a.getJunValue()));
        m.put("Jul", bdToInt(a.getJulValue()));
        m.put("Aug", bdToInt(a.getAugValue()));
        m.put("Sep", bdToInt(a.getSepValue()));
        m.put("Oct", bdToInt(a.getOctValue()));
        m.put("Nov", bdToInt(a.getNovValue()));
        m.put("Dec", bdToInt(a.getDecValue()));
        return m;
    }

    private static int bdToInt(BigDecimal b) {
        return b == null ? 0 : b.intValue();
    }

    static int sumMonths(Map<String, Integer> values, List<String> months) {
        if (values == null) {
            return 0;
        }
        int total = 0;
        for (String m : months) {
            total += values.getOrDefault(m, 0);
        }
        return total;
    }

    static double averageMonths(Map<String, Integer> values, List<String> months) {
        if (months == null || months.isEmpty()) {
            return 0.0;
        }
        return (double) sumMonths(values, months) / months.size();
    }

    static List<String> monthsForPeriod(String period) {
        if (period == null || period.isBlank() || "full_year".equalsIgnoreCase(period)) {
            return PlanMonthlyDetails.MONTH_KEYS;
        }
        if ("Q1".equals(period)) {
            return List.of("Jan", "Feb", "Mar");
        }
        if ("Q2".equals(period)) {
            return List.of("Apr", "May", "Jun");
        }
        if ("Q3".equals(period)) {
            return List.of("Jul", "Aug", "Sep");
        }
        if ("Q4".equals(period)) {
            return List.of("Oct", "Nov", "Dec");
        }
        return PlanMonthlyDetails.MONTH_KEYS.contains(period) ? List.of(period) : PlanMonthlyDetails.MONTH_KEYS;
    }

    static String normalizePeriod(String period) {
        if (period == null || period.isBlank()) {
            return "full_year";
        }
        String p = period.trim();
        if (p.startsWith("q") || p.startsWith("Q")) {
            return p.toUpperCase(Locale.ROOT);
        }
        return p;
    }

    private static boolean matchesLineTypes(PlanMonthlyDetails li, List<String> lineTypes) {
        return lineTypes == null || lineTypes.isEmpty() || lineTypes.stream()
                .anyMatch(t -> li.getType().name().equalsIgnoreCase(t));
    }

    private static boolean matchesCategory(PlanMonthlyDetails li, String category) {
        return matchesFilterExpression(li.getCategory(), category);
    }

    private static boolean matchesDepartment(PlanMonthlyDetails li, String department) {
        return matchesFilterExpression(li.getDepartment(), department);
    }

    private static boolean matchesCoaCode(PlanMonthlyDetails li, String coaCode) {
        if (coaCode == null || coaCode.isBlank()) return true;
        return matchesFilterExpression(li.getCoaCode(), coaCode)
                || matchesFilterExpression(li.getLineKey(), coaCode);
    }

    private static boolean matchesCoaName(PlanMonthlyDetails li, String coaName) {
        if (coaName == null || coaName.isBlank()) return true;
        return matchesFilterExpression(li.getCoaName(), coaName)
                || matchesFilterExpression(li.getLabel(), coaName);
    }

    private static boolean matchesSearchText(PlanMonthlyDetails li, String searchText) {
        if (searchText == null || searchText.isBlank()) {
            return true;
        }
        String q = searchText.trim().toLowerCase(Locale.ROOT);
        if (li.getLabel().toLowerCase(Locale.ROOT).contains(q)
                || li.getLineKey().toLowerCase(Locale.ROOT).contains(q)
                || li.getDepartment().toLowerCase(Locale.ROOT).contains(q)
                || Optional.ofNullable(li.getCoaCode()).orElse("").toLowerCase(Locale.ROOT).contains(q)
                || Optional.ofNullable(li.getCoaName()).orElse("").toLowerCase(Locale.ROOT).contains(q)
                || li.getCategory().toLowerCase(Locale.ROOT).contains(q)) {
            return true;
        }
        return fuzzyMatch(li.getLabel(), q)
                || fuzzyMatch(li.getLineKey(), q)
                || fuzzyMatch(li.getDepartment(), q)
                || fuzzyMatch(li.getCoaCode(), q)
                || fuzzyMatch(li.getCoaName(), q)
                || fuzzyMatch(li.getCategory(), q);
    }

    private static int nullToZero(Integer v) {
        return v == null ? 0 : v;
    }

    private static int clampTopN(Integer requested) {
        int v = requested == null ? DEFAULT_TOP_N : requested;
        if (v < 1) {
            return 1;
        }
        return Math.min(v, MAX_TOP_N);
    }

    private static String buildSummary(ParsedAskIntent intent, int count, int topN) {
        String base;
        if ("compare_plan".equals(intent.intent())) {
            base = "Compared base plan with selected plan.";
        } else if ("compare_actuals".equals(intent.intent())) {
            base = "Compared base plan with actuals.";
        } else if ("top_n".equals(intent.intent())) {
            base = "Ranked line items by requested metric.";
        } else if ("filter".equals(intent.intent())) {
            base = "Filtered line items by requested constraints.";
        } else {
            base = "Analyzed line items for the requested question.";
        }
        return base + " Returned " + count + " row(s), capped at top " + topN + ".";
    }

    // ── intent parsing ──────────────────────────────────────────────────

    private ParsedAskIntent parseIntent(String provider, String question) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question is required");
        }
        JsonNode aiNode = fetchIntentJson(provider, question);
        ParsedAskIntent parsed = toIntent(aiNode);
        if (parsed.isEmpty()) {
            return heuristicIntent(question);
        }
        return parsed;
    }

    private JsonNode fetchIntentJson(String provider, String question) {
        String p = Optional.ofNullable(provider).orElse("").toLowerCase(Locale.ROOT).trim();
        if ("openai".equals(p)) {
            return parseOpenAiIntent(question);
        }
        if ("gemini".equals(p)) {
            return parseGeminiIntent(question);
        }
        throw new IllegalArgumentException("provider must be 'openai' or 'gemini'");
    }

    private JsonNode parseOpenAiIntent(String question) {
        if (openaiApiKey == null || openaiApiKey.isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY / planora.ai.openai.api-key is not configured");
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", "gpt-4o-mini");
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", buildSystemPrompt());
        messages.addObject().put("role", "user").put("content", "Question: \"" + question + "\"");
        body.putObject("response_format").put("type", "json_object");
        body.put("temperature", 0);
        String raw;
        try {
            raw = restClient.post()
                    .uri("https://api.openai.com/v1/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + openaiApiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(body))
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            throw new IllegalStateException("OpenAI request failed: " + e.getMessage(), e);
        }
        try {
            JsonNode tree = objectMapper.readTree(raw);
            String content = tree.path("choices").path(0).path("message").path("content").asText();
            return objectMapper.readTree(content);
        } catch (Exception e) {
            throw new IllegalStateException("OpenAI response parse failed: " + e.getMessage(), e);
        }
    }

    private JsonNode parseGeminiIntent(String question) {
        if (geminiApiKey == null || geminiApiKey.isBlank()) {
            throw new IllegalStateException("GEMINI_API_KEY / planora.ai.gemini.api-key is not configured");
        }
        String fullPrompt = buildSystemPrompt() + "\n\nQuestion: \"" + question + "\"";
        ObjectNode body = objectMapper.createObjectNode();
        body.putArray("contents").addObject().putArray("parts").addObject().put("text", fullPrompt);
        body.putObject("generationConfig")
                .put("responseMimeType", "application/json")
                .put("temperature", 0);
        String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key="
                + geminiApiKey;
        String raw;
        try {
            raw = restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(body))
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            throw new IllegalStateException("Gemini request failed: " + e.getMessage(), e);
        }
        try {
            JsonNode root = objectMapper.readTree(raw);
            String text = root.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText();
            return objectMapper.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException("Gemini response parse failed: " + e.getMessage(), e);
        }
    }

    static ParsedAskIntent toIntent(JsonNode n) {
        if (n == null || !n.isObject()) {
            return ParsedAskIntent.empty();
        }
        String intent = text(n, "intent");
        String lineType = text(n, "lineType");
        List<String> lineTypes = lineTypes(n);
        String category = text(n, "category");
        String department = text(n, "department");
        String coaCode = text(n, "coaCode");
        String coaName = text(n, "coaName");
        String period = text(n, "period");
        Integer topN = intVal(n, "topN");
        Boolean includeActuals = boolVal(n, "includeActuals");
        String compareMode = text(n, "compareMode");
        Integer actualYear = intVal(n, "actualYear");
        Integer comparePlanYear = intVal(n, "comparePlanYear");
        String comparePlanType = text(n, "comparePlanType");
        String rankMode = text(n, "rankMode");
        String searchText = text(n, "searchText");
        Integer queryPlanYear = intVal(n, "queryPlanYear");
        String queryPlanType = text(n, "queryPlanType");
        String totalFilter = text(n, "totalFilter");
        String groupBy = text(n, "groupBy");
        return new ParsedAskIntent(
                blankToNull(intent),
                normalizeLineTypes(lineTypes, lineType),
                blankToNull(category),
                blankToNull(department),
                blankToNull(coaCode),
                blankToNull(coaName),
                blankToNull(period),
                topN,
                includeActuals,
                normalizeCompareMode(compareMode),
                actualYear,
                comparePlanYear,
                normalizePlanType(comparePlanType),
                normalizeRankMode(rankMode),
                blankToNull(searchText),
                queryPlanYear,
                normalizePlanType(queryPlanType),
                blankToNull(totalFilter),
                null,
                blankToNull(groupBy));
    }

    static ParsedAskIntent heuristicIntent(String q) {
        String s = Optional.ofNullable(q).orElse("").toLowerCase(Locale.ROOT);

        String intent = "within_plan";
        String compareMode = "none";
        Integer actualYear = null;
        Integer comparePlanYear = null;
        String comparePlanType = null;
        Integer queryPlanYear = null;
        String queryPlanTypeStr = null;
        String rankMode = inferRankMode(s);

        boolean asksExistence =
                (s.contains("do we have") || s.contains("is there") || s.contains("are there")
                        || s.contains("do i have") || s.contains("have any") || s.contains("any plan"))
                        && (s.contains("budget") || s.contains("forecast") || s.contains("plan"));

        boolean wantCompare = COMPARE_WORDS.matcher(s).find();

        if (asksExistence) {
            intent = "plan_exists";
            queryPlanYear = extractYearNearKeyword(s, "budget");
            if (queryPlanYear == null) {
                queryPlanYear = extractYearNearKeyword(s, "forecast");
            }
            if (queryPlanYear == null) {
                queryPlanYear = extractAnyFiscalYear(s);
            }
            if (queryPlanYear == null) {
                queryPlanYear = resolveRelativeYear(s);
            }
            queryPlanTypeStr = inferPlanTypeFromKeywords(s);
        } else if (wantCompare && s.contains("actual")) {
            intent = "compare_actuals";
            compareMode = "actuals";
            actualYear = extractYearNearKeyword(s, "actual");
            if (actualYear == null) {
                actualYear = resolveRelativeYear(s);
            }
        } else if (wantCompare) {
            intent = "compare_plan";
            compareMode = "plan";
            comparePlanYear = extractYearNearKeyword(s, "budget");
            if (comparePlanYear == null) {
                comparePlanYear = extractYearNearKeyword(s, "forecast");
            }
            if (comparePlanYear == null) {
                comparePlanYear = extractAnyFiscalYear(s);
            }
            if (comparePlanYear == null) {
                comparePlanYear = resolveRelativeYear(s);
            }
            comparePlanType = inferPlanTypeFromKeywords(s);
        } else if (s.contains("top ")) {
            intent = "top_n";
        } else if (s.contains("show") || s.contains("filter")) {
            intent = "filter";
        } else {
            intent = "unknown";
        }

        if (s.contains("actual") && !"compare_actuals".equals(intent)) {
            actualYear = actualYear != null ? actualYear : extractYearNearKeyword(s, "actual");
            if (actualYear == null) {
                actualYear = resolveRelativeYear(s);
            }
        }

        if (!wantCompare && !asksExistence) {
            if (s.contains("budget")) {
                Integer y = extractYearNearKeyword(s, "budget");
                if (y == null) {
                    y = resolveRelativeYear(s);
                }
                if (y != null) {
                    queryPlanYear = y;
                    queryPlanTypeStr = "BUDGET";
                }
            }
            if (queryPlanYear == null && s.contains("forecast")) {
                Integer y = extractYearNearKeyword(s, "forecast");
                if (y == null) {
                    y = resolveRelativeYear(s);
                }
                if (y != null) {
                    queryPlanYear = y;
                    queryPlanTypeStr = "FORECAST";
                }
            }
        }

        String groupBy = inferGroupBy(s);

        List<String> lineTypes = normalizeLineTypes(List.of(
                s.contains("revenue") ? "Revenue" : null,
                s.contains("expense") ? "Expense" : null,
                s.contains("statistics") ? "Statistics" : null), null);
        Integer topN = extractTopN(s);
        String searchText = extractSearchText(s);
        if (searchText == null && "filter".equals(intent)) {
            searchText = extractShowSubject(s);
        }
        if (topN == null && s.startsWith("what is")) {
            topN = 1;
        }
        return new ParsedAskIntent(
                intent,
                lineTypes,
                null,
                null,
                null,
                null,
                null,
                topN,
                null,
                compareMode,
                actualYear,
                comparePlanYear,
                comparePlanType,
                rankMode,
                searchText,
                queryPlanYear,
                queryPlanTypeStr,
                null,
                null,
                groupBy);
    }

    static Integer resolveRelativeYear(String text) {
        if (text == null) return null;
        String s = text.toLowerCase(Locale.ROOT);
        int now = java.time.Year.now().getValue();
        if (s.contains("this year") || s.contains("current year")) return now;
        if (s.contains("last year") || s.contains("previous year")
                || s.contains("prior year")) return now - 1;
        if (s.contains("next year")) return now + 1;
        return null;
    }

    private static ParsedAskIntent resolveRelativeYearsIfMissing(ParsedAskIntent intent, String question) {
        Integer comparePlanYear = intent.comparePlanYear();
        Integer actualYear = intent.actualYear();
        Integer queryPlanYear = intent.queryPlanYear();
        String compareMode = intent.compareMode();
        boolean changed = false;

        Integer serverYear = resolveRelativeYear(question);

        if (("plan".equals(compareMode) || "compare_plan".equals(intent.intent()))
                && serverYear != null && !serverYear.equals(comparePlanYear)) {
            comparePlanYear = serverYear;
            compareMode = "plan";
            changed = true;
        }
        if (("actuals".equals(compareMode) || "compare_actuals".equals(intent.intent()))
                && serverYear != null && !serverYear.equals(actualYear)) {
            actualYear = serverYear;
            compareMode = "actuals";
            changed = true;
        }
        if (queryPlanYear == null && intent.queryPlanType() != null && serverYear != null) {
            queryPlanYear = serverYear;
            changed = true;
        }

        if (!changed) return intent;

        return new ParsedAskIntent(
                intent.intent(), intent.lineTypes(), intent.category(),
                intent.department(), intent.coaCode(), intent.coaName(),
                intent.period(), intent.topN(), intent.includeActuals(),
                compareMode, actualYear, comparePlanYear,
                intent.comparePlanType(), intent.rankMode(), intent.searchText(),
                queryPlanYear, intent.queryPlanType(),
                intent.totalFilter(), intent.totalFilterMonths(), intent.groupBy());
    }

    private static String inferGroupBy(String s) {
        if (s == null) return null;
        if (s.contains("by department") || s.contains("department-wise")
                || s.contains("per department")) return "department";
        if (s.contains("by category") || s.contains("category-wise")
                || s.contains("per category")) return "category";
        if (s.contains("by type") || s.contains("type-wise")
                || s.contains("per type") || s.contains("by account type")) return "type";
        return null;
    }

    static int editDistance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int[] curr = new int[b.length() + 1];
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            prev = curr;
        }
        return prev[b.length()];
    }

    static boolean fuzzyMatch(String fieldValue, String query) {
        if (fieldValue == null || query == null) return false;
        String v = fieldValue.toLowerCase(Locale.ROOT);
        String q = query.toLowerCase(Locale.ROOT);
        int maxDist = q.length() <= 4 ? 1 : 2;
        if (editDistance(v, q) <= maxDist) return true;
        for (String word : v.split("[\\s&/,.-]+")) {
            if (!word.isEmpty() && editDistance(word, q) <= maxDist) return true;
        }
        return false;
    }

    static boolean matchesFilterExpression(String fieldValue, String expression) {
        if (expression == null || expression.isBlank()) return true;
        String expr = expression.trim();
        String value = Optional.ofNullable(fieldValue).orElse("").toLowerCase(Locale.ROOT);

        if (expr.startsWith("!")) {
            String neg = expr.substring(1).trim().toLowerCase(Locale.ROOT);
            return !value.contains(neg);
        }
        if (expr.contains("|")) {
            for (String part : expr.split("\\|")) {
                String p = part.trim().toLowerCase(Locale.ROOT);
                if (!p.isEmpty() && (value.contains(p) || fuzzyMatch(value, p))) return true;
            }
            return false;
        }
        if (expr.startsWith("^")) {
            String prefix = expr.substring(1).trim().toLowerCase(Locale.ROOT);
            return value.startsWith(prefix);
        }
        if (expr.startsWith("=")) {
            String exact = expr.substring(1).trim().toLowerCase(Locale.ROOT);
            return value.equals(exact);
        }
        String exprLower = expr.toLowerCase(Locale.ROOT);
        return value.contains(exprLower) || fuzzyMatch(value, exprLower);
    }

    static boolean matchesTotalFilter(AskPlanRowDto row,
            String totalFilter, List<String> filterMonths, String period) {
        if (totalFilter == null || "any".equalsIgnoreCase(totalFilter)) return true;
        List<String> months = (filterMonths != null && !filterMonths.isEmpty())
                ? filterMonths : monthsForPeriod(normalizePeriod(period));
        int sum = sumMonths(row.baseValues(), months);
        return "zero".equalsIgnoreCase(totalFilter) ? sum == 0 : sum != 0;
    }

    private static String resolveGroupKey(AskPlanRowDto row, String groupBy) {
        if ("department".equals(groupBy)) return row.department();
        if ("category".equals(groupBy)) return row.category();
        if ("type".equals(groupBy)) return row.type();
        return "other";
    }

    private static String inferPlanTypeFromKeywords(String s) {
        if (s.contains("forecast")) {
            return "FORECAST";
        }
        if (s.contains("what if") || s.contains("what-if")) {
            return "WHAT_IF";
        }
        return "BUDGET";
    }

    static Integer extractAnyFiscalYear(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = Pattern.compile("\\b(20\\d{2})\\b").matcher(text);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    static Integer extractTopN(String s) {
        if (s == null) {
            return null;
        }
        String[] tokens = s.split("\\s+");
        for (int i = 0; i < tokens.length - 1; i++) {
            if ("top".equals(tokens[i])) {
                try {
                    return Integer.parseInt(tokens[i + 1]);
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static ParsedAskIntent mergeWithOverrides(ParsedAskIntent ai, AskPlanRequest req) {
        Integer comparePlanYear = req.comparePlanYear() != null ? req.comparePlanYear() : ai.comparePlanYear();
        Integer actualYear = req.actualYear() != null ? req.actualYear() : ai.actualYear();
        String compareMode = ai.compareMode();
        if ("compare_plan".equals(ai.intent()) && comparePlanYear != null) {
            compareMode = "plan";
        }
        if ("compare_actuals".equals(ai.intent()) && actualYear != null) {
            compareMode = "actuals";
        }
        if (req.comparePlanId() != null) {
            compareMode = "plan";
        }
        if (Boolean.TRUE.equals(req.includeActuals())) {
            compareMode = "actuals";
        }
        return new ParsedAskIntent(
                Optional.ofNullable(ai.intent()).orElse("within_plan"),
                firstNonEmpty(
                        normalizeLineTypes(req.lineTypes(), req.lineType()),
                        ai.lineTypes()),
                coalesce(blankToNull(req.category()), ai.category()),
                coalesce(blankToNull(req.department()), ai.department()),
                coalesce(blankToNull(req.coaCode()), ai.coaCode()),
                coalesce(blankToNull(req.coaName()), ai.coaName()),
                coalesce(blankToNull(req.period()), ai.period()),
                req.topN() != null ? req.topN() : ai.topN(),
                req.includeActuals() != null ? req.includeActuals() : ai.includeActuals(),
                normalizeCompareMode(compareMode),
                actualYear,
                comparePlanYear,
                coalesce(normalizePlanType(req.comparePlanType()), ai.comparePlanType()),
                ai.rankMode(),
                coalesce(blankToNull(req.searchText()), ai.searchText()),
                req.queryPlanYear() != null ? req.queryPlanYear() : ai.queryPlanYear(),
                coalesce(normalizePlanType(req.queryPlanType()), ai.queryPlanType()),
                coalesce(blankToNull(req.totalFilter()), ai.totalFilter()),
                req.totalFilterMonths() != null && !req.totalFilterMonths().isEmpty()
                        ? req.totalFilterMonths() : ai.totalFilterMonths(),
                ai.groupBy());
    }

    // ── generic utilities ───────────────────────────────────────────────

    private static <T> T coalesce(T a, T b) {
        return a != null ? a : b;
    }

    private static List<String> firstNonEmpty(List<String> a, List<String> b) {
        return (a != null && !a.isEmpty()) ? a : b;
    }

    private static List<String> normalizeLineTypes(List<String> lineTypes, String lineType) {
        Set<String> out = new LinkedHashSet<>();
        if (lineTypes != null) {
            for (String v : lineTypes) {
                String n = normalizeLineTypeToken(v);
                if (n != null) {
                    out.add(n);
                }
            }
        }
        if (lineType != null && !lineType.isBlank()) {
            for (String token : lineType.split("[,|/]")) {
                String n = normalizeLineTypeToken(token);
                if (n != null) {
                    out.add(n);
                }
            }
        }
        return out.isEmpty() ? null : List.copyOf(out);
    }

    private static String normalizeLineTypeToken(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String lt = value.trim().toLowerCase(Locale.ROOT);
        lt = lt.replace("account type", "").replace("account", "").trim();
        if ("expense".equals(lt) || "expenses".equals(lt)) {
            return "Expense";
        }
        if ("revenue".equals(lt) || "revenues".equals(lt)) {
            return "Revenue";
        }
        if ("statistics".equals(lt) || "statistic".equals(lt)) {
            return "Statistics";
        }
        if (editDistance(lt, "expense") <= 2 || editDistance(lt, "expenses") <= 2) return "Expense";
        if (editDistance(lt, "revenue") <= 2 || editDistance(lt, "revenues") <= 2) return "Revenue";
        if (editDistance(lt, "statistics") <= 2 || editDistance(lt, "statistic") <= 2) return "Statistics";
        return null;
    }

    private static String normalizeCompareMode(String compareMode) {
        if (compareMode == null || compareMode.isBlank()) {
            return "none";
        }
        String c = compareMode.trim().toLowerCase(Locale.ROOT);
        if (Objects.equals(c, "plan") || Objects.equals(c, "actuals") || Objects.equals(c, "none")) {
            return c;
        }
        return "none";
    }

    private static String normalizePlanType(String planType) {
        if (planType == null || planType.isBlank()) {
            return null;
        }
        String p = planType.trim().toUpperCase(Locale.ROOT);
        if ("BUDGET".equals(p) || "FORECAST".equals(p) || "WHAT_IF".equals(p)) {
            return p;
        }
        return null;
    }

    private static String normalizeRankMode(String rankMode) {
        if (rankMode == null || rankMode.isBlank()) {
            return "max";
        }
        String r = rankMode.trim().toLowerCase(Locale.ROOT);
        if ("max".equals(r) || "min".equals(r) || "avg".equals(r)) {
            return r;
        }
        return "max";
    }

    static String inferRankMode(String q) {
        if (q == null || q.isBlank()) {
            return "max";
        }
        String s = q.toLowerCase(Locale.ROOT);
        if (s.contains("average") || s.contains("avg") || s.contains("mean")) {
            return "avg";
        }
        if (s.contains("minimum") || s.contains("min ") || s.contains("lowest")
                || s.contains("least") || s.contains("bottom")) {
            return "min";
        }
        if (s.contains("maximum") || s.contains("max ") || s.contains("highest") || s.contains("top")) {
            return "max";
        }
        return "max";
    }

    private static Optional<PlanType> parsePlanType(String planType) {
        try {
            return Optional.ofNullable(planType).map(PlanType::valueOf);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String text(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    private static List<String> lineTypes(JsonNode n) {
        JsonNode arr = n.get("lineTypes");
        if (arr == null || !arr.isArray()) {
            return null;
        }
        Set<String> out = new LinkedHashSet<>();
        for (JsonNode v : arr) {
            String token = v == null || v.isNull() ? null : v.asText();
            String normalized = normalizeLineTypeToken(token);
            if (normalized != null) {
                out.add(normalized);
            }
        }
        return out.isEmpty() ? null : List.copyOf(out);
    }

    private static Integer intVal(JsonNode n, String key) {
        JsonNode v = n.get(key);
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isInt() || v.isLong()) {
            return v.intValue();
        }
        try {
            return Integer.parseInt(v.asText());
        } catch (Exception e) {
            return null;
        }
    }

    static Integer extractYearNearKeyword(String text, String keyword) {
        if (text == null || keyword == null) {
            return null;
        }
        Pattern near = Pattern.compile("\\b" + Pattern.quote(keyword) + "\\b[^\\d]*(20\\d{2})");
        Matcher m = near.matcher(text);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        Pattern any = Pattern.compile("\\b(20\\d{2})\\b");
        Matcher anyM = any.matcher(text);
        return anyM.find() ? Integer.parseInt(anyM.group(1)) : null;
    }

    static String extractSearchText(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String q = s.trim();
        if (q.startsWith("what is ")) {
            String t = q.substring("what is ".length());
            t = t.replaceAll("\\bin\\s+actual\\s+20\\d{2}\\b", "");
            t = t.replaceAll("\\bin\\s+budget\\s+20\\d{2}\\b", "");
            t = t.replaceAll("\\b(actual|budget)\\s+20\\d{2}\\b", "");
            t = t.replaceAll("\\s+", " ").trim();
            return t.isBlank() ? null : t;
        }
        if (q.startsWith("show me ")) {
            return q.substring("show me ".length()).trim();
        }
        return null;
    }

    static String extractShowSubject(String query) {
        if (query == null || !query.startsWith("show ")) return null;
        String text = query.substring("show ".length());
        text = text.replaceAll(
                "\\b(of|for|in|from)\\s+(budget|forecast|what[- ]if)(\\s+\\d{4})?\\b", "");
        text = text.replaceAll("\\b(actual|budget|forecast)\\s+20\\d{2}\\b", "");
        text = text.replaceAll(
                "\\b(all|the|my|items?|line\\s*items?|details?|data|me)\\b", "");
        text = text.replaceAll("\\s+", " ").trim();
        String check = text.replaceAll("\\b(revenues?|expenses?|statistics?)\\b", "")
                .replaceAll("[\\s-]+", "").trim();
        if (check.isEmpty()) return null;
        return text;
    }

    private static Boolean boolVal(JsonNode n, String key) {
        JsonNode v = n.get(key);
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isBoolean()) {
            return v.booleanValue();
        }
        String s = v.asText();
        if ("true".equalsIgnoreCase(s)) {
            return true;
        }
        if ("false".equalsIgnoreCase(s)) {
            return false;
        }
        return null;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() || "null".equalsIgnoreCase(s) ? null : s.trim();
    }

    // ── intent record ───────────────────────────────────────────────────

    record ParsedAskIntent(
            String intent,
            List<String> lineTypes,
            String category,
            String department,
            String coaCode,
            String coaName,
            String period,
            Integer topN,
            Boolean includeActuals,
            String compareMode,
            Integer actualYear,
            Integer comparePlanYear,
            String comparePlanType,
            String rankMode,
            String searchText,
            Integer queryPlanYear,
            String queryPlanType,
            String totalFilter,
            List<String> totalFilterMonths,
            String groupBy) {
        static ParsedAskIntent empty() {
            return new ParsedAskIntent(
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null);
        }

        boolean isEmpty() {
            return intent == null
                    && (lineTypes == null || lineTypes.isEmpty())
                    && category == null
                    && department == null
                    && coaCode == null
                    && coaName == null
                    && period == null
                    && topN == null
                    && includeActuals == null
                    && compareMode == null
                    && actualYear == null
                    && comparePlanYear == null
                    && comparePlanType == null
                    && rankMode == null
                    && searchText == null
                    && queryPlanYear == null
                    && queryPlanType == null
                    && totalFilter == null
                    && (totalFilterMonths == null || totalFilterMonths.isEmpty())
                    && groupBy == null;
        }
    }
}
