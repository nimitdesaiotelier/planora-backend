package com.planora.web.dto;

import java.util.List;
import java.util.Map;

public record UpdateLineItemValuesRequest(
        /**
         * Month totals. Required when {@code dailyDetails} is null.
         * When {@code dailyDetails} is set, month columns are derived from daily sums and this map is ignored.
         */
        Map<String, Integer> values,
        /** When present, replaces stored daily breakdown; jan–dec totals are set from sums of these lists. */
        Map<String, List<Integer>> dailyDetails
) {}
