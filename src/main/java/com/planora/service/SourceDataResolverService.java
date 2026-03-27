package com.planora.service;

import com.planora.domain.ActualsDetails;
import com.planora.domain.Plan;
import com.planora.domain.PlanMonthlyDetails;
import com.planora.enums.PlanStatus;
import com.planora.domain.Property;
import com.planora.enums.PlanType;
import com.planora.repo.ActualsDetailRepository;
import com.planora.repo.LineItemRepository;
import com.planora.repo.PlanRepository;
import com.planora.repo.PropertyRepository;
import com.planora.web.dto.InstructionStepDto;
import java.math.BigDecimal;
import java.time.Year;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Unified source-data resolver for all copy operations.
 *
 * <p>Given a parsed {@link InstructionStepDto} with {@code action=copy},
 * resolves the correct source data from either:
 * <ul>
 *   <li>{@code tbl_actuals_details}        — when type = "actuals"</li>
 *   <li>{@code tbl_plan_monthly_details}   — when type = "budget", "forecast", or "what_if"</li>
 * </ul>
 *
 * <p>Year is derived with deterministic precedence:
 * LLM {@code sourceYear} → explicit 4-digit year from raw instruction → inferred from
 * instruction phrases (e.g. "last year" = plan fiscal year − 1) → plan fiscal year → current calendar year.
 *
 * <p>Copy type is derived with deterministic precedence:
 * LLM {@code type} (user-specified) → current plan's own {@link PlanType} (fallback).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SourceDataResolverService {

    private static final long DEFAULT_ORG_ID = 1L;

    /** Detects an explicit 4-digit year in copy phrasing, e.g. "Copy from 2026 actuals" → 2026. */
    private static final Pattern EXPLICIT_COPY_FROM_YEAR =
            Pattern.compile("(?i)copy\\s+from\\s+(?:the\\s+(?:year\\s+)?)?(20\\d{2})\\b");

    private static Integer explicitCopySourceYear(String instruction) {
        if (instruction == null || instruction.isBlank()) {
            return null;
        }
        Matcher m = EXPLICIT_COPY_FROM_YEAR.matcher(instruction.trim());
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    /**
     * Resolves relative year phrases against the plan's fiscal year (not the current calendar year).
     * Falls back to {@code Year.now()} only when {@code planFiscalYear} is null.
     */
    private static Integer inferYearFromInstruction(String instruction, Integer planFiscalYear) {
        if (instruction == null || instruction.isBlank()) {
            return null;
        }
        String t = instruction.toLowerCase(Locale.ROOT);
        int baseYear = planFiscalYear != null ? planFiscalYear : Year.now().getValue();

        if (t.contains("year before last") || t.contains("last to last year")) {
            return baseYear - 2;
        }
        if (t.contains("last year") || t.contains("previous year") || t.contains("prior year")) {
            return baseYear - 1;
        }
        if (t.contains("this year") || t.contains("current year")) {
            return baseYear;
        }

        Matcher nYearsAgo = Pattern.compile("\\b(\\d+)\\s+years?\\s+ago\\b").matcher(t);
        if (nYearsAgo.find()) {
            return baseYear - Integer.parseInt(nYearsAgo.group(1));
        }
        return null;
    }

    private final PlanRepository planRepository;
    private final LineItemRepository lineItemRepository;
    private final ActualsDetailRepository actualsDetailRepository;
    private final PropertyRepository propertyRepository;

    public record SourceLookupResult(
            boolean available,
            Map<String, Integer> months,
            String warning) {

        static SourceLookupResult success(Map<String, Integer> months) {
            return new SourceLookupResult(true, months, null);
        }

        static SourceLookupResult unavailable(String warning) {
            return new SourceLookupResult(false, Map.of(), warning);
        }
    }

    @Transactional(readOnly = true)
    public SourceLookupResult resolve(long currentPlanId, String lineKey, InstructionStepDto step, String instruction) {
        if (!"copy".equalsIgnoreCase(step.action())) {
            return SourceLookupResult.success(Map.of());
        }

        Plan currentPlan = planRepository.findById(currentPlanId).orElse(null);
        if (currentPlan == null) {
            return SourceLookupResult.unavailable("Current plan not found.");
        }
        if (lineKey == null || lineKey.isBlank()) {
            return SourceLookupResult.unavailable("Line key is missing.");
        }

        String sourceType = normalizeType(step.type());

        // ── current_plan: cross-row copy within the same plan ──────────────
        if ("current_plan".equals(sourceType)) {
            return resolveCurrentPlanRow(currentPlanId, step.sourceRowLabel(), lineKey);
        }

        // ── If LLM didn't specify a type, fall back to the current plan's own type ──
        if (sourceType == null) {
            sourceType = planTypeToSourceType(currentPlan.getPlanType());
            log.debug("LLM returned null type — falling back to current plan type: {}", sourceType);
        }

        // ── external source: actuals / budget / forecast / what_if ─────────
        Integer planFiscalYear = currentPlan.getFiscalYear();
        Integer llmSourceYear = step.sourceYear();
        Integer explicitYear = explicitCopySourceYear(instruction);
        Integer inferredYear = inferYearFromInstruction(instruction, planFiscalYear);
        int targetYear =
                llmSourceYear != null
                        ? llmSourceYear
                        : explicitYear != null
                                ? explicitYear
                                : inferredYear != null
                                        ? inferredYear
                                        : planFiscalYear != null
                                                ? planFiscalYear
                                                : Year.now().getValue();
        Property targetProperty = resolveProperty(step.propertyHint(), currentPlan.getProperty());

        log.debug(
                "Resolving copy: type={}, llmSourceYear={}, explicitYearFromInstruction={}, inferredYearFromInstruction={}, targetYear={}, property={}",
                sourceType,
                llmSourceYear,
                explicitYear,
                inferredYear,
                targetYear,
                targetProperty.getName());

        return switch (sourceType) {
            case "actuals" -> resolveActuals(targetYear, targetProperty, lineKey);
            case "budget" -> resolvePlanData(targetYear, targetProperty, PlanType.BUDGET, lineKey);
            case "forecast" -> resolvePlanData(targetYear, targetProperty, PlanType.FORECAST, lineKey);
            case "what_if" -> resolvePlanData(targetYear, targetProperty, PlanType.WHAT_IF, lineKey);
            default -> SourceLookupResult.unavailable(
                    "Unknown copy type: \"%s\". Use actuals, budget, forecast, what_if, or current_plan.".formatted(step.type()));
        };
    }

    // ─── Current-plan cross-row copy ─────────────────────────────────────

    /**
     * Copies from a different row within the same plan.
     * Matches by {@code sourceRowLabel} (fuzzy label match), falls back to the request's own {@code lineKey}.
     */
    private SourceLookupResult resolveCurrentPlanRow(long planId, String sourceRowLabel, String fallbackLineKey) {
        String targetLineKey = fallbackLineKey;

        if (sourceRowLabel != null && !sourceRowLabel.isBlank()) {
            String hint = normalize(sourceRowLabel);
            List<PlanMonthlyDetails> rows = lineItemRepository.findByPlan_Id(planId);

            // exact label match first
            Optional<PlanMonthlyDetails> exact = rows.stream()
                    .filter(r -> normalize(r.getLabel()).equals(hint))
                    .findFirst();
            if (exact.isPresent()) {
                targetLineKey = exact.get().getLineKey();
            } else {
                // partial / contains match
                Optional<PlanMonthlyDetails> partial = rows.stream()
                        .filter(r -> {
                            String lab = normalize(r.getLabel());
                            return !lab.isEmpty() && (lab.contains(hint) || hint.contains(lab));
                        })
                        .findFirst();
                if (partial.isPresent()) {
                    targetLineKey = partial.get().getLineKey();
                } else {
                    return SourceLookupResult.unavailable(
                            "No row matching \"%s\" was found in the current plan.".formatted(sourceRowLabel));
                }
            }
        }

        log.debug("Resolving current_plan copy: sourceRowLabel={}, resolvedLineKey={}", sourceRowLabel, targetLineKey);

        Optional<PlanMonthlyDetails> lineItem = lineItemRepository.findByPlan_IdAndLineKey(planId, targetLineKey);
        if (lineItem.isEmpty()) {
            return SourceLookupResult.unavailable(
                    "Row \"%s\" was not found in the current plan.".formatted(targetLineKey));
        }
        return SourceLookupResult.success(lineItem.get().toValuesMap());
    }

    // ─── Actuals (tbl_actuals_details) ───────────────────────────────────

    private SourceLookupResult resolveActuals(int year, Property property, String lineKey) {
        Optional<ActualsDetails> actuals = actualsDetailRepository
                .findByCoaCodeAndYearAndProperty_IdAndOrganizationId(
                        lineKey, year, property.getId(), DEFAULT_ORG_ID);
        if (actuals.isEmpty()) {
            return SourceLookupResult.unavailable(
                    "Actuals data is not available for %s FY %d.".formatted(property.getName(), year));
        }
        return SourceLookupResult.success(actualsToMonthMap(actuals.get()));
    }

    // ─── Plan data (tbl_plan_monthly_details) ────────────────────────────

    private SourceLookupResult resolvePlanData(int year, Property property, PlanType planType, String lineKey) {
        Optional<Plan> sourcePlan = planRepository.findFirstByProperty_IdAndFiscalYearAndPlanTypeAndStatusOrderByIdAsc(
                property.getId(), year, planType, PlanStatus.ACTIVE);
        if (sourcePlan.isEmpty()) {
            return SourceLookupResult.unavailable(
                    "%s plan data is not available for %s FY %d."
                            .formatted(planType.name(), property.getName(), year));
        }
        Optional<PlanMonthlyDetails> lineItem =
                lineItemRepository.findByPlan_IdAndLineKey(sourcePlan.get().getId(), lineKey);
        if (lineItem.isEmpty()) {
            return SourceLookupResult.unavailable(
                    "%s plan line item is not available for %s FY %d."
                            .formatted(planType.name(), property.getName(), year));
        }
        return SourceLookupResult.success(lineItem.get().toValuesMap());
    }

    // ─── Property resolution ─────────────────────────────────────────────

    private Property resolveProperty(String hint, Property fallback) {
        if (hint == null || hint.isBlank()) {
            return fallback;
        }
        String normalizedHint = normalize(hint);
        List<Property> allProperties = propertyRepository.findAllByOrderByNameAsc();

        for (Property p : allProperties) {
            if (normalize(p.getName()).equals(normalizedHint)) {
                return p;
            }
        }
        for (Property p : allProperties) {
            if (normalize(p.getName()).contains(normalizedHint)
                    || normalizedHint.contains(normalize(p.getName()))) {
                return p;
            }
        }
        return fallback;
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    /**
     * Normalizes the LLM-provided type string.
     * Returns {@code null} when type is blank/null — callers must handle the fallback to plan context.
     */
    private static String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        String t = type.toLowerCase(Locale.ROOT).trim();
        if ("ly_actual".equals(t) || "ly_actuals".equals(t)) {
            return "actuals";
        }
        if ("ly_budget".equals(t) || "lly_budget".equals(t) || t.startsWith("fy_minus_")) {
            return "budget";
        }
        if ("plan_copy".equals(t)) {
            return "budget";
        }
        return t;
    }

    /**
     * Maps the current plan's {@link PlanType} enum to the copy source-type string.
     */
    private static String planTypeToSourceType(PlanType planType) {
        if (planType == null) {
            return "budget";
        }
        return switch (planType) {
            case BUDGET -> "budget";
            case FORECAST -> "forecast";
            case WHAT_IF -> "what_if";
        };
    }

    private static String normalize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static Map<String, Integer> actualsToMonthMap(ActualsDetails a) {
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
}
