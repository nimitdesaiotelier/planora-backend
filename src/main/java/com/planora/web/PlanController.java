package com.planora.web;

import com.planora.service.PlanService;
import com.planora.web.dto.LineItemDto;
import com.planora.web.dto.PlanSummaryDto;
import com.planora.web.dto.UpdateLineItemValuesRequest;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
}
