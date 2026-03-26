package com.planora.service;

import com.planora.domain.ActualsDetail;
import com.planora.domain.LineItem;
import com.planora.domain.LineItemType;
import com.planora.domain.Plan;
import com.planora.repo.ActualsDetailRepository;
import com.planora.repo.LineItemRepository;
import com.planora.repo.PlanRepository;
import com.planora.web.dto.LineItemDto;
import com.planora.web.dto.PlanSummaryDto;
import com.planora.web.dto.UpdateLineItemValuesRequest;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    @Transactional(readOnly = true)
    public List<PlanSummaryDto> listPlans(Long propertyId) {
        List<Plan> plans = propertyId == null
                ? planRepository.findAll()
                : planRepository.findByProperty_IdOrderByIdAsc(propertyId);
        return plans.stream()
                .map(this::toSummary)
                .sorted((a, b) -> Long.compare(a.id(), b.id()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<LineItemDto> listLineItems(Long planId) {
        Plan plan = planRepository.findById(planId).orElseThrow(() -> new EntityNotFoundException("Plan not found: " + planId));

        Map<String, ActualsDetail> actualsByCoa = actualsDetailRepository
                .findByYearAndProperty_IdAndOrganizationIdOrderByCoaCodeAsc(
                        plan.getFiscalYear(), plan.getProperty().getId(), DEFAULT_ORG_ID)
                .stream()
                .collect(Collectors.toMap(ActualsDetail::getCoaCode, a -> a, (a, b) -> a));

        return lineItemRepository.findByPlan_Id(planId).stream()
                .sorted(Comparator
                        .comparing((LineItem li) -> lineTypeOrder(li.getType()))
                        .thenComparing(LineItem::getLabel, String.CASE_INSENSITIVE_ORDER))
                .map(li -> toLineItemDto(li, actualsByCoa.get(li.getLineKey())))
                .toList();
    }

    @Transactional
    public LineItemDto updateLineItemValues(Long planId, Long lineItemId, UpdateLineItemValuesRequest body) {
        Plan plan = planRepository.findById(planId).orElseThrow(() -> new EntityNotFoundException("Plan not found: " + planId));
        LineItem item = lineItemRepository
                .findByIdAndPlan_Id(lineItemId, planId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Line item %d not found for plan %d".formatted(lineItemId, planId)));
        item.applyValuesMap(body.values());
        if (body.dailyDetails() != null) {
            item.applyDailyDetailsMap(body.dailyDetails());
        }
        LineItem saved = lineItemRepository.save(item);
        ActualsDetail ad = actualsDetailRepository
                .findByCoaCodeAndYearAndProperty_IdAndOrganizationId(
                        saved.getLineKey(), plan.getFiscalYear(), plan.getProperty().getId(), DEFAULT_ORG_ID)
                .orElse(null);
        return toLineItemDto(saved, ad);
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

    private static int lineTypeOrder(LineItemType t) {
        return switch (t) {
            case Revenue -> 0;
            case Statistics -> 1;
            case Expense -> 2;
        };
    }

    private LineItemDto toLineItemDto(LineItem li, ActualsDetail actuals) {
        Map<String, List<Integer>> daily = li.getDailyDetails() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(li.getDailyDetails());
        return new LineItemDto(
                String.valueOf(li.getId()),
                li.getLineKey(),
                li.getDepartment(),
                li.getType().name(),
                li.getCategory(),
                li.getLabel(),
                li.toValuesMap(),
                daily,
                actuals == null ? Map.of() : actualsToMonthMap(actuals));
    }

    private static Map<String, Integer> actualsToMonthMap(ActualsDetail a) {
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
