package com.nemaz.school.subscriptions.infrastructure;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.nemaz.school.subscriptions.SubscriptionStatus;
import com.nemaz.school.subscriptions.domain.Subscription;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

	/** Live (TRIALING/ACTIVE) subscriptions of a school whose period covers {@code now}, newest first. */
	@Query("""
			select s from Subscription s
			where s.schoolId = :schoolId and s.status in :statuses
			  and (s.currentPeriodStart is null or s.currentPeriodStart <= :now)
			  and (s.currentPeriodEnd is null or s.currentPeriodEnd > :now)
			order by s.createdAt desc, s.id desc
			""")
	List<Subscription> findCovering(@Param("schoolId") UUID schoolId,
			@Param("statuses") Collection<SubscriptionStatus> statuses, @Param("now") Instant now);

	/** Every live subscription row of a school regardless of period (what a new plan must end). */
	List<Subscription> findBySchoolIdAndStatusIn(UUID schoolId, Collection<SubscriptionStatus> statuses);

}
