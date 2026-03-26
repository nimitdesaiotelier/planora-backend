package com.planora.web;

import com.planora.service.ActualsService;
import com.planora.web.dto.ActualsDetailDto;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/actuals")
@RequiredArgsConstructor
public class ActualsController {

    private final ActualsService actualsService;

    @GetMapping
    public List<ActualsDetailDto> list(
            @RequestParam int year,
            @RequestParam long propertyId,
            @RequestParam(defaultValue = "1") long organizationId
    ) {
        return actualsService.list(year, propertyId, organizationId);
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam int year,
            @RequestParam long propertyId,
            @RequestParam(defaultValue = "1") long organizationId
    ) {
        try {
            int rows = actualsService.importExcel(file, year, propertyId, organizationId);
            return ResponseEntity.ok(Map.of("importedRows", rows));
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Could not read Excel: " + e.getMessage()));
        }
    }
}
