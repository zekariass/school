package com.nemaz.school.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.identity.UserAccounts;
import com.nemaz.school.identity.domain.AccountType;
import com.nemaz.school.identity.domain.PasswordCredential;
import com.nemaz.school.identity.domain.User;
import com.nemaz.school.identity.domain.UserStatus;
import com.nemaz.school.identity.infrastructure.PasswordCredentialRepository;
import com.nemaz.school.identity.infrastructure.UserRepository;
import com.nemaz.school.reference.ReferenceData;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link UserAccounts}: account creation and changes for other modules (people), with account-level invariants. */
@Service
@Transactional
class DefaultUserAccounts implements UserAccounts {

	/** Starts with a letter (so it never looks like a phone number), 3 to 31 characters. */
	static final Pattern USERNAME = Pattern.compile("^[a-z][a-z0-9._-]{2,30}$");

	static final int MIN_INITIAL_PASSWORD_LENGTH = 8;

	private final UserRepository users;

	private final PasswordCredentialRepository credentials;

	private final PasswordEncoder encoder;

	private final ReferenceData reference;

	private final TokenService tokens;

	private final UserSnapshotCache snapshots;

	private final Clock clock;

	private final PhoneInputs phones;

	DefaultUserAccounts(UserRepository users, PasswordCredentialRepository credentials, PasswordEncoder encoder,
			ReferenceData reference, TokenService tokens, UserSnapshotCache snapshots, Clock clock, PhoneInputs phones) {
		this.phones = phones;
		this.snapshots = snapshots;
		this.users = users;
		this.credentials = credentials;
		this.encoder = encoder;
		this.reference = reference;
		this.tokens = tokens;
		this.clock = clock;
	}

	@Override
	public ProvisionedUser findOrCreateVerified(ProvisionRequest p) {
		boolean hasPhone = notBlank(p.phone());
		boolean hasEmail = notBlank(p.email());
		if (!hasPhone && !hasEmail) {
			throw ApiException.badRequest("user.contact_required");
		}
		String phone = hasPhone ? this.phones.toE164(p.phone(), p.country())
			.orElseThrow(() -> ApiException.badRequest("user.invalid_phone")) : null;
		String email = hasEmail
				? Emails.normalize(p.email()).orElseThrow(() -> ApiException.badRequest("user.invalid_email")) : null;
		Optional<User> byPhone = (phone != null) ? this.users.findByPhoneE164(phone) : Optional.empty();
		Optional<User> byEmail = (email != null) ? this.users.findByEmail(email) : Optional.empty();
		if (byPhone.isPresent() && byEmail.isPresent() && !byPhone.get().getId().equals(byEmail.get().getId())) {
			throw ApiException.conflict("user.contact_conflict");
		}
		User existing = byPhone.or(() -> byEmail).orElse(null);
		if (existing != null) {
			if (!existing.isActive()) {
				throw ApiException.conflict("user.not_active");
			}
			return view(existing, false);
		}
		String givenName = p.givenName().trim();
		String familyName = notBlank(p.familyName()) ? p.familyName().trim() : null;
		String displayName = notBlank(p.displayName()) ? p.displayName().trim()
				: ((familyName != null) ? givenName + " " + familyName : givenName);
		String language = notBlank(p.preferredLanguageCode()) ? p.preferredLanguageCode() : null;
		if (language != null && !this.reference.languageExists(language)) {
			throw ApiException.badRequest("language.unknown");
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
			// A concurrent request created the same user: use theirs.
			User winner = ((phone != null) ? this.users.findByPhoneE164(phone) : this.users.findByEmail(email))
				.orElseThrow(() -> ex);
			return view(winner, false);
		}
		return view(user, true);
	}

	@Override
	public UUID createManaged(ManagedRequest r) {
		String givenName = r.givenName().trim();
		String middle = notBlank(r.middleName()) ? r.middleName().trim() : null;
		String family = notBlank(r.familyName()) ? r.familyName().trim() : null;
		String username = normalizeUsername(r.username());
		if (r.preferredLanguageCode() != null && !this.reference.languageExists(r.preferredLanguageCode())) {
			throw ApiException.badRequest("language.unknown");
		}
		if (username != null && this.users.findByUsername(username).isPresent()) {
			throw ApiException.conflict("user.username_taken");
		}
		User user = User.createManaged(givenName, middle, family, displayName(givenName, middle, family),
				r.dateOfBirth(), blankToNull(r.gender()), username, r.preferredLanguageCode());
		try {
			return this.users.saveAndFlush(user).getId();
		}
		catch (DataIntegrityViolationException ex) {
			throw ApiException.conflict("user.username_taken");
		}
	}

