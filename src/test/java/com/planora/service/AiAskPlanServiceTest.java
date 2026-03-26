package com.planora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AiAskPlanServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void toIntentParsesStructuredJson() throws Exception {
        String json = """
                {
                  "intent":"top_n",
                  "lineTypes":["Expense","Revenue"],
                  "category":"Utilities",
                  "period":"Q1",
                  "topN":5,
                  "includeActuals":true,
                  "compareMode":"actuals",
                  "searchText":"room"
                }
                """;

        var intent = AiAskPlanService.toIntent(objectMapper.readTree(json));

        assertEquals("top_n", intent.intent());
        assertEquals(List.of("Expense", "Revenue"), intent.lineTypes());
        assertEquals("Utilities", intent.category());
        assertEquals("Q1", intent.period());
        assertEquals(5, intent.topN());
        assertEquals(true, intent.includeActuals());
        assertEquals("actuals", intent.compareMode());
        assertEquals("room", intent.searchText());
    }

    @Test
    void heuristicIntentDetectsCombinedTypes() {
        var intent = AiAskPlanService.heuristicIntent("show top 5 revenue and expense line items");
        assertEquals("top_n", intent.intent());
        assertEquals(List.of("Revenue", "Expense"), intent.lineTypes());
        assertEquals(5, intent.topN());
        assertEquals("none", intent.compareMode());
        assertEquals("max", intent.rankMode());
    }

    @Test
    void periodHelpersResolveQuarterAndTotals() {
        List<String> months = AiAskPlanService.monthsForPeriod("Q2");
        assertEquals(List.of("Apr", "May", "Jun"), months);

        int q2Total = AiAskPlanService.sumMonths(
                Map.of("Apr", 10, "May", 20, "Jun", 30, "Jan", 999),
                months);
        assertEquals(60, q2Total);
    }

    @Test
    void extractTopNReturnsNullWhenMissing() {
        assertNull(AiAskPlanService.extractTopN("show me expense rows"));
    }

    @Test
    void heuristicIntentSupportsCompareWithActualYear() {
        var intent = AiAskPlanService.heuristicIntent("Compare with Actuals 2024");
        assertEquals("compare_actuals", intent.intent());
        assertEquals("actuals", intent.compareMode());
        assertEquals(2024, intent.actualYear());
    }

    @Test
    void heuristicIntentSupportsBudgetYearCompare() {
        var intent = AiAskPlanService.heuristicIntent("what is room available in budget 2025");
        assertEquals("compare_plan", intent.intent());
        assertEquals("plan", intent.compareMode());
        assertEquals(2025, intent.comparePlanYear());
        assertEquals("BUDGET", intent.comparePlanType());
    }

    @Test
    void heuristicIntentSupportsRoomRevenueActualQuestion() {
        var intent = AiAskPlanService.heuristicIntent("What is room Revenue in Actual 2024");
        assertEquals("actuals", intent.compareMode());
        assertEquals(2024, intent.actualYear());
        assertEquals("room revenue", intent.searchText());
        assertEquals(1, intent.topN());
    }

    @Test
    void heuristicIntentSupportsMinimumQuery() {
        var intent = AiAskPlanService.heuristicIntent("show top 1 minimum expense item");
        assertEquals("min", intent.rankMode());
        assertEquals(List.of("Expense"), intent.lineTypes());
    }

    @Test
    void inferRankModeSupportsAverage() {
        assertEquals("avg", AiAskPlanService.inferRankMode("show average revenue for q1"));
    }
}
