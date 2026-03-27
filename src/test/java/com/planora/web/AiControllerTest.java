package com.planora.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planora.service.AiAskPlanService;
import com.planora.service.AiParseService;
import com.planora.service.AskPlanExcelExportService;
import com.planora.web.dto.AskPlanAnalyzeRequest;
import com.planora.web.dto.AskPlanAnalyzeResponse;
import com.planora.web.dto.AskPlanExcelExportRequest;
import com.planora.web.dto.AskPlanExcelExportResult;
import com.planora.web.dto.AskPlanResponse;
import com.planora.web.dto.InstructionStepDto;
import com.planora.web.dto.ParsedInstructionDto;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AiController.class)
class AiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AiParseService aiParseService;

    @MockBean
    private AiAskPlanService aiAskPlanService;

    @MockBean
    private AskPlanExcelExportService askPlanExcelExportService;

    @Test
    void askPlanReturns200() throws Exception {
        AskPlanResponse resp = new AskPlanResponse(
                "Filtered expense rows.",
                "filter",
                Map.of("topN", 5),
                List.of(),
                Map.of("resultCount", 0));
        when(aiAskPlanService.ask(any())).thenReturn(resp);

        Map<String, Object> body = Map.of(
                "provider", "openai",
                "question", "show top 5 expense line items",
                "basePlanId", 1,
                "includeActuals", false);

        mockMvc.perform(post("/api/ai/ask-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value("filter"));
    }

    @Test
    void askPlanReturns400OnIllegalArgument() throws Exception {
        when(aiAskPlanService.ask(any())).thenThrow(new IllegalArgumentException("bad query"));

        Map<String, Object> body = Map.of(
                "provider", "openai",
                "question", "compare",
                "basePlanId", 1);

        mockMvc.perform(post("/api/ai/ask-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad query"));
    }

    @Test
    void analyzeAskPlanReturns200() throws Exception {
        AskPlanResponse resp = new AskPlanResponse(
                "summary", "top_n", Map.of("topN", 5), List.of(), Map.of("resultCount", 0));
        when(aiAskPlanService.analyze(any()))
                .thenReturn(new AskPlanAnalyzeResponse(List.of("Point one.", "Point two.")));

        AskPlanAnalyzeRequest body = new AskPlanAnalyzeRequest("openai", "show top 5", resp);

        mockMvc.perform(post("/api/ai/ask-plan/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points[0]").value("Point one."))
                .andExpect(jsonPath("$.points[1]").value("Point two."));
    }

    @Test
    void exportAskPlanExcelReturns200() throws Exception {
        when(askPlanExcelExportService.export(any()))
                .thenReturn(new AskPlanExcelExportResult(new byte[] {80, 75, 3, 4}, "ask-plan-export.xlsx"));

        AskPlanResponse resp = new AskPlanResponse(
                "summary", "filter", Map.of("compareMode", "none"), List.of(), Map.of("resultCount", 0));
        AskPlanExcelExportRequest exportBody = new AskPlanExcelExportRequest(resp, null, null, null, null, false, null);

        mockMvc.perform(post("/api/ai/ask-plan/export-xlsx")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(exportBody)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("attachment")));
    }

    @Test
    void parseInstructionStillWorks() throws Exception {
        ParsedInstructionDto resp = new ParsedInstructionDto(
                "Applied change.",
                List.of(new InstructionStepDto(
                        "increase",
                        10,
                        "percentage",
                        null,
                        null,
                        null,
                        "Jan",
                        null,
                        null,
                        null,
                        null,
                        null)),
                List.of(),
                Map.of("Jan", 110),
                Map.of());
        when(aiParseService.parse(any())).thenReturn(resp);

        Map<String, Object> body = Map.of(
                "provider", "openai",
                "rowLabel", "Rooms Revenue",
                "instruction", "increase Jan by 10%",
                "values", Map.of("Jan", 100),
                "planId", 1,
                "lineKey", "4000");

        mockMvc.perform(post("/api/ai/parse-instruction")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value("Applied change."));
    }
}
