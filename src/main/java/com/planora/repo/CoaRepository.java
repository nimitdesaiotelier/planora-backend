package com.planora.repo;

import com.planora.domain.Coa;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CoaRepository extends JpaRepository<Coa, Long> {

    List<Coa> findByProperty_IdAndOrganizationIdOrderByCoaCodeAsc(Long propertyId, Long organizationId);

    Optional<Coa> findByIdAndProperty_IdAndOrganizationId(Long id, Long propertyId, Long organizationId);

    boolean existsByCoaCodeAndProperty_IdAndOrganizationId(String coaCode, Long propertyId, Long organizationId);

    boolean existsByCoaCodeAndProperty_IdAndOrganizationIdAndIdNot(
            String coaCode, Long propertyId, Long organizationId, Long id);

    Optional<Coa> findByCoaCodeAndProperty_IdAndOrganizationId(
            String coaCode, Long propertyId, Long organizationId);
}
