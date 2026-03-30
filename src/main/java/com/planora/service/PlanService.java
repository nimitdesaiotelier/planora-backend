package com.planora.service;

import com.planora.domain.ActualsDetails;
import com.planora.domain.Coa;
import com.planora.domain.Plan;
import com.planora.domain.PlanMonthlyDetails;
import com.planora.domain.Property;
import com.planora.enums.LineItemType;
import com.planora.enums.PlanInitMode;
import com.planora.enums.PlanStatus;
import com.planora.repo.ActualsDetailRepository;
import com.planora.repo.CoaRepository;
import com.planora.repo.LineItemRepository;
import com.planora.repo.PlanRepository;
import com.planora.repo.PropertyRepository;
import com.planora.web.dto.CreatePlanRequest;
import com.planora.web.dto.LineItemDto;
import com.planora.web.dto.PlanSummaryDto;
import com.planora.web.dto.RegenerateDailyDetailsResponse;
import com.planora.web.dto.UpdateLineItemValuesRequest;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PlanService {

    private static final long DEFAULT_ORG_ID = 1L;

    private final PlanRepository planRepository;
    private final LineItemRepository lineItemRepository;
    private final ActualsDetailRepository actualsDetailRepository;
    private final CoaRepository coaRepository;
    private final PropertyRepository propertyRepository;
    private final DailyDetailsSplitService dailyDetailsSplitService;

    @Transactional(readOnly = true)
    public List<PlanSummaryDto> listPlans(Long propertyId) {
        List<Plan> plans = propertyId == null
                ? planRepository.findByStatusOrderByIdAsc(PlanStatus.ACTIVE)
                : planRepository.findByProperty_IdAndStatusOrderByIdAsc(propertyId, PlanStatus.ACTIVE);
        return plans.stream()
                .map(this::toSummary)
                .sorted((a, b) -> Long.compare(a.id(), b.id()))
                .toList();
    }

    @Transactional
    public PlanSummaryDto createPlan(CreatePlanRequest req) {
        long organizationId = req.organizationId() == null ? DEFAULT_ORG_ID : req.organizationId();
        Property property = propertyRepository.findById(req.propertyId())
                .orElseThrow(() -> new EntityNotFoundException("Property not found: " + req.propertyId()));

        planRepository
                .findFirstByProperty_IdAndFiscalYearAndPlanTypeAndStatusOrderByIdAsc(
                        req.propertyId(), req.fiscalYear(), req.planType(), PlanStatus.ACTIVE)
                .ifPresent(existing -> {
                    String message = "An active %s plan already exists for %s FY %d."
                            .formatted(req.planType().name(), property.getName(), req.fiscalYear());
                    throw new DuplicateActivePlanException(message, existing.getId());
                });

        Plan p = new Plan();
        p.setName("%s FY %d - %s".formatted(
                req.planType().name(), req.fiscalYear(), property.getName()));
        p.setPlanType(req.planType());
        p.setFiscalYear(req.fiscalYear());
        p.setProperty(property);
        p.setStatus(PlanStatus.ACTIVE);

        List<Coa> coas = coaRepository.findByProperty_IdAndOrganizationIdOrderByCoaCodeAsc(
                req.propertyId(), organizationId);
        for (Coa coa : coas) {
            PlanMonthlyDetails item = new PlanMonthlyDetails();
            item.setPlan(p);
            item.setCoaId(coa.getId());
            item.setCoaCode(coa.getCoaCode());
            item.setCoaName(coa.getCoaName());
            item.setLineKey(coa.getCoaCode());
            item.setDepartment(coa.getDepartment());
            item.setType(coa.getLineItemType());
            item.setCategory(coa.getLineItemType().name());
            item.setLabel(coa.getCoaName());
            p.getLineItems().add(item);
        }
        Plan saved = planRepository.save(p);
        applyInitialization(req, saved);
        return toSummary(saved);
    }

    /**
     * Optionally copies Jan–Dec (and daily breakdown when present) from a source plan onto the new plan’s
     * lines, matched by {@link PlanMonthlyDetails#getLineKey()}.
     */
    private void applyInitialization(CreatePlanRequest req, Plan newPlan) {
        PlanInitMode mode = req.initMode() == null ? PlanInitMode.NONE : req.initMode();
        if (mode == PlanInitMode.NONE) {
            return;
        }
        if (mode == PlanInitMode.FROM_ACTUALS) {
            copyLineValuesFromActuals(req, newPlan);
            return;
        }
        Plan source = resolveSourcePlan(req, newPlan);
        copyLineValuesFromPlan(newPlan.getId(), source.getId());
    }

    /**
     * Fills month values (and regenerated daily split for the new plan fiscal year) from uploaded actuals,
     * matched by COA / {@link PlanMonthlyDetails#getLineKey()}.
     */
    private void copyLineValuesFromActuals(CreatePlanRequest req, Plan newPlan) {
        if (req.sourceYear() == null) {
            throw new IllegalArgumentException("sourceYear is required when copying from actuals.");
        }
        long organizationId = req.organizationId() == null ? DEFAULT_ORG_ID : req.organizationId();
        int sourceYear = req.sourceYear();
        Long propertyId = req.propertyId();
        List<ActualsDetails> rows = actualsDetailRepository
                .findByYearAndProperty_IdAndOrganizationIdOrderByCoaCodeAsc(sourceYear, propertyId, organizationId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException(
                    "No actuals found for year " + sourceYear + ". Upload actuals for that year first.");
        }
        Map<String, ActualsDetails> byCoa = rows.stream()
                .collect(Collectors.toMap(ActualsDetails::getCoaCode, a -> a, (a, b) -> a));
        List<PlanMonthlyDetails> targets = lineItemRepository.findByPlan_Id(newPlan.getId());
        int fiscalYear = newPlan.getFiscalYear();
        for (PlanMonthlyDetails t : targets) {
            ActualsDetails a = byCoa.get(t.getLineKey());
            if (a != null) {
                Map<String, Integer> months = actualsToMonthMap(a);
                t.applyValuesMap(months);
                t.applyDailyDetailsMap(
                        dailyDetailsSplitService.buildDailyFromMonthly(months, fiscalYear, t.getType()));
            }
        }
        lineItemRepository.saveAll(targets);
    }

    private Plan resolveSourcePlan(CreatePlanRequest req, Plan newPlan) {
        Long propertyId = req.propertyId();
        PlanInitMode mode = req.initMode() == null ? PlanInitMode.NONE : req.initMode();
        return switch (mode) {
            case NONE -> throw new IllegalStateException("resolveSourcePlan called with NONE");
            case LAST_YEAR -> planRepository
                    .findFirstByProperty_IdAndFiscalYearAndPlanTypeAndStatusOrderByIdAsc(
                            propertyId, req.fiscalYear() - 1, req.planType(), PlanStatus.ACTIVE)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "No active plan found for the previous year to copy from. Start empty or pick another source."));
            case FROM_YEAR -> {
                if (req.sourceYear() == null) {
                    throw new IllegalArgumentException("sourceYear is required when initializing from a year.");
                }
                if (Objects.equals(req.sourceYear(), req.fiscalYear())) {
                    throw new IllegalArgumentException("Source year must be different from the new plan's year.");
                }
                yield planRepository
                        .findFirstByProperty_IdAndFiscalYearAndPlanTypeAndStatusOrderByIdAsc(
                                propertyId, req.sourceYear(), req.planType(), PlanStatus.ACTIVE)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "No active plan found for year " + req.sourceYear() + " to copy from."));
            }
            case FROM_PLAN -> {
                if (req.sourcePlanId() == null) {
                    throw new IllegalArgumentException("sourcePlanId is required when copying from a plan.");
                }
                Plan src = planRepository
                        .findByIdAndStatus(req.sourcePlanId(), PlanStatus.ACTIVE)
                        .orElseThrow(() -> new EntityNotFoundException("Source plan not found: " + req.sourcePlanId()));
                if (!src.getProperty().getId().equals(propertyId)) {
                    throw new IllegalArgumentException("Source plan must belong to the same property.");
                }
                if (src.getId().equals(newPlan.getId())) {
                    throw new IllegalArgumentException("Cannot copy from the plan being created.");
                }
                yield src;
            }
            case FROM_ACTUALS -> throw new IllegalStateException("FROM_ACTUALS is handled in applyInitialization");
        };
    }

    private void copyLineValuesFromPlan(Long targetPlanId, Long sourcePlanId) {
        Map<String, PlanMonthlyDetails> sourceByKey = lineItemRepository.findByPlan_Id(sourcePlanId).stream()
                .collect(Collectors.toMap(PlanMonthlyDetails::getLineKey, li -> li, (a, b) -> a));
        List<PlanMonthlyDetails> targets = lineItemRepository.findByPlan_Id(targetPlanId);
        for (PlanMonthlyDetails t : targets) {
            PlanMonthlyDetails s = sourceByKey.get(t.getLineKey());
            if (s != null) {
                t.applyValuesMap(new LinkedHashMap<>(s.toValuesMap()));
                Map<String, List<Integer>> dd = s.getDailyDetails();
                if (dd != null && !dd.isEmpty()) {
                    t.applyDailyDetailsMap(dd);
                }
            }
        }
        lineItemRepository.saveAll(targets);
    }

    @Transactional
    public void softDeletePlan(Long planId) {
        Plan plan = planRepository.findByIdAndStatus(planId, PlanStatus.ACTIVE)
                .orElseThrow(() -> new EntityNotFoundException("Plan not found: " + planId));
        plan.setStatus(PlanStatus.DELETED);
        planRepository.save(plan);
    }

    @Transactional(readOnly = true)
    public List<LineItemDto> listLineItems(Long planId) {
        Plan plan = planRepository.findByIdAndStatus(planId, PlanStatus.ACTIVE)
                .orElseThrow(() -> new EntityNotFoundException("Plan not found: " + planId));

        Map<String, ActualsDetails> actualsByCoa = actualsDetailRepository
                .findByYearAndProperty_IdAndOrganizationIdOrderByCoaCodeAsc(
                        plan.getFiscalYear(), plan.getProperty().getId(), DEFAULT_ORG_ID)
                .stream()
                .collect(Collectors.toMap(ActualsDetails::getCoaCode, a -> a, (a, b) -> a));

        return lineItemRepository.findByPlan_Id(planId).stream()
                .sorted(Comparator
                        .comparing((PlanMonthlyDetails li) -> lineTypeOrder(li.getType()))
                        .thenComparing(PlanMonthlyDetails::getLabel, String.CASE_INSENSITIVE_ORDER))
                .map(li -> toLineItemDto(li, actualsByCoa.get(li.getLineKey())))
                .toList();
    }

    @Transactional
    public LineItemDto updateLineItemValues(Long planId, Long lineItemId, UpdateLineItemValuesRequest body) {
        Plan plan = planRepository.findByIdAndStatus(planId, PlanStatus.ACTIVE)
                .orElseThrow(() -> new EntityNotFoundException("Plan not found: " + planId));
        PlanMonthlyDetails item = lineItemRepository
                .findByIdAndPlan_Id(lineItemId, planId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Line item %d not found for plan %d".formatted(lineItemId, planId)));
        if (body.dailyDetails() != null) {
            item.applyDailyDetailsMap(body.dailyDetails());
            applyMonthTotalsFromDailySums(item);
        } else {
            if (body.values() == null) {
                throw new IllegalArgumentException("values are required when dailyDetails is omitted");
            }
            item.applyValuesMap(body.values());
            Map<String, List<Integer>> regeneratedDaily = dailyDetailsSplitService.buildDailyFromMonthly(
                    item.toValuesMap(),
                    plan.getFiscalYear(),
                    item.getType());
            item.applyDailyDetailsMap(regeneratedDaily);
        }
        PlanMonthlyDetails saved = lineItemRepository.save(item);
        ActualsDetails ad = actualsDetailRepository
                .findByCoaCodeAndYearAndProperty_IdAndOrganizationId(
                        saved.getLineKey(), plan.getFiscalYear(), plan.getProperty().getId(), DEFAULT_ORG_ID)
                .orElse(null);
        return toLineItemDto(saved, ad);
    }

    @Transactional
    public RegenerateDailyDetailsResponse regenerateDailyDetails(Long planId, Long lineItemId) {
        Plan plan = planRepository.findById(planId)
                .orElseThrow(() -> new EntityNotFoundException("Plan not found: " + planId));

        List<PlanMonthlyDetails> items;
        if (lineItemId != null) {
            PlanMonthlyDetails one = lineItemRepository.findByIdAndPlan_Id(lineItemId, planId)
                    .orElseThrow(() -> new EntityNotFoundException(
                            "Line item %d not found for plan %d".formatted(lineItemId, planId)));
            items = List.of(one);
        } else {
            items = lineItemRepository.findByPlan_Id(planId);
        }

        for (PlanMonthlyDetails item : items) {
            Map<String, List<Integer>> daily = dailyDetailsSplitService.buildDailyFromMonthly(
                    item.toValuesMap(),
                    plan.getFiscalYear(),
                    item.getType());
            item.applyDailyDetailsMap(daily);
        }
        lineItemRepository.saveAll(items);

        List<Long> updatedIds = items.stream().map(PlanMonthlyDetails::getId).toList();
        String message = lineItemId == null
                ? "Regenerated daily details for " + items.size() + " line item(s)."
                : "Regenerated daily details for line item " + lineItemId + ".";
        return new RegenerateDailyDetailsResponse(planId, items.size(), updatedIds, message);
    }

    private PlanSummaryDto toSummary(Plan p) {
        return new PlanSummaryDto(
                p.getId(),
                p.getName(),
                p.getPlanType(),
                p.getFiscalYear(),
                p.getProperty().getId(),
                p.getProperty().getName());
    }

    /** Sets jan–dec columns from the sum of each month’s daily list. */
    private static void applyMonthTotalsFromDailySums(PlanMonthlyDetails item) {
        Map<String, List<Integer>> d = item.getDailyDetails();
        if (d == null) {
            return;
        }
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (String month : PlanMonthlyDetails.MONTH_KEYS) {
            List<Integer> list = d.get(month);
            int sum = 0;
            if (list != null) {
                for (Integer v : list) {
                    sum += v != null ? v : 0;
                }
            }
            totals.put(month, sum);
        }
        item.applyValuesMap(totals);
    }

    private static int lineTypeOrder(LineItemType t) {
        return switch (t) {
            case Revenue -> 0;
            case Statistics -> 1;
            case Expense -> 2;
        };
    }

    private LineItemDto toLineItemDto(PlanMonthlyDetails li, ActualsDetails actuals) {
        Map<String, List<Integer>> daily = li.getDailyDetails() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(li.getDailyDetails());
        return new LineItemDto(
                String.valueOf(li.getId()),
                li.getCoaCode() == null || li.getCoaCode().isBlank() ? li.getLineKey() : li.getCoaCode(),
                li.getCoaName() == null || li.getCoaName().isBlank() ? li.getLabel() : li.getCoaName(),
                li.getLineKey(),
                li.getDepartment(),
                li.getType().name(),
                li.getCategory(),
                li.getLabel(),
                li.toValuesMap(),
                daily,
                actuals == null ? Map.of() : actualsToMonthMap(actuals));
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
