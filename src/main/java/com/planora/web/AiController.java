package com.planora.web;

import com.planora.service.AiParseService;
import com.planora.service.AiAskPlanService;
import com.planora.web.dto.AskPlanRequest;
import com.planora.web.dto.AskPlanResponse;
import com.planora.web.dto.ParseInstructionRequest;
import com.planora.web.dto.ParsedInstructionDto;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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
}
