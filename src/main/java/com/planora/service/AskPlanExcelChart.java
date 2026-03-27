package com.planora.service;

import com.planora.web.dto.AskPlanRowDto;
import java.util.List;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xddf.usermodel.chart.AxisPosition;
import org.apache.poi.xddf.usermodel.chart.ChartTypes;
import org.apache.poi.xddf.usermodel.chart.LegendPosition;
import org.apache.poi.xddf.usermodel.chart.XDDFCategoryAxis;
import org.apache.poi.xddf.usermodel.chart.XDDFChartLegend;
import org.apache.poi.xddf.usermodel.chart.XDDFDataSourcesFactory;
import org.apache.poi.xddf.usermodel.chart.XDDFLineChartData;
import org.apache.poi.xddf.usermodel.chart.XDDFValueAxis;
import org.apache.poi.xssf.usermodel.XSSFChart;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Line chart: X = months, Y = base monthly amount — one series per result row (line item).
 */
final class AskPlanExcelChart {

    private static final String[] MONTHS = {
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    };

    private static final int MAX_SERIES_TITLE_LEN = 250;

    /** Anchor height in rows (see {@link #addMonthlyLineChartPerLineItem} {@code XSSFClientAnchor} row2 offset). */
    static final int CHART_ANCHOR_ROW_SPAN = 16;

    /** Hidden sheet holding month × line-item formulas so the Result sheet stays table + chart only. */
    private static final String CHART_DATA_SHEET = "ChartData";

    private AskPlanExcelChart() {}

    /**
     * First sheet row index below the monthly line chart (and a small gap), for appending content such as analysis.
     * Must match {@link #addMonthlyLineChartPerLineItem} anchor placement ({@code chartTopRow = totalRowIndex + 2}).
     */
    static int firstRowBelowMonthlyChart(int totalRowIndex) {
        int chartTopRow = totalRowIndex + 2;
        return chartTopRow + CHART_ANCHOR_ROW_SPAN + 2;
    }

    static void addMonthlyLineChartPerLineItem(
            XSSFSheet resultSheet,
            boolean baseActualMonthLayout,
            int dataStartRow0,
            List<AskPlanRowDto> lineItems,
            int totalRowIndex) {
        if (lineItems == null || lineItems.isEmpty()) {
            return;
        }

        XSSFWorkbook wb = resultSheet.getWorkbook();
        XSSFSheet dataSheet = wb.createSheet(CHART_DATA_SHEET);
        wb.setSheetHidden(wb.getSheetIndex(dataSheet), true);

        String resultSheetName = resultSheet.getSheetName();
        writeLineItemMonthHelpers(
                dataSheet, resultSheetName, baseActualMonthLayout, dataStartRow0, lineItems, 0);

        int n = lineItems.size();
        int chartTopRow = totalRowIndex + 2;
        int chartCol = 0;
        XSSFDrawing drawing = resultSheet.createDrawingPatriarch();
        XSSFClientAnchor anchor =
                new XSSFClientAnchor(0, 0, 0, 0, chartCol, chartTopRow, chartCol + 12, chartTopRow + CHART_ANCHOR_ROW_SPAN);
        XSSFChart chart = drawing.createChart(anchor);
        chart.setTitleText("Monthly base by line item");

        XDDFChartLegend legend = chart.getOrAddLegend();
        legend.setPosition(LegendPosition.BOTTOM);

        XDDFCategoryAxis bottomAxis = chart.createCategoryAxis(AxisPosition.BOTTOM);
        bottomAxis.setTitle("Month");
        XDDFValueAxis leftAxis = chart.createValueAxis(AxisPosition.LEFT);
        leftAxis.setTitle("Amount");

        XDDFLineChartData data = (XDDFLineChartData) chart.createData(ChartTypes.LINE, bottomAxis, leftAxis);

        int lastMonthRow = 11;
        CellRangeAddress catRange = new CellRangeAddress(0, lastMonthRow, 0, 0);

        for (int i = 0; i < n; i++) {
            CellRangeAddress valRange = new CellRangeAddress(0, lastMonthRow, 1 + i, 1 + i);
            XDDFLineChartData.Series s = (XDDFLineChartData.Series) data.addSeries(
                    XDDFDataSourcesFactory.fromStringCellRange(dataSheet, catRange),
                    XDDFDataSourcesFactory.fromNumericCellRange(dataSheet, valRange));
            s.setTitle(seriesTitle(lineItems.get(i), i), null);
        }

        chart.plot(data);
    }

    private static String seriesTitle(AskPlanRowDto row, int index) {
        String label = row.label();
        if (label == null || label.isBlank()) {
            label = "Line " + (index + 1);
        }
        label = label.trim();
        if (label.length() > MAX_SERIES_TITLE_LEN) {
            label = label.substring(0, MAX_SERIES_TITLE_LEN) + "...";
        }
        return label;
    }

    /**
     * On {@code helperSheet}: column A = month labels; columns 1..N = formulas pointing at {@code resultSheetName}.
     */
    private static void writeLineItemMonthHelpers(
            XSSFSheet helperSheet,
            String resultSheetName,
            boolean baseActualMonthLayout,
            int dataStartRow0,
            List<AskPlanRowDto> lineItems,
            int helperMonthStartRow) {
        String refPrefix = quoteSheetNameForFormula(resultSheetName) + "!";

        for (int m = 0; m < 12; m++) {
            XSSFRow row = helperSheet.getRow(helperMonthStartRow + m);
            if (row == null) {
                row = helperSheet.createRow(helperMonthStartRow + m);
            }
            row.createCell(0).setCellValue(MONTHS[m]);
        }

        int lineCount = lineItems.size();
        for (int i = 0; i < lineCount; i++) {
            int excelDataRow = dataStartRow0 + i + 1;
            for (int m = 0; m < 12; m++) {
                XSSFRow row = helperSheet.getRow(helperMonthStartRow + m);
                int colRef = baseActualMonthLayout ? (4 + 2 * m) : (3 + m);
                String colLetter = CellReference.convertNumToColString(colRef);
                row.createCell(1 + i).setCellFormula(refPrefix + colLetter + excelDataRow);
            }
        }
    }

    /** Excel sheet reference prefix for formulas (quote if name has spaces or special chars). */
    private static String quoteSheetNameForFormula(String name) {
        if (name == null || name.isEmpty()) {
            return "''";
        }
        boolean plain = true;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!((c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '_')) {
                plain = false;
                break;
            }
        }
        if (plain) {
            return name;
        }
        return "'" + name.replace("'", "''") + "'";
    }
}
