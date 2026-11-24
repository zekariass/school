package com.nemaz.school.identity.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;

import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.security.Masking;
import com.nemaz.school.common.web.ClientRequests;
import com.nemaz.school.identity.application.TokenService.TokenPair;
import com.nemaz.school.identity.domain.AuthMethod;
import com.nemaz.school.identity.domain.PasswordCredential;
import com.nemaz.school.identity.domain.User;
import com.nemaz.school.identity.infrastructure.PasswordCredentialRepository;
import com.nemaz.school.identity.infrastructure.UserRepository;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Password sign-in (secondary, for staff who prefer it). Identifier: email, phone (national
 * format read in {@code country}, Ethiopia by default) or username. Unknown account, wrong
 * password, locked credential and inactive account all answer the same 400
 * {@code auth.invalid_credentials}, and an unknown account still runs one argon2 hash, so
 * neither the body nor the timing reveals which. Five consecutive failures lock the password
 * for 15 minutes (a correct password is refused while locked).
 */
@Service
public class PasswordSignInService {

	static final int MAX_PASSWORD_LENGTH = 128;

	private final UserRepository users;

	private final PasswordCredentialRepository credentials;

	private final PasswordEncoder encoder;

	private final TokenService tokens;

	private final AuthRateLimits limits;

	private final AuditLog audit;

	private final IdentityProperties props;

	private final Clock clock;

	private final TransactionTemplate tx;

	private final String dummyHash;

	PasswordSignInService(UserRepository users, PasswordCredentialRepository credentials, PasswordEncoder encoder,
			TokenService tokens, AuthRateLimits limits, AuditLog audit, IdentityProperties props, Clock clock,
			TransactionTemplate tx) {
		this.users = users;
		this.credentials = credentials;
		this.encoder = encoder;
		this.tokens = tokens;
		this.limits = limits;
		this.audit = audit;
		this.props = props;
		this.clock = clock;
		this.tx = tx;
		byte[] random = new byte[24];
		new SecureRandom().nextBytes(random);
		this.dummyHash = encoder.encode(Base64.getEncoder().encodeToString(random));
	}

	public TokenPair signIn(String identifier, String country, String password) {
		String normalized = normalizeIdentifier(identifier, country);
		this.limits.password(normalized, ClientRequests.ip());
		String checked = (password != null && password.length() <= MAX_PASSWORD_LENGTH) ? password : "";
		TokenPair result = this.tx.execute((status) -> attempt(normalized, checked).orElse(null));
		if (result == null) {
			throw ApiException.badRequest("auth.invalid_credentials");
		}
		return result;
	}

	private Optional<TokenPair> attempt(String identifier, String password) {
		User user = lookup(identifier).orElse(null);
		PasswordCredential credential = (user != null) ? this.credentials.lockByUserId(user.getId()).orElse(null)
				: null;
		if (credential == null) {
			this.encoder.matches(password, this.dummyHash);
			return fail(identifier, user, AuditOutcome.FAILURE, "unknown_account");
		}
		Instant now = this.clock.instant();
		if (credential.isLocked(now)) {
			this.encoder.matches(password, this.dummyHash);
			return fail(identifier, user, AuditOutcome.DENIED, "locked");
		}
		if (!this.encoder.matches(password, credential.getPasswordHash())) {
			boolean locked = credential.recordFailure(this.props.password().maxFailures(),
					now.plus(this.props.password().lockDuration()));
			this.credentials.save(credential);
			if (locked) {
				this.audit.record(AuditEntry.of("auth.password.lockout", AuditOutcome.DENIED)
					.actor(null)
					.target("user", user.getId())
					.metadata("lockedUntil", credential.getLockedUntil().toString()));
			}
			return fail(identifier, user, AuditOutcome.FAILURE, "wrong_password");
		}
		if (!user.isActive()) {
			return fail(identifier, user, AuditOutcome.DENIED, "inactive_account");
		}
		credential.recordSuccess();
		this.credentials.save(credential);
		user.signedIn(now);
		this.users.save(user);
		TokenPair pair = this.tokens.startSession(user.getId(), AuthMethod.PASSWORD);
		this.audit.record(AuditEntry.of("auth.password.sign_in", AuditOutcome.SUCCESS)
			.actor(user.getId())
			.target("user", user.getId())
			.metadata("identifier", Masking.identifier(identifier)));
		return Optional.of(pair);
	}

	private Optional<TokenPair> fail(String identifier, User user, AuditOutcome outcome, String reason) {
		AuditEntry entry = AuditEntry.of("auth.password.sign_in", outcome)
			.actor(null)
			.metadata("identifier", Masking.identifier(identifier))
			.metadata("reason", reason);
		if (user != null) {
			entry.target("user", user.getId());
		}
		this.audit.record(entry);
		return Optional.empty();
	}

	private Optional<User> lookup(String identifier) {
		if (identifier.indexOf('@') >= 0) {
			return this.users.findByEmail(identifier);
		}
		if (identifier.startsWith("+")) {
			return this.users.findByPhoneE164(identifier);
		}
		return this.users.findByUsername(identifier);
	}

	/** Email lower-cased, phone in E.164, otherwise the username lower-cased. */
	static String normalizeIdentifier(String identifier, String country) {
		String raw = (identifier != null) ? identifier.trim() : "";
		if (raw.indexOf('@') >= 0) {
			return Emails.normalize(raw).orElse(raw.toLowerCase(Locale.ROOT));
		}
		if (raw.matches("^[+0-9][0-9 ()-]{5,}$")) {
			Optional<String> phone = PhoneNumbers.toE164(raw, country);
			if (phone.isPresent()) {
				return phone.get();
			}
		}
		return raw.toLowerCase(Locale.ROOT);
	}

}
