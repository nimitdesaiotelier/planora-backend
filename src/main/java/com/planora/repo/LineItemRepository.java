package com.planora.repo;

import com.planora.domain.PlanMonthlyDetails;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LineItemRepository extends JpaRepository<PlanMonthlyDetails, Long> {

    List<PlanMonthlyDetails> findByPlan_Id(Long planId);

    Optional<PlanMonthlyDetails> findByIdAndPlan_Id(Long id, Long planId);

    Optional<PlanMonthlyDetails> findByPlan_IdAndLineKey(Long planId, String lineKey);
}
