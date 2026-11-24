package com.nemaz.school.identity.application;

import java.util.UUID;

import com.nemaz.school.common.tx.AfterCommit;
import com.nemaz.school.identity.domain.UserStatus;
import com.nemaz.school.identity.infrastructure.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * What every authenticated request needs to know about its user: status and preferred
 * language. Cached in Redis for at most a minute and evicted after commit when either changes,
 * so suspending an account takes effect on the next request.
 */
@Component
public class UserSnapshotCache {

	public static final String CACHE_NAME = "auth-users";

	private static final Logger log = LoggerFactory.getLogger(UserSnapshotCache.class);

	private final UserRepository users;

	private final CacheManager caches;

	UserSnapshotCache(UserRepository users, CacheManager caches) {
		this.users = users;
		this.caches = caches;
	}

	/** Null for an unknown user (not cached). */
	@Cacheable(cacheNames = CACHE_NAME, key = "#userId")
	@Transactional(readOnly = true)
	public UserSnapshot get(UUID userId) {
		return this.users.findById(userId)
			.map((u) -> new UserSnapshot((u.getDeletedAt() == null) ? u.getStatus() : UserStatus.DEACTIVATED,
					u.getPreferredLanguageCode()))
			.orElse(null);
	}

	public void evictAfterCommit(UUID userId) {
		AfterCommit.run(() -> {
			try {
				Cache cache = this.caches.getCache(CACHE_NAME);
				if (cache != null) {
					cache.evict(userId);
				}
			}
			catch (RuntimeException ex) {
				log.warn("Could not evict cached account of user {} (stale for at most a minute): {}", userId,
						ex.getMessage());
			}
		});
	}

	public record UserSnapshot(UserStatus status, String preferredLanguageCode) {
	}

}
