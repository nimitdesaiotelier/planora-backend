package com.planora.web;

import com.planora.service.ActualsService;
import com.planora.web.dto.ActualsBulkImportResult;
import com.planora.web.dto.ActualsDetailDto;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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

    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final ActualsService actualsService;

    @GetMapping
    public List<ActualsDetailDto> list(
            @RequestParam int year,
            @RequestParam long propertyId,
            @RequestParam(defaultValue = "1") long organizationId
    ) {
        return actualsService.list(year, propertyId, organizationId);
    }

    /** Same workbook layout as upload — A: coaCode, B: coaName, C-N: Jan-Dec, O+: daily values. */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestParam int year,
            @RequestParam long propertyId,
            @RequestParam(defaultValue = "1") long organizationId
    ) throws IOException {
        byte[] bytes = actualsService.exportExcel(year, propertyId, organizationId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(XLSX);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("actuals-export.xlsx", java.nio.charset.StandardCharsets.UTF_8)
                .build());
        headers.setContentLength(bytes.length);
        return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
    }

    /** Incremental upsert by COA code for this year/property/org. */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam int year,
            @RequestParam long propertyId,
            @RequestParam(defaultValue = "1") long organizationId
    ) {
        try {
            ActualsBulkImportResult result = actualsService.importExcel(file, year, propertyId, organizationId);
            return ResponseEntity.ok(Map.of(
                    "importedRows", result.importedRows(),
                    "inserted", result.inserted(),
                    "updated", result.updated()
            ));
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Could not read Excel: " + e.getMessage()));
        }
    }
}
