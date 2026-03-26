package com.planora.service;

import com.planora.domain.Coa;
import com.planora.domain.Property;
import com.planora.repo.CoaRepository;
import com.planora.repo.PropertyRepository;
import com.planora.service.CoaExcelParser.ParsedRow;
import com.planora.web.dto.CoaBulkImportResult;
import com.planora.web.dto.CoaDto;
import com.planora.web.dto.CreateCoaRequest;
import com.planora.web.dto.UpdateCoaRequest;
import jakarta.persistence.EntityNotFoundException;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class CoaService {

    private final CoaRepository coaRepository;
    private final PropertyRepository propertyRepository;
    private final CoaExcelParser coaExcelParser;

    @Transactional(readOnly = true)
    public List<CoaDto> list(long propertyId, long organizationId) {
        return coaRepository.findByProperty_IdAndOrganizationIdOrderByCoaCodeAsc(propertyId, organizationId).stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public byte[] exportExcel(long propertyId, long organizationId) throws IOException {
        List<Coa> rows = coaRepository.findByProperty_IdAndOrganizationIdOrderByCoaCodeAsc(propertyId, organizationId);
        return coaExcelParser.buildExport(rows);
    }

    @Transactional
    public CoaDto create(CreateCoaRequest req) {
        Property property = propertyRepository.findById(req.propertyId())
                .orElseThrow(() -> new EntityNotFoundException("Property not found: " + req.propertyId()));
        if (coaRepository.existsByCoaCodeAndProperty_IdAndOrganizationId(
                req.coaCode().trim(), req.propertyId(), req.organizationId())) {
            throw new IllegalStateException("COA code already exists for this property and organization");
        }
        Coa c = new Coa();
        c.setCoaCode(req.coaCode().trim());
        c.setCoaName(req.coaName().trim());
        c.setDepartment(req.department().trim());
        c.setLineItemType(req.lineItemType());
        c.setProperty(property);
        c.setOrganizationId(req.organizationId());
        try {
            return toDto(coaRepository.save(c));
        } catch (DataIntegrityViolationException e) {
            throw new IllegalStateException("COA code already exists for this property and organization", e);
        }
    }

    @Transactional
    public CoaDto update(long id, long propertyId, long organizationId, UpdateCoaRequest req) {
        Coa c = coaRepository
                .findByIdAndProperty_IdAndOrganizationId(id, propertyId, organizationId)
                .orElseThrow(() -> new EntityNotFoundException("COA not found: " + id));
        String code = req.coaCode().trim();
        if (coaRepository.existsByCoaCodeAndProperty_IdAndOrganizationIdAndIdNot(
                code, propertyId, organizationId, id)) {
            throw new IllegalStateException("COA code already exists for this property and organization");
        }
        c.setCoaCode(code);
        c.setCoaName(req.coaName().trim());
        c.setDepartment(req.department().trim());
        c.setLineItemType(req.lineItemType());
        try {
            return toDto(coaRepository.save(c));
        } catch (DataIntegrityViolationException e) {
            throw new IllegalStateException("COA code already exists for this property and organization", e);
        }
    }

    @Transactional
    public void delete(long id, long propertyId, long organizationId) {
        Coa c = coaRepository
                .findByIdAndProperty_IdAndOrganizationId(id, propertyId, organizationId)
                .orElseThrow(() -> new EntityNotFoundException("COA not found: " + id));
        coaRepository.delete(c);
    }

    /**
     * Incremental upsert from Excel: each row is keyed by {@code coaCode} (trimmed) for this property
     * and organization. Existing codes are updated (name, department, line type); new codes are inserted.
     * Rows not present in the file are left unchanged (this is not a full replace).
     */
    @Transactional
    public CoaBulkImportResult importExcel(MultipartFile file, long propertyId, long organizationId)
            throws IOException {
        Property property = propertyRepository.findById(propertyId)
                .orElseThrow(() -> new EntityNotFoundException("Property not found: " + propertyId));
        List<ParsedRow> rows;
        try {
            rows = coaExcelParser.parse(file.getInputStream());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
        int inserted = 0;
        int updated = 0;
        for (ParsedRow r : rows) {
            String code = r.coaCode().trim();
            if (r.coaName().isBlank()) {
                throw new IllegalArgumentException("coaName is required for COA: " + code);
            }
            if (r.department().isBlank()) {
                throw new IllegalArgumentException("Department is required for COA: " + code);
            }
            Optional<Coa> existing = coaRepository.findByCoaCodeAndProperty_IdAndOrganizationId(
                    code, propertyId, organizationId);
            Coa e;
            if (existing.isPresent()) {
                e = existing.get();
                updated++;
            } else {
                e = new Coa();
                e.setProperty(property);
                e.setOrganizationId(organizationId);
                inserted++;
            }
            e.setCoaCode(code);
            e.setCoaName(r.coaName().trim());
            e.setDepartment(r.department().trim());
            e.setLineItemType(r.lineItemType());
            coaRepository.save(e);
        }
        int total = inserted + updated;
        return new CoaBulkImportResult(total, inserted, updated);
    }

    private CoaDto toDto(Coa c) {
        return new CoaDto(
                c.getId(),
                c.getCoaCode(),
                c.getCoaName(),
                c.getDepartment(),
                c.getLineItemType(),
                c.getProperty().getId(),
                c.getProperty().getName(),
                c.getOrganizationId());
    }
}
