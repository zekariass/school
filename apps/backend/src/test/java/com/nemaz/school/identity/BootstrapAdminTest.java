package com.nemaz.school.identity;

import java.util.Optional;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.identity.application.BootstrapAdmin;
import com.nemaz.school.identity.application.BootstrapProperties;
import com.nemaz.school.identity.domain.User;
import com.nemaz.school.support.Personas;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bootstrap administrator. Runs inside a transaction that is rolled back, because the
 * shared test database has other platform administrators (personas).
 */
@IntegrationTest
class BootstrapAdminTest {

	@Autowired
	BootstrapAdmin bootstrap;

	@Autowired
	TransactionTemplate tx;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	Personas personas;

	@Test
	void createsTheFirstPlatformAdministratorOnceAndIsIdempotent() {
		this.personas.fixture(); // a platform administrator exists
		String phone = Personas.newPhone();
		BootstrapProperties config = new BootstrapProperties("0" + phone.substring(4), null, "Boot Admin");
		assertThat(this.bootstrap.ensure(config)).isEmpty(); // someone already is platform admin

		this.tx.executeWithoutResult((status) -> {
			status.setRollbackOnly();
			this.jdbc.update("update role_assignments set status = 'REVOKED', revoked_at = now() "
					+ "where role_code = 'PLATFORM_ADMIN' and status = 'ACTIVE'");
			Optional<User> created = this.bootstrap.ensure(config);
			assertThat(created).isPresent();
			assertThat(created.get().getPhoneE164()).isEqualTo(phone);
			assertThat(created.get().getPhoneVerifiedAt()).isNotNull();
			assertThat(created.get().getDisplayName()).isEqualTo("Boot Admin");
			assertThat(this.bootstrap.ensure(config)).isEmpty();
			assertThat(this.bootstrap.ensure(new BootstrapProperties(null, "other@example.org", "x"))).isEmpty();
			assertThat(this.jdbc.queryForObject(
					"select count(*) from role_assignments where role_code = 'PLATFORM_ADMIN' and status = 'ACTIVE'",
					Integer.class)).isEqualTo(1);
			assertThat(this.jdbc.queryForObject("select count(*) from users where phone_e164 = ?", Integer.class, phone))
				.isEqualTo(1);
			assertThat(this.jdbc.queryForObject(
					"select metadata::text from audit_logs where action = 'identity.bootstrap_admin' and target_id = ?",
					String.class, created.get().getId()))
				.doesNotContain(phone)
				.contains("\"userCreated\": true");
		});
		assertThat(this.jdbc.queryForObject("select count(*) from users where phone_e164 = ?", Integer.class, phone))
			.isZero();
	}

	@Test
	void reusesAnExistingAccountAndDoesNothingWithoutConfiguration() {
		assertThat(this.bootstrap.ensure(new BootstrapProperties(" ", "", "x"))).isEmpty();
		this.tx.executeWithoutResult((status) -> {
			status.setRollbackOnly();
			java.util.UUID existing = this.personas.createUser("existing", "ACTIVE");
			String phone = this.jdbc.queryForObject("select phone_e164 from users where id = ?", String.class, existing);
			this.jdbc.update("update role_assignments set status = 'REVOKED', revoked_at = now() "
					+ "where role_code = 'PLATFORM_ADMIN' and status = 'ACTIVE'");
			assertThat(this.bootstrap.ensure(new BootstrapProperties(phone, null, "ignored")))
				.hasValueSatisfying((u) -> assertThat(u.getId()).isEqualTo(existing));
			assertThat(this.jdbc.queryForObject(
					"select count(*) from role_assignments where user_id = ? and role_code = 'PLATFORM_ADMIN' and scope_type = 'PLATFORM'",
					Integer.class, existing)).isEqualTo(1);
		});
	}

}
