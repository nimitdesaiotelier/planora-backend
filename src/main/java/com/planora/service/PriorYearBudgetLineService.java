package com.planora.service;

import com.planora.domain.LineItem;
import com.planora.domain.Plan;
import com.planora.domain.PlanType;
import com.planora.repo.LineItemRepository;
import com.planora.repo.PlanRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Last year" / LY amounts for AI copy rules = prior fiscal year's operating {@link PlanType#BUDGET}
 * for the same property and {@code line_key} (not uploaded actuals).
 */
@Service
@RequiredArgsConstructor
public class PriorYearBudgetLineService {

    private final PlanRepository planRepository;
    private final LineItemRepository lineItemRepository;

    @Transactional(readOnly = true)
    public Map<String, Integer> getPriorYearBudgetMonths(long currentPlanId, String lineKey) {
        Plan current = planRepository.findById(currentPlanId).orElse(null);
        if (current == null || lineKey == null || lineKey.isBlank()) {
            return zeroMonths();
        }
        int priorYear = current.getFiscalYear() - 1;
        if (priorYear < 1900) {
            return zeroMonths();
        }
        Long propertyId = current.getProperty().getId();
        Optional<Plan> priorBudget = planRepository.findFirstByProperty_IdAndFiscalYearAndPlanTypeOrderByIdAsc(
                propertyId, priorYear, PlanType.BUDGET);
        if (priorBudget.isEmpty()) {
            return zeroMonths();
        }
        return lineItemRepository
                .findByPlan_IdAndLineKey(priorBudget.get().getId(), lineKey)
                .map(LineItem::toValuesMap)
                .orElseGet(PriorYearBudgetLineService::zeroMonths);
    }

    private static Map<String, Integer> zeroMonths() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String month : LineItem.MONTH_KEYS) {
            m.put(month, 0);
        }
        return m;
    }
}
