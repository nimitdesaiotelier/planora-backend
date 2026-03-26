package com.planora.repo;

import com.planora.domain.ActualsDetail;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActualsDetailRepository extends JpaRepository<ActualsDetail, Long> {

    List<ActualsDetail> findByYearAndProperty_IdAndOrganizationIdOrderByCoaCodeAsc(
            Integer year, Long propertyId, Long organizationId);

    Optional<ActualsDetail> findByCoaCodeAndYearAndProperty_IdAndOrganizationId(
            String coaCode, Integer year, Long propertyId, Long organizationId);

    void deleteByYearAndProperty_IdAndOrganizationId(Integer year, Long propertyId, Long organizationId);
}
