package com.nemaz.school.subscriptions.infrastructure;

import java.util.List;
import java.util.UUID;

import com.nemaz.school.subscriptions.domain.PlanEntitlement;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanEntitlementRepository extends JpaRepository<PlanEntitlement, PlanEntitlement.Key> {

	List<PlanEntitlement> findByPlanId(UUID planId);

}
