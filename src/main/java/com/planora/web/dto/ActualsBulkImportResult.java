package com.planora.web.dto;

/**
 * Excel upload result: upsert by coaCode + year + property + organization — existing rows updated,
 * new rows inserted; actuals not listed in the file are unchanged.
 */
public record ActualsBulkImportResult(int importedRows, int inserted, int updated) {}
