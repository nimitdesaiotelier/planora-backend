package com.planora.service;

import com.planora.domain.ActualsDetails;
import com.planora.domain.Coa;
import com.planora.domain.Property;
import com.planora.repo.ActualsDetailRepository;
import com.planora.repo.CoaRepository;
import com.planora.repo.PropertyRepository;
import com.planora.service.ActualsExcelParser.ParsedActualsRow;
import com.planora.web.dto.ActualsBulkImportResult;
import com.planora.web.dto.ActualsDetailDto;
import jakarta.persistence.EntityNotFoundException;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class ActualsService {

    private final ActualsDetailRepository actualsDetailRepository;
    private final PropertyRepository propertyRepository;
    private final CoaRepository coaRepository;
    private final ActualsExcelParser actualsExcelParser;

    @Transactional(readOnly = true)
    public List<Integer> listYearsWithData(long propertyId, long organizationId) {
        return actualsDetailRepository.findDistinctYearsByPropertyIdAndOrganizationId(propertyId, organizationId);
    }

    @Transactional(readOnly = true)
    public List<ActualsDetailDto> list(int year, long propertyId, long organizationId) {
        List<ActualsDetails> rows = actualsDetailRepository
                .findByYearAndProperty_IdAndOrganizationIdOrderByCoaCodeAsc(year, propertyId, organizationId);
        Map<String, String> coaNameByCode = coaRepository
                .findByProperty_IdAndOrganizationIdOrderByCoaCodeAsc(propertyId, organizationId)
                .stream()
                .collect(Collectors.toMap(Coa::getCoaCode, Coa::getCoaName, (a, b) -> a));
        return rows.stream().map(e -> toDto(e, coaNameByCode)).toList();
    }

    @Transactional(readOnly = true)
    public byte[] exportExcel(int year, long propertyId, long organizationId) throws IOException {
        List<ActualsDetails> rows = actualsDetailRepository
                .findByYearAndProperty_IdAndOrganizationIdOrderByCoaCodeAsc(year, propertyId, organizationId);
        return actualsExcelParser.buildExport(rows);
    }

    /**
     * Incremental upsert: each row is keyed by coaCode + year + property + organization.
     * Existing rows are updated; new COA codes insert a row. Rows not in the file are unchanged.
     */
    @Transactional
    public ActualsBulkImportResult importExcel(MultipartFile file, int year, long propertyId, long organizationId)
            throws IOException {
        Property property = propertyRepository.findById(propertyId)
                .orElseThrow(() -> new EntityNotFoundException("Property not found: " + propertyId));
        List<ParsedActualsRow> rows = actualsExcelParser.parse(file.getInputStream());
        Map<String, String> coaNameByCode = coaRepository
                .findByProperty_IdAndOrganizationIdOrderByCoaCodeAsc(propertyId, organizationId)
                .stream()
                .collect(Collectors.toMap(Coa::getCoaCode, Coa::getCoaName, (a, b) -> a));
        Set<String> unknownCoa = new LinkedHashSet<>();
        for (ParsedActualsRow r : rows) {
            String code = r.coaCode();
            if (!coaNameByCode.containsKey(code)) {
                unknownCoa.add(code);
            }
        }
        if (!unknownCoa.isEmpty()) {
            throw new IllegalArgumentException(
                    "These COA codes are not defined for this property in Chart of accounts: "
                            + String.join(", ", unknownCoa)
                            + ". Add them under COA first, then upload actuals again.");
        }
        int inserted = 0;
        int updated = 0;
        for (ParsedActualsRow r : rows) {
            Optional<ActualsDetails> opt = actualsDetailRepository.findByCoaCodeAndYearAndProperty_IdAndOrganizationId(
                    r.coaCode(), year, propertyId, organizationId);
            ActualsDetails e;
            if (opt.isPresent()) {
                e = opt.get();
                updated++;
            } else {
                e = new ActualsDetails();
                inserted++;
            }
            e.setCoaCode(r.coaCode());
            String resolvedCoaName = coaNameByCode.getOrDefault(r.coaCode(), r.coaName());
            e.setCoaName(resolvedCoaName == null ? null : resolvedCoaName.trim());
            e.setYear(year);
            e.setProperty(property);
            e.setOrganizationId(organizationId);
            var m = r.months();
            e.setJanValue(m[0]);
            e.setFebValue(m[1]);
            e.setMarValue(m[2]);
            e.setAprValue(m[3]);
            e.setMayValue(m[4]);
            e.setJunValue(m[5]);
            e.setJulValue(m[6]);
            e.setAugValue(m[7]);
            e.setSepValue(m[8]);
            e.setOctValue(m[9]);
            e.setNovValue(m[10]);
            e.setDecValue(m[11]);
            e.setDailyDetails(r.daily().isEmpty() ? null : r.daily());
            actualsDetailRepository.save(e);
        }
        int total = inserted + updated;
        return new ActualsBulkImportResult(total, inserted, updated);
    }

    private ActualsDetailDto toDto(ActualsDetails e, Map<String, String> coaNameByCode) {
        String coaName = e.getCoaName();
        if (coaName == null || coaName.isBlank()) {
            coaName = coaNameByCode.getOrDefault(e.getCoaCode(), "");
        }
        return new ActualsDetailDto(
                e.getId(),
                e.getCoaCode(),
                coaName,
                e.getJanValue(),
                e.getFebValue(),
                e.getMarValue(),
                e.getAprValue(),
                e.getMayValue(),
                e.getJunValue(),
                e.getJulValue(),
                e.getAugValue(),
                e.getSepValue(),
                e.getOctValue(),
                e.getNovValue(),
                e.getDecValue(),
                e.getDailyDetails(),
                e.getYear(),
                e.getProperty().getId(),
                e.getProperty().getName(),
                e.getOrganizationId()
        );
    }
}
