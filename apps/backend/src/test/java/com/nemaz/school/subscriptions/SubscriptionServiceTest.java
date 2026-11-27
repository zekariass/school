package com.nemaz.school.subscriptions;

import java.time.Instant;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.tenancy.TenancyTestSupport;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
class SubscriptionServiceTest extends TenancyTestSupport {

	@Autowired
	SubscriptionService subscriptions;

	@Test
	void aSchoolWithoutASubscriptionGetsTheFreePlan() {
		SchoolEntitlements entitlements = this.subscriptions.entitlementsFor(newSchool());
		assertThat(entitlements.planCode()).isEqualTo("SCHOOL_FREE");
		assertThat(entitlements.freeFallback()).isTrue();
		assertThat(entitlements.of("attendance").enabled()).isTrue();
		assertThat(entitlements.of("library").enabled()).isFalse();
		assertThat(entitlements.of("max_students").limit()).isEqualTo(100L);
		assertThat(entitlements.of("not_a_feature")).isEqualTo(Entitlement.NONE);
	}

	@Test
	void assigningAPlanEndsThePreviousSubscription() {
		UUID school = newSchool();
		SubscriptionView first = this.subscriptions.assignToSchool(school, new AssignSubscription("SCHOOL_BASIC", null, null), null);
		SubscriptionView second = this.subscriptions.assignToSchool(school,
				new AssignSubscription("SCHOOL_PREMIUM", SubscriptionStatus.TRIALING, Instant.now().plusSeconds(3600)), null);
		assertThat(first.status()).isEqualTo(SubscriptionStatus.ACTIVE);
		assertThat(second.trialEndsAt()).isNotNull();
		assertThat(this.jdbc.queryForObject(
				"select count(*) from subscriptions where school_id = ? and status in ('TRIALING', 'ACTIVE')", Integer.class, school))
			.isEqualTo(1);
		assertThat(this.jdbc.queryForObject("select cancel_at is not null from subscriptions where id = ?", Boolean.class, first.id()))
			.isTrue();
		SchoolEntitlements current = this.subscriptions.entitlementsFor(school);
		assertThat(current.planCode()).isEqualTo("SCHOOL_PREMIUM");
		assertThat(current.freeFallback()).isFalse();
	}

	@Test
	void theDatabaseAllowsOnlyOneLiveSubscriptionPerSchool() {
		UUID school = newSchool();
		this.subscriptions.assignToSchool(school, new AssignSubscription("SCHOOL_BASIC", null, null), null);
		assertThatThrownBy(() -> this.jdbc.update(
				"insert into subscriptions (plan_id, subscriber_type, school_id, status) "
						+ "select id, 'SCHOOL', ?, 'ACTIVE' from subscription_plans where code = 'SCHOOL_FREE'",
				school))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void rejectsUnknownAndNonSchoolPlans() {
		UUID school = newSchool();
		assertThatThrownBy(() -> this.subscriptions.assignToSchool(school, new AssignSubscription("NOPE", null, null), null))
			.isInstanceOfSatisfying(ApiException.class, (e) -> assertThat(e.code()).isEqualTo("subscription.plan_not_found"));
		assertThat(this.jdbc.queryForObject("select count(*) from subscriptions where school_id = ?", Integer.class, school)).isZero();
	}

}
