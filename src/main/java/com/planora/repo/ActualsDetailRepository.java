package com.planora.repo;

import com.planora.domain.ActualsDetails;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ActualsDetailRepository extends JpaRepository<ActualsDetails, Long> {

    @Query(
            "SELECT DISTINCT a.year FROM ActualsDetails a WHERE a.property.id = :propertyId "
                    + "AND a.organizationId = :organizationId ORDER BY a.year DESC")
    List<Integer> findDistinctYearsByPropertyIdAndOrganizationId(
            @Param("propertyId") Long propertyId, @Param("organizationId") Long organizationId);

    List<ActualsDetails> findByYearAndProperty_IdAndOrganizationIdOrderByCoaCodeAsc(
            Integer year, Long propertyId, Long organizationId);

    Optional<ActualsDetails> findByCoaCodeAndYearAndProperty_IdAndOrganizationId(
            String coaCode, Integer year, Long propertyId, Long organizationId);

    void deleteByYearAndProperty_IdAndOrganizationId(Integer year, Long propertyId, Long organizationId);
}
