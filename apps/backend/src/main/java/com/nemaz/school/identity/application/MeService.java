package com.nemaz.school.identity.application;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.authorization.RoleAssignments;
import com.nemaz.school.authorization.RoleAssignments.AssignmentView;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.identity.AccountMemberships;
import com.nemaz.school.identity.AccountMemberships.SchoolMembership;
import com.nemaz.school.identity.domain.PasswordCredential;
import com.nemaz.school.identity.domain.User;
import com.nemaz.school.identity.infrastructure.AuthSessionStore;
import com.nemaz.school.identity.infrastructure.PasswordCredentialRepository;
import com.nemaz.school.identity.infrastructure.UserRepository;
import com.nemaz.school.media.MediaContext;
import com.nemaz.school.media.MediaPurpose;
import com.nemaz.school.media.MediaService;
import com.nemaz.school.media.MediaSummary;
import com.nemaz.school.reference.ReferenceData;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The signed-in user's own account: profile, role assignments, preferences and password. */
@Service
@Transactional
public class MeService {

	static final int MIN_PASSWORD_LENGTH = 10;

	static final long FRESH_SIGN_IN_MINUTES = 10;

	/** Setting a first password needs a sign-in this recent (the session's start). */
	static final Duration FRESH_SIGN_IN = Duration.ofMinutes(FRESH_SIGN_IN_MINUTES);

	private final UserRepository users;

	private final PasswordCredentialRepository credentials;

	private final RoleAssignments roleAssignments;

	private final ReferenceData reference;

	private final UserSnapshotCache snapshots;

	private final AuthSessionStore sessions;

	private final TokenService tokens;

	private final PasswordEncoder encoder;

	private final AuditLog audit;

	private final MediaService media;

	private final List<AccountMemberships> memberships;

	private final Clock clock;

