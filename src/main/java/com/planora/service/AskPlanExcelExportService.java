package com.planora.service;

import com.planora.domain.Plan;
import com.planora.enums.PlanType;
import com.planora.repo.PlanRepository;
import com.planora.web.dto.AskPlanExcelExportRequest;
import com.planora.web.dto.AskPlanExcelExportResult;
import com.planora.web.dto.AskPlanResponse;
import com.planora.web.dto.AskPlanRowDto;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.RegionUtil;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds an .xlsx from {@link AskPlanExcelExportRequest}. Title block and download filename come from the plan
 * row ({@code meta.basePlanId} in the ask-plan response); optional DTO fields override if the plan is missing.
 */
@Service
@RequiredArgsConstructor
public class AskPlanExcelExportService {

    private final PlanRepository planRepository;

    private static final String SHEET_NAME = "Result";

    private static final String[] MONTHS = {
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    };

    @Transactional(readOnly = true)
    public AskPlanExcelExportResult export(AskPlanExcelExportRequest request) throws IOException {
        AskPlanResponse response = request.response();
        List<AskPlanRowDto> rows =
                response != null && response.resultRows() != null ? response.resultRows() : List.of();
        ViewFlags flags = ViewFlags.from(response != null ? response.appliedFilters() : null);
        Optional<Plan> planOpt = resolvePlanForHeader(response);

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            boolean includeChart = Boolean.TRUE.equals(request.includeChart());
            DataFormat dataFormat = wb.createDataFormat();
            TableStyles tableStyles = createTableStyles(wb, dataFormat);

            Font headlineFont = wb.createFont();
            headlineFont.setBold(true);
            headlineFont.setFontHeightInPoints((short) 14);
            CellStyle headlineStyle = wb.createCellStyle();
            headlineStyle.setFont(headlineFont);

            Font titleLineFont = wb.createFont();
            titleLineFont.setBold(true);
            titleLineFont.setFontHeightInPoints((short) 11);
            CellStyle titleLineStyle = wb.createCellStyle();
            titleLineStyle.setFont(titleLineFont);

            XSSFSheet sheet = wb.createSheet(SHEET_NAME);
            int headerCols = countHeaderColumns(flags);

            List<String> titleLines = buildTitleLines(request, planOpt);
            int rowIdx = 0;
            for (int i = 0; i < titleLines.size(); i++) {
                Row titleRow = sheet.createRow(rowIdx++);
                Cell tc = titleRow.createCell(0);
                tc.setCellValue(titleLines.get(i));
                tc.setCellStyle(i == 0 ? headlineStyle : titleLineStyle);
            }
            if (!titleLines.isEmpty()) {
                sheet.createRow(rowIdx++);
            }

            int headerRowIndex = rowIdx;
            int headerRowCount = 1;
            if (useBaseActualMonthLayout(flags)) {
                buildBaseActualTwoRowHeader(sheet, headerRowIndex, tableStyles.tableHeader());
                headerRowCount = 2;
            } else {
                buildHeader(sheet.createRow(headerRowIndex), flags, tableStyles.tableHeader());
            }

            Totals totals = new Totals(flags);

            int dataStart = headerRowIndex + headerRowCount;
            int r = dataStart;
            for (AskPlanRowDto row : rows) {
                writeDataRow(sheet.createRow(r++), row, flags, totals, tableStyles);
            }
            int totalRowIndex = r;
            Row totalRow = sheet.createRow(totalRowIndex);
            writeTotalRow(totalRow, flags, totals, tableStyles);

            for (int c = 0; c < headerCols; c++) {
                sheet.autoSizeColumn(c);
            }
            if (useBaseActualMonthLayout(flags)) {
                sheet.setColumnWidth(0, 5 * 256);
            }

            if (includeChart && !rows.isEmpty()) {
                AskPlanExcelChart.addMonthlyLineChartPerLineItem(
                        sheet, useBaseActualMonthLayout(flags), dataStart, rows, totalRowIndex);
            }

            byte[] bytes = toBytes(wb);
            String filename = resolveFilename(planOpt, includeChart);
            return new AskPlanExcelExportResult(bytes, filename);
        }
    }

    private record TableStyles(
            CellStyle tableHeader,
            CellStyle dataLabel,
            CellStyle dataText,
            CellStyle dataNumber,
            CellStyle totalLabel,
            CellStyle totalEmpty,
            CellStyle totalNumber) {}

    private static TableStyles createTableStyles(XSSFWorkbook wb, DataFormat dataFormat) {
        short intFmt = dataFormat.getFormat("#,##0");

        Font headerFont = wb.createFont();
        headerFont.setBold(true);
        headerFont.setFontHeightInPoints((short) 11);
        CellStyle tableHeader = wb.createCellStyle();
        applyThinBorder(tableHeader);
        tableHeader.setFont(headerFont);
        tableHeader.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        tableHeader.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        tableHeader.setVerticalAlignment(VerticalAlignment.CENTER);
        tableHeader.setAlignment(HorizontalAlignment.CENTER);

        Font labelBold = wb.createFont();
        labelBold.setBold(true);
        CellStyle dataLabel = wb.createCellStyle();
        applyThinBorder(dataLabel);
        dataLabel.setFont(labelBold);
        dataLabel.setVerticalAlignment(VerticalAlignment.CENTER);

        Font normal = wb.createFont();
        CellStyle dataText = wb.createCellStyle();
        applyThinBorder(dataText);
        dataText.setFont(normal);
        dataText.setVerticalAlignment(VerticalAlignment.CENTER);

        CellStyle dataNumber = wb.createCellStyle();
        applyThinBorder(dataNumber);
        dataNumber.setFont(normal);
        dataNumber.setVerticalAlignment(VerticalAlignment.CENTER);
        dataNumber.setAlignment(HorizontalAlignment.RIGHT);
        dataNumber.setDataFormat(intFmt);

        Font totalFont = wb.createFont();
        totalFont.setBold(true);

        CellStyle totalLabel = wb.createCellStyle();
        applyThinBorder(totalLabel);
        totalLabel.setFont(totalFont);
        totalLabel.setVerticalAlignment(VerticalAlignment.CENTER);
        totalLabel.setAlignment(HorizontalAlignment.LEFT);

        CellStyle totalEmpty = wb.createCellStyle();
        applyThinBorder(totalEmpty);
        totalEmpty.setFont(totalFont);
        totalEmpty.setVerticalAlignment(VerticalAlignment.CENTER);

        CellStyle totalNumber = wb.createCellStyle();
        applyThinBorder(totalNumber);
        totalNumber.setFont(totalFont);
        totalNumber.setVerticalAlignment(VerticalAlignment.CENTER);
        totalNumber.setAlignment(HorizontalAlignment.RIGHT);
        totalNumber.setDataFormat(intFmt);

        return new TableStyles(tableHeader, dataLabel, dataText, dataNumber, totalLabel, totalEmpty, totalNumber);
    }

    private static void applyThinBorder(CellStyle s) {
        s.setBorderTop(BorderStyle.THIN);
        s.setBorderBottom(BorderStyle.THIN);
        s.setBorderLeft(BorderStyle.THIN);
        s.setBorderRight(BorderStyle.THIN);
    }

    /** Excel only paints borders from the merge anchor; apply full outline on the merged rectangle. */
    private static void applyMergedRegionThinBorder(XSSFSheet sheet, CellRangeAddress region) {
        RegionUtil.setBorderTop(BorderStyle.THIN, region, sheet);
        RegionUtil.setBorderBottom(BorderStyle.THIN, region, sheet);
        RegionUtil.setBorderLeft(BorderStyle.THIN, region, sheet);
        RegionUtil.setBorderRight(BorderStyle.THIN, region, sheet);
    }

    private Optional<Plan> resolvePlanForHeader(AskPlanResponse response) {
        Long id = basePlanIdFromMeta(response);
        if (id == null) {
            return Optional.empty();
        }
        return planRepository.findByIdWithProperty(id);
    }

    private static Long basePlanIdFromMeta(AskPlanResponse response) {
        Map<String, Object> meta = response != null ? response.meta() : null;
        if (meta == null) {
            return null;
        }
        Object v = meta.get("basePlanId");
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private List<String> buildTitleLines(AskPlanExcelExportRequest req, Optional<Plan> planOpt) {
        if (planOpt.isPresent()) {
            Plan p = planOpt.get();
            List<String> lines = new ArrayList<>();
            addIfNotBlank(lines, p.getName());
            addIfNotBlank(lines, planTypeLabel(p.getPlanType()));
            if (p.getFiscalYear() != null) {
                addIfNotBlank(lines, "FY " + p.getFiscalYear());
            }
            if (p.getProperty() != null) {
                addIfNotBlank(lines, p.getProperty().getName());
            }
            return lines;
        }
        List<String> lines = new ArrayList<>();
        addIfNotBlank(lines, req.planHeadline());
        addIfNotBlank(lines, req.planTypeLabel());
        addIfNotBlank(lines, req.fiscalYearLabel());
        addIfNotBlank(lines, req.propertyName());
        return lines;
    }

    private static String planTypeLabel(PlanType type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case BUDGET -> "Budget";
            case FORECAST -> "Forecast";
            case WHAT_IF -> "What-if";
        };
    }

    private static String resolveFilename(Optional<Plan> planOpt, boolean includeChart) {
        String suffix = includeChart ? "-chart" : "";
        if (planOpt.isEmpty()) {
            return "ask-plan-export" + suffix + ".xlsx";
        }
        Plan p = planOpt.get();
        String raw = p.getName() != null && !p.getName().isBlank() ? p.getName() : "plan-" + p.getId();
        return sanitizeFilenameBase(raw) + suffix + ".xlsx";
    }

    /**
     * Windows + HTTP Content-Disposition: only ASCII letters, digits, dot, underscore, hyphen.
     * Strips other characters (including Unicode punctuation) so Tomcat and browsers never emit RFC 2047
     * {@code filename="=?UTF-8?Q?...?="} or fragile {@code filename*} fragments that break downloads.
     */
    private static String sanitizeFilenameBase(String name) {
        String t = name.trim()
                .replace('\u2014', '-') // em dash
                .replace('\u2013', '-') // en dash
                .replaceAll("[\\\\/:*?\"<>|]+", "-")
                .replaceAll("\\s+", "-");
        t = t.replaceAll("[^a-zA-Z0-9._-]", "-");
        t = t.replaceAll("-+", "-");
        t = t.replaceAll("^[.-]+|[.-]+$", "");
        if (t.length() > 120) {
            t = t.substring(0, 120);
        }
        return t.isEmpty() ? "ask-plan-export" : t;
    }

    private static void addIfNotBlank(List<String> lines, String s) {
        if (s != null && !s.trim().isEmpty()) {
            lines.add(s.trim());
        }
    }

    /** Actuals without plan-compare: two header rows (month + Base/Actual), leading blank column, merges. */
    private static boolean useBaseActualMonthLayout(ViewFlags flags) {
        return flags.showActuals && !flags.showCompare;
    }

    private static int countHeaderColumns(ViewFlags flags) {
        if (useBaseActualMonthLayout(flags)) {
            return 1 + 3 + MONTHS.length * 2 + 2;
        }
        int c = 3 + MONTHS.length + 1;
        if (flags.showCompare) {
            c += MONTHS.length + 2;
        }
        if (flags.showActuals) {
            c += MONTHS.length + 2;
        }
        return c;
    }

    /**
     * Base | Actual: column 0 blank (merged across two header rows); Label/Type/Category merge vertically;
     * each month merges {@code Jan}…{@code Dec} over Base+Actual; Total merges over Total Base + Total Actual.
     */
    private static void buildBaseActualTwoRowHeader(XSSFSheet sheet, int topRow, CellStyle tableHeaderStyle) {
        Row r0 = sheet.createRow(topRow);
        Row r1 = sheet.createRow(topRow + 1);

        Cell cBlank = r0.createCell(0);
        cBlank.setBlank();
        cBlank.setCellStyle(tableHeaderStyle);
        CellRangeAddress rBlank = new CellRangeAddress(topRow, topRow + 1, 0, 0);
        sheet.addMergedRegion(rBlank);
        applyMergedRegionThinBorder(sheet, rBlank);

        setHeaderCell(r0, 1, "Label", tableHeaderStyle);
        CellRangeAddress rLabel = new CellRangeAddress(topRow, topRow + 1, 1, 1);
        sheet.addMergedRegion(rLabel);
        applyMergedRegionThinBorder(sheet, rLabel);
        setHeaderCell(r0, 2, "Type", tableHeaderStyle);
        CellRangeAddress rType = new CellRangeAddress(topRow, topRow + 1, 2, 2);
        sheet.addMergedRegion(rType);
        applyMergedRegionThinBorder(sheet, rType);
        setHeaderCell(r0, 3, "Category", tableHeaderStyle);
        CellRangeAddress rCat = new CellRangeAddress(topRow, topRow + 1, 3, 3);
        sheet.addMergedRegion(rCat);
        applyMergedRegionThinBorder(sheet, rCat);

        int col = 4;
        for (String m : MONTHS) {
            setHeaderCell(r0, col, m, tableHeaderStyle);
            CellRangeAddress mo = new CellRangeAddress(topRow, topRow, col, col + 1);
            sheet.addMergedRegion(mo);
            applyMergedRegionThinBorder(sheet, mo);
            setHeaderCell(r1, col, "Base", tableHeaderStyle);
            setHeaderCell(r1, col + 1, "Actual", tableHeaderStyle);
            col += 2;
        }
        setHeaderCell(r0, col, "Total", tableHeaderStyle);
        CellRangeAddress rTot = new CellRangeAddress(topRow, topRow, col, col + 1);
        sheet.addMergedRegion(rTot);
        applyMergedRegionThinBorder(sheet, rTot);
        setHeaderCell(r1, col, "Total Base", tableHeaderStyle);
        setHeaderCell(r1, col + 1, "Total Actual", tableHeaderStyle);
    }

    private static void buildHeader(Row h, ViewFlags flags, CellStyle tableHeaderStyle) {
        int c = 0;
        setHeaderCell(h, c++, "Label", tableHeaderStyle);
        setHeaderCell(h, c++, "Type", tableHeaderStyle);
        setHeaderCell(h, c++, "Category", tableHeaderStyle);
        for (String m : MONTHS) {
            setHeaderCell(h, c++, m, tableHeaderStyle);
        }
        setHeaderCell(h, c++, "Total", tableHeaderStyle);
        if (flags.showCompare) {
            for (String m : MONTHS) {
                setHeaderCell(h, c++, "Compare " + m, tableHeaderStyle);
            }
            setHeaderCell(h, c++, "Compare Total", tableHeaderStyle);
            setHeaderCell(h, c++, "Delta Vs Compare", tableHeaderStyle);
        }
        if (flags.showActuals) {
            for (String m : MONTHS) {
                setHeaderCell(h, c++, "Actual " + m, tableHeaderStyle);
            }
            setHeaderCell(h, c++, "Actual Total", tableHeaderStyle);
            setHeaderCell(h, c++, "Delta Vs Actual", tableHeaderStyle);
        }
    }

    private static void setHeaderCell(Row row, int col, String text, CellStyle tableHeaderStyle) {
        Cell cell = row.createCell(col);
        cell.setCellValue(text);
        cell.setCellStyle(tableHeaderStyle);
    }

    private static void writeDataRow(Row row, AskPlanRowDto r, ViewFlags flags, Totals totals, TableStyles st) {
        int c = 0;
        if (useBaseActualMonthLayout(flags)) {
            Cell lead = row.createCell(c++);
            lead.setBlank();
            lead.setCellStyle(st.dataText());
            setStringCell(row.createCell(c++), r.label(), st.dataLabel());
            setStringCell(row.createCell(c++), r.type(), st.dataText());
            setStringCell(row.createCell(c++), r.category(), st.dataText());
            for (String m : MONTHS) {
                Integer bv = r.baseValues() != null ? r.baseValues().get(m) : null;
                Integer av = r.actualValues() != null ? r.actualValues().get(m) : null;
                setIntCell(row.createCell(c++), bv, st.dataNumber());
                setIntCell(row.createCell(c++), av, st.dataNumber());
                totals.addBaseMonth(m, bv);
                totals.addActualMonth(m, av);
            }
            setIntCell(row.createCell(c++), r.baseTotal(), st.dataNumber());
            setIntCell(row.createCell(c++), r.actualTotal(), st.dataNumber());
            totals.addBaseTotal(r.baseTotal());
            totals.addActualTotal(r.actualTotal());
            return;
        }

        setStringCell(row.createCell(c++), r.label(), st.dataLabel());
        setStringCell(row.createCell(c++), r.type(), st.dataText());
        setStringCell(row.createCell(c++), r.category(), st.dataText());

        for (String m : MONTHS) {
            Integer v = r.baseValues() != null ? r.baseValues().get(m) : null;
            setIntCell(row.createCell(c++), v, st.dataNumber());
            totals.addBaseMonth(m, v);
        }
        setIntCell(row.createCell(c++), r.baseTotal(), st.dataNumber());
        totals.addBaseTotal(r.baseTotal());

        if (flags.showCompare) {
            for (String m : MONTHS) {
                Integer v = r.compareValues() != null ? r.compareValues().get(m) : null;
                setIntCell(row.createCell(c++), v, st.dataNumber());
                totals.addCompareMonth(m, v);
            }
            setIntCell(row.createCell(c++), r.compareTotal(), st.dataNumber());
            totals.addCompareTotal(r.compareTotal());
            setIntCell(row.createCell(c++), r.deltaVsCompare(), st.dataNumber());
            totals.addDeltaCompare(r.deltaVsCompare());
        }
        if (flags.showActuals) {
            for (String m : MONTHS) {
                Integer v = r.actualValues() != null ? r.actualValues().get(m) : null;
                setIntCell(row.createCell(c++), v, st.dataNumber());
                totals.addActualMonth(m, v);
            }
            setIntCell(row.createCell(c++), r.actualTotal(), st.dataNumber());
            totals.addActualTotal(r.actualTotal());
            setIntCell(row.createCell(c++), r.deltaVsActual(), st.dataNumber());
            totals.addDeltaActual(r.deltaVsActual());
        }
    }

    private static void writeTotalRow(Row row, ViewFlags flags, Totals totals, TableStyles st) {
        int c = 0;
        if (useBaseActualMonthLayout(flags)) {
            Cell lead = row.createCell(c++);
            lead.setBlank();
            lead.setCellStyle(st.totalEmpty());
            Cell totalLabel = row.createCell(c++);
            totalLabel.setCellValue("Total");
            totalLabel.setCellStyle(st.totalLabel());
            for (int i = 0; i < 2; i++) {
                Cell blank = row.createCell(c++);
                blank.setBlank();
                blank.setCellStyle(st.totalEmpty());
            }
            for (String m : MONTHS) {
                setLongCell(row.createCell(c++), totals.baseMonthSum(m), st.totalNumber());
                setLongCell(row.createCell(c++), totals.actualMonthSum(m), st.totalNumber());
            }
            setLongCell(row.createCell(c++), totals.baseTotalSum(), st.totalNumber());
            setLongCell(row.createCell(c++), totals.actualTotalSum(), st.totalNumber());
            return;
        }

        Cell first = row.createCell(c++);
        first.setCellValue("Total");
        first.setCellStyle(st.totalLabel());
        for (int i = 0; i < 2; i++) {
            Cell blank = row.createCell(c++);
            blank.setBlank();
            blank.setCellStyle(st.totalEmpty());
        }

        for (String m : MONTHS) {
            setLongCell(row.createCell(c++), totals.baseMonthSum(m), st.totalNumber());
        }
        setLongCell(row.createCell(c++), totals.baseTotalSum(), st.totalNumber());

        if (flags.showCompare) {
            for (String m : MONTHS) {
                setLongCell(row.createCell(c++), totals.compareMonthSum(m), st.totalNumber());
            }
            setLongCell(row.createCell(c++), totals.compareTotalSum(), st.totalNumber());
            setLongCell(row.createCell(c++), totals.deltaCompareSum(), st.totalNumber());
        }
        if (flags.showActuals) {
            for (String m : MONTHS) {
                setLongCell(row.createCell(c++), totals.actualMonthSum(m), st.totalNumber());
            }
            setLongCell(row.createCell(c++), totals.actualTotalSum(), st.totalNumber());
            setLongCell(row.createCell(c++), totals.deltaActualSum(), st.totalNumber());
        }
    }

    private static void setStringCell(Cell cell, String v, CellStyle style) {
        if (v == null) {
            cell.setBlank();
        } else {
            cell.setCellValue(v);
        }
        cell.setCellStyle(style);
    }

    private static void setIntCell(Cell cell, Integer v, CellStyle style) {
        if (v == null) {
            cell.setBlank();
        } else {
            cell.setCellValue(v.doubleValue());
        }
        cell.setCellStyle(style);
    }

    private static void setLongCell(Cell cell, long v, CellStyle style) {
        cell.setCellValue((double) v);
        cell.setCellStyle(style);
    }

    private static byte[] toBytes(XSSFWorkbook wb) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        wb.write(bos);
        return bos.toByteArray();
    }

    private record ViewFlags(boolean showCompare, boolean showActuals) {
        static ViewFlags from(Map<String, Object> appliedFilters) {
            if (appliedFilters == null || appliedFilters.isEmpty()) {
                return new ViewFlags(false, false);
            }
            Object cm = appliedFilters.get("compareMode");
            String compareMode = cm != null ? String.valueOf(cm).trim() : "none";
            boolean includeActuals = Boolean.TRUE.equals(appliedFilters.get("includeActuals"));
            boolean showCompare = "plan".equals(compareMode);
            boolean showActuals = "actuals".equals(compareMode) || includeActuals;
            return new ViewFlags(showCompare, showActuals);
        }
    }

    private static final class Totals {
        private final Map<String, Long> baseMonth = newMonthMap();
        private final Map<String, Long> compareMonth;
        private final Map<String, Long> actualMonth;
        private final ViewFlags flags;

        private long baseTotal;
        private long compareTotal;
        private long actualTotal;
        private long deltaCompare;
        private long deltaActual;

        Totals(ViewFlags flags) {
            this.flags = flags;
            this.compareMonth = flags.showCompare ? newMonthMap() : null;
            this.actualMonth = flags.showActuals ? newMonthMap() : null;
        }

        private static Map<String, Long> newMonthMap() {
            Map<String, Long> m = new LinkedHashMap<>();
            for (String mo : MONTHS) {
                m.put(mo, 0L);
            }
            return m;
        }

        void addBaseMonth(String m, Integer v) {
            if (v != null) {
                baseMonth.merge(m, v.longValue(), Long::sum);
            }
        }

        void addBaseTotal(Integer v) {
            if (v != null) {
                baseTotal += v.longValue();
            }
        }

        void addCompareMonth(String m, Integer v) {
            if (flags.showCompare && v != null) {
                compareMonth.merge(m, v.longValue(), Long::sum);
            }
        }

        void addCompareTotal(Integer v) {
            if (flags.showCompare && v != null) {
                compareTotal += v.longValue();
            }
        }

        void addDeltaCompare(Integer v) {
            if (flags.showCompare && v != null) {
                deltaCompare += v.longValue();
            }
        }

        void addActualMonth(String m, Integer v) {
            if (flags.showActuals && v != null) {
                actualMonth.merge(m, v.longValue(), Long::sum);
            }
        }

        void addActualTotal(Integer v) {
            if (flags.showActuals && v != null) {
                actualTotal += v.longValue();
            }
        }

        void addDeltaActual(Integer v) {
            if (flags.showActuals && v != null) {
                deltaActual += v.longValue();
            }
        }

        long baseMonthSum(String m) {
            return baseMonth.getOrDefault(m, 0L);
        }

        long baseTotalSum() {
            return baseTotal;
        }

        long compareMonthSum(String m) {
            return compareMonth != null ? compareMonth.getOrDefault(m, 0L) : 0L;
        }

        long compareTotalSum() {
            return compareTotal;
        }

        long deltaCompareSum() {
            return deltaCompare;
        }

        long actualMonthSum(String m) {
            return actualMonth != null ? actualMonth.getOrDefault(m, 0L) : 0L;
        }

        long actualTotalSum() {
            return actualTotal;
        }

        long deltaActualSum() {
            return deltaActual;
        }
    }
}
