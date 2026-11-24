package com.nemaz.school.subscriptions.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.nemaz.school.subscriptions.domain.SubscriptionPlan;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionPlanRepository extends JpaRepository<SubscriptionPlan, UUID> {

	Optional<SubscriptionPlan> findByCode(String code);

	List<SubscriptionPlan> findByAudienceAndActiveTrueOrderByCodeAsc(com.nemaz.school.subscriptions.domain.PlanAudience audience);

}
