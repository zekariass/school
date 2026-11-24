package com.nemaz.school.authorization.application;

import java.util.List;
import java.util.UUID;

import com.nemaz.school.authorization.infrastructure.RolePermissions;
import com.nemaz.school.common.security.RoleGrant;
import com.nemaz.school.common.tx.AfterCommit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Redis cache of a user's ACTIVE grants, read on every authenticated request. A separate
 * bean so the {@code @Cacheable} proxy is crossed; Redis errors fall through to the database
 * (CacheConfig's error handler).
 */
@Component
public class GrantCache {

	public static final String CACHE_NAME = "role-grants";

	private static final Logger log = LoggerFactory.getLogger(GrantCache.class);

	private final RolePermissions rolePermissions;

	private final CacheManager caches;

	GrantCache(RolePermissions rolePermissions, CacheManager caches) {
		this.rolePermissions = rolePermissions;
		this.caches = caches;
	}

	@Cacheable(cacheNames = CACHE_NAME, key = "#userId")
	@Transactional(readOnly = true)
	public GrantSet get(UUID userId) {
		return new GrantSet(this.rolePermissions.activeGrants(userId));
	}

	/** Evicts the user's entry once the current transaction has committed. */
	void evictAfterCommit(UUID userId) {
		AfterCommit.run(() -> {
			try {
				Cache cache = this.caches.getCache(CACHE_NAME);
				if (cache != null) {
					cache.evict(userId);
				}
			}
			catch (RuntimeException ex) {
				log.warn("Could not evict role grants of user {} (stale for at most the cache TTL): {}", userId,
						ex.getMessage());
			}
		});
	}

	public record GrantSet(List<RoleGrant> grants) {

		public GrantSet {
			grants = List.copyOf(grants);
		}

	}

}
