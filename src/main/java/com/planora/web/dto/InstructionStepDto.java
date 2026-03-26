package com.planora.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record InstructionStepDto(
        String action,
        Object value,
        String type,
        Integer yearsAgo,
        String propertyHint,
        String period,
        String summary,
        /** 1-based calendar day within month; when set with {@link #dayTo}, only those days are changed. */
        Integer dayFrom,
        /** 1-based inclusive end day; defaults to {@link #dayFrom} when only one day. */
        Integer dayTo) {}
