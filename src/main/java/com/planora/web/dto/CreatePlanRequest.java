package com.planora.web.dto;

import com.planora.enums.PlanType;
import jakarta.validation.constraints.NotNull;

public record CreatePlanRequest(
        @NotNull Long propertyId,
        @NotNull Integer fiscalYear,
        @NotNull PlanType planType,
        Long organizationId
) {}
