package com.nemaz.school.subscriptions.domain;

import java.time.Instant;
import java.util.UUID;

import com.nemaz.school.common.persistence.BaseEntity;
import com.nemaz.school.subscriptions.SubscriptionStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

@Entity
@Table(name = "subscriptions")
public class Subscription extends BaseEntity {

	private UUID planId;

	@Enumerated(EnumType.STRING)
	private SubscriberType subscriberType;

	private UUID schoolId;

	private UUID organizationId;

	@Enumerated(EnumType.STRING)
	private SubscriptionStatus status;

	private Instant currentPeriodStart;

	private Instant currentPeriodEnd;

	private Instant trialEndsAt;

	private Instant cancelAt;

	private UUID assignedBy;

	private String externalRef;

	protected Subscription() {
	}

	public static Subscription forSchool(UUID planId, UUID schoolId, SubscriptionStatus status, Instant start,
			Instant end, UUID assignedBy) {
		Subscription s = new Subscription();
		s.planId = planId;
		s.subscriberType = SubscriberType.SCHOOL;
		s.schoolId = schoolId;
		s.status = status;
		s.currentPeriodStart = start;
		s.currentPeriodEnd = end;
		s.trialEndsAt = (status == SubscriptionStatus.TRIALING) ? end : null;
		s.assignedBy = assignedBy;
		return s;
	}

	/** Ends this subscription immediately (a new plan replaces it). */
	public void cancel(Instant now) {
		this.status = SubscriptionStatus.CANCELLED;
		this.cancelAt = now;
	}

	public UUID getPlanId() {
		return this.planId;
	}

	public UUID getSchoolId() {
		return this.schoolId;
	}

	public SubscriptionStatus getStatus() {
		return this.status;
	}

	public Instant getCurrentPeriodStart() {
		return this.currentPeriodStart;
	}

	public Instant getCurrentPeriodEnd() {
		return this.currentPeriodEnd;
	}

	public Instant getTrialEndsAt() {
		return this.trialEndsAt;
	}

}
