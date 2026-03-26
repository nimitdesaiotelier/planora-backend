package com.planora.web;

import com.planora.service.PlanService;
import com.planora.web.dto.CreatePlanRequest;
import com.planora.web.dto.LineItemDto;
import com.planora.web.dto.PlanSummaryDto;
import com.planora.web.dto.RegenerateDailyDetailsResponse;
import com.planora.web.dto.UpdateLineItemValuesRequest;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/plans")
@RequiredArgsConstructor
public class PlanController {

    private final PlanService planService;

    @GetMapping
    public List<PlanSummaryDto> listPlans(@RequestParam(required = false) Long propertyId) {
        return planService.listPlans(propertyId);
    }

    @PostMapping
    public ResponseEntity<PlanSummaryDto> createPlan(@Valid @RequestBody CreatePlanRequest body) {
        return ResponseEntity.ok(planService.createPlan(body));
    }

    @DeleteMapping("/{planId}")
    public ResponseEntity<Void> deletePlan(@PathVariable Long planId) {
        planService.softDeletePlan(planId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{planId}/line-items")
    public List<LineItemDto> listLineItems(@PathVariable Long planId) {
        return planService.listLineItems(planId);
    }

    @PatchMapping("/{planId}/line-items/{lineItemId}")
    public ResponseEntity<LineItemDto> patchLineItemValues(
            @PathVariable Long planId,
            @PathVariable Long lineItemId,
            @Valid @RequestBody UpdateLineItemValuesRequest body
    ) {
        return ResponseEntity.ok(planService.updateLineItemValues(planId, lineItemId, body));
    }

    /**
     * Rebuilds daily_details from Jan-Dec totals for one line item or entire plan.
     *
     * Postman examples:
     * - POST /api/plans/123/daily-details/regenerate
     * - POST /api/plans/123/daily-details/regenerate?lineItemId=456
     */
    @PostMapping("/{planId}/daily-details/regenerate")
    public ResponseEntity<RegenerateDailyDetailsResponse> regenerateDailyDetails(
            @PathVariable Long planId,
            @RequestParam(required = false) Long lineItemId
    ) {
        return ResponseEntity.ok(planService.regenerateDailyDetails(planId, lineItemId));
    }
}
