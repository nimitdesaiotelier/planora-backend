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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
 * <p>Year offset and property are derived from the step's {@code yearsAgo}
 * and {@code propertyHint} fields, with deterministic defaults.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SourceDataResolverService {

    private static final long DEFAULT_ORG_ID = 1L;

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
    public SourceLookupResult resolve(long currentPlanId, String lineKey, InstructionStepDto step) {
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
        int yearsAgo = step.yearsAgo() != null ? step.yearsAgo() : 0;
        int targetYear = currentPlan.getFiscalYear() - yearsAgo;
        Property targetProperty = resolveProperty(step.propertyHint(), currentPlan.getProperty());

        log.debug("Resolving copy: type={}, yearsAgo={}, targetYear={}, property={}",
                sourceType, yearsAgo, targetYear, targetProperty.getName());

        return switch (sourceType) {
            case "actuals" -> resolveActuals(targetYear, targetProperty, lineKey);
            case "budget" -> resolvePlanData(targetYear, targetProperty, PlanType.BUDGET, lineKey);
            case "forecast" -> resolvePlanData(targetYear, targetProperty, PlanType.FORECAST, lineKey);
            case "what_if" -> resolvePlanData(targetYear, targetProperty, PlanType.WHAT_IF, lineKey);
            default -> SourceLookupResult.unavailable(
                    "Unknown copy type: \"%s\". Use actuals, budget, forecast, or what_if.".formatted(step.type()));
        };
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

    private static String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            return "budget";
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
