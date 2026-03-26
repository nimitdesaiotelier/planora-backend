package com.planora.repo;

import com.planora.domain.Property;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PropertyRepository extends JpaRepository<Property, Long> {

    List<Property> findByOrganizationIdOrderByNameAsc(Long organizationId);

    List<Property> findAllByOrderByNameAsc();
}
