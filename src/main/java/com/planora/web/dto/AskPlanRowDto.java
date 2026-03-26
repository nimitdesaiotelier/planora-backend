package com.planora.web.dto;

import java.util.Map;

public record AskPlanRowDto(
        String lineKey,
        String label,
        String type,
        String category,
        Map<String, Integer> baseValues,
        Integer baseTotal,
        Map<String, Integer> compareValues,
        Integer compareTotal,
        Integer deltaVsCompare,
        Map<String, Integer> actualValues,
        Integer actualTotal,
        Integer deltaVsActual) {}
