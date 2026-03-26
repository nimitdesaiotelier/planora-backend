package com.planora.web.dto;

/**
 * Result of Excel bulk upload: each row is matched by {@code coaCode} + property + organization —
 * existing rows are updated, new codes are inserted (incremental upsert).
 */
public record CoaBulkImportResult(int importedRows, int inserted, int updated) {}
