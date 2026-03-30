package com.planora.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Excel export payload: primarily {@link #response}. Title rows and the download filename are resolved on the server
 * using {@code response.meta.basePlanId} and the plan/property tables. Optional fields below are used only when
 * the plan cannot be loaded (e.g. tests).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record AskPlanExcelExportRequest(
        AskPlanResponse response,
        String planHeadline,
        String planTypeLabel,
        String fiscalYearLabel,
        String propertyName,
        /** Optional AI analysis bullets (same as Ask Plan UI); placed below the data table. */
        List<String> analysisPoints) {}
