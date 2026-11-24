package com.nemaz.school.subscriptions.application;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.subscriptions.AssignSubscription;
import com.nemaz.school.subscriptions.Entitlement;
import com.nemaz.school.subscriptions.SchoolEntitlements;
import com.nemaz.school.subscriptions.SubscriptionAssigned;
import com.nemaz.school.subscriptions.SubscriptionService;
import com.nemaz.school.subscriptions.SubscriptionStatus;
import com.nemaz.school.subscriptions.SubscriptionView;
import com.nemaz.school.subscriptions.domain.PlanAudience;
import com.nemaz.school.subscriptions.domain.Subscription;
import com.nemaz.school.subscriptions.domain.SubscriptionPlan;
import com.nemaz.school.subscriptions.infrastructure.PlanEntitlementRepository;
import com.nemaz.school.subscriptions.infrastructure.SubscriptionPlanRepository;
import com.nemaz.school.subscriptions.infrastructure.SubscriptionRepository;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DefaultSubscriptionService implements SubscriptionService {

	private static final Set<SubscriptionStatus> LIVE = EnumSet.of(SubscriptionStatus.TRIALING,
			SubscriptionStatus.ACTIVE);

	private final SubscriptionRepository subscriptions;

	private final SubscriptionPlanRepository plans;

	private final PlanEntitlementRepository entitlements;

	private final ApplicationEventPublisher events;

	private final Clock clock;

	DefaultSubscriptionService(SubscriptionRepository subscriptions, SubscriptionPlanRepository plans,
			PlanEntitlementRepository entitlements, ApplicationEventPublisher events, Clock clock) {
		this.subscriptions = subscriptions;
		this.plans = plans;
		this.entitlements = entitlements;
		this.events = events;
		this.clock = clock;
	}

	@Override
	@Transactional(readOnly = true)
	public SchoolEntitlements entitlementsFor(UUID schoolId) {
		Optional<Subscription> current = this.subscriptions.findCovering(schoolId, LIVE, Instant.now(this.clock))
			.stream()
			.findFirst();
		SubscriptionPlan plan = current.flatMap((s) -> this.plans.findById(s.getPlanId()))
			.orElseGet(() -> this.plans.findByCode(FREE_PLAN_CODE)
				.orElseThrow(() -> new IllegalStateException("Plan " + FREE_PLAN_CODE + " is not seeded")));
		Map<String, Entitlement> byFeature = new HashMap<>();
		this.entitlements.findByPlanId(plan.getId())
			.forEach((e) -> byFeature.put(e.getFeatureKey(), new Entitlement(e.isEnabled(), e.getLimitValue())));
		return new SchoolEntitlements(plan.getCode(), current.isEmpty(), byFeature);
	}

	@Override
	@Transactional
	public SubscriptionView assignToSchool(UUID schoolId, AssignSubscription command, UUID actorId) {
		SubscriptionPlan plan = this.plans.findByCode(command.planCode())
			.orElseThrow(() -> ApiException.notFound("subscription.plan_not_found"));
		if (!plan.isActive() || plan.getAudience() != PlanAudience.SCHOOL) {
			throw ApiException.badRequest("subscription.plan_not_applicable");
		}
		SubscriptionStatus status = (command.status() != null) ? command.status() : SubscriptionStatus.ACTIVE;
		if (!LIVE.contains(status)) {
			throw ApiException.badRequest("subscription.invalid_status");
		}
		Instant now = Instant.now(this.clock);
		if (command.currentPeriodEnd() != null && !command.currentPeriodEnd().isAfter(now)) {
			throw ApiException.badRequest("subscription.invalid_period");
		}

		// The partial unique index allows one live row per school: end the old one first.
		this.subscriptions.findBySchoolIdAndStatusIn(schoolId, LIVE).forEach((old) -> old.cancel(now));
		this.subscriptions.flush();

		Subscription created = this.subscriptions.saveAndFlush(
				Subscription.forSchool(plan.getId(), schoolId, status, now, command.currentPeriodEnd(), actorId));
		this.events.publishEvent(new SubscriptionAssigned(created.getId(), schoolId, plan.getCode(), status));
		return new SubscriptionView(created.getId(), schoolId, plan.getCode(), status, created.getCurrentPeriodStart(),
				created.getCurrentPeriodEnd(), created.getTrialEndsAt(), created.getCreatedAt());
	}

}
