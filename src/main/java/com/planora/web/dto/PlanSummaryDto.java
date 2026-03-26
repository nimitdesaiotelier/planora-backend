package com.planora.web.dto;

import com.planora.enums.PlanType;

public record PlanSummaryDto(
        Long id,
        String name,
        PlanType planType,
        Integer fiscalYear,
        Long propertyId,
        String propertyName
) {}
