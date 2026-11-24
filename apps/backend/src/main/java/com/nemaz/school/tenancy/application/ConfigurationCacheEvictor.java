package com.nemaz.school.tenancy.application;

import java.util.List;
import java.util.UUID;

import com.nemaz.school.subscriptions.SubscriptionAssigned;
import com.nemaz.school.tenancy.ConfigurationChanged;
import com.nemaz.school.tenancy.infrastructure.SchoolRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Evicts cached school configurations after the transaction that changed them has
 * committed (evicting earlier would let a concurrent reader re-cache the old data).
 *
 * <ul>
 * <li>school scope or a subscription change: that school's entry</li>
 * <li>organization scope: the entries of the organization's schools</li>
 * <li>country or platform scope: the whole cache (simple and correct; these changes are rare)</li>
 * </ul>
 *
 * Eviction is idempotent, so redelivery of the event is harmless. Staleness is bounded by
 * the cache TTL even if Redis was unreachable at eviction time.
 */
@Component
class ConfigurationCacheEvictor {

	private static final Logger log = LoggerFactory.getLogger(ConfigurationCacheEvictor.class);

	private final SchoolConfigurationCache cache;

	private final SchoolRepository schools;

	ConfigurationCacheEvictor(SchoolConfigurationCache cache, SchoolRepository schools) {
		this.cache = cache;
		this.schools = schools;
	}

	@TransactionalEventListener
	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	void on(ConfigurationChanged event) {
		switch (event.scopeType()) {
			case SCHOOL -> this.cache.evict(event.schoolId());
			case ORGANIZATION -> {
				List<UUID> ids = this.schools.findIdsByOrganizationId(event.organizationId());
				ids.forEach(this.cache::evict);
			}
			case COUNTRY, PLATFORM -> this.cache.clear();
		}
		log.debug("Evicted school configuration for {} change at {}", event.kind(), event.scopeType());
	}

	@TransactionalEventListener
	void on(SubscriptionAssigned event) {
		this.cache.evict(event.schoolId());
	}

}
