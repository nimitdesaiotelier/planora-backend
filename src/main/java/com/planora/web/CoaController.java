package com.planora.web;

import com.planora.service.CoaService;
import com.planora.web.dto.CoaBulkImportResult;
import com.planora.web.dto.CoaDto;
import com.planora.web.dto.CreateCoaRequest;
import com.planora.web.dto.UpdateCoaRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/coa")
@RequiredArgsConstructor
public class CoaController {

    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final CoaService coaService;

    @GetMapping
    public List<CoaDto> list(
            @RequestParam long propertyId,
            @RequestParam(defaultValue = "1") long organizationId
    ) {
        return coaService.list(propertyId, organizationId);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CoaDto> create(@Valid @RequestBody CreateCoaRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(coaService.create(body));
    }

    @PutMapping("/{id}")
    public CoaDto update(
            @PathVariable long id,
            @RequestParam long propertyId,
            @RequestParam(defaultValue = "1") long organizationId,
            @Valid @RequestBody UpdateCoaRequest body
    ) {
        return coaService.update(id, propertyId, organizationId, body);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable long id,
            @RequestParam long propertyId,
            @RequestParam(defaultValue = "1") long organizationId
    ) {
        coaService.delete(id, propertyId, organizationId);
        return ResponseEntity.noContent().build();
    }

    /** Excel: columns coaCode, coaName, department, lineItemType — same as bulk upload; empty list = header row only. */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestParam long propertyId,
            @RequestParam(defaultValue = "1") long organizationId
    ) throws IOException {
        byte[] bytes = coaService.exportExcel(propertyId, organizationId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(XLSX);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("coa-export.xlsx", java.nio.charset.StandardCharsets.UTF_8)
                .build());
        headers.setContentLength(bytes.length);
        return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
    }

    /**
     * Upserts by {@code coaCode} per property/org: new codes insert, existing codes update.
     * COAs not listed in the file are not deleted.
     */
    @PostMapping(value = "/bulk-upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> bulkUpload(
            @RequestPart("file") MultipartFile file,
            @RequestParam long propertyId,
            @RequestParam(defaultValue = "1") long organizationId
    ) {
        try {
            CoaBulkImportResult result = coaService.importExcel(file, propertyId, organizationId);
            return ResponseEntity.ok(Map.of(
                    "importedRows", result.importedRows(),
                    "inserted", result.inserted(),
                    "updated", result.updated()
            ));
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Could not read file: " + e.getMessage()));
        }
    }
}
