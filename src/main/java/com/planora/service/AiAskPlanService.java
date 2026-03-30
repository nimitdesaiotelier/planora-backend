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
import com.planora.web.dto.AskPlanAnalyzeRequest;
import com.planora.web.dto.AskPlanAnalyzeResponse;
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

    /** Display / merge key when a grouping dimension is null or blank on a line. */
    private static final String GROUP_KEY_NONE = "(none)";

    private static final Pattern COMPARE_WORDS = Pattern.compile(
            "\\b(compare|vs\\.?|versus|against)\\b", Pattern.CASE_INSENSITIVE);

    /** Whole-word "total" / common typo "toal" for profit heuristic (avoid matching "totally"). */
    private static final Pattern WORD_TOTAL = Pattern.compile("\\btotal\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern WORD_TOAL = Pattern.compile("\\btoal\\b", Pattern.CASE_INSENSITIVE);

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
            + "  \"groupBy\":\"grand|profit|department|category|type|coa_code|coa_name|null\",\n"
            + "  \"chartType\":\"bar|pie|line|null\"\n"
            + "}\n"
            + "Rules:\n"
            + "- Extract department, coaCode, coaName when user mentions department name, GL/COA code, or account name.\n"
            + "- The current calendar year is " + now + ". Resolve relative years: 'this year'/'current year' = " + now + ", 'last year'/'previous year' = " + (now - 1) + ", 'next year' = " + (now + 1) + ". Output the resolved numeric year.\n"
            + "- For filters (department, coaCode, coaName, category), use prefixes to express advanced matching:\n"
            + "  \"!Rooms\" = exclude Rooms, \"Rooms|F&B\" = either Rooms or F&B,\n"
            + "  \"^41\" = starts with 41, \"=4100\" = exact match.\n"
            + "  Default (no prefix) = case-insensitive contains.\n"
            + "- Department synonyms (backend also maps these): F&B / FandB / food & beverage / food and beverage / food & department; "
            + "non-operating / non operating / Non Operating.\n"
            + "- Use plan_exists when the user asks whether a budget/forecast/plan exists for a year (do we have, is there, any plan).\n"
            + "- Use queryPlanYear and queryPlanType when the user wants analytics for a specific fiscal plan year without comparing (e.g. show statistics for Budget 2025); keep compareMode none.\n"
            + "- Use compare_plan only when the user explicitly compares plans (compare, vs, versus, against).\n"
            + "- Use compare_actuals for plan-vs-actual requests.\n"
            + "- Use top_n when user asks for top/bottom style ranking.\n"
            + "- Use filter when user asks for constrained subsets (by category/type/text).\n"
            + "- groupBy controls row aggregation (rolled-up result rows), not zero filtering:\n"
            + "  * grand (aliases: grand_total) - one total row over all matched lines after filters. Use for \"total revenue\" OR \"total expense\" alone, \"grand total\", \"sum of ...\", \"how much total\" (when not breaking down by a dimension).\n"
            + "  * department | category | type - one row per distinct value of that dimension.\n"
            + "  * coa_code - one row per COA / GL code; coa_name - one row per account name.\n"
            + "  * profit (aliases: pnl, p_and_l, profit_loss) - Revenue in baseValues, Expense in compareValues; deltaVsCompare = revenue minus expense. Use for \"total revenue and expense\", P&amp;L, and \"revenue and expense by department\" (multiple profit rows). Prefer profit over department when both revenue and expense appear with a department breakdown. compareMode must be none; do not combine with plan-vs-plan or plan-vs-actuals.\n"
            + "  * null - return individual line items (no roll-up).\n"
            + "- totalFilter is ONLY for filtering lines by whether their plan sum is zero: any | zero | non_zero. Do NOT use totalFilter for questions that ask for a total dollar amount; use groupBy grand with the right lineTypes instead.\n"
            + "- Set chartType when user asks for a chart, graph, plot, or visualization. Use \"bar\" for comparisons, \"pie\" for proportions/shares, \"line\" for trends over time. Default to \"line\" if chart is requested but type is unclear.\n"
            + "- If unclear, use unknown.\n"
            + "Respond with valid JSON only.";
    }

    private static final String ANALYSIS_SYSTEM_PROMPT =
            "You are a concise financial analyst for hotel budget line items.\n"
                    + "Given the user's question and a JSON object with result rows (labels, base totals, optional compare/actual deltas), "
                    + "write a very short analysis.\n"
                    + "Respond with ONLY valid JSON of this exact shape: {\"points\":[\"...\",\"...\"]}\n"
                    + "Rules:\n"
                    + "- The \"points\" array must contain between 1 and 5 strings.\n"
                    + "- Each string is one short factual sentence (no markdown, no bullet characters, no numbering prefix).\n"
                    + "- Focus on largest line items, revenue vs expense mix, and notable variances when compare or actual fields are present.\n";

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
                && normalizeGroupBy(intent.groupBy()) == null
                && intent.topN() == null
                && (intent.lineTypes() == null || intent.lineTypes().isEmpty())
                && intent.category() == null
                && intent.searchText() == null
                && intent.actualYear() == null
                && intent.queryPlanYear() == null
                && !"plan".equals(intent.compareMode())
                && !"actuals".equals(intent.compareMode())) {
            throw new IllegalArgumentException("That is not a valid action instruction. Please try again with a clear action.");
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
                        .collect(LinkedHashMap::new, (m, li) -> m.put(effectiveCoaCode(li), li), Map::putAll);

        Integer actualYear = intent.actualYear() != null ? intent.actualYear() : basePlan.getFiscalYear();
        boolean includeActuals = Boolean.TRUE.equals(intent.includeActuals())
                || "actuals".equals(intent.compareMode())
                || "actual".equals(intent.compareMode())
                || intent.actualYear() != null;
        Map<String, Map<String, Integer>> actualsByKey = includeActuals
                ? loadActualsByLineKey(actualYear, basePlan.getProperty().getId(), DEFAULT_ORG_ID)
                : Map.of();

        if ("profit".equals(normalizeGroupBy(intent.groupBy()))) {
            if (comparePlanId != null) {
                throw new IllegalArgumentException(
                        "Profit summary cannot be combined with plan comparison.");
            }
            if ("actuals".equals(intent.compareMode()) || "actual".equals(intent.compareMode())) {
                throw new IllegalArgumentException(
                        "Profit summary cannot be combined with plan-vs-actuals.");
            }
            if (Boolean.TRUE.equals(intent.includeActuals())) {
                throw new IllegalArgumentException(
                        "Profit summary cannot be combined with includeActuals.");
            }
        }

        List<String> periodMonths = monthsForPeriod(normalizePeriod(intent.period()));
        boolean isFullYear = periodMonths.equals(PlanMonthlyDetails.MONTH_KEYS);

        List<AskPlanRowDto> matchedRows = baseRows.stream()
                .filter(li -> matchesLineTypes(li, intent.lineTypes()))
                .filter(li -> matchesCategory(li, intent.category()))
                .filter(li -> matchesDepartment(li, intent.department()))
                .filter(li -> matchesCoaCode(li, intent.coaCode()))
                .filter(li -> matchesCoaName(li, intent.coaName()))
                .filter(li -> matchesSearchText(li, intent.searchText()))
                .map(li -> toResultRow(li, compareByKey.get(effectiveCoaCode(li)),
                        actualsByKey.get(effectiveCoaCode(li)), isFullYear ? null : periodMonths))
                .filter(row -> matchesTotalFilter(row, intent.totalFilter(),
                        intent.totalFilterMonths(), intent.period()))
                .toList();

        String effectiveGroupBy = normalizeGroupBy(intent.groupBy());
        List<AskPlanRowDto> rowsForLimit = buildResultRowsAfterGrouping(
                matchedRows, effectiveGroupBy, intent, periodMonths, isFullYear, req.question());

        Comparator<AskPlanRowDto> rowComparator = sorter(intent);
        Integer effectiveTopN = intent.topN() != null ? clampTopN(intent.topN()) : null;
        boolean aggregated = effectiveGroupBy != null;
        List<AskPlanRowDto> limited =
                applyTopNLimit(rowsForLimit, intent, rowComparator, effectiveTopN, aggregated);

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
        appliedFilters.put("groupBy", effectiveGroupBy);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("basePlanId", basePlan.getId());
        meta.put("anchorPlanId", anchorPlan.getId());
        meta.put("comparePlanId", comparePlanId);
        meta.put("actualYear", includeActuals ? actualYear : null);
        meta.put("resultCount", limited.size());
        meta.put("totalMatchedBeforeLimit", matchedRows.size());

        if (effectiveGroupBy != null) {
            Map<String, Long> grouped = new LinkedHashMap<>();
            for (AskPlanRowDto row : matchedRows) {
                String key = resolveGroupKey(row, effectiveGroupBy);
                grouped.merge(key, (long) sumMonths(row.baseValues(), periodMonths), Long::sum);
            }
            meta.put("groupedTotals", grouped);
            meta.put("groupBy", effectiveGroupBy);
        }

        // TODO: re-enable chart support after improving AI prompt accuracy
        meta.put("isChart", false);
        meta.put("chartType", null);

        boolean topNPerLineType = effectiveTopN != null
                && !aggregated
                && intent.lineTypes() != null
                && intent.lineTypes().size() > 1;
        String summary = buildSummary(intent, limited.size(), effectiveTopN, topNPerLineType);
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
                .findFirstByProperty_IdAndFiscalYearAndPlanTypeAndStatusOrderByIdAsc(
                        basePlan.getProperty().getId(), intent.comparePlanYear(), type, PlanStatus.ACTIVE)
                .map(Plan::getId);
    }

    private static String effectiveCoaCode(PlanMonthlyDetails li) {
        return li.getCoaCode() != null && !li.getCoaCode().isBlank()
                ? li.getCoaCode()
                : li.getLineKey();
    }

    // ── sorter / row building / actuals ─────────────────────────────────

    /**
     * When {@code topN} is set and multiple line types are filtered, returns up to {@code topN} rows
     * per type (in line-types filter order). Otherwise applies a single global limit.
     */
    private static List<AskPlanRowDto> applyTopNLimit(
            List<AskPlanRowDto> matchedRows,
            ParsedAskIntent intent,
            Comparator<AskPlanRowDto> rowComparator,
            Integer effectiveTopN,
            boolean aggregated) {
        if (effectiveTopN == null) {
            return matchedRows.stream().sorted(rowComparator).toList();
        }
        List<String> lineTypes = intent.lineTypes();
        if (!aggregated && lineTypes != null && lineTypes.size() > 1) {
            List<AskPlanRowDto> out = new ArrayList<>();
            for (String lineType : lineTypes) {
                matchedRows.stream()
                        .filter(r -> r.type().equalsIgnoreCase(lineType))
                        .sorted(rowComparator)
                        .limit(effectiveTopN)
                        .forEach(out::add);
            }
            return out;
        }
        return matchedRows.stream().sorted(rowComparator).limit(effectiveTopN).toList();
    }

    /**
     * After filters, either pass detail rows through or build one row per group / grand total.
     */
    private static List<AskPlanRowDto> buildResultRowsAfterGrouping(
            List<AskPlanRowDto> matchedRows,
            String effectiveGroupBy,
            ParsedAskIntent intent,
            List<String> periodMonths,
            boolean isFullYear,
            String question) {
        if (effectiveGroupBy == null) {
            return matchedRows;
        }
        if (matchedRows.isEmpty()) {
            return List.of();
        }
        if ("profit".equals(effectiveGroupBy)) {
            if (wantsProfitByDepartmentBreakdown(question)) {
                Map<String, List<AskPlanRowDto>> byDept = new LinkedHashMap<>();
                for (AskPlanRowDto row : matchedRows) {
                    String key = resolveGroupKey(row, "department");
                    byDept.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
                }
                List<AskPlanRowDto> profitRows = new ArrayList<>();
                for (Map.Entry<String, List<AskPlanRowDto>> e : byDept.entrySet()) {
                    profitRows.add(buildProfitSyntheticRow(
                            e.getValue(), isFullYear ? null : periodMonths, intent, e.getKey()));
                }
                return profitRows;
            }
            return List.of(buildProfitSyntheticRow(
                    matchedRows, isFullYear ? null : periodMonths, intent, null));
        }
        if ("grand".equals(effectiveGroupBy)) {
            String label = grandTotalLabel(intent);
            String scopedDept = inferDepartmentForScopedAggregate(matchedRows, intent.department());
            return List.of(aggregateAskPlanRows(
                    matchedRows,
                    "__grand__",
                    label,
                    scopedDept,
                    null,
                    null,
                    mergedLineType(matchedRows),
                    null,
                    isFullYear ? null : periodMonths));
        }
        Map<String, List<AskPlanRowDto>> buckets = new LinkedHashMap<>();
        for (AskPlanRowDto row : matchedRows) {
            String key = resolveGroupKey(row, effectiveGroupBy);
            buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }
        List<AskPlanRowDto> out = new ArrayList<>();
        for (Map.Entry<String, List<AskPlanRowDto>> e : buckets.entrySet()) {
            out.add(buildSyntheticGroupRow(e.getKey(), e.getValue(), effectiveGroupBy, isFullYear ? null : periodMonths));
        }
        return out;
    }

    /**
     * When rolled-up rows all belong to one department (or a simple department filter applies), expose it on
     * synthetic totals (profit / grand).
     */
    private static String inferDepartmentForScopedAggregate(
            List<AskPlanRowDto> matchedRows, String intentDepartment) {
        if (matchedRows == null || matchedRows.isEmpty()) {
            return null;
        }
        Set<String> distinct = new LinkedHashSet<>();
        for (AskPlanRowDto r : matchedRows) {
            String d = r.department();
            if (d != null && !d.isBlank()) {
                distinct.add(d.trim());
            }
        }
        if (distinct.size() == 1) {
            return distinct.iterator().next();
        }
        if (distinct.isEmpty()
                && intentDepartment != null
                && !intentDepartment.isBlank()
                && !departmentFilterUsesAdvancedSyntax(intentDepartment)) {
            return intentDepartment.trim();
        }
        return null;
    }

    /** True when the user asked for a department breakdown together with profit-style totals. */
    private static boolean wantsProfitByDepartmentBreakdown(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String s = question.toLowerCase(Locale.ROOT);
        return s.contains("by department")
                || s.contains("per department")
                || s.contains("department-wise")
                || s.contains("for each department")
                || s.contains("each department");
    }

    private static String profitLineKeyForDepartmentBucket(String departmentBucketKey) {
        if (departmentBucketKey == null) {
            return "__profit__";
        }
        String safe = departmentBucketKey.replace(':', '_');
        return "__profit__:" + safe;
    }

    /**
     * One row: base = summed Revenue baseValues; compare = summed Expense baseValues (not second plan).
     * Assumes revenue and expense amounts are stored as positive magnitudes in the plan.
     *
     * @param departmentBucketKey when non-null, profit for that department bucket only (lineKey and department set);
     *                            null = single global profit row.
     */
    private static AskPlanRowDto buildProfitSyntheticRow(
            List<AskPlanRowDto> matchedRows,
            List<String> periodMonths,
            ParsedAskIntent intent,
            String departmentBucketKey) {
        List<Map<String, Integer>> revenueMaps = new ArrayList<>();
        List<Map<String, Integer>> expenseMaps = new ArrayList<>();
        for (AskPlanRowDto r : matchedRows) {
            String t = r.type();
            if (t == null) {
                continue;
            }
            if ("Revenue".equalsIgnoreCase(t)) {
                revenueMaps.add(r.baseValues());
            } else if ("Expense".equalsIgnoreCase(t)) {
                expenseMaps.add(r.baseValues());
            }
        }
        Map<String, Integer> baseValues = mergeMonthValueMaps(revenueMaps);
        Map<String, Integer> compareValues = mergeMonthValueMaps(expenseMaps);
        Integer baseTotal = sumMonths(baseValues, PlanMonthlyDetails.MONTH_KEYS);
        Integer compareTotal = sumMonths(compareValues, PlanMonthlyDetails.MONTH_KEYS);
        Integer deltaVsCompare = baseTotal - compareTotal;
        Integer periodTotal = periodMonths != null ? sumMonths(baseValues, periodMonths) : null;
        Integer comparePeriodTotal = periodMonths != null ? sumMonths(compareValues, periodMonths) : null;
        String department =
                departmentBucketKey != null
                        ? departmentBucketKey
                        : inferDepartmentForScopedAggregate(matchedRows, intent.department());
        String lineKey = profitLineKeyForDepartmentBucket(departmentBucketKey);
        return new AskPlanRowDto(
                lineKey,
                department,
                null,
                null,
                "Profit",
                null,
                null,
                baseValues,
                baseTotal,
                periodTotal,
                compareValues,
                compareTotal,
                comparePeriodTotal,
                deltaVsCompare,
                null,
                null,
                null,
                null);
    }

    private static AskPlanRowDto buildSyntheticGroupRow(
            String groupKey,
            List<AskPlanRowDto> rows,
            String groupBy,
            List<String> periodMonths) {
        String lineKey = syntheticLineKey(groupBy, groupKey);
        String label = "department".equals(groupBy) ? null : groupKey;
        String department = null;
        String coaCode = null;
        String coaName = null;
        String category = null;
        String type = null;
        if ("department".equals(groupBy)) {
            department = groupKey;
        } else if ("category".equals(groupBy)) {
            category = groupKey;
        } else if ("type".equals(groupBy)) {
            type = groupKey;
        } else if ("coa_code".equals(groupBy)) {
            coaCode = groupKey;
        } else if ("coa_name".equals(groupBy)) {
            coaName = groupKey;
        }
        if (!"type".equals(groupBy)) {
            type = mergedLineType(rows);
        }
        return aggregateAskPlanRows(rows, lineKey, label, department, coaCode, coaName, type, category, periodMonths);
    }

    private static String grandTotalLabel(ParsedAskIntent intent) {
        List<String> lt = intent.lineTypes();
        if (lt != null && lt.size() == 1) {
            return "Total - " + lt.get(0);
        }
        return "Grand total";
    }

    private static String syntheticLineKey(String groupBy, String groupKey) {
        String safe = groupKey == null ? GROUP_KEY_NONE : groupKey.replace(':', '_');
        return "__group__:" + groupBy + ":" + safe;
    }

    private static String mergedLineType(List<AskPlanRowDto> rows) {
        Set<String> types = new LinkedHashSet<>();
        for (AskPlanRowDto r : rows) {
            if (r.type() != null && !r.type().isBlank()) {
                types.add(r.type());
            }
        }
        if (types.isEmpty()) {
            return null;
        }
        if (types.size() == 1) {
            return types.iterator().next();
        }
        return "Mixed";
    }

    private static Map<String, Integer> mergeMonthValueMaps(List<Map<String, Integer>> maps) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map<String, Integer> m : maps) {
            if (m == null) {
                continue;
            }
            for (Map.Entry<String, Integer> e : m.entrySet()) {
                out.merge(e.getKey(), e.getValue() != null ? e.getValue() : 0, Integer::sum);
            }
        }
        return out;
    }

    /**
     * Sums monthly maps and totals; compare/actual fields are null unless every row in the group has that side.
     */
    private static AskPlanRowDto aggregateAskPlanRows(
            List<AskPlanRowDto> rows,
            String lineKey,
            String label,
            String department,
            String coaCode,
            String coaName,
            String type,
            String category,
            List<String> periodMonths) {
        List<Map<String, Integer>> baseMaps = rows.stream().map(AskPlanRowDto::baseValues).toList();
        Map<String, Integer> baseValues = mergeMonthValueMaps(baseMaps);

        boolean allCompare = !rows.isEmpty() && rows.stream().allMatch(r -> r.compareValues() != null);
        Map<String, Integer> compareValues =
                allCompare ? mergeMonthValueMaps(rows.stream().map(AskPlanRowDto::compareValues).toList()) : null;

        boolean allActual = !rows.isEmpty() && rows.stream().allMatch(r -> r.actualValues() != null);
        Map<String, Integer> actualValues =
                allActual ? mergeMonthValueMaps(rows.stream().map(AskPlanRowDto::actualValues).toList()) : null;

        Integer baseTotal = sumMonths(baseValues, PlanMonthlyDetails.MONTH_KEYS);
        Integer compareTotal = compareValues == null ? null : sumMonths(compareValues, PlanMonthlyDetails.MONTH_KEYS);
        Integer actualTotal = actualValues == null ? null : sumMonths(actualValues, PlanMonthlyDetails.MONTH_KEYS);
        Integer deltaVsCompare = compareTotal == null ? null : (baseTotal - compareTotal);
        Integer deltaVsActual = actualTotal == null ? null : (baseTotal - actualTotal);

        Integer periodTotal = periodMonths != null ? sumMonths(baseValues, periodMonths) : null;
        Integer comparePeriodTotal =
                periodMonths != null && compareValues != null ? sumMonths(compareValues, periodMonths) : null;
        Integer actualPeriodTotal =
                periodMonths != null && actualValues != null ? sumMonths(actualValues, periodMonths) : null;

        return new AskPlanRowDto(
                lineKey,
                department,
                coaCode,
                coaName,
                label,
                type,
                category,
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
                base.getDepartment(),
                base.getCoaCode(),
                base.getCoaName(),
                base.getLabel(),
                base.getType().name(),
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

    private static final String DEPT_ALIAS_FB = "DEPT_FB";
    private static final String DEPT_ALIAS_NON_OP = "DEPT_NON_OP";

    private static boolean departmentFilterUsesAdvancedSyntax(String expression) {
        if (expression == null) {
            return false;
        }
        String e = expression.trim();
        return e.startsWith("!") || e.startsWith("^") || e.startsWith("=") || e.contains("|");
    }

    /** Lowercase; & and - to spaces; collapse whitespace — for alias bucket detection only. */
    static String normalizeDepartmentForAlias(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.toLowerCase(Locale.ROOT).replace('&', ' ').replace('-', ' ');
        return s.replaceAll("\\s+", " ").trim();
    }

    private static String collapseAlphaNum(String s) {
        return s.replaceAll("[^a-z0-9]", "");
    }

    /**
     * Canonical key when {@code raw} is a known department alias (F&amp;B family or non-operating); otherwise null.
     */
    static String resolveDepartmentAliasKey(String raw) {
        String n = normalizeDepartmentForAlias(raw);
        if (n.isEmpty()) {
            return null;
        }
        if (n.contains("non operating")) {
            return DEPT_ALIAS_NON_OP;
        }
        String collapsed = collapseAlphaNum(n);
        if (collapsed.contains("nonoperating")) {
            return DEPT_ALIAS_NON_OP;
        }
        if ("f b".equals(n) || "fandb".equals(n) || "fb".equals(n)) {
            return DEPT_ALIAS_FB;
        }
        if (n.contains("food beverage") || n.contains("food and beverage")) {
            return DEPT_ALIAS_FB;
        }
        if (n.contains("food department") || n.contains("food and department")) {
            return DEPT_ALIAS_FB;
        }
        return null;
    }

    private static boolean matchesDepartment(PlanMonthlyDetails li, String department) {
        if (department == null || department.isBlank()) {
            return true;
        }
        if (departmentFilterUsesAdvancedSyntax(department)) {
            return matchesFilterExpression(li.getDepartment(), department);
        }
        String keyFilter = resolveDepartmentAliasKey(department);
        String keyField = resolveDepartmentAliasKey(li.getDepartment());
        if (keyFilter != null && keyFilter.equals(keyField)) {
            return true;
        }
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

    /**
     * Strips generic Ask Plan search noise (e.g. "department", "rows") so "show F&amp;B department rows"
     * matches rows whose department is "F&amp;B".
     */
    private static String normalizeAskPlanSearchQuery(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String q = raw.trim().toLowerCase(Locale.ROOT);
        String next;
        do {
            next = q.replaceAll(
                            "\\b(department|departments|rows?|line\\s*items?|details?|for|from|in|the|all|my|show)\\b",
                            " ")
                    .replaceAll("\\s+", " ")
                    .trim();
            if (next.equals(q)) {
                break;
            }
            q = next;
        } while (true);
        return q.isBlank() ? raw.trim().toLowerCase(Locale.ROOT) : q;
    }

    private static boolean matchesSearchText(PlanMonthlyDetails li, String searchText) {
        if (searchText == null || searchText.isBlank()) {
            return true;
        }
        String q = normalizeAskPlanSearchQuery(searchText);
        String keyQ = resolveDepartmentAliasKey(q);
        String keyDept = resolveDepartmentAliasKey(li.getDepartment());
        if (keyQ != null && keyQ.equals(keyDept)) {
            return true;
        }
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

    private static String buildSummary(
            ParsedAskIntent intent, int count, Integer topN, boolean topNPerLineType) {
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
        if (topN != null) {
            if (topNPerLineType) {
                return base + " Returned " + count + " row(s), up to " + topN + " per line type.";
            }
            return base + " Returned " + count + " row(s), capped at top " + topN + ".";
        }
        return base + " Returned " + count + " row(s).";
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

    public AskPlanAnalyzeResponse analyze(AskPlanAnalyzeRequest req) {
        AskPlanResponse resp = req.response();
        if (resp == null || resp.resultRows() == null || resp.resultRows().isEmpty()) {
            throw new IllegalArgumentException("No result rows to analyze");
        }
        String provider = Optional.ofNullable(req.provider()).orElse("").toLowerCase(Locale.ROOT).trim();
        String userPayload = buildAnalysisUserPayload(req.question(), resp);
        JsonNode root;
        if ("openai".equals(provider)) {
            root = fetchAnalysisJsonOpenAi(userPayload);
        } else if ("gemini".equals(provider)) {
            root = fetchAnalysisJsonGemini(userPayload);
        } else {
            throw new IllegalArgumentException("provider must be 'openai' or 'gemini'");
        }
        return toAnalyzeResponse(root);
    }

    private String buildAnalysisUserPayload(String question, AskPlanResponse resp) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("userQuestion", question);
            root.put("summary", resp.summary());
            root.put("intent", resp.intent());
            if (resp.appliedFilters() != null) {
                root.set("appliedFilters", objectMapper.valueToTree(resp.appliedFilters()));
            }
            ArrayNode rows = objectMapper.createArrayNode();
            for (AskPlanRowDto r : resp.resultRows()) {
                ObjectNode o = objectMapper.createObjectNode();
                o.put("label", r.label());
                o.put("type", r.type());
                o.put("category", r.category());
                putIntIfPresent(o, "baseTotal", r.baseTotal());
                putIntIfPresent(o, "compareTotal", r.compareTotal());
                putIntIfPresent(o, "deltaVsCompare", r.deltaVsCompare());
                putIntIfPresent(o, "actualTotal", r.actualTotal());
                putIntIfPresent(o, "deltaVsActual", r.deltaVsActual());
                rows.add(o);
            }
            root.set("resultRows", rows);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build analysis payload: " + e.getMessage(), e);
        }
    }

    private static void putIntIfPresent(ObjectNode o, String key, Integer v) {
        if (v != null) {
            o.put(key, v);
        }
    }

    private JsonNode fetchAnalysisJsonOpenAi(String userPayload) {
        if (openaiApiKey == null || openaiApiKey.isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY / planora.ai.openai.api-key is not configured");
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", "gpt-4o-mini");
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", ANALYSIS_SYSTEM_PROMPT);
        messages.addObject().put("role", "user").put("content", userPayload);
        body.putObject("response_format").put("type", "json_object");
        body.put("temperature", 0.2);
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
            throw new IllegalStateException("OpenAI analysis request failed: " + e.getMessage(), e);
        }
        try {
            JsonNode tree = objectMapper.readTree(raw);
            String content = tree.path("choices").path(0).path("message").path("content").asText();
            return objectMapper.readTree(content);
        } catch (Exception e) {
            throw new IllegalStateException("OpenAI analysis response parse failed: " + e.getMessage(), e);
        }
    }

    private JsonNode fetchAnalysisJsonGemini(String userPayload) {
        if (geminiApiKey == null || geminiApiKey.isBlank()) {
            throw new IllegalStateException("GEMINI_API_KEY / planora.ai.gemini.api-key is not configured");
        }
        String fullPrompt = ANALYSIS_SYSTEM_PROMPT + "\n\nData (JSON):\n" + userPayload;
        ObjectNode body = objectMapper.createObjectNode();
        body.putArray("contents").addObject().putArray("parts").addObject().put("text", fullPrompt);
        body.putObject("generationConfig")
                .put("responseMimeType", "application/json")
                .put("temperature", 0.2);
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
            throw new IllegalStateException("Gemini analysis request failed: " + e.getMessage(), e);
        }
        try {
            JsonNode root = objectMapper.readTree(raw);
            String text = root.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText();
            return objectMapper.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException("Gemini analysis response parse failed: " + e.getMessage(), e);
        }
    }

    private static AskPlanAnalyzeResponse toAnalyzeResponse(JsonNode root) {
        if (root == null || !root.isObject()) {
            return new AskPlanAnalyzeResponse(List.of("Analysis could not be parsed."));
        }
        JsonNode arr = root.get("points");
        if (arr == null || !arr.isArray()) {
            return new AskPlanAnalyzeResponse(List.of("Analysis could not be parsed."));
        }
        List<String> out = new ArrayList<>();
        for (JsonNode p : arr) {
            if (p == null || p.isNull()) {
                continue;
            }
            String t = p.asText("").trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
            if (out.size() >= 5) {
                break;
            }
        }
        if (out.isEmpty()) {
            return new AskPlanAnalyzeResponse(List.of("No analysis points returned."));
        }
        return new AskPlanAnalyzeResponse(List.copyOf(out));
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
        String groupBy = normalizeGroupBy(blankToNull(text(n, "groupBy")));
        String chartType = text(n, "chartType");
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
                groupBy,
                normalizeChartType(blankToNull(chartType)));
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

        String groupBy = normalizeGroupBy(inferGroupBy(s));

        String chartType = null;
        if (s.contains("chart") || s.contains("graph") || s.contains("plot") || s.contains("visuali")) {
            if (s.contains("bar")) chartType = "bar";
            else if (s.contains("pie")) chartType = "pie";
            else chartType = "line";
        }

        List<String> lineTypes = normalizeLineTypes(List.of(
                s.contains("revenue") ? "Revenue" : null,
                s.contains("expense") ? "Expense" : null,
                s.contains("statistics") ? "Statistics" : null), null);
        if ("profit".equals(groupBy)) {
            LinkedHashSet<String> lt = new LinkedHashSet<>();
            if (lineTypes != null) {
                lt.addAll(lineTypes);
            }
            lt.add("Revenue");
            lt.add("Expense");
            lineTypes = List.copyOf(lt);
        }
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
                groupBy,
                chartType);
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
                intent.totalFilter(), intent.totalFilterMonths(), intent.groupBy(),
                intent.chartType());
    }

    private static String inferGroupBy(String s) {
        if (s == null) {
            return null;
        }
        if (s.contains("net income")
                || s.contains("bottom line")
                || s.contains("profit and loss")
                || s.contains("p&l")
                || s.contains("p & l")
                || s.contains("p/l")
                || (s.contains("revenue") && s.contains("expense") && s.contains("profit"))) {
            return "profit";
        }
        if (s.contains("profit") && (s.contains("revenue") || s.contains("expense"))) {
            return "profit";
        }
        // Revenue + expense + department breakdown => profit (revenue in base, expense in compare), one row per department.
        if (s.contains("revenue")
                && s.contains("expense")
                && (s.contains("by department")
                        || s.contains("per department")
                        || s.contains("department-wise")
                        || s.contains("for each department")
                        || s.contains("each department"))) {
            return "profit";
        }
        // Breakdown dimensions before "total + revenue + expense" -> profit, so other "by X" phrases still win over plain totals.
        if (s.contains("by department")
                || s.contains("department-wise")
                || s.contains("per department")) {
            return "department";
        }
        if (s.contains("by category")
                || s.contains("category-wise")
                || s.contains("per category")) {
            return "category";
        }
        if (s.contains("by type")
                || s.contains("type-wise")
                || s.contains("per type")
                || s.contains("by account type")) {
            return "type";
        }
        if (s.contains("by coa name")
                || s.contains("by account name")
                || s.contains("per coa name")
                || s.contains("per account name")
                || s.contains("by gl name")) {
            return "coa_name";
        }
        if (s.contains("by coa")
                || s.contains("by gl")
                || s.contains("per coa")
                || s.contains("by account code")
                || s.contains("by gl code")) {
            return "coa_code";
        }
        if (s.contains("revenue")
                && s.contains("expense")
                && (WORD_TOTAL.matcher(s).find() || WORD_TOAL.matcher(s).find())) {
            return "profit";
        }
        if (s.contains("grand total") || s.contains("grand_total")) {
            return "grand";
        }
        if (s.contains("total revenue")
                || s.contains("total expenses")
                || s.contains("total expense")
                || s.contains("total statistics")
                || s.contains("sum of ")
                || s.contains("sum of the ")
                || s.contains("what's the total")
                || s.contains("what is the total")
                || s.contains("how much total")) {
            return "grand";
        }
        return null;
    }

    /**
     * Canonical aggregation dimension; unknown or empty tokens yield null (keep detail rows).
     */
    static String normalizeGroupBy(String raw) {
        if (raw == null || raw.isBlank() || "null".equalsIgnoreCase(raw.trim())) {
            return null;
        }
        String compact = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(" ", "");
        if ("grand".equals(compact) || "grand_total".equals(compact) || "grandtotal".equals(compact)) {
            return "grand";
        }
        if ("coa_code".equals(compact) || "coacode".equals(compact)) {
            return "coa_code";
        }
        if ("coa_name".equals(compact)
                || "coaname".equals(compact)
                || "account_name".equals(compact)
                || "accountname".equals(compact)) {
            return "coa_name";
        }
        if ("department".equals(compact) || "dept".equals(compact)) {
            return "department";
        }
        if ("category".equals(compact)) {
            return "category";
        }
        if ("type".equals(compact)
                || "account_type".equals(compact)
                || "accounttype".equals(compact)) {
            return "type";
        }
        if ("profit".equals(compact)
                || "pnl".equals(compact)
                || "p_and_l".equals(compact)
                || "pandl".equals(compact)
                || "profit_loss".equals(compact)
                || "profitloss".equals(compact)
                || "netincome".equals(compact)
                || "net_income".equals(compact)) {
            return "profit";
        }
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

    private static String nullSafeGroupKey(String v) {
        return (v == null || v.isBlank()) ? GROUP_KEY_NONE : v;
    }

    private static String resolveGroupKey(AskPlanRowDto row, String groupBy) {
        if (groupBy == null) {
            return GROUP_KEY_NONE;
        }
        if ("grand".equals(groupBy)) {
            return "__grand__";
        }
        if ("profit".equals(groupBy)) {
            return "__profit__";
        }
        if ("department".equals(groupBy)) {
            return nullSafeGroupKey(row.department());
        }
        if ("category".equals(groupBy)) {
            return nullSafeGroupKey(row.category());
        }
        if ("type".equals(groupBy)) {
            return nullSafeGroupKey(row.type());
        }
        if ("coa_code".equals(groupBy)) {
            return nullSafeGroupKey(row.coaCode());
        }
        if ("coa_name".equals(groupBy)) {
            return nullSafeGroupKey(row.coaName() != null && !row.coaName().isBlank() ? row.coaName() : row.label());
        }
        return GROUP_KEY_NONE;
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
                normalizeGroupBy(coalesce(blankToNull(req.groupBy()), ai.groupBy())),
                ai.chartType());
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

    private static String normalizeChartType(String ct) {
        if (ct == null) return null;
        String c = ct.trim().toLowerCase(Locale.ROOT);
        if ("bar".equals(c) || "pie".equals(c) || "line".equals(c)) return c;
        return "line";
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
            String groupBy,
            String chartType) {
        static ParsedAskIntent empty() {
            return new ParsedAskIntent(
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null);
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
                    && groupBy == null
                    && chartType == null;
        }
    }
}
