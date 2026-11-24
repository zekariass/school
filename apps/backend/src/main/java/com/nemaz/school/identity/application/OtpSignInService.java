package com.nemaz.school.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.common.tx.AfterCommit;
import com.nemaz.school.common.web.ClientRequests;
import com.nemaz.school.identity.application.Destination.Channel;
import com.nemaz.school.identity.application.TokenService.TokenPair;
import com.nemaz.school.identity.domain.AuthMethod;
import com.nemaz.school.identity.domain.User;
import com.nemaz.school.identity.infrastructure.OtpChallengeStore;
import com.nemaz.school.identity.infrastructure.OtpChallengeStore.Challenge;
import com.nemaz.school.identity.infrastructure.UserRepository;
import com.nemaz.school.system.EmailSender;
import com.nemaz.school.system.SmsSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sign-in with a one-time code (phone first, or email).
 * <ul>
 * <li><b>Request:</b> always the same answer, whether or not an account exists. Only an ACTIVE
 * account with that verified phone or email gets a code; a new code ends the older ones.
 * Delivery happens after commit on another thread, so response time does not reveal the
 * account either.</li>
 * <li><b>Verify:</b> the newest live code of the destination, at most 5 guesses, 5 minutes,
 * single use, constant-time comparison. Every failure is the same 400
 * {@code auth.invalid_code}; attempts are committed before the error is returned.</li>
 * </ul>
 */
@Service
public class OtpSignInService {

	static final String PURPOSE = "LOGIN";

	private static final Logger log = LoggerFactory.getLogger(OtpSignInService.class);

	private static final Pattern CODE = Pattern.compile("^\\d{6}$");

	private final UserRepository users;

	private final OtpChallengeStore challenges;

	private final OtpCodes codes;

	private final TokenService tokens;

	private final AuthRateLimits limits;

	private final AuditLog audit;

	private final SmsSender sms;

	private final EmailSender email;

	private final MessageSource messages;

	private final TaskExecutor executor;

	private final IdentityProperties props;

	private final Clock clock;

	private final TransactionTemplate tx;

	OtpSignInService(UserRepository users, OtpChallengeStore challenges, OtpCodes codes, TokenService tokens,
			AuthRateLimits limits, AuditLog audit, SmsSender sms, EmailSender email, MessageSource messages,
			@Qualifier("applicationTaskExecutor") TaskExecutor executor, IdentityProperties props, Clock clock,
			TransactionTemplate tx) {
		this.users = users;
		this.challenges = challenges;
		this.codes = codes;
		this.tokens = tokens;
		this.limits = limits;
		this.audit = audit;
		this.sms = sms;
		this.email = email;
		this.messages = messages;
		this.executor = executor;
		this.props = props;
		this.clock = clock;
		this.tx = tx;
	}

	public void request(Destination destination) {
		String ip = ClientRequests.ip();
		this.limits.otpRequest(destination.value(), ip);
		Locale requestLocale = LocaleContextHolder.getLocale();
		this.tx.executeWithoutResult((status) -> {
			Instant now = this.clock.instant();
			User user = find(destination).orElse(null);
			AuditEntry entry = AuditEntry.of("auth.otp.request", AuditOutcome.SUCCESS)
				.actor(null)
				.metadata("channel", destination.channel().name())
				.metadata("destination", destination.masked())
				.metadata("codeSent", user != null);
			if (user != null) {
				entry.target("user", user.getId());
				this.challenges.invalidateLive(destination.value(), PURPOSE, now);
				UUID id = Ids.newId();
				String code = this.codes.newCode();
				this.challenges.insert(id, user.getId(), challengeChannel(destination), destination.value(), PURPOSE,
						this.codes.hash(id, code), now.plus(this.props.otp().ttl()), ip, now);
				Locale locale = (user.getPreferredLanguageCode() != null)
						? Locale.forLanguageTag(user.getPreferredLanguageCode()) : requestLocale;
				AfterCommit.run(() -> this.executor.execute(() -> deliver(destination, code, locale)));
			}
			this.audit.record(entry);
		});
	}

	public TokenPair verify(Destination destination, String code) {
		this.limits.otpVerify(destination.value());
		TokenPair result = this.tx.execute((status) -> check(destination, code).orElse(null));
		if (result == null) {
			throw ApiException.badRequest("auth.invalid_code");
		}
		return result;
	}

	private Optional<TokenPair> check(Destination destination, String code) {
		Instant now = this.clock.instant();
		User user = find(destination).orElse(null);
		if (user == null) {
			this.codes.dummyCheck(String.valueOf(code));
			return fail(destination, null, "unknown_destination", null);
		}
		Challenge challenge = this.challenges.lockLatest(user.getId(), destination.value(), PURPOSE).orElse(null);
		if (challenge == null) {
			this.codes.dummyCheck(String.valueOf(code));
			return fail(destination, user.getId(), "no_challenge", null);
		}
		if (!challenge.expiresAt().isAfter(now)) {
			return fail(destination, user.getId(), "expired", challenge.attempts());
		}
		if (challenge.attempts() >= this.props.otp().maxAttempts()) {
			return fail(destination, user.getId(), "too_many_attempts", challenge.attempts());
		}
		this.challenges.recordAttempt(challenge.id());
		boolean wellFormed = code != null && CODE.matcher(code).matches();
		if (!wellFormed || !this.codes.matches(challenge.id(), code, challenge.codeHash())) {
			return fail(destination, user.getId(), "wrong_code", challenge.attempts() + 1);
		}
		this.challenges.consume(challenge.id(), now);
		user.signedIn(now);
		this.users.save(user);
		TokenPair pair = this.tokens.startSession(user.getId(), AuthMethod.OTP);
		this.audit.record(AuditEntry.of("auth.otp.verify", AuditOutcome.SUCCESS)
			.actor(user.getId())
			.target("user", user.getId())
			.metadata("channel", destination.channel().name())
			.metadata("destination", destination.masked()));
		return Optional.of(pair);
	}

	private Optional<TokenPair> fail(Destination destination, UUID userId, String reason, Integer attempts) {
		AuditEntry entry = AuditEntry.of("auth.otp.verify", AuditOutcome.FAILURE)
			.actor(null)
			.metadata("channel", destination.channel().name())
			.metadata("destination", destination.masked())
			.metadata("reason", reason)
			.metadata("attempts", attempts);
		if (userId != null) {
			entry.target("user", userId);
		}
		this.audit.record(entry);
		return Optional.empty();
	}

	private Optional<User> find(Destination destination) {
		return (destination.channel() == Channel.SMS) ? this.users.findActiveByVerifiedPhone(destination.value())
				: this.users.findActiveByVerifiedEmail(destination.value());
	}

	private void deliver(Destination destination, String code, Locale locale) {
		long minutes = this.props.otp().ttl().toMinutes();
		try {
			if (destination.channel() == Channel.SMS) {
				this.sms.send(destination.value(),
						this.messages.getMessage("auth.otp.sms", new Object[] { code, minutes }, locale));
			}
			else {
				this.email.send(destination.value(), this.messages.getMessage("auth.otp.email.subject", null, locale),
						this.messages.getMessage("auth.otp.email.body", new Object[] { code, minutes }, locale));
			}
		}
		catch (RuntimeException ex) {
			log.error("Sign-in code to {} could not be delivered: {}", destination.masked(), ex.getMessage());
		}
	}

	private static String challengeChannel(Destination destination) {
		return (destination.channel() == Channel.SMS) ? "SMS" : "EMAIL";
	}

}
