package com.planora.web.dto;

import java.util.List;
import java.util.Map;

public record LineItemDto(
        String id,
        String coaCode,
        String coaName,
        String lineKey,
        String department,
        String type,
        String category,
        String label,
        Map<String, Integer> values,
        Map<String, List<Integer>> dailyDetails,
        /** Monthly actuals from tbl_actuals_details (coa_code = line_key); used instead of stored LY on the line */
        Map<String, Integer> actualsValues
) {}
