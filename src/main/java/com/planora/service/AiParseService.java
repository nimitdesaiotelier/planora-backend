package com.planora.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.planora.domain.PlanMonthlyDetails;
import com.planora.web.dto.InstructionStepDto;
import com.planora.web.dto.ParseInstructionRequest;
import com.planora.web.dto.ParsedInstructionDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
@RequiredArgsConstructor
public class AiParseService {

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
                  "type": "<percentage|absolute|ly_actual|plan_copy|null>",
                  "period": "<Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec|Q1|Q2|Q3|Q4|full_year|null>",
                  "summary": "<optional per-step note>"
                }
              ]
            }

            Rules:
            - If the user gives MULTIPLE independent changes (e.g. "Jan +2000 and Feb +10%"), use MULTIPLE objects in "instructions" — one object per change, each with its own period, type, and value.
            - Do not merge different periods into one step. Do not pick one quarter for the whole prompt unless the user only mentioned one period.
            - Single change still uses an array with one element.
            - action: increase, decrease, set, or copy
            - type ly_actual means copy prior fiscal year BUDGET for this row (server applies it)
            - period: one month, Q1–Q4, or full_year

            Example for "increase Jan by $2000 and Feb by 10%":
            {"summary":"Raise Jan by $2,000 and Feb by 10%.","instructions":[
              {"action":"increase","value":2000,"type":"absolute","period":"Jan"},
              {"action":"increase","value":10,"type":"percentage","period":"Feb"}
            ]}

            Respond with only valid JSON, no markdown.""";

    private final ObjectMapper objectMapper;
    private final PriorYearBudgetLineService priorYearBudgetLineService;
    private final RestClient restClient = RestClient.create();

    @Value("${planora.ai.openai.api-key:}")
    private String openaiApiKey;

    @Value("${planora.ai.gemini.api-key:}")
    private String geminiApiKey;

    public ParsedInstructionDto parse(ParseInstructionRequest req) {
        JsonNode root = fetchParsedJson(req);
        String summary = textOrNull(root, "summary");
        List<InstructionStepDto> steps = parseInstructionsList(root);

        if (steps.isEmpty()) {
            throw new IllegalStateException("AI returned no instructions — try rephrasing.");
        }

        boolean needsPriorYear = steps.stream()
                .anyMatch(s -> "copy".equalsIgnoreCase(s.action()) && "ly_actual".equalsIgnoreCase(s.type()));
        Map<String, Integer> priorYearBudget =
                needsPriorYear ? priorYearBudgetLineService.getPriorYearBudgetMonths(req.planId(), req.lineKey()) : Map.of();

        Map<String, Integer> working = copyMonths(req.values());
        for (InstructionStepDto step : steps) {
            Map<String, Integer> priorForStep =
                    ("copy".equalsIgnoreCase(step.action()) && "ly_actual".equalsIgnoreCase(step.type()))
                            ? priorYearBudget
                            : Map.of();
            working = BudgetInstructionApplyService.apply(
                    working,
                    priorForStep,
                    step.action(),
                    step.type(),
                    step.period(),
                    BudgetInstructionApplyService.toDouble(step.value()));
        }

        String mergedSummary = summary;
        if (mergedSummary == null || mergedSummary.isBlank()) {
            mergedSummary = steps.stream()
                    .map(InstructionStepDto::summary)
                    .filter(s -> s != null && !s.isBlank())
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("Applied " + steps.size() + " change(s).");
        }

        return new ParsedInstructionDto(mergedSummary, steps, working);
    }

    private static Map<String, Integer> copyMonths(Map<String, Integer> values) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String month : PlanMonthlyDetails.MONTH_KEYS) {
            m.put(month, values != null ? values.getOrDefault(month, 0) : 0);
        }
        return m;
    }

    /**
     * Accepts: (1) object with "instructions" array, (2) legacy single-step object with "action",
     * (3) root JSON array of step objects (normalized).
     */
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
        return new InstructionStepDto(
                textOrNull(n, "action"),
                val,
                textOrNull(n, "type"),
                textOrNull(n, "period"),
                textOrNull(n, "summary"));
    }

    private JsonNode fetchParsedJson(ParseInstructionRequest req) {
        String p = req.provider().toLowerCase(Locale.ROOT).trim();
        return switch (p) {
            case "openai" -> parseOpenAiToJson(req);
            case "gemini" -> parseGeminiToJson(req);
            default -> throw new IllegalArgumentException("provider must be 'openai' or 'gemini'");
        };
    }

    private JsonNode parseOpenAiToJson(ParseInstructionRequest req) {
        if (openaiApiKey == null || openaiApiKey.isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY / planora.ai.openai.api-key is not configured");
        }
        String userContent = "Row: \"%s\"\nInstruction: \"%s\"\n\nParse into the JSON object."
                .formatted(req.rowLabel(), req.instruction());

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", "gpt-4o-mini");
        ArrayNode messages = body.putArray("messages");
        ObjectNode systemMsg = messages.addObject();
        systemMsg.put("role", "system");
        systemMsg.put("content", SYSTEM_PROMPT);
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", userContent);
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

    private JsonNode parseGeminiToJson(ParseInstructionRequest req) {
        if (geminiApiKey == null || geminiApiKey.isBlank()) {
            throw new IllegalStateException("GEMINI_API_KEY / planora.ai.gemini.api-key is not configured");
        }
        String fullPrompt = SYSTEM_PROMPT + "\n\nRow: \"%s\"\nInstruction: \"%s\"\n\nParse into the JSON only."
                .formatted(req.rowLabel(), req.instruction());

        ObjectNode body = objectMapper.createObjectNode();
        body.putArray("contents")
                .addObject().putArray("parts").addObject().put("text", fullPrompt);
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
            return normalizeRoot(objectMapper.readTree(text));
        } catch (Exception e) {
            throw new IllegalStateException("Gemini response parse failed: " + e.getMessage(), e);
        }
    }

    /** If the model returns a raw array of steps, wrap as { "instructions": [...] }. */
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
