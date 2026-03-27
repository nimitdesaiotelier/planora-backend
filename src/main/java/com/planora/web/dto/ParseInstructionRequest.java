package com.planora.web.dto;

import com.planora.domain.PlanMonthlyDetails;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

public record ParseInstructionRequest(
        /** "openai" or "gemini" */
        @NotBlank String provider,
        @NotBlank String rowLabel,
        @NotBlank String instruction,
        /** Current plan row month totals (Jan–Dec) */
        @NotNull Map<String, Integer> values,
        /** Plan being edited — used with {@code lineKey} to resolve source data for copy operations */
        @NotNull Long planId,
        /** Stable row id within plan (matches {@link PlanMonthlyDetails#lineKey}) */
        @NotBlank String lineKey,
        /** Optional: current daily breakdown (for future day-level parsing). */
        Map<String, List<Integer>> dailyDetails,
        /** Plan fiscal year; with {@code lineItemType}, enables {@link ParsedInstructionDto#newDailyDetails}. */
        Integer fiscalYear,
        /** One of {@code Revenue}, {@code Expense}, {@code Statistics} — same as line item type. */
        String lineItemType,
        /** Current plan type: {@code BUDGET}, {@code FORECAST}, or {@code WHAT_IF}. Used as fallback when user doesn't specify a data type for copy operations. */
        String planType) {}
