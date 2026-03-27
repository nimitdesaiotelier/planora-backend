package com.planora.web.dto;

/** Result of server-side Ask Plan Excel generation: file bytes and suggested download name. */
public record AskPlanExcelExportResult(byte[] bytes, String filename) {}
