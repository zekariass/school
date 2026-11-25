package com.nemaz.school.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.common.tx.AfterCommit;
import com.nemaz.school.common.web.ClientRequests;
import com.nemaz.school.identity.InvitationRedeemer;
import com.nemaz.school.identity.InvitationRedeemer.InvitationKey;
import com.nemaz.school.identity.InvitationRedeemer.InvitationTarget;
import com.nemaz.school.identity.InvitationRedeemer.VerifiedContact;
import com.nemaz.school.identity.UserAccounts;
import com.nemaz.school.identity.UserAccounts.ProvisionRequest;
import com.nemaz.school.identity.UserAccounts.ProvisionedUser;
import com.nemaz.school.identity.application.Destination.Channel;
import com.nemaz.school.identity.application.TokenService.TokenPair;
import com.nemaz.school.identity.domain.AuthMethod;
import com.nemaz.school.identity.domain.User;
import com.nemaz.school.identity.infrastructure.OtpChallengeStore;
import com.nemaz.school.identity.infrastructure.OtpChallengeStore.Challenge;
import com.nemaz.school.identity.infrastructure.UserRepository;
import com.nemaz.school.reference.ReferenceData;
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
 * Accepting an invitation as a (possibly new) adult: the only flow that sends a one-time code to a destination
 * without an account (M5).
 * <ul>
 * <li><b>Request a code:</b> only while the invitation is live (else 404 {@code invitation.not_found}, like the
 * preview); then always 202 whether or not an account has the destination. Rate limited per destination (3 per hour)
 * and per client address (20 per hour), on top of the invitation-key guessing limits of the {@link InvitationRedeemer}.
 * Purpose {@code ACCEPT_INVITE}; 6 digits, HMAC with the pepper, 5 minutes, 5 attempts, single use, a new code ends
 * the older ones (the sign-in machinery).</li>
 * <li><b>Accept:</b> checks the code (every failure is the same 400 {@code auth.invalid_code}, attempts are committed),
 * then in one transaction finds or creates the adult (STANDARD, destination verified: the code proved it), lets the
 * redeemer link them, and signs them in. An invitation that died in between rolls everything back (404).</li>
 * </ul>
 * The invited destination never proves anything by itself: the redeemer decides the link status.
 */
@Service
public class InvitationSignInService {

	static final String PURPOSE = "ACCEPT_INVITE";

	private static final Logger log = LoggerFactory.getLogger(InvitationSignInService.class);

	private static final Pattern CODE = Pattern.compile("^\\d{6}$");

	private final List<InvitationRedeemer> redeemers;

	private final UserRepository users;

	private final UserAccounts accounts;

	private final OtpChallengeStore challenges;

	private final OtpCodes codes;

	private final TokenService tokens;

	private final AuthRateLimits limits;

	private final ReferenceData reference;

	private final AuditLog audit;

	private final SmsSender sms;

	private final EmailSender email;

	private final MessageSource messages;

	private final TaskExecutor executor;

	private final IdentityProperties props;

	private final Clock clock;

	private final TransactionTemplate tx;

	InvitationSignInService(List<InvitationRedeemer> redeemers, UserRepository users, UserAccounts accounts,
			OtpChallengeStore challenges, OtpCodes codes, TokenService tokens, AuthRateLimits limits,
			ReferenceData reference, AuditLog audit, SmsSender sms, EmailSender email, MessageSource messages,
			@Qualifier("applicationTaskExecutor") TaskExecutor executor, IdentityProperties props, Clock clock,
			TransactionTemplate tx) {
		this.redeemers = redeemers;
		this.users = users;
		this.accounts = accounts;
		this.challenges = challenges;
		this.codes = codes;
		this.tokens = tokens;
		this.limits = limits;
		this.reference = reference;
		this.audit = audit;
		this.sms = sms;
		this.email = email;
		this.messages = messages;
		this.executor = executor;
		this.props = props;
		this.clock = clock;
		this.tx = tx;
	}

	public void requestCode(InvitationKey key, Destination destination) {
		String ip = ClientRequests.ip();
		this.limits.inviteOtpRequest(destination.value(), ip);
		InvitationTarget target = find(key);
		Locale requestLocale = LocaleContextHolder.getLocale();
		this.tx.executeWithoutResult((status) -> {
			Instant now = this.clock.instant();
			User existing = findActive(destination);
			this.challenges.invalidateLive(destination.value(), PURPOSE, now);
			UUID id = Ids.newId();
			String code = this.codes.newCode();
			this.challenges.insert(id, (existing != null) ? existing.getId() : null, channel(destination),
					destination.value(), PURPOSE, this.codes.hash(id, code), now.plus(this.props.otp().ttl()), ip, now);
			Locale locale = (existing != null && existing.getPreferredLanguageCode() != null)
					? Locale.forLanguageTag(existing.getPreferredLanguageCode()) : requestLocale;
			this.audit.record(AuditEntry.of("auth.invite_otp.request", AuditOutcome.SUCCESS)
				.actor(null)
				.school(target.schoolId())
				.target("invitation", target.invitationId())
				.metadata("channel", destination.channel().name())
				.metadata("destination", destination.masked()));
			AfterCommit.run(() -> this.executor.execute(() -> deliver(destination, code, locale)));
		});
	}

