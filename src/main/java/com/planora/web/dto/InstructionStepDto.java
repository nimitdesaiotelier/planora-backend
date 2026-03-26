package com.planora.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record InstructionStepDto(
        String action,
        Object value,
        String type,
        String period,
        String summary) {}
