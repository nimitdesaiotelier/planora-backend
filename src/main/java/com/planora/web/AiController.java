package com.planora.web;

import com.planora.service.AiParseService;
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
}
