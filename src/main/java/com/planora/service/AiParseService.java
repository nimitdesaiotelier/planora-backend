package com.planora.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.planora.domain.PlanMonthlyDetails;
import com.planora.enums.LineItemType;
import com.planora.web.dto.InstructionStepDto;
import com.planora.web.dto.ParseInstructionRequest;
import com.planora.web.dto.ParsedInstructionDto;
import java.time.Duration;
import java.time.Year;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Natural-language budget instruction processor.
 *
 * <p>Flow: user text → LLM structured JSON → resolve source data → apply month mutations → optional
 * daily split from new month totals → preview ({@code newValues}, {@code newDailyDetails}).
 *
 * <p>Supported copy sources (the "type" field in LLM output):
 * <ul>
 *   <li>{@code actuals}       – real uploaded actuals from {@code tbl_actuals_details}</li>
 *   <li>{@code budget}        – plan data from {@code tbl_plan_monthly_details} with {@link com.planora.enums.PlanType#BUDGET}</li>
 *   <li>{@code forecast}      – plan data with {@link com.planora.enums.PlanType#FORECAST}</li>
 *   <li>{@code what_if}       – plan data with {@link com.planora.enums.PlanType#WHAT_IF}</li>
 *   <li>{@code percentage}    – increase/decrease by %</li>
 *   <li>{@code absolute}      – increase/decrease/set by $ amount</li>
 * </ul>
 */
@Slf4j
@Service
public class AiParseService {

    // @formatter:off
    private static final String SYSTEM_PROMPT = """
            You are a financial planning assistant.
            Parse the user's natural language into structured JSON only.

            Output shape (always a single JSON object):
            {
              "summary": "<one short sentence describing all changes>",
              "instructions": [
                {
                  "action": "<increase|decrease|set|copy>",
                  "value": <number or null>,
                  "type": "<percentage|absolute|actuals|budget|forecast|what_if|null>",
                  "source_year": <4-digit integer or null>,
                  "property_hint": "<property/country/hotel name or null>",
                  "period": "<Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec|Q1|Q2|Q3|Q4|full_year|null>",
                  "day_from": <1-31 or null>,
                  "day_to": <1-31 or null>,
                  "day_filter": "<weekends|weekdays|null>",
                  "source_row_label": "<row label to copy from within the current plan, or null>",
                  "summary": "<optional per-step note>"
                }
              ]
            }

            RULES:

            The "Plan year" and "Plan type" are provided in the user message context.
            - "Plan year" is the fiscal year of the plan being edited.
            - "Plan type" is the type of the plan being edited (e.g. BUDGET, FORECAST, WHAT_IF).
            Use "Plan year" as the base for all relative year calculations (last year, previous year, N years ago, etc.).

            action:
            - "increase" for growth/raise/add
            - "decrease" for reduction/lower/subtract
            - "set" for absolute assignment (set to X)
            - "copy" for copying from another data source

            type (for increase/decrease/set):
            - "percentage" for % changes
            - "absolute" for $ / fixed-number changes

            type (for copy):
            - "actuals"      — copy from real actuals data. Use when user says "actuals", "actual data", "actual numbers", "real numbers".
            - "budget"       — copy from a BUDGET plan. Use when user says "budget", "budget data", "budget plan".
            - "forecast"     — copy from a FORECAST plan. Use when user says "forecast", "forecast data".
            - "what_if"      — copy from a WHAT-IF / scenario plan. Use when user says "what if", "what-if", "scenario".
            - "current_plan" — copy from a DIFFERENT ROW within the same current plan. Use when user says "current plan", "this plan", "same plan", or names a specific row/account (e.g. "Banquet Revenue") without mentioning actuals/budget/forecast/what_if.

            IMPORTANT — resolving type when user says generic terms like "plan", "data", or "last year" WITHOUT specifying a data type:
            - If user says "copy from last year plan" or just "copy from last year" without specifying actuals/budget/forecast/what_if:
              → Use the "Plan type" from context as the copy type.
              → e.g. if Plan type = BUDGET → type = "budget"; if Plan type = WHAT_IF → type = "what_if"; if Plan type = FORECAST → type = "forecast".
            - User-specified data type ALWAYS overrides the plan context:
              → "copy from last year budget" → type = "budget" regardless of Plan type.
              → "copy from last year what-if" → type = "what_if" regardless of Plan type.
              → "copy from last year actuals" → type = "actuals" regardless of Plan type.

            source_row_label (for copy with type="current_plan" only):
            - The human-readable label of the row to copy FROM within the same plan (e.g. "Banquet Revenue", "Room Revenue").
            - Set to null for all other copy types (actuals, budget, forecast, what_if).

            source_year (for copy only):
            - REQUIRED for actuals/budget/forecast/what_if copy instructions (never leave null for those).
            - Must be a concrete 4-digit year (e.g. 2025, 2024, 2023).
            - ALWAYS derive relative years from "Plan year" (NOT the current calendar year):
              "this year" / "current year"              → source_year = Plan year
              "last year" / "previous year"              → source_year = Plan year − 1
              "last to last year" / "year before last"   → source_year = Plan year − 2
              "N years ago"                              → source_year = Plan year − N
            - If user gives an explicit 4-digit year (e.g. "2023 actuals"), use that year directly as source_year.
            - For type="current_plan" set source_year = null (no year needed; it's the same plan).
            - For non-copy actions, set source_year = null.

            property_hint (for copy only):
            - The property/country/hotel name mentioned by user, or null if same property

            period:
            - A specific month (Jan–Dec), quarter (Q1–Q4), or "full_year"
            - If user says multiple months, create separate instructions per period
            - If no period mentioned, default to "full_year"

            day_from / day_to (optional, calendar days 1–31 inclusive within each affected month):
            - Omit BOTH when the change applies to the whole month (or whole quarter/year via period).
            - When the user names specific day(s) in a month, set them for that month’s period.
            - "April 1 only", "first day of April", "Apr 1st" → period "Apr", day_from 1, day_to 1
            - "April 1 through 10" → period "Apr", day_from 1, day_to 10
            - For a single day you may set only day_from; day_to defaults to the same day.
            - "copy" steps: omit day_from/day_to (whole months are copied); the engine replaces all days in affected months.

            day_filter (optional, day-of-week filter):
            - "weekends"  — apply only to Saturdays and Sundays
            - "weekdays"  — apply only to Monday through Friday
            - null         — apply to all days (default)
            - Can be combined with period (e.g. "increase weekends in Q2 by 10%").
            - day_filter CAN be combined with day_from/day_to: first iterate those calendar days, then skip days that do not match the filter.
            - "first week weekdays of Jan" → period "Jan", day_from 1, day_to 7, day_filter "weekdays" (calendar days 1–7, weekdays only).

            MULTIPLE CHANGES:
            - If the user gives MULTIPLE independent changes (e.g. "Jan +2000 and Feb +10%"), use MULTIPLE objects in "instructions" — one per change.
            - Do NOT merge different periods into one step.

            EXAMPLES (assuming Plan year = 2025):

            "Increase by 10% for Q2":
            {"summary":"Increase Q2 by 10%.","instructions":[
              {"action":"increase","value":10,"type":"percentage","source_year":null,"property_hint":null,"period":"Q2","day_from":null,"day_to":null}
            ]}

            "Increase 10% for April on the 1st only":
            {"summary":"Increase April 1 by 10%.","instructions":[
              {"action":"increase","value":10,"type":"percentage","source_year":null,"property_hint":null,"period":"Apr","day_from":1,"day_to":1}
            ]}

            "Increase Jan by $2000 and Feb by 10%":
            {"summary":"Raise Jan by $2,000 and Feb by 10%.","instructions":[
              {"action":"increase","value":2000,"type":"absolute","source_year":null,"property_hint":null,"period":"Jan"},
              {"action":"increase","value":10,"type":"percentage","source_year":null,"property_hint":null,"period":"Feb"}
            ]}

            "Copy from last year actuals" (Plan year = 2025 → source_year = 2024):
            {"summary":"Copy last year actuals.","instructions":[
              {"action":"copy","value":null,"type":"actuals","source_year":2024,"property_hint":null,"period":"full_year"}
            ]}

            "Take data from last to last year budget" (Plan year = 2025 → source_year = 2023):
            {"summary":"Copy budget from two years ago.","instructions":[
              {"action":"copy","value":null,"type":"budget","source_year":2023,"property_hint":null,"period":"full_year"}
            ]}

            "Copy Jan data from this year Burlington forecast" (Plan year = 2025 → source_year = 2025):
            {"summary":"Copy Jan from Burlington forecast for this year.","instructions":[
              {"action":"copy","value":null,"type":"forecast","source_year":2025,"property_hint":"Burlington","period":"Jan"}
            ]}

            "Copy 3 years ago budget" (Plan year = 2025 → source_year = 2022):
            {"summary":"Copy budget from 3 years ago.","instructions":[
              {"action":"copy","value":null,"type":"budget","source_year":2022,"property_hint":null,"period":"full_year"}
            ]}

            "Copy from 2022 actuals":
            {"summary":"Copy 2022 actuals.","instructions":[
              {"action":"copy","value":null,"type":"actuals","source_year":2022,"property_hint":null,"period":"full_year"}
            ]}

            "Copy from last year plan" (Plan year = 2025, Plan type = BUDGET → type = budget, source_year = 2024):
            {"summary":"Copy last year budget.","instructions":[
              {"action":"copy","value":null,"type":"budget","source_year":2024,"property_hint":null,"period":"full_year"}
            ]}

            "Copy from last year" (Plan year = 2026, Plan type = WHAT_IF → type = what_if, source_year = 2025):
            {"summary":"Copy last year what-if data.","instructions":[
              {"action":"copy","value":null,"type":"what_if","source_year":2025,"property_hint":null,"period":"full_year"}
            ]}

            "Copy from last year" (Plan year = 2025, Plan type = FORECAST → type = forecast, source_year = 2024):
            {"summary":"Copy last year forecast.","instructions":[
              {"action":"copy","value":null,"type":"forecast","source_year":2024,"property_hint":null,"period":"full_year"}
            ]}

            "Copy from last year budget" (Plan year = 2025, Plan type = WHAT_IF → user override: type = budget, source_year = 2024):
            {"summary":"Copy last year budget.","instructions":[
              {"action":"copy","value":null,"type":"budget","source_year":2024,"property_hint":null,"period":"full_year"}
            ]}

            "Increase all weekends by 1000":
            {"summary":"Increase all weekend days by $1,000.","instructions":[
              {"action":"increase","value":1000,"type":"absolute","source_year":null,"property_hint":null,"period":"full_year","day_from":null,"day_to":null,"day_filter":"weekends"}
            ]}

            "Decrease weekdays by 5% for Q3":
            {"summary":"Decrease Q3 weekdays by 5%.","instructions":[
              {"action":"decrease","value":5,"type":"percentage","source_year":null,"property_hint":null,"period":"Q3","day_from":null,"day_to":null,"day_filter":"weekdays"}
            ]}

            "Increase $1000 for first week weekdays of Jan" (calendar days 1–7, weekdays only):
            {"summary":"Increase Jan weekdays in the first calendar week by $1,000.","instructions":[
              {"action":"increase","value":1000,"type":"absolute","source_year":null,"property_hint":null,"period":"Jan","day_from":1,"day_to":7,"day_filter":"weekdays"}
            ]}

            "Copy data of Banquet Revenue from current plan":
            {"summary":"Copy Banquet Revenue data from the current plan.","instructions":[
              {"action":"copy","value":null,"type":"current_plan","source_year":null,"source_row_label":"Banquet Revenue","property_hint":null,"period":"full_year"}
            ]}

            "Copy Room Revenue from this plan for Jan":
            {"summary":"Copy Room Revenue from current plan for January.","instructions":[
              {"action":"copy","value":null,"type":"current_plan","source_year":null,"source_row_label":"Room Revenue","property_hint":null,"period":"Jan"}
            ]}

            Respond with ONLY valid JSON, no markdown.""";
    // @formatter:on

    private final ObjectMapper objectMapper;
    private final SourceDataResolverService sourceDataResolverService;
    private final DailyDetailsSplitService dailyDetailsSplitService;
    private final RestClient restClient;

    @Value("${planora.ai.openai.api-key:}")
    private String openaiApiKey;

    @Value("${planora.ai.gemini.api-key:}")
    private String geminiApiKey;

    public AiParseService(
            ObjectMapper objectMapper,
            SourceDataResolverService sourceDataResolverService,
            DailyDetailsSplitService dailyDetailsSplitService) {
        this.objectMapper = objectMapper;
        this.sourceDataResolverService = sourceDataResolverService;
        this.dailyDetailsSplitService = dailyDetailsSplitService;
        var settings = new ClientHttpRequestFactorySettings(
                null,
                Duration.ofSeconds(10),
                Duration.ofSeconds(60),
                null);
        this.restClient = RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .build();
    }

    // ─── Public entry point ──────────────────────────────────────────────

    public ParsedInstructionDto parse(ParseInstructionRequest req) {
        JsonNode root = fetchParsedJson(req);
        String summary = textOrNull(root, "summary");
        List<InstructionStepDto> steps = parseInstructionsList(root);

        log.debug("AI parsed steps: {}", steps);

        if (steps.isEmpty()) {
            throw new IllegalStateException("Could not interpret your request — please refine the prompt");
        }

        Set<String> warnings = new LinkedHashSet<>();
        Integer fy = req.fiscalYear();
        String typeStr = req.lineItemType();

        Map<String, Integer> working;
        Map<String, List<Integer>> newDailyDetails = null;

        if (DailyBudgetInstructionApplyService.anyStepTargetsSpecificDays(steps)) {
            if (fy == null || typeStr == null || typeStr.isBlank()) {
                throw new IllegalStateException(
                        "Day-specific changes require fiscalYear and lineItemType on the request.");
            }
            LineItemType lineType;
            try {
                lineType = LineItemType.valueOf(typeStr.trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("Invalid lineItemType: " + typeStr);
            }
            Map<String, List<Integer>> daily =
                    DailyBudgetInstructionApplyService.baseline(
                            req.values(),
                            req.dailyDetails(),
                            fy,
                            lineType,
                            dailyDetailsSplitService);
            for (InstructionStepDto step : steps) {
                Map<String, Integer> sourceMonths = resolveSourceData(req, step, warnings);
                DailyBudgetInstructionApplyService.applyStep(
                        daily, step, sourceMonths, fy, lineType, dailyDetailsSplitService);
            }
            working = DailyBudgetInstructionApplyService.aggregateMonthsFromDaily(daily);
            newDailyDetails = deepCopyDaily(daily);
        } else {
            working = copyMonths(req.values());
            for (InstructionStepDto step : steps) {
                Map<String, Integer> sourceMonths = resolveSourceData(req, step, warnings);
                working = BudgetInstructionApplyService.apply(working, sourceMonths, step);
            }
            if (fy != null && typeStr != null && !typeStr.isBlank()) {
                try {
                    LineItemType lineType = LineItemType.valueOf(typeStr.trim());
                    newDailyDetails = dailyDetailsSplitService.buildDailyFromMonthly(working, fy, lineType);
                } catch (IllegalArgumentException e) {
                    log.warn("Skipping newDailyDetails: bad lineItemType \"{}\"", typeStr);
                }
            }
        }

        String mergedSummary = summary;
        if (mergedSummary == null || mergedSummary.isBlank()) {
            mergedSummary = steps.stream()
                    .map(InstructionStepDto::summary)
                    .filter(s -> s != null && !s.isBlank())
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("Applied " + steps.size() + " change(s).");
        }

        List<String> warningList = warnings.isEmpty() ? null : List.copyOf(warnings);

        return new ParsedInstructionDto(mergedSummary, steps, warningList, working, newDailyDetails);
    }

    // ─── Source data resolution ──────────────────────────────────────────

    private Map<String, Integer> resolveSourceData(
            ParseInstructionRequest req, InstructionStepDto step, Set<String> warnings) {
        if (!"copy".equalsIgnoreCase(step.action())) {
            return Map.of();
        }
        SourceDataResolverService.SourceLookupResult result =
                sourceDataResolverService.resolve(req.planId(), req.lineKey(), step, req.instruction());
        if (!result.available()) {
            if (result.warning() != null && !result.warning().isBlank()) {
                warnings.add(result.warning());
            }
        }
        return result.months();
    }

    // ─── Month-map helpers ───────────────────────────────────────────────

    private static Map<String, Integer> copyMonths(Map<String, Integer> values) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String month : PlanMonthlyDetails.MONTH_KEYS) {
            m.put(month, values != null ? values.getOrDefault(month, 0) : 0);
        }
        return m;
    }

    private static Map<String, List<Integer>> deepCopyDaily(Map<String, List<Integer>> daily) {
        Map<String, List<Integer>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<Integer>> e : daily.entrySet()) {
            List<Integer> list = e.getValue();
            out.put(e.getKey(), list == null ? new ArrayList<>() : new ArrayList<>(list));
        }
        return out;
    }

    // ─── LLM JSON parsing ───────────────────────────────────────────────

    private List<InstructionStepDto> parseInstructionsList(JsonNode root) {
        if (root == null || root.isNull()) {
            return List.of();
        }
        if (root.isArray()) {
            return parseStepsArray(root);
        }
        if (root.isObject()) {
            JsonNode arr = root.get("instructions");
            if (arr != null && arr.isArray()) {
                return parseStepsArray(arr);
            }
            if (root.has("action")) {
                return List.of(parseStep(root));
            }
        }
        return List.of();
    }

    private List<InstructionStepDto> parseStepsArray(JsonNode arrayNode) {
        List<InstructionStepDto> out = new ArrayList<>();
        for (JsonNode el : arrayNode) {
            if (el != null && el.isObject() && el.has("action")) {
                out.add(parseStep(el));
            }
        }
        return out;
    }

    private InstructionStepDto parseStep(JsonNode n) {
        Object val = null;
        if (n.has("value") && !n.get("value").isNull()) {
            try {
                val = objectMapper.convertValue(n.get("value"), Object.class);
            } catch (IllegalArgumentException e) {
                val = n.get("value").asText();
            }
        }
        Integer sourceYear = optionalInt(n, "source_year");
        return new InstructionStepDto(
                textOrNull(n, "action"),
                val,
                textOrNull(n, "type"),
                null,
                sourceYear,
                textOrNull(n, "property_hint"),
                textOrNull(n, "period"),
                textOrNull(n, "summary"),
                optionalInt(n, "day_from"),
                optionalInt(n, "day_to"),
                textOrNull(n, "day_filter"),
                textOrNull(n, "source_row_label"));
    }

    private static Integer optionalInt(JsonNode n, String field) {
        if (n == null || !n.isObject() || !n.has(field) || n.get(field).isNull()) {
            return null;
        }
        return n.get(field).asInt();
    }

    // ─── LLM provider calls ─────────────────────────────────────────────

    private JsonNode fetchParsedJson(ParseInstructionRequest req) {
        String p = req.provider().toLowerCase(Locale.ROOT).trim();
        return switch (p) {
            case "openai" -> callOpenAi(req);
            case "gemini" -> callGemini(req);
            default -> throw new IllegalArgumentException("provider must be 'openai' or 'gemini'");
        };
    }

    private JsonNode callOpenAi(ParseInstructionRequest req) {
        if (openaiApiKey == null || openaiApiKey.isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY / planora.ai.openai.api-key is not configured");
        }
        int planYear = req.fiscalYear() != null ? req.fiscalYear() : Year.now().getValue();
        String planType = req.planType() != null && !req.planType().isBlank() ? req.planType() : "BUDGET";
        String userContent = "Plan year: %d\nPlan type: %s\nRow: \"%s\"\nInstruction: \"%s\"\n\nParse into the JSON object."
                .formatted(planYear, planType, req.rowLabel(), req.instruction());

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", "gpt-4o-mini");
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", SYSTEM_PROMPT);
        messages.addObject().put("role", "user").put("content", userContent);
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
            return normalizeRoot(objectMapper.readTree(content));
        } catch (Exception e) {
            throw new IllegalStateException("OpenAI response parse failed: " + e.getMessage(), e);
        }
    }

    private JsonNode callGemini(ParseInstructionRequest req) {
        if (geminiApiKey == null || geminiApiKey.isBlank()) {
            throw new IllegalStateException("GEMINI_API_KEY / planora.ai.gemini.api-key is not configured");
        }
        int planYear = req.fiscalYear() != null ? req.fiscalYear() : Year.now().getValue();
        String planType = req.planType() != null && !req.planType().isBlank() ? req.planType() : "BUDGET";
        String fullPrompt = SYSTEM_PROMPT + "\n\nPlan year: %d\nPlan type: %s\nRow: \"%s\"\nInstruction: \"%s\"\n\nParse into the JSON only."
                .formatted(planYear, planType, req.rowLabel(), req.instruction());

        ObjectNode body = objectMapper.createObjectNode();
        body.putArray("contents")
                .addObject().putArray("parts").addObject().put("text", fullPrompt);
        body.putObject("generationConfig")
                .put("responseMimeType", "application/json")
                .put("temperature", 0);

        String raw;
        try {
            raw = restClient.post()
                    .uri("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key={key}", geminiApiKey)
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
            return normalizeRoot(objectMapper.readTree(text));
        } catch (Exception e) {
            throw new IllegalStateException("Gemini response parse failed: " + e.getMessage(), e);
        }
    }

    private JsonNode normalizeRoot(JsonNode node) {
        if (node != null && node.isArray()) {
            ObjectNode wrap = objectMapper.createObjectNode();
            wrap.set("instructions", node);
            return wrap;
        }
        return node;
    }

    private static String textOrNull(JsonNode n, String field) {
        if (n == null || !n.isObject()) {
            return null;
        }
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        return v.asText();
    }
}
