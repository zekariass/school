package com.nemaz.school.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.web.ClientRequests;
import com.nemaz.school.identity.LoginCodeRedeemer;
import com.nemaz.school.identity.LoginCodeRedeemer.LoginCodeGrant;
import com.nemaz.school.identity.application.TokenService.TokenPair;
import com.nemaz.school.identity.domain.AccountType;
import com.nemaz.school.identity.domain.AuthMethod;
import com.nemaz.school.identity.domain.User;
import com.nemaz.school.identity.infrastructure.UserRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Student sign-in with a teacher-issued QR token or text code (decision D6, R-31).
 * <ul>
 * <li>The people module validates the code ({@link LoginCodeRedeemer}); every failure (unknown, revoked or
 * expired code, withdrawn student, inactive school or account) is the same 400 {@code auth.invalid_code}.</li>
 * <li>Rate limits count failures per client address (20 / 15 min) and per code prefix (10 / 15 min), plus a
 * generous 300 attempts per address, so a classroom behind one address can sign in together while
 * guessing stays infeasible (see {@link AuthRateLimits}).</li>
 * <li>The session is a normal one except for its shorter lifetime ({@code school.auth.sessions.login-code-*}).</li>
 * <li>The submitted code is never logged or audited.</li>
 * </ul>
 */
@Service
public class LoginCodeSignInService {

	private final List<LoginCodeRedeemer> redeemers;

	private final UserRepository users;

	private final TokenService tokens;

	private final AuthRateLimits limits;

	private final AuditLog audit;

	private final Clock clock;

	private final TransactionTemplate tx;

	LoginCodeSignInService(List<LoginCodeRedeemer> redeemers, UserRepository users, TokenService tokens,
			AuthRateLimits limits, AuditLog audit, Clock clock, TransactionTemplate tx) {
		this.redeemers = redeemers;
		this.users = users;
		this.tokens = tokens;
		this.limits = limits;
		this.audit = audit;
		this.clock = clock;
		this.tx = tx;
	}

	public TokenPair signIn(String submitted) {
		String ip = ClientRequests.ip();
		String prefix = prefix(submitted);
		this.limits.loginCodeAttempt(ip, prefix);
		TokenPair result = this.tx.execute((status) -> attempt(submitted).orElse(null));
		if (result == null) {
			this.limits.loginCodeFailed(ip, prefix);
			this.tx.executeWithoutResult((status) -> this.audit
				.record(AuditEntry.of("auth.login_code.sign_in", AuditOutcome.FAILURE).actor(null)));
			throw ApiException.badRequest("auth.invalid_code");
		}
		return result;
	}

	private Optional<TokenPair> attempt(String submitted) {
		Instant now = this.clock.instant();
		for (LoginCodeRedeemer redeemer : this.redeemers) {
			LoginCodeGrant grant = redeemer.redeem(submitted, now).orElse(null);
			if (grant == null) {
				continue;
			}
			User user = this.users.findById(grant.userId()).orElse(null);
			if (user == null || !user.isActive() || user.getAccountType() != AccountType.MANAGED) {
				return Optional.empty();
			}
			user.signedIn(now);
			this.users.save(user);
			TokenPair pair = this.tokens.startSession(user.getId(), AuthMethod.LOGIN_CODE);
			this.audit.record(AuditEntry.of("auth.login_code.sign_in", AuditOutcome.SUCCESS)
				.actor(user.getId())
				.target("user", user.getId())
				.school(grant.schoolId())
				.metadata("codeId", grant.codeId().toString()));
			return Optional.of(pair);
		}
		return Optional.empty();
	}

	/** The first 5 letters and digits, upper-cased: stable across the hyphen and case variants of one code. */
	static String prefix(String submitted) {
		StringBuilder out = new StringBuilder(5);
		if (submitted != null) {
			for (int i = 0; i < submitted.length() && out.length() < 5; i++) {
				char c = submitted.charAt(i);
				if (Character.isLetterOrDigit(c)) {
					out.append(Character.toUpperCase(c));
				}
			}
		}
		return out.toString().toUpperCase(Locale.ROOT);
	}

}
