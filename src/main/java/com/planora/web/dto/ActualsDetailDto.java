package com.planora.web.dto;

import java.math.BigDecimal;
import java.util.List;

public record ActualsDetailDto(
        Long id,
        String coaCode,
        String coaName,
        BigDecimal janValue,
        BigDecimal febValue,
        BigDecimal marValue,
        BigDecimal aprValue,
        BigDecimal mayValue,
        BigDecimal junValue,
        BigDecimal julValue,
        BigDecimal augValue,
        BigDecimal sepValue,
        BigDecimal octValue,
        BigDecimal novValue,
        BigDecimal decValue,
        List<BigDecimal> dailyDetails,
        Integer year,
        Long propertyId,
        String propertyName,
        Long organizationId
) {}
