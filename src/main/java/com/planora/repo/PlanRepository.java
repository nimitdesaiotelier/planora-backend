package com.planora.repo;

import com.planora.domain.Plan;
import com.planora.enums.PlanStatus;
import com.planora.enums.PlanType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanRepository extends JpaRepository<Plan, Long> {

    List<Plan> findByStatusOrderByIdAsc(PlanStatus propertyId);

    Optional<Plan> findFirstByProperty_IdAndFiscalYearAndPlanTypeOrderByIdAsc(
            Long propertyId, Integer fiscalYear, PlanType planType);

    List<Plan> findByProperty_IdAndStatusOrderByIdAsc(Long propertyId, PlanStatus status);

    Optional<Plan> findByIdAndStatus(Long id, PlanStatus status);

    Optional<Plan> findFirstByProperty_IdAndFiscalYearAndPlanTypeAndStatusOrderByIdAsc(
            Long propertyId, Integer fiscalYear, PlanType planType, PlanStatus status);
}
