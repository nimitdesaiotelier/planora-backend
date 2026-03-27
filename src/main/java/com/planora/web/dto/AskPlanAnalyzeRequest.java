package com.planora.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AskPlanAnalyzeRequest(
        /** "openai" or "gemini" — same provider as Ask Plan */
        @NotBlank String provider,
        /** Original user question for context */
        @NotBlank String question,
        /** Full ask-plan response including result rows shown in the UI */
        @NotNull AskPlanResponse response) {}