	MeService(UserRepository users, PasswordCredentialRepository credentials, RoleAssignments roleAssignments,
			ReferenceData reference, UserSnapshotCache snapshots, AuthSessionStore sessions, TokenService tokens,
			PasswordEncoder encoder, AuditLog audit, MediaService media, List<AccountMemberships> memberships,
			Clock clock) {
		this.users = users;
		this.credentials = credentials;
		this.roleAssignments = roleAssignments;
		this.reference = reference;
		this.snapshots = snapshots;
		this.sessions = sessions;
		this.tokens = tokens;
		this.encoder = encoder;
		this.audit = audit;
		this.media = media;
		this.memberships = memberships;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public Me get(CurrentUser current, Locale locale) {
		User user = find(current);
		return me(user, locale);
	}

	public Me update(CurrentUser current, ProfileChange change, Locale locale) {
		User user = find(current);
		if (change.displayName() != null && change.displayName().isBlank()) {
			throw ApiException.badRequest("profile.display_name_required");
		}
		if (change.preferredLanguageCode() != null && !this.reference.languageExists(change.preferredLanguageCode())) {
			throw ApiException.badRequest("language.unknown");
		}
		if (change.timezone() != null) {
			try {
				ZoneId.of(change.timezone());
			}
			catch (DateTimeException ex) {
				throw ApiException.badRequest("timezone.invalid");
			}
		}
		if (change.calendarPreference() != null && !this.reference.calendarExists(change.calendarPreference())) {
			throw ApiException.badRequest("calendar.unknown");
		}
		Map<String, Object> before = snapshot(user);
		user.updateProfile((change.displayName() != null) ? change.displayName().trim() : null,
				change.preferredLanguageCode(), change.timezone(), change.calendarPreference());
		this.users.saveAndFlush(user);
		Map<String, Object> after = snapshot(user);
		if (!before.equals(after)) {
			this.audit.record(AuditEntry.of("identity.profile.update", AuditOutcome.SUCCESS)
				.target("user", user.getId())
				.changes(before, after));
		}
		if (!Objects.equals(before.get("preferredLanguageCode"), after.get("preferredLanguageCode"))) {
			this.snapshots.evictAfterCommit(user.getId());
		}
		return me(user, locale);
	}

	/**
	 * Makes a READY avatar upload of the caller the user's avatar; the previous avatar file is
	 * deleted.
	 */
	public Me setAvatar(CurrentUser current, UUID mediaId, Locale locale) {
		User user = find(current);
		this.media.requireAttachable(mediaId, MediaPurpose.AVATAR, MediaContext.user(user.getId()), current);
		UUID previous = user.getAvatarMediaId();
		if (!mediaId.equals(previous)) {
			user.setAvatarMediaId(mediaId);
			this.users.saveAndFlush(user);
			if (previous != null) {
				this.media.discard(previous);
			}
			this.audit.record(AuditEntry.of("identity.avatar.set", AuditOutcome.SUCCESS)
				.target("user", user.getId())
				.metadata("mediaId", mediaId.toString()));
		}
		return me(user, locale);
	}

	/** Removes the avatar and deletes its file. */
	public Me clearAvatar(CurrentUser current, Locale locale) {
		User user = find(current);
		UUID previous = user.getAvatarMediaId();
		if (previous != null) {
			user.setAvatarMediaId(null);
			this.users.saveAndFlush(user);
			this.media.discard(previous);
			this.audit.record(AuditEntry.of("identity.avatar.clear", AuditOutcome.SUCCESS)
				.target("user", user.getId())
				.metadata("mediaId", previous.toString()));
		}
		return me(user, locale);
	}

	private Me me(User user, Locale locale) {
		MediaSummary avatar = (user.getAvatarMediaId() != null) ? this.media.summary(user.getAvatarMediaId()).orElse(null)
				: null;
		return new Me(user, this.credentials.existsById(user.getId()),
				this.roleAssignments.activeAssignments(user.getId(), locale), avatar,
				this.memberships.stream().flatMap((m) -> m.activeFor(user.getId()).stream()).toList());
	}

	/**
	 * Sets or changes the password. Changing needs the current password; setting the first one
	 * needs a sign-in in the last {@value #FRESH_SIGN_IN_MINUTES} minutes. Other sessions of the
	 * user are signed out.
	 */
	public void changePassword(CurrentUser current, String currentPassword, String newPassword) {
		if (newPassword == null || newPassword.length() < MIN_PASSWORD_LENGTH
				|| newPassword.length() > PasswordSignInService.MAX_PASSWORD_LENGTH) {
			throw ApiException.badRequest("password.too_weak", MIN_PASSWORD_LENGTH);
		}
		User user = find(current);
		Instant now = this.clock.instant();
		PasswordCredential credential = this.credentials.lockByUserId(user.getId()).orElse(null);
		if (credential != null) {
			if (currentPassword == null || currentPassword.length() > PasswordSignInService.MAX_PASSWORD_LENGTH
					|| !this.encoder.matches(currentPassword, credential.getPasswordHash())) {
				throw ApiException.badRequest("password.current_invalid");
			}
			credential.change(this.encoder.encode(newPassword), now);
		}
		else {
			Instant started = this.sessions.familyStartedAt(current.sessionId()).orElse(Instant.EPOCH);
			if (started.plus(FRESH_SIGN_IN).isBefore(now)) {
				throw ApiException.forbidden("auth.reauthentication_required");
			}
			credential = new PasswordCredential(user.getId(), this.encoder.encode(newPassword), now);
		}
		this.credentials.save(credential);
		List<java.util.UUID> signedOut = this.tokens.revokeAll(user.getId(), current.sessionId(), "PASSWORD_CHANGED");
		this.audit.record(AuditEntry.of("identity.password.change", AuditOutcome.SUCCESS)
			.target("user", user.getId())
			.metadata("otherSessionsRevoked", signedOut.size()));
	}

	private User find(CurrentUser current) {
		return this.users.findById(current.userId()).orElseThrow(() -> ApiException.unauthorized("auth.account_inactive"));
	}

	private static Map<String, Object> snapshot(User user) {
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("displayName", user.getDisplayName());
		values.put("preferredLanguageCode", user.getPreferredLanguageCode());
		values.put("timezone", user.getTimezone());
		values.put("calendarPreference", user.getCalendarPreference());
		return values;
	}

	public record ProfileChange(String displayName, String preferredLanguageCode, String timezone,
			String calendarPreference) {
	}

	public record Me(User user, boolean hasPassword, List<AssignmentView> assignments, MediaSummary avatar,
			List<SchoolMembership> memberships) {
	}

}
