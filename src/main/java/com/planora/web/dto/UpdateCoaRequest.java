package com.planora.web.dto;

import com.planora.enums.LineItemType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateCoaRequest(
        @NotBlank @Size(max = 128) String coaCode,
        @NotBlank @Size(max = 512) String coaName,
        @NotBlank @Size(max = 255) String department,
        @NotNull LineItemType lineItemType
) {}
