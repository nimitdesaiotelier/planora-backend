package com.planora.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record AskPlanRequest(
        /** "openai" or "gemini" */
        @NotBlank String provider,
        /** Natural language query from user */
        @NotBlank String question,
        /** Base plan to query */
        @NotNull Long basePlanId,
        /** Optional plan for comparison */
        Long comparePlanId,
        /** Optional year for compare plan discovery (e.g. Budget 2025) */
        Integer comparePlanYear,
        /** Optional compare plan type (BUDGET|FORECAST|WHAT_IF) */
        String comparePlanType,
        /** Include actuals in comparison result */
        Boolean includeActuals,
        /** Optional year for actuals lookup override */
        Integer actualYear,
        /** Optional hard limit for top results */
        @Min(1) @Max(100) Integer topN,
        /** Optional period override: Jan..Dec, Q1..Q4, full_year */
        String period,
        /** Optional line type filter: Revenue|Expense|Statistics (single value compatibility) */
        String lineType,
        /** Optional multi-select line type filter */
        List<String> lineTypes,
        /** Optional explicit search text override */
        String searchText,
        /** Optional category filter (contains, case-insensitive) */
        String category) {}
