package com.nemaz.school.moderation.application;

import java.util.UUID;

import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.text.PlainText;
import com.nemaz.school.identity.UserAccounts;
import com.nemaz.school.moderation.application.ModerationModels.LockView;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The platform lock of an account (docs/Safeguarding_Rules.md S2.4, S5.3): only for holders of {@code platform.users.lock}
 * (platform moderators and administrators), never for a school moderator. Locking revokes every session through the token
 * service, so the sockets and push devices of the person close (SessionsRevoked); nothing is deleted. Reversible. The reason
 * (10 to 500 characters) is written to the audit row, which is the record of who locked whom and why.
 */
@Service
public class PlatformLockService {

	private final UserAccounts accounts;

	private final JdbcClient jdbc;

	private final AuditLog audit;

	PlatformLockService(UserAccounts accounts, JdbcClient jdbc, AuditLog audit) {
		this.accounts = accounts;
		this.jdbc = jdbc;
		this.audit = audit;
	}

	@Transactional
	public LockView lock(CurrentUser actor, UUID userId, String rawReason) {
		String reason = reason(rawReason);
		if (actor.userId().equals(userId)) {
			throw ApiException.conflict("moderation.cannot_lock_self");
		}
		requireExists(userId);
		if (!this.accounts.lock(userId) && !isLocked(userId)) {
			throw ApiException.conflict("moderation.user_not_lockable");
		}
		this.audit.record(AuditEntry.of("moderation.user.lock", AuditOutcome.SUCCESS)
			.target("user", userId)
			.metadata("reason", reason));
		return new LockView(userId, true);
	}

	@Transactional
	public LockView unlock(CurrentUser actor, UUID userId, String rawReason) {
		String reason = reason(rawReason);
		requireExists(userId);
		if (!this.accounts.unlock(userId) && isLocked(userId)) {
			throw ApiException.conflict("moderation.user_not_lockable");
		}
		this.audit.record(AuditEntry.of("moderation.user.unlock", AuditOutcome.SUCCESS)
			.target("user", userId)
			.metadata("reason", reason));
		return new LockView(userId, false);
	}

	private static String reason(String raw) {
		String clean = (raw == null) ? "" : PlainText.clean(raw);
		if (clean.length() < CaseService.MIN_REASON) {
			throw ApiException.badRequest("moderation.reason_required");
		}
		if (clean.length() > CaseService.MAX_REASON) {
			throw ApiException.badRequest("moderation.reason_too_long");
		}
		return clean;
	}

	private void requireExists(UUID userId) {
		boolean exists = this.jdbc.sql("select exists (select 1 from users where id = :id)").param("id", userId).query(Boolean.class).single();
		if (!exists) {
			throw ApiException.notFound("user.not_found");
		}
	}

	private boolean isLocked(UUID userId) {
		return this.jdbc.sql("select status = 'LOCKED' from users where id = :id").param("id", userId).query(Boolean.class).single();
	}

}
