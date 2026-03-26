package com.planora.web;

import com.planora.domain.Property;
import com.planora.repo.PropertyRepository;
import com.planora.web.dto.PropertyDto;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/properties")
@RequiredArgsConstructor
public class PropertyController {

    private final PropertyRepository propertyRepository;

    @GetMapping
    public List<PropertyDto> list(@RequestParam(required = false) Long organizationId) {
        List<Property> list = organizationId == null
                ? propertyRepository.findAllByOrderByNameAsc()
                : propertyRepository.findByOrganizationIdOrderByNameAsc(organizationId);
        return list.stream().map(p -> new PropertyDto(p.getId(), p.getName(), p.getOrganizationId())).toList();
    }
}
