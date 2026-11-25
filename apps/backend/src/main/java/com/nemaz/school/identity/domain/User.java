package com.nemaz.school.identity.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.nemaz.school.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A person's account (Database_Design §4.3). Emails are stored lower-cased and phones in
 * E.164, so lookups are exact. Ends through {@code status} (and {@code deleted_at}), never by
 * deleting the row.
 */
@Entity
@Table(name = "users")
public class User extends BaseEntity {

	@Enumerated(EnumType.STRING)
	private AccountType accountType;

	@Column(columnDefinition = "citext")
	private String email;

	private Instant emailVerifiedAt;

	private String phoneE164;

	private Instant phoneVerifiedAt;

	@Column(columnDefinition = "citext")
	private String username;

	private String givenName;

	private String middleName;

	private String familyName;

	private String displayName;

	private LocalDate dateOfBirth;

	private String gender;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(length = 2, columnDefinition = "char(2)")
	private String homeCountryCode;

	private String preferredLanguageCode;

	private String locale;

	private String timezone;

	private String calendarPreference;

	@Enumerated(EnumType.STRING)
	private UserStatus status;

	private Instant lastLoginAt;

	private UUID avatarMediaId;

	private Instant deletedAt;

	protected User() {
	}

	/** A STANDARD, ACTIVE account. {@code givenName} defaults to the display name. */
	public static User create(String displayName, String email, String phoneE164) {
		User user = new User();
		user.accountType = AccountType.STANDARD;
		user.status = UserStatus.ACTIVE;
		user.displayName = displayName;
		user.givenName = displayName;
		user.email = email;
		user.phoneE164 = phoneE164;
		return user;
	}

	/** A STANDARD, ACTIVE account created by an administrator, with its names and language. */
	public static User createByAdmin(String givenName, String familyName, String displayName, String email,
			String phoneE164, String preferredLanguageCode) {
		User user = create(displayName, email, phoneE164);
		user.givenName = givenName;
		user.familyName = familyName;
		user.preferredLanguageCode = preferredLanguageCode;
		return user;
	}

	/** A MANAGED, ACTIVE school-managed account without phone or email (R-31). */
	public static User createManaged(String givenName, String middleName, String familyName, String displayName,
			LocalDate dateOfBirth, String gender, String username, String preferredLanguageCode) {
		User user = createByAdmin(givenName, familyName, displayName, null, null, preferredLanguageCode);
		user.accountType = AccountType.MANAGED;
		user.middleName = middleName;
		user.dateOfBirth = dateOfBirth;
		user.gender = gender;
		user.username = username;
		return user;
	}

	/** Replaces the personal fields of a managed account (callers pass the complete new values). */
	public void updateManaged(String givenName, String middleName, String familyName, String displayName,
			LocalDate dateOfBirth, String gender, String username) {
		this.givenName = givenName;
		this.middleName = middleName;
		this.familyName = familyName;
		this.displayName = displayName;
		this.dateOfBirth = dateOfBirth;
		this.gender = gender;
		this.username = username;
	}

	public User verifyEmail(Instant at) {
		this.emailVerifiedAt = at;
		return this;
	}

	public User verifyPhone(Instant at) {
		this.phoneVerifiedAt = at;
		return this;
	}

	public void signedIn(Instant at) {
		this.lastLoginAt = at;
	}

	public void changeStatus(UserStatus status) {
		this.status = status;
	}

	public void updateProfile(String displayName, String preferredLanguageCode, String timezone,
			String calendarPreference) {
		if (displayName != null) {
			this.displayName = displayName;
		}
		if (preferredLanguageCode != null) {
			this.preferredLanguageCode = preferredLanguageCode;
		}
		if (timezone != null) {
			this.timezone = timezone;
		}
		if (calendarPreference != null) {
			this.calendarPreference = calendarPreference;
		}
	}

	/** The READY AVATAR media shown for this user, or null. */
	public void setAvatarMediaId(UUID mediaId) {
		this.avatarMediaId = mediaId;
	}

	public boolean isActive() {
		return this.status == UserStatus.ACTIVE && this.deletedAt == null;
	}

	public AccountType getAccountType() {
		return this.accountType;
	}

	public String getEmail() {
		return this.email;
	}

	public Instant getEmailVerifiedAt() {
		return this.emailVerifiedAt;
	}

	public String getPhoneE164() {
		return this.phoneE164;
	}

	public Instant getPhoneVerifiedAt() {
		return this.phoneVerifiedAt;
	}

	public String getUsername() {
		return this.username;
	}

	public String getGivenName() {
		return this.givenName;
	}

	public LocalDate getDateOfBirth() {
		return this.dateOfBirth;
	}

	public String getGender() {
		return this.gender;
	}

	public String getMiddleName() {
		return this.middleName;
	}

	public String getFamilyName() {
		return this.familyName;
	}

	public String getDisplayName() {
		return this.displayName;
	}

	public String getHomeCountryCode() {
		return this.homeCountryCode;
	}

	public String getPreferredLanguageCode() {
		return this.preferredLanguageCode;
	}

	public String getTimezone() {
		return this.timezone;
	}

	public String getCalendarPreference() {
		return this.calendarPreference;
	}

	public UserStatus getStatus() {
		return this.status;
	}

	public Instant getLastLoginAt() {
		return this.lastLoginAt;
	}

	public UUID getAvatarMediaId() {
		return this.avatarMediaId;
	}

	public Instant getDeletedAt() {
		return this.deletedAt;
	}

}
