package com.planora.service;

import com.planora.domain.ActualsDetails;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

@Component
public class ActualsExcelParser {

    public static final String SHEET_NAME = "Actuals";

    private static final String[] MONTH_HEADERS = {
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    };

    /**
     * First sheet: column A = COA code, B–M = Jan…Dec, optional N+ = daily values.
     * Row 0 may be a header row (coaCode / Jan / …); data rows follow. Supports .xlsx and .xls.
     */
    public List<ParsedActualsRow> parse(InputStream in) throws IOException {
        List<ParsedActualsRow> out = new ArrayList<>();
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
                String coa = stringCell(row.getCell(0));
                if (coa == null || coa.isBlank()) {
                    continue;
                }
                BigDecimal[] months = new BigDecimal[12];
                for (int m = 0; m < 12; m++) {
                    months[m] = numberCell(row.getCell(1 + m));
                }
                List<BigDecimal> daily = new ArrayList<>();
                int last = row.getLastCellNum();
                if (last < 0) {
                    last = 0;
                }
                for (int c = 13; c < last; c++) {
                    BigDecimal d = numberCell(row.getCell(c));
                    if (d != null) {
                        daily.add(d);
                    }
                }
                out.add(new ParsedActualsRow(coa.trim(), months, daily.isEmpty() ? List.of() : daily));
            }
        }
        return out;
    }

    private static boolean looksLikeHeader(Row row) {
        String a = stringCell(row.getCell(0));
        if (a == null) {
            return false;
        }
        String lower = a.toLowerCase().trim();
        return lower.contains("coa") || lower.equals("code") || lower.startsWith("line");
    }

    /** Same workbook layout used for upload — empty list yields headers only (starter file). */
    public byte[] buildExport(List<ActualsDetails> rows) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet(SHEET_NAME);
            Row h = sheet.createRow(0);
            h.createCell(0).setCellValue("coaCode");
            for (int i = 0; i < 12; i++) {
                h.createCell(1 + i).setCellValue(MONTH_HEADERS[i]);
            }
            h.createCell(13).setCellValue("daily…");
            int r = 1;
            for (ActualsDetails e : rows) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(e.getCoaCode());
                setMonthCell(row, 1, e.getJanValue());
                setMonthCell(row, 2, e.getFebValue());
                setMonthCell(row, 3, e.getMarValue());
                setMonthCell(row, 4, e.getAprValue());
                setMonthCell(row, 5, e.getMayValue());
                setMonthCell(row, 6, e.getJunValue());
                setMonthCell(row, 7, e.getJulValue());
                setMonthCell(row, 8, e.getAugValue());
                setMonthCell(row, 9, e.getSepValue());
                setMonthCell(row, 10, e.getOctValue());
                setMonthCell(row, 11, e.getNovValue());
                setMonthCell(row, 12, e.getDecValue());
                List<BigDecimal> daily = e.getDailyDetails();
                if (daily != null) {
                    for (int c = 0; c < daily.size(); c++) {
                        setMonthCell(row, 13 + c, daily.get(c));
                    }
                }
            }
            for (int c = 0; c < 14; c++) {
                sheet.autoSizeColumn(c);
            }
            return toBytes(wb);
        }
    }

    private static void setMonthCell(Row row, int col, BigDecimal v) {
        if (v == null) {
            return;
        }
        row.createCell(col).setCellValue(v.doubleValue());
    }

    private static byte[] toBytes(XSSFWorkbook wb) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        wb.write(bos);
        return bos.toByteArray();
    }

    private static String stringCell(Cell cell) {
        if (cell == null) {
            return null;
        }
        if (cell.getCellType() == CellType.STRING) {
            return cell.getStringCellValue();
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            return BigDecimal.valueOf(cell.getNumericCellValue()).toPlainString();
        }
        return null;
    }

    private static BigDecimal numberCell(Cell cell) {
        if (cell == null) {
            return null;
        }
        return switch (cell.getCellType()) {
            case NUMERIC -> BigDecimal.valueOf(cell.getNumericCellValue());
            case STRING -> {
                String s = cell.getStringCellValue().trim();
                if (s.isEmpty()) {
                    yield null;
                }
                yield new BigDecimal(s.replace(",", ""));
            }
            case BOOLEAN -> cell.getBooleanCellValue() ? BigDecimal.ONE : BigDecimal.ZERO;
            case BLANK -> null;
            case FORMULA -> {
                if (cell.getCachedFormulaResultType() == CellType.NUMERIC) {
                    yield BigDecimal.valueOf(cell.getNumericCellValue());
                }
                if (cell.getCachedFormulaResultType() == CellType.STRING) {
                    String s = cell.getStringCellValue().trim();
                    yield s.isEmpty() ? null : new BigDecimal(s.replace(",", ""));
                }
                yield null;
            }
            default -> null;
        };
    }

    public record ParsedActualsRow(String coaCode, BigDecimal[] months, List<BigDecimal> daily) {}
}
