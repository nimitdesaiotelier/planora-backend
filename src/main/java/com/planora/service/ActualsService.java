package com.planora.service;

import com.planora.domain.ActualsDetails;
import com.planora.domain.Property;
import com.planora.repo.ActualsDetailRepository;
import com.planora.repo.PropertyRepository;
import com.planora.service.ActualsExcelParser.ParsedActualsRow;
import com.planora.web.dto.ActualsDetailDto;
import jakarta.persistence.EntityNotFoundException;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class ActualsService {

    private final ActualsDetailRepository actualsDetailRepository;
    private final PropertyRepository propertyRepository;
    private final ActualsExcelParser actualsExcelParser;

    @Transactional(readOnly = true)
    public List<ActualsDetailDto> list(int year, long propertyId, long organizationId) {
        return actualsDetailRepository
                .findByYearAndProperty_IdAndOrganizationIdOrderByCoaCodeAsc(year, propertyId, organizationId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public int importExcel(MultipartFile file, int year, long propertyId, long organizationId) throws IOException {
        Property property = propertyRepository.findById(propertyId)
                .orElseThrow(() -> new EntityNotFoundException("Property not found: " + propertyId));
        List<ParsedActualsRow> rows = actualsExcelParser.parse(file.getInputStream());
        int count = 0;
        for (ParsedActualsRow r : rows) {
            ActualsDetails e = actualsDetailRepository
                    .findByCoaCodeAndYearAndProperty_IdAndOrganizationId(
                            r.coaCode(), year, propertyId, organizationId)
                    .orElseGet(ActualsDetails::new);
            e.setCoaCode(r.coaCode());
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
            count++;
        }
        return count;
    }

    private ActualsDetailDto toDto(ActualsDetails e) {
        return new ActualsDetailDto(
                e.getId(),
                e.getCoaCode(),
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
