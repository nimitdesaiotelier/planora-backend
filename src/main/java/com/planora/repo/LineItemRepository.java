package com.planora.repo;

import com.planora.domain.LineItem;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LineItemRepository extends JpaRepository<LineItem, Long> {

    List<LineItem> findByPlan_Id(Long planId);

    Optional<LineItem> findByIdAndPlan_Id(Long id, Long planId);

    Optional<LineItem> findByPlan_IdAndLineKey(Long planId, String lineKey);
}