	@Override
	public void updateManaged(UUID userId, ManagedChange c) {
		User user = managed(userId);
		String givenName = (c.givenName() != null) ? c.givenName().trim() : user.getGivenName();
		String middle = (c.middleName() != null) ? blankToNull(c.middleName()) : user.getMiddleName();
		String family = (c.familyName() != null) ? blankToNull(c.familyName()) : user.getFamilyName();
		String username = user.getUsername();
		if (c.username() != null) {
			username = normalizeUsername(c.username());
			if (username != null && !username.equalsIgnoreCase(user.getUsername())
					&& this.users.findByUsername(username).isPresent()) {
				throw ApiException.conflict("user.username_taken");
			}
		}
		user.updateManaged(givenName, middle, family, displayName(givenName, middle, family),
				(c.dateOfBirth() != null) ? c.dateOfBirth() : user.getDateOfBirth(),
				(c.gender() != null) ? blankToNull(c.gender()) : user.getGender(), username);
		try {
			this.users.saveAndFlush(user);
		}
		catch (DataIntegrityViolationException ex) {
			throw ApiException.conflict("user.username_taken");
		}
	}

	@Override
	public void setInitialPassword(UUID userId, String password) {
		if (password == null || password.length() < MIN_INITIAL_PASSWORD_LENGTH
				|| password.length() > PasswordSignInService.MAX_PASSWORD_LENGTH) {
			throw ApiException.badRequest("password.too_weak", MIN_INITIAL_PASSWORD_LENGTH);
		}
		User user = managed(userId);
		if (user.getUsername() == null) {
			throw ApiException.conflict("user.username_required");
		}
		Instant now = this.clock.instant();
		String hash = this.encoder.encode(password);
		PasswordCredential credential = this.credentials.lockByUserId(userId).orElse(null);
		if (credential == null) {
			this.credentials.save(PasswordCredential.initial(userId, hash, now));
		}
		else {
			credential.resetByAdmin(hash, now);
			this.credentials.save(credential);
		}
		this.tokens.revokeAll(userId, null, "PASSWORD_CHANGED");
	}

	@Override
	public void signOutEverywhere(UUID userId) {
		this.tokens.revokeAll(userId, null, "USER_INACTIVE");
	}

	@Override
	public boolean lock(UUID userId) {
		User user = this.users.findById(userId).orElseThrow(() -> ApiException.notFound("user.not_found"));
		if (user.getStatus() != UserStatus.ACTIVE || user.getDeletedAt() != null) {
			return false;
		}
		user.changeStatus(UserStatus.LOCKED);
		this.users.saveAndFlush(user);
		this.snapshots.evictAfterCommit(userId);
		this.tokens.revokeAll(userId, null, "USER_INACTIVE");
		return true;
	}

	@Override
	public boolean unlock(UUID userId) {
		User user = this.users.findById(userId).orElseThrow(() -> ApiException.notFound("user.not_found"));
		if (user.getStatus() != UserStatus.LOCKED || user.getDeletedAt() != null) {
			return false;
		}
		user.changeStatus(UserStatus.ACTIVE);
		this.users.saveAndFlush(user);
		this.snapshots.evictAfterCommit(userId);
		return true;
	}

	private User managed(UUID userId) {
		User user = this.users.findById(userId).orElseThrow(() -> ApiException.notFound("user.not_found"));
		if (user.getAccountType() != AccountType.MANAGED || user.getStatus() == UserStatus.DEACTIVATED) {
			throw ApiException.conflict("user.not_managed");
		}
		return user;
	}

	private static String normalizeUsername(String raw) {
		if (!notBlank(raw)) {
			return null;
		}
		String username = raw.trim().toLowerCase(java.util.Locale.ROOT);
		if (!USERNAME.matcher(username).matches()) {
			throw ApiException.badRequest("user.invalid_username");
		}
		return username;
	}

	private static String displayName(String given, String middle, String family) {
		StringBuilder name = new StringBuilder(given);
		if (middle != null) {
			name.append(' ').append(middle);
		}
		if (family != null) {
			name.append(' ').append(family);
		}
		return name.toString();
	}

	private static ProvisionedUser view(User u, boolean created) {
		return new ProvisionedUser(u.getId(), created, u.getDisplayName(), u.getPreferredLanguageCode(),
				u.getPhoneE164(), u.getEmail());
	}

	private static boolean notBlank(String value) {
		return value != null && !value.isBlank();
	}

	private static String blankToNull(String value) {
		return notBlank(value) ? value.trim() : null;
	}

}
