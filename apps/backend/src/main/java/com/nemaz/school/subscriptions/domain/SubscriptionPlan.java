package com.nemaz.school.subscriptions.domain;

import java.util.Map;

import com.nemaz.school.common.persistence.IdentifiedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "subscription_plans")
public class SubscriptionPlan extends IdentifiedEntity {

	private String code;

	@Enumerated(EnumType.STRING)
	private PlanAudience audience;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	private Map<String, String> nameI18n;

	@Enumerated(EnumType.STRING)
	private BillingPeriod billingPeriod;

	@Column(name = "is_active")
	private boolean active;

	protected SubscriptionPlan() {
	}

	public String getCode() {
		return this.code;
	}

	public PlanAudience getAudience() {
		return this.audience;
	}

	public Map<String, String> getNameI18n() {
		return this.nameI18n;
	}

	public BillingPeriod getBillingPeriod() {
		return this.billingPeriod;
	}

	public boolean isActive() {
		return this.active;
	}

}
