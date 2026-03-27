package com.planora.web;

import com.planora.service.AiParseService;
import com.planora.service.AiAskPlanService;
import com.planora.service.AskPlanExcelExportService;
import com.planora.web.dto.AskPlanExcelExportRequest;
import com.planora.web.dto.AskPlanExcelExportResult;
import com.planora.web.dto.AskPlanRequest;
import com.planora.web.dto.AskPlanResponse;
import com.planora.web.dto.ParseInstructionRequest;
import com.planora.web.dto.ParsedInstructionDto;
import jakarta.validation.Valid;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiParseService aiParseService;
    private final AiAskPlanService aiAskPlanService;
    private final AskPlanExcelExportService askPlanExcelExportService;

    @PostMapping("/parse-instruction")
    public ResponseEntity<?> parse(@Valid @RequestBody ParseInstructionRequest body) {
        try {
            ParsedInstructionDto dto = aiParseService.parse(body);
            return ResponseEntity.ok(dto);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/ask-plan")
    public ResponseEntity<?> askPlan(@Valid @RequestBody AskPlanRequest body) {
        try {
            AskPlanResponse dto = aiAskPlanService.ask(body);
            return ResponseEntity.ok(dto);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Builds an .xlsx from the ask-plan response plus optional plan title lines (see {@link AskPlanExcelExportRequest}).
     */
    @PostMapping(value = "/ask-plan/export-xlsx", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> exportAskPlanExcel(@RequestBody AskPlanExcelExportRequest body) {
        try {
            if (body == null || body.response() == null) {
                return ResponseEntity.badRequest().build();
            }
            AskPlanExcelExportResult result = askPlanExcelExportService.export(body);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentDisposition(ContentDisposition.attachment()
                    .filename(result.filename(), StandardCharsets.UTF_8)
                    .build());
            headers.setContentType(MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
            headers.setContentLength(result.bytes().length);
            return new ResponseEntity<>(result.bytes(), headers, HttpStatus.OK);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
