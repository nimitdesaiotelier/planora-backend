package com.planora.service;

import com.planora.domain.Coa;
import com.planora.enums.LineItemType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFDataValidation;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/**
 * First sheet: A = coaCode, B = coaName, C = department, D = lineItemType (Revenue | Expense | Statistics).
 * Row 0 = header; data from row 1. Supports .xlsx and .xls via POI.
 * <p>
 * Bulk upload uses {@code coaCode} as the natural key: export current data, edit in Excel, re-upload —
 * existing codes are updated; new rows are inserted; codes omitted from the file are not removed.
 */
@Component
public class CoaExcelParser {

    public static final String SHEET_NAME = "COA";

    private static final int COL_CODE = 0;
    private static final int COL_NAME = 1;
    private static final int COL_DEPT = 2;
    private static final int COL_TYPE = 3;
    private static final int VALIDATION_MAX_ROW = 5000;

    public record ParsedRow(String coaCode, String coaName, String department, LineItemType lineItemType) {}

    public List<ParsedRow> parse(InputStream in) throws IOException {
        List<ParsedRow> out = new ArrayList<>();
        try (Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = wb.getNumberOfSheets() > 0 ? wb.getSheetAt(0) : null;
            if (sheet == null) {
                return out;
            }
            int firstDataRow = 1;
            Row header = sheet.getRow(0);
            if (header == null) {
                firstDataRow = 0;
            } else if (!looksLikeHeader(header)) {
                firstDataRow = 0;
            }
            for (int r = firstDataRow; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                String code = stringCell(row.getCell(COL_CODE));
                if (code == null || code.isBlank()) {
                    continue;
                }
                String name = stringCell(row.getCell(COL_NAME));
                if (name == null) {
                    name = "";
                }
                name = name.trim();
                String dept = stringCell(row.getCell(COL_DEPT));
                if (dept == null) {
                    dept = "";
                }
                dept = dept.trim();
                String typeStr = stringCell(row.getCell(COL_TYPE));
                if (typeStr == null || typeStr.isBlank()) {
                    throw new IllegalArgumentException("lineItemType is required for COA: " + code.trim());
                }
                LineItemType type = parseLineItemType(typeStr);
                out.add(new ParsedRow(code.trim(), name, dept, type));
            }
        }
        return out;
    }

    private static boolean looksLikeHeader(Row row) {
        String a = stringCell(row.getCell(COL_CODE));
        if (a == null) {
            return false;
        }
        String lower = a.toLowerCase().trim();
        return (lower.contains("coa") && lower.contains("code")) || lower.equals("code");
    }

    /** Same layout as bulk upload — empty list yields header row only. */
    public byte[] buildExport(Iterable<Coa> rows) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet(SHEET_NAME);
            Row h = sheet.createRow(0);
            h.createCell(COL_CODE).setCellValue("coaCode");
            h.createCell(COL_NAME).setCellValue("coaName");
            h.createCell(COL_DEPT).setCellValue("department");
            h.createCell(COL_TYPE).setCellValue("lineItemType");
            addLineItemTypeDropdown(sheet);
            int r = 1;
            for (Coa c : rows) {
                writeDataRow(sheet, r++, c.getCoaCode(), c.getCoaName(), c.getDepartment(), c.getLineItemType().name());
            }
            for (int c = 0; c <= COL_TYPE; c++) {
                sheet.autoSizeColumn(c);
            }
            return toBytes(wb);
        }
    }

    private static void writeDataRow(Sheet sheet, int rowIndex, String code, String name, String dept, String type) {
        Row row = sheet.createRow(rowIndex);
        row.createCell(COL_CODE).setCellValue(code);
        row.createCell(COL_NAME).setCellValue(name);
        row.createCell(COL_DEPT).setCellValue(dept);
        row.createCell(COL_TYPE).setCellValue(type);
    }

    private static void addLineItemTypeDropdown(Sheet sheet) {
        DataValidationHelper helper = sheet.getDataValidationHelper();
        String[] allowedTypes = java.util.Arrays.stream(LineItemType.values())
                .map(LineItemType::name)
                .toArray(String[]::new);
        DataValidationConstraint constraint = helper.createExplicitListConstraint(allowedTypes);
        CellRangeAddressList addressList = new CellRangeAddressList(1, VALIDATION_MAX_ROW, COL_TYPE, COL_TYPE);
        DataValidation validation = helper.createValidation(constraint, addressList);
        if (validation instanceof XSSFDataValidation) {
            // XSSF requires suppressDropDownArrow=true for the in-cell dropdown to show reliably.
            validation.setSuppressDropDownArrow(true);
        } else {
            validation.setSuppressDropDownArrow(false);
        }
        validation.setShowErrorBox(true);
        validation.createErrorBox("Invalid lineItemType", "Please select a value from the dropdown list.");
        sheet.addValidationData(validation);
    }

    private static byte[] toBytes(XSSFWorkbook wb) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        wb.write(bos);
        return bos.toByteArray();
    }

    private static LineItemType parseLineItemType(String s) {
        String t = s.trim();
        try {
            return LineItemType.valueOf(t);
        } catch (IllegalArgumentException e) {
            for (LineItemType lt : LineItemType.values()) {
                if (lt.name().equalsIgnoreCase(t)) {
                    return lt;
                }
            }
            throw new IllegalArgumentException("Invalid lineItemType: " + s + " (use Revenue, Expense, or Statistics)");
        }
    }

    private static String stringCell(Cell cell) {
        if (cell == null) {
            return null;
        }
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> BigDecimal.valueOf(cell.getNumericCellValue()).toPlainString();
            case BOOLEAN -> cell.getBooleanCellValue() ? "true" : "false";
            case BLANK -> null;
            case FORMULA -> {
                if (cell.getCachedFormulaResultType() == CellType.STRING) {
                    yield cell.getStringCellValue();
                }
                if (cell.getCachedFormulaResultType() == CellType.NUMERIC) {
                    yield BigDecimal.valueOf(cell.getNumericCellValue()).toPlainString();
                }
                yield null;
            }
            default -> null;
        };
    }
}