	public TokenPair accept(InvitationKey key, Destination destination, String code, String displayName,
			String preferredLanguage) {
		this.limits.inviteOtpVerify(destination.value());
		InvitationTarget target = find(key);
		String language = blankToNull(preferredLanguage);
		if (language != null && !this.reference.languageExists(language)) {
			throw ApiException.badRequest("language.unknown");
		}
		Result result = this.tx.execute((status) -> {
			Result checked = check(destination, code);
			if (checked != null) {
				return checked;
			}
			String name = name(displayName, target);
			ProvisionedUser account = this.accounts.findOrCreateVerified(new ProvisionRequest(
					(destination.channel() == Channel.SMS) ? destination.value() : null,
					(destination.channel() == Channel.EMAIL) ? destination.value() : null, null, name, null, name,
					(language != null) ? language : target.languageCode()));
			Instant now = this.clock.instant();
			User user = this.users.findById(account.id()).orElseThrow();
			// The code just proved the destination: an existing account whose copy was unverified is now verified.
			if (destination.channel() == Channel.SMS && user.getPhoneVerifiedAt() == null) {
				user.verifyPhone(now);
			}
			if (destination.channel() == Channel.EMAIL && user.getEmailVerifiedAt() == null) {
				user.verifyEmail(now);
			}
			VerifiedContact proof = new VerifiedContact((destination.channel() == Channel.SMS) ? destination.value() : null,
					(destination.channel() == Channel.EMAIL) ? destination.value() : null);
			UUID linkId = redeemer().redeem(key, user.getId(), proof).orElse(null);
			if (linkId == null) {
				status.setRollbackOnly();
				return Result.GONE;
			}
			user.signedIn(now);
			this.users.save(user);
			TokenPair pair = this.tokens.startSession(user.getId(), AuthMethod.OTP);
			this.audit.record(AuditEntry.of("auth.invite.accept", AuditOutcome.SUCCESS)
				.actor(user.getId())
				.school(target.schoolId())
				.target("invitation", target.invitationId())
				.metadata("channel", destination.channel().name())
				.metadata("destination", destination.masked())
				.metadata("accountCreated", account.created())
				.metadata("guardianLinkId", linkId.toString()));
			return new Result(pair, null);
		});
		if (result == Result.GONE) {
			throw ApiException.notFound("invitation.not_found");
		}
		if (result.tokens() == null) {
			throw ApiException.badRequest("auth.invalid_code");
		}
		return result.tokens();
	}

	/** Null when the code is right (and consumed); otherwise the failure, with the attempt recorded. */
	private Result check(Destination destination, String code) {
		Instant now = this.clock.instant();
		Challenge challenge = this.challenges.lockLatestForDestination(destination.value(), PURPOSE).orElse(null);
		if (challenge == null) {
			this.codes.dummyCheck(String.valueOf(code));
			return fail(destination, "no_challenge", null);
		}
		if (!challenge.expiresAt().isAfter(now)) {
			return fail(destination, "expired", challenge.attempts());
		}
		if (challenge.attempts() >= this.props.otp().maxAttempts()) {
			return fail(destination, "too_many_attempts", challenge.attempts());
		}
		this.challenges.recordAttempt(challenge.id());
		boolean wellFormed = code != null && CODE.matcher(code).matches();
		if (!wellFormed || !this.codes.matches(challenge.id(), code, challenge.codeHash())) {
			return fail(destination, "wrong_code", challenge.attempts() + 1);
		}
		this.challenges.consume(challenge.id(), now);
		return null;
	}

	private Result fail(Destination destination, String reason, Integer attempts) {
		this.audit.record(AuditEntry.of("auth.invite.accept", AuditOutcome.FAILURE)
			.actor(null)
			.metadata("channel", destination.channel().name())
			.metadata("destination", destination.masked())
			.metadata("reason", reason)
			.metadata("attempts", attempts));
		return Result.INVALID;
	}

	private InvitationTarget find(InvitationKey key) {
		return redeemer().find(key).orElseThrow(() -> ApiException.notFound("invitation.not_found"));
	}

	private InvitationRedeemer redeemer() {
		if (this.redeemers.isEmpty()) {
			throw ApiException.notFound("invitation.not_found");
		}
		return this.redeemers.getFirst();
	}

	private String name(String displayName, InvitationTarget target) {
		String name = blankToNull(displayName);
		if (name == null) {
			name = blankToNull(target.suggestedName());
		}
		if (name == null) {
			Locale locale = (target.languageCode() != null) ? Locale.forLanguageTag(target.languageCode())
					: LocaleContextHolder.getLocale();
			name = this.messages.getMessage("account.default_guardian_name", null, "Parent", locale);
		}
		return name;
	}

	private User findActive(Destination destination) {
		return ((destination.channel() == Channel.SMS) ? this.users.findActiveByVerifiedPhone(destination.value())
				: this.users.findActiveByVerifiedEmail(destination.value()))
			.orElse(null);
	}

	private void deliver(Destination destination, String code, Locale locale) {
		long minutes = this.props.otp().ttl().toMinutes();
		try {
			if (destination.channel() == Channel.SMS) {
				this.sms.send(destination.value(),
						this.messages.getMessage("auth.invite_otp.sms", new Object[] { code, minutes }, locale));
			}
			else {
				this.email.send(destination.value(),
						this.messages.getMessage("auth.invite_otp.email.subject", null, locale),
						this.messages.getMessage("auth.invite_otp.email.body", new Object[] { code, minutes }, locale));
			}
		}
		catch (RuntimeException ex) {
			log.error("Invitation code to {} could not be delivered: {}", destination.masked(), ex.getMessage());
		}
	}

	private static String channel(Destination destination) {
		return (destination.channel() == Channel.SMS) ? "SMS" : "EMAIL";
	}

	private static String blankToNull(String value) {
		return (value == null || value.isBlank()) ? null : value.trim();
	}

	/** The outcome of one accept transaction: tokens, or a failure marker. */
	private record Result(TokenPair tokens, String failure) {

		static final Result INVALID = new Result(null, "invalid");

		static final Result GONE = new Result(null, "gone");

	}

}
