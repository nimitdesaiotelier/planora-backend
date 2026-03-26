package com.planora.bootstrap;

import com.planora.domain.Property;
import com.planora.repo.PropertyRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(1)
@RequiredArgsConstructor
public class PropertySampleDataLoader implements ApplicationRunner {

    private static final Long DEMO_ORG_ID = 1L;

    private final PropertyRepository propertyRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (propertyRepository.count() > 0) {
            return;
        }
        List<Property> props = List.of(
                property("Courtyard Burlington"),
                property("Doubletree New Bern"),
                property("SpringHill Suites South Raleigh"),
                property("Hampton Inn Elizabeth City"),
                property("Hampton Inn Roanoke Rapids")
        );
        propertyRepository.saveAll(props);
    }

    private static Property property(String name) {
        return property(DEMO_ORG_ID, name);
    }

    private static Property property(Long orgId, String name) {
        Property p = new Property();
        p.setName(name);
        p.setOrganizationId(orgId);
        return p;
    }
}
