package com.planora.web.dto;

import com.planora.domain.PlanMonthlyDetails;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

public record ParseInstructionRequest(
        /** "openai" or "gemini" */
        @NotBlank String provider,
        @NotBlank String rowLabel,
        @NotBlank String instruction,
        /** Current plan row month totals (Jan–Dec) */
        @NotNull Map<String, Integer> values,
        /** Plan being edited — used with {@code lineKey} to load prior FY budget for {@code copy}+{@code ly_actual} */
        @NotNull Long planId,
        /** Stable row id within plan (matches {@link PlanMonthlyDetails#lineKey}) */
        @NotBlank String lineKey) {}
