package com.planora.repo;

import com.planora.domain.Plan;
import com.planora.domain.PlanType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanRepository extends JpaRepository<Plan, Long> {

    List<Plan> findByProperty_IdOrderByIdAsc(Long propertyId);

    Optional<Plan> findFirstByProperty_IdAndFiscalYearAndPlanTypeOrderByIdAsc(
            Long propertyId, Integer fiscalYear, PlanType planType);
}
