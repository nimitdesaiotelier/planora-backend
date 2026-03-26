package com.planora.web.dto;

import java.util.List;

public record RegenerateDailyDetailsResponse(
        Long planId,
        int updatedCount,
        List<Long> updatedLineItemIds,
        String message) {}

