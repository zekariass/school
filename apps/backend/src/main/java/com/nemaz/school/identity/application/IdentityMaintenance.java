package com.nemaz.school.identity.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import com.nemaz.school.identity.infrastructure.AuthSessionStore;
import com.nemaz.school.identity.infrastructure.OtpChallengeStore;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Daily clean-up of sign-in data that is no longer needed: one-time code challenges older
 * than a week (they hold phone numbers and emails) and sessions expired for a month. The
 * audit log keeps the history.
 */
@Component
public class IdentityMaintenance {

	private static final Logger log = LoggerFactory.getLogger(IdentityMaintenance.class);

	private final OtpChallengeStore challenges;

	private final AuthSessionStore sessions;

	private final Clock clock;

	IdentityMaintenance(OtpChallengeStore challenges, AuthSessionStore sessions, Clock clock) {
		this.challenges = challenges;
		this.sessions = sessions;
		this.clock = clock;
	}

	@Scheduled(cron = "0 40 2 * * *", zone = "UTC")
	@SchedulerLock(name = "identity.purge")
	@Transactional
	public void purge() {
		Instant now = this.clock.instant();
		int codes = this.challenges.purgeCreatedBefore(now.minus(Duration.ofDays(7)));
		int expired = this.sessions.purgeExpiredBefore(now.minus(Duration.ofDays(30)));
		if (codes + expired > 0) {
			log.info("Purged {} old sign-in codes and {} expired sessions", codes, expired);
		}
	}

}
