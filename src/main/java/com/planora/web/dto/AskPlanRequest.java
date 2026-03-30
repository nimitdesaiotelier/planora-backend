package com.planora.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record AskPlanRequest(
        /** "openai" or "gemini" */
        @NotBlank String provider,
        /** Natural language query from the user */
        @NotBlank String question,
        /** Base plan to query (property context; may be overridden by queryPlanYear) */
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
        String category,
        /** Optional department filter (contains, case-insensitive) */
        String department,
        /** Optional COA code filter (contains, case-insensitive) */
        String coaCode,
        /** Optional COA name filter (contains, case-insensitive) */
        String coaName,
        /** Optional: any | zero | non_zero — base plan values over period or totalFilterMonths */
        String totalFilter,
        /** Optional month keys for total filter sum (e.g. Feb, Apr); overrides period when non-empty */
        List<String> totalFilterMonths,
        /** When set, use this fiscal year's plan as the data source instead of basePlanId (same property). */
        Integer queryPlanYear,
        /** BUDGET | FORECAST | WHAT_IF — pairs with queryPlanYear */
        String queryPlanType,
        /** Optional aggregation: grand | department | category | type | coa_code | coa_name (overrides AI intent when set). */
        String groupBy) {}
