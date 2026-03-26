package com.planora.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record InstructionStepDto(
        String action,
        Object value,
        String type,
//        /** @deprecated Kept for backward compatibility; prefer {@link #sourceYear}. */
        Integer yearsAgo,
        /** Concrete source year for copy operations (e.g. 2025). Derived by LLM from current calendar year. */
        Integer sourceYear,
        String propertyHint,
        String period,
        String summary,
        /** 1-based calendar day within month; when set with {@link #dayTo}, only those days are changed. */
        Integer dayFrom,
        /** 1-based inclusive end day; defaults to {@link #dayFrom} when only one day. */
        Integer dayTo,
        /** Day-of-week filter: {@code "weekends"}, {@code "weekdays"}, or {@code null} for all days. */
        String dayFilter,
        /**
         * Human-readable label of the row to copy FROM within the same plan (e.g. "Banquet Revenue").
         * Only used when {@code type = "current_plan"}. Null means copy the same row.
         */
        String sourceRowLabel) {}
