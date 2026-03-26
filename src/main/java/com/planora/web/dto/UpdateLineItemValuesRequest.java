package com.planora.web.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

public record UpdateLineItemValuesRequest(
        @NotNull Map<String, Integer> values,
        /** When present, replaces stored daily breakdown (month → day values). */
        Map<String, List<Integer>> dailyDetails
) {}
