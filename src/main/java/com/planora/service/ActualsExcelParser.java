package com.planora.service;

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
import org.springframework.stereotype.Component;

@Component
public class ActualsExcelParser {

    /**
     * Expects first sheet: column A = COA / line code, B–M = Jan…Dec, optional N+ = daily values for that row.
     */
    public List<ParsedActualsRow> parse(InputStream in) throws IOException {
        List<ParsedActualsRow> out = new ArrayList<>();
        try (Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = wb.getNumberOfSheets() > 0 ? wb.getSheetAt(0) : null;
            if (sheet == null) {
                return out;
            }
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
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
