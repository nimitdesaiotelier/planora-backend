package com.planora.web.dto;

import java.util.Map;

public record AskPlanRowDto(
        String lineKey,
        String label,
        String type,
        String department,
        String category,
        Map<String, Integer> baseValues,
        Integer baseTotal,
        Integer periodTotal,
        Map<String, Integer> compareValues,
        Integer compareTotal,
        Integer comparePeriodTotal,
        Integer deltaVsCompare,
        Map<String, Integer> actualValues,
        Integer actualTotal,
        Integer actualPeriodTotal,
        Integer deltaVsActual) {}
