package com.planora.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AskPlanResponse(
        String summary,
        String intent,
        Map<String, Object> appliedFilters,
        List<AskPlanRowDto> resultRows,
        Map<String, Object> meta) {}
