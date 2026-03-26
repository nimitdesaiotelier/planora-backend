package com.planora.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ParsedInstructionDto(
        /** Overall description of all steps */
        String summary,
        /** One entry per distinct change (e.g. Jan +$2000 and Feb +10% → two objects) */
        List<InstructionStepDto> instructions,
        /** Month totals after applying every step in order */
        Map<String, Integer> newValues) {}
