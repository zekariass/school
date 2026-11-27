package com.nemaz.school.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.authorization.RoleAssignments;
import com.nemaz.school.authorization.RoleCodes;
import com.nemaz.school.common.security.AccessScope;
import com.nemaz.school.common.security.Masking;
import com.nemaz.school.identity.domain.User;
import com.nemaz.school.identity.infrastructure.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the first platform administrator at startup: when no ACTIVE PLATFORM_ADMIN exists
 * and {@code school.bootstrap.admin.phone} and/or {@code .email} is set, the user with that
 * phone or email is reused (or created ACTIVE with the destination verified) and granted
 * PLATFORM_ADMIN at platform scope. Idempotent, and serialized across instances by a
 * transaction-scoped advisory lock.
 */
@Component
public class BootstrapAdmin implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(BootstrapAdmin.class);

	private final BootstrapProperties props;

	private final UserRepository users;

	private final RoleAssignments roleAssignments;

	private final AuditLog audit;

	private final JdbcTemplate jdbc;

	private final Clock clock;

	private final PhoneInputs phones;

	BootstrapAdmin(BootstrapProperties props, UserRepository users, RoleAssignments roleAssignments, AuditLog audit,
			JdbcTemplate jdbc, Clock clock, PhoneInputs phones) {
		this.phones = phones;
		this.props = props;
		this.users = users;
		this.roleAssignments = roleAssignments;
		this.audit = audit;
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		ensure(this.props);
	}

	/** Returns the administrator created or reused, or empty when nothing had to be done. */
	@Transactional
	public Optional<User> ensure(BootstrapProperties config) {
		String phone = blankToNull(config.phone());
		String email = blankToNull(config.email());
		if (phone == null && email == null) {
			return Optional.empty();
		}
		String e164 = null;
		if (phone != null) {
			e164 = this.phones.toE164(phone, null)
				.orElseThrow(() -> new IllegalStateException("school.bootstrap.admin.phone is not a valid phone number"));
		}
		String normalizedEmail = null;
		if (email != null) {
			normalizedEmail = Emails.normalize(email)
				.orElseThrow(() -> new IllegalStateException("school.bootstrap.admin.email is not a valid email"));
		}
		this.jdbc.queryForList("select pg_advisory_xact_lock(hashtext('identity.bootstrap-admin'))");
		if (this.roleAssignments.anyActive(RoleCodes.PLATFORM_ADMIN)) {
			return Optional.empty();
		}
		Instant now = this.clock.instant();
		User user = findExisting(e164, normalizedEmail).orElse(null);
		boolean created = false;
		if (user == null) {
			user = User.create(config.name(), normalizedEmail, e164);
			if (e164 != null) {
				user.verifyPhone(now);
			}
			if (normalizedEmail != null) {
				user.verifyEmail(now);
			}
			user = this.users.saveAndFlush(user);
			created = true;
		}
		else if (!user.isActive()) {
			log.warn("Bootstrap administrator {} exists but is not ACTIVE; no role granted",
					(e164 != null) ? Masking.phone(e164) : Masking.email(normalizedEmail));
			return Optional.empty();
		}
		this.roleAssignments.grantAsSystem(user.getId(), RoleCodes.PLATFORM_ADMIN, AccessScope.platform());
		this.audit.record(AuditEntry.of("identity.bootstrap_admin", AuditOutcome.SUCCESS)
			.actor(null)
			.target("user", user.getId())
			.metadata("userCreated", created)
			.metadata("phone", Masking.phone(e164))
			.metadata("email", Masking.email(normalizedEmail)));
		log.info("Bootstrap platform administrator {} ({})", user.getId(),
				(e164 != null) ? Masking.phone(e164) : Masking.email(normalizedEmail));
		return Optional.of(user);
	}

	private Optional<User> findExisting(String e164, String email) {
		Optional<User> byPhone = (e164 != null) ? this.users.findByPhoneE164(e164) : Optional.empty();
		return byPhone.isPresent() ? byPhone : ((email != null) ? this.users.findByEmail(email) : Optional.empty());
	}

	private static String blankToNull(String value) {
		return (value == null || value.isBlank()) ? null : value.trim();
	}

}
