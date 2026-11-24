package com.nemaz.school.subscriptions.domain;

import java.io.Serializable;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

@Entity
@Table(name = "plan_entitlements")
@IdClass(PlanEntitlement.Key.class)
public class PlanEntitlement {

	@Id
	private UUID planId;

	@Id
	private String featureKey;

	private boolean enabled;

	private Long limitValue;

	protected PlanEntitlement() {
	}

	public UUID getPlanId() {
		return this.planId;
	}

	public String getFeatureKey() {
		return this.featureKey;
	}

	public boolean isEnabled() {
		return this.enabled;
	}

	public Long getLimitValue() {
		return this.limitValue;
	}

	public record Key(UUID planId, String featureKey) implements Serializable {
	}

}
