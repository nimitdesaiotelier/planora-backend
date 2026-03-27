package com.planora.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Excel export payload: primarily {@link #response}. Title rows and the download filename are resolved on the server
 * using {@code response.meta.basePlanId} and the plan/property tables. Optional fields below are used only when
 * the plan cannot be loaded (e.g. tests).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AskPlanExcelExportRequest(
        AskPlanResponse response,
        String planHeadline,
        String planTypeLabel,
        String fiscalYearLabel,
        String propertyName,
        /** When true, the workbook includes a line chart (months on X, totals on Y). */
        Boolean includeChart) {}
