package com.planora.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.planora.domain.ActualsDetails;
import com.planora.domain.Plan;
import com.planora.domain.PlanMonthlyDetails;
import com.planora.enums.PlanType;
import com.planora.repo.ActualsDetailRepository;
import com.planora.repo.LineItemRepository;
import com.planora.repo.PlanRepository;
import com.planora.web.dto.AskPlanRequest;
import com.planora.web.dto.AskPlanResponse;
import com.planora.web.dto.AskPlanRowDto;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
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

    private static final String SYSTEM_PROMPT = "You are a financial analytics intent parser.\n"
            + "Read a user question and return ONLY JSON using this exact shape:\n"
            + "{\n"
            + "  \"intent\":\"within_plan|compare_plan|compare_actuals|top_n|filter|unknown\",\n"
            + "  \"lineType\":\"Revenue|Expense|Statistics|null\",\n"
            + "  \"lineTypes\":[\"Revenue|Expense|Statistics\", \"...\"],\n"
            + "  \"category\":\"string or null\",\n"
            + "  \"period\":\"Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec|Q1|Q2|Q3|Q4|full_year|null\",\n"
            + "  \"topN\":number or null,\n"
            + "  \"includeActuals\":true|false|null,\n"
            + "  \"compareMode\":\"none|plan|actuals\",\n"
            + "  \"actualYear\":number or null,\n"
            + "  \"comparePlanYear\":number or null,\n"
            + "  \"comparePlanType\":\"BUDGET|FORECAST|WHAT_IF|null\",\n"
            + "  \"rankMode\":\"max|min|avg|null\",\n"
            + "  \"searchText\":\"string or null\"\n"
            + "}\n"
            + "Rules:\n"
            + "- Use compare_plan for plan-vs-plan requests.\n"
            + "- Use compare_actuals for plan-vs-actual requests.\n"
            + "- Use top_n when user asks for top/bottom style ranking.\n"
            + "- Use filter when user asks for constrained subsets (by category/type/text).\n"
            + "- If unclear, use unknown.\n"
            + "Respond with valid JSON only.";

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
        ParsedAskIntent intent = mergeWithOverrides(aiIntent, req);

        if ("unknown".equals(intent.intent())
                && intent.topN() == null
                && (intent.lineTypes() == null || intent.lineTypes().isEmpty())
                && intent.category() == null
                && intent.searchText() == null
                && intent.actualYear() == null
                && !"plan".equals(intent.compareMode())
                && !"actuals".equals(intent.compareMode())) {
            throw new IllegalArgumentException("Could not understand query intent. Try asking with clearer compare/filter terms.");
        }

        Plan basePlan = planRepository.findById(req.basePlanId())
                .orElseThrow(() -> new EntityNotFoundException("Plan not found: " + req.basePlanId()));
        List<PlanMonthlyDetails> baseRows = lineItemRepository.findByPlan_Id(basePlan.getId());

        Long comparePlanId = resolveComparePlanId(req, intent, basePlan);
        if ("plan".equals(intent.compareMode()) && comparePlanId == null) {
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

        List<AskPlanRowDto> rows = baseRows.stream()
                .filter(li -> matchesLineTypes(li, intent.lineTypes()))
                .filter(li -> matchesCategory(li, intent.category()))
                .filter(li -> matchesSearchText(li, intent.searchText()))
                .map(li -> toResultRow(li, compareByKey.get(li.getLineKey()), actualsByKey.get(li.getLineKey())))
                .sorted(sorter(intent))
                .toList();

        int effectiveTopN = clampTopN(intent.topN());
        List<AskPlanRowDto> limited = rows.stream().limit(effectiveTopN).toList();

        Map<String, Object> appliedFilters = new LinkedHashMap<>();
        appliedFilters.put("period", normalizePeriod(intent.period()));
        appliedFilters.put("lineTypes", intent.lineTypes());
        appliedFilters.put("category", intent.category());
        appliedFilters.put("searchText", intent.searchText());
        appliedFilters.put("topN", effectiveTopN);
        appliedFilters.put("compareMode", intent.compareMode());
        appliedFilters.put("includeActuals", includeActuals);
        appliedFilters.put("actualYear", includeActuals ? actualYear : null);
        appliedFilters.put("comparePlanYear", intent.comparePlanYear());
        appliedFilters.put("comparePlanType", intent.comparePlanType());
        appliedFilters.put("rankMode", normalizeRankMode(intent.rankMode()));

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("basePlanId", basePlan.getId());
        meta.put("comparePlanId", comparePlanId);
        meta.put("actualYear", includeActuals ? actualYear : null);
        meta.put("resultCount", limited.size());
        meta.put("totalMatchedBeforeLimit", rows.size());

        String summary = buildSummary(intent, limited.size(), effectiveTopN);
        return new AskPlanResponse(summary, intent.intent(), appliedFilters, limited, meta);
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
            Map<String, Integer> actualValues) {
        Map<String, Integer> baseValues = base.toValuesMap();
        Map<String, Integer> compareValues = compare != null ? compare.toValuesMap() : null;
        Integer baseTotal = sumMonths(baseValues, PlanMonthlyDetails.MONTH_KEYS);
        Integer compareTotal = compareValues == null ? null : sumMonths(compareValues, PlanMonthlyDetails.MONTH_KEYS);
        Integer actualTotal = actualValues == null ? null : sumMonths(actualValues, PlanMonthlyDetails.MONTH_KEYS);
        Integer deltaVsCompare = compareTotal == null ? null : (baseTotal - compareTotal);
        Integer deltaVsActual = actualTotal == null ? null : (baseTotal - actualTotal);
        return new AskPlanRowDto(
                base.getLineKey(),
                base.getLabel(),
                base.getType().name(),
                base.getCategory(),
                baseValues,
                baseTotal,
                compareValues,
                compareTotal,
                deltaVsCompare,
                actualValues,
                actualTotal,
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
        return category == null
                || category.isBlank()
                || li.getCategory().toLowerCase(Locale.ROOT).contains(category.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean matchesSearchText(PlanMonthlyDetails li, String searchText) {
        if (searchText == null || searchText.isBlank()) {
            return true;
        }
        String q = searchText.trim().toLowerCase(Locale.ROOT);
        return li.getLabel().toLowerCase(Locale.ROOT).contains(q)
                || li.getLineKey().toLowerCase(Locale.ROOT).contains(q)
                || li.getDepartment().toLowerCase(Locale.ROOT).contains(q)
                || li.getCategory().toLowerCase(Locale.ROOT).contains(q);
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

    private Long resolveComparePlanId(AskPlanRequest req, ParsedAskIntent intent, Plan basePlan) {
        if (req.comparePlanId() != null) {
            return req.comparePlanId();
        }
        if (!"plan".equals(intent.compareMode())) {
            return null;
        }
        if (intent.comparePlanYear() == null) {
            return null;
        }
        PlanType type = parsePlanType(intent.comparePlanType()).orElse(PlanType.BUDGET);
        return planRepository
                .findFirstByProperty_IdAndFiscalYearAndPlanTypeOrderByIdAsc(
                        basePlan.getProperty().getId(), intent.comparePlanYear(), type)
                .map(Plan::getId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No " + type + " plan found for year " + intent.comparePlanYear()));
    }

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
        messages.addObject().put("role", "system").put("content", SYSTEM_PROMPT);
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
        String fullPrompt = SYSTEM_PROMPT + "\n\nQuestion: \"" + question + "\"";
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
        String period = text(n, "period");
        Integer topN = intVal(n, "topN");
        Boolean includeActuals = boolVal(n, "includeActuals");
        String compareMode = text(n, "compareMode");
        Integer actualYear = intVal(n, "actualYear");
        Integer comparePlanYear = intVal(n, "comparePlanYear");
        String comparePlanType = text(n, "comparePlanType");
        String rankMode = text(n, "rankMode");
        String searchText = text(n, "searchText");
        return new ParsedAskIntent(
                blankToNull(intent),
                normalizeLineTypes(lineTypes, lineType),
                blankToNull(category),
                blankToNull(period),
                topN,
                includeActuals,
                normalizeCompareMode(compareMode),
                actualYear,
                comparePlanYear,
                normalizePlanType(comparePlanType),
                normalizeRankMode(rankMode),
                blankToNull(searchText));
    }

    static ParsedAskIntent heuristicIntent(String q) {
        String s = Optional.ofNullable(q).orElse("").toLowerCase(Locale.ROOT);
        String intent = "within_plan";
        String compareMode = "none";
        Integer actualYear = null;
        Integer comparePlanYear = null;
        String comparePlanType = null;
        String rankMode = inferRankMode(s);
        if (s.contains("compare") && s.contains("actual")) {
            intent = "compare_actuals";
            compareMode = "actuals";
            actualYear = extractYearNearKeyword(s, "actual");
        } else if (s.contains("compare")) {
            intent = "compare_plan";
            compareMode = "plan";
            comparePlanYear = extractYearNearKeyword(s, "budget");
            comparePlanType = comparePlanYear != null || s.contains("budget") ? "BUDGET" : null;
        } else if (s.contains("top ")) {
            intent = "top_n";
        } else if (s.contains("show") || s.contains("filter")) {
            intent = "filter";
        } else {
            intent = "unknown";
        }
        if (s.contains("actual")) {
            actualYear = actualYear != null ? actualYear : extractYearNearKeyword(s, "actual");
        }
        if (s.contains("budget")) {
            comparePlanYear = comparePlanYear != null ? comparePlanYear : extractYearNearKeyword(s, "budget");
            comparePlanType = comparePlanType != null ? comparePlanType : "BUDGET";
            compareMode = Objects.equals(compareMode, "none") ? "plan" : compareMode;
            intent = Objects.equals(intent, "within_plan") || Objects.equals(intent, "filter") ? "compare_plan" : intent;
        }
        List<String> lineTypes = normalizeLineTypes(List.of(
                s.contains("revenue") ? "Revenue" : null,
                s.contains("expense") ? "Expense" : null,
                s.contains("statistics") ? "Statistics" : null), null);
        Integer topN = extractTopN(s);
        String searchText = extractSearchText(s);
        if (topN == null && s.startsWith("what is")) {
            topN = 1;
        }
        return new ParsedAskIntent(
                intent, lineTypes, null, null, topN, null, compareMode, actualYear, comparePlanYear, comparePlanType, rankMode, searchText);
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
        String compareMode = ai.compareMode();
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
                coalesce(blankToNull(req.period()), ai.period()),
                req.topN() != null ? req.topN() : ai.topN(),
                req.includeActuals() != null ? req.includeActuals() : ai.includeActuals(),
                normalizeCompareMode(compareMode),
                req.actualYear() != null ? req.actualYear() : ai.actualYear(),
                req.comparePlanYear() != null ? req.comparePlanYear() : ai.comparePlanYear(),
                coalesce(normalizePlanType(req.comparePlanType()), ai.comparePlanType()),
                ai.rankMode(),
                coalesce(blankToNull(req.searchText()), ai.searchText()));
    }

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
        if ("expense".equals(lt) || "expenses".equals(lt)) {
            return "Expense";
        }
        if ("revenue".equals(lt) || "revenues".equals(lt)) {
            return "Revenue";
        }
        if ("statistics".equals(lt) || "statistic".equals(lt)) {
            return "Statistics";
        }
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

    record ParsedAskIntent(
            String intent,
            List<String> lineTypes,
            String category,
            String period,
            Integer topN,
            Boolean includeActuals,
            String compareMode,
            Integer actualYear,
            Integer comparePlanYear,
            String comparePlanType,
            String rankMode,
            String searchText) {
        static ParsedAskIntent empty() {
            return new ParsedAskIntent(null, null, null, null, null, null, null, null, null, null, null, null);
        }

        boolean isEmpty() {
            return intent == null
                    && (lineTypes == null || lineTypes.isEmpty())
                    && category == null
                    && period == null
                    && topN == null
                    && includeActuals == null
                    && compareMode == null
                    && actualYear == null
                    && comparePlanYear == null
                    && comparePlanType == null
                    && rankMode == null
                    && searchText == null;
        }
    }
}
