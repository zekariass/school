package com.nemaz.school.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.Masking;
import com.nemaz.school.identity.domain.User;
import com.nemaz.school.identity.infrastructure.UserRepository;
import com.nemaz.school.reference.ReferenceData;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Minimal user provisioning for administrators (full invitations arrive with M3/M5): creates
 * an ACTIVE STANDARD account whose phone or email counts as verified (the administrator
 * vouches for it) so the person can sign in with a one-time code, and finds a user by an
 * exact phone or email, for assigning roles. There is deliberately no listing or search.
 */
@Service
@Transactional
public class UserProvisioningService {

	private final UserRepository users;

	private final ReferenceData reference;

	private final AuthRateLimits limits;

	private final AuditLog audit;

	private final Clock clock;

	private final PhoneInputs phones;

	UserProvisioningService(UserRepository users, ReferenceData reference, AuthRateLimits limits, AuditLog audit,
			Clock clock, PhoneInputs phones) {
		this.phones = phones;
		this.users = users;
		this.reference = reference;
		this.limits = limits;
		this.audit = audit;
		this.clock = clock;
	}

	public User create(Provision p) {
		boolean hasPhone = notBlank(p.phone());
		boolean hasEmail = notBlank(p.email());
		if (!hasPhone && !hasEmail) {
			throw ApiException.badRequest("user.contact_required");
		}
		String phone = hasPhone ? this.phones.toE164(p.phone(), p.country())
			.orElseThrow(() -> ApiException.badRequest("user.invalid_phone")) : null;
		String email = hasEmail
				? Emails.normalize(p.email()).orElseThrow(() -> ApiException.badRequest("user.invalid_email")) : null;
		String givenName = p.givenName().trim();
		String familyName = notBlank(p.familyName()) ? p.familyName().trim() : null;
		String displayName = notBlank(p.displayName()) ? p.displayName().trim()
				: ((familyName != null) ? givenName + " " + familyName : givenName);
		String language = notBlank(p.preferredLanguageCode()) ? p.preferredLanguageCode() : null;
		if (language != null && !this.reference.languageExists(language)) {
			throw ApiException.badRequest("language.unknown");
		}
		if (phone != null && this.users.findByPhoneE164(phone).isPresent()) {
			throw ApiException.conflict("user.phone_taken");
		}
		if (email != null && this.users.findByEmail(email).isPresent()) {
			throw ApiException.conflict("user.email_taken");
		}
		Instant now = this.clock.instant();
		User user = User.createByAdmin(givenName, familyName, displayName, email, phone, language);
		if (phone != null) {
			user.verifyPhone(now);
		}
		if (email != null) {
			user.verifyEmail(now);
		}
		try {
			user = this.users.saveAndFlush(user);
		}
		catch (DataIntegrityViolationException ex) {
			// A concurrent request took the phone or email between the check and the insert.
			throw ApiException.conflict((phone != null) ? "user.phone_taken" : "user.email_taken");
		}
		this.audit.record(AuditEntry.of("user.create", AuditOutcome.SUCCESS)
			.target("user", user.getId())
			.metadata("phone", Masking.phone(phone))
			.metadata("email", Masking.email(email)));
		return user;
	}

	/** An exact match on one phone or one email; {@code user.not_found} (404) otherwise. */
	@Transactional(readOnly = true)
	public UserMatch lookup(CurrentUser actor, String phone, String email, String country) {
		boolean hasPhone = notBlank(phone);
		boolean hasEmail = notBlank(email);
		if (hasPhone == hasEmail) {
			throw ApiException.badRequest("user.contact_required");
		}
		this.limits.userLookup(actor.userId());
		Optional<User> found;
		boolean byPhone = hasPhone;
		if (byPhone) {
			String e164 = this.phones.toE164(phone, country).orElseThrow(() -> ApiException.badRequest("user.invalid_phone"));
			found = this.users.findByPhoneE164(e164);
		}
		else {
			String normalized = Emails.normalize(email).orElseThrow(() -> ApiException.badRequest("user.invalid_email"));
			found = this.users.findByEmail(normalized);
		}
		User user = found.filter((u) -> u.getDeletedAt() == null).orElseThrow(() -> ApiException.notFound("user.not_found"));
		return new UserMatch(user.getId(), user.getDisplayName(),
				byPhone ? Masking.phone(user.getPhoneE164()) : Masking.email(user.getEmail()), user.getStatus().name());
	}

	/**
	 * A user by id (ids are unguessable UUIDs), for listings that only hold ids such as role
	 * assignments: display name and masked destination, never the full phone or email.
	 */
	@Transactional(readOnly = true)
	public UserMatch get(UUID id) {
		User user = this.users.findById(id)
			.filter((u) -> u.getDeletedAt() == null)
			.orElseThrow(() -> ApiException.notFound("user.not_found"));
		String destination = (user.getPhoneE164() != null) ? Masking.phone(user.getPhoneE164())
				: Masking.email(user.getEmail());
		return new UserMatch(user.getId(), user.getDisplayName(), destination, user.getStatus().name());
	}

	private static boolean notBlank(String value) {
		return value != null && !value.isBlank();
	}

	public record Provision(String phone, String email, String country, String givenName, String familyName,
			String displayName, String preferredLanguageCode) {
	}

	public record UserMatch(UUID id, String displayName, String maskedDestination, String status) {
	}

}
