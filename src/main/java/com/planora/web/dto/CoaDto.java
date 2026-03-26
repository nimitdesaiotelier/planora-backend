package com.planora.web.dto;

import com.planora.enums.LineItemType;

public record CoaDto(
        Long id,
        String coaCode,
        String coaName,
        String department,
        LineItemType lineItemType,
        Long propertyId,
        String propertyName,
        Long organizationId
) {}
