package com.nemaz.school.guardians.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.guardians.GuardianAccess;
import com.nemaz.school.guardians.GuardianPermission;
import com.nemaz.school.guardians.RelationshipType;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link GuardianAccess} straight from {@code student_guardians}: no cache, so revocations and restrictions apply to
 * the next request. Depends on nothing but the database, because the people module's ports (avatar policy) use it.
 */
@Component
@Transactional(readOnly = true)
class DefaultGuardianAccess implements GuardianAccess {

	/** The predicate every answer is built on: ACTIVE and not custody-restricted. */
	static final String GRANTING = "status = 'ACTIVE' and not custody_restricted";

	private static final String COLUMNS = """
			id, guardian_id, student_id, relationship_type, is_primary, can_view_academics, can_message_staff,
			can_receive_notifications, can_award_points, can_give_consent, can_book_and_pay""";

	private final JdbcClient jdbc;

	DefaultGuardianAccess(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public boolean isActiveGuardian(UUID userId, UUID studentId) {
		if (userId == null || studentId == null) {
			return false;
		}
		return this.jdbc.sql("select exists (select 1 from student_guardians where guardian_id = :g and student_id = :s and "
				+ GRANTING + ")")
			.param("g", userId)
			.param("s", studentId)
			.query(Boolean.class)
			.single();
	}

	@Override
	public boolean can(UUID userId, UUID studentId, GuardianPermission permission) {
		if (userId == null || studentId == null || permission == null) {
			return false;
		}
		return this.jdbc.sql("select exists (select 1 from student_guardians where guardian_id = :g and student_id = :s and "
				+ GRANTING + " and " + column(permission) + ")")
			.param("g", userId)
			.param("s", studentId)
			.query(Boolean.class)
			.single();
	}

	@Override
	public List<GuardianGrant> guardiansOf(UUID studentId) {
		return this.jdbc.sql("select " + COLUMNS + " from student_guardians where student_id = :s and " + GRANTING
				+ " order by is_primary desc, created_at, id")
			.param("s", studentId)
			.query(DefaultGuardianAccess::grant)
			.list();
	}

	@Override
	public List<GuardianGrant> childrenOf(UUID userId) {
		return this.jdbc.sql("select " + COLUMNS + " from student_guardians where guardian_id = :g and " + GRANTING
				+ " order by created_at, id")
			.param("g", userId)
			.query(DefaultGuardianAccess::grant)
			.list();
	}

	@Override
	public Optional<GuardianGrant> activeLink(UUID linkId) {
		if (linkId == null) {
			return Optional.empty();
		}
		return this.jdbc.sql("select " + COLUMNS + " from student_guardians where id = :id and " + GRANTING)
			.param("id", linkId)
			.query(DefaultGuardianAccess::grant)
			.optional();
	}

	/** The flag column of a permission (a fixed mapping, never user input). */
	static String column(GuardianPermission permission) {
		return switch (permission) {
			case VIEW_ACADEMICS -> "can_view_academics";
			case MESSAGE_STAFF -> "can_message_staff";
			case RECEIVE_NOTIFICATIONS -> "can_receive_notifications";
			case AWARD_POINTS -> "can_award_points";
			case GIVE_CONSENT -> "can_give_consent";
			case BOOK_AND_PAY -> "can_book_and_pay";
		};
	}

	static Set<GuardianPermission> permissions(ResultSet rs) throws SQLException {
		Set<GuardianPermission> permissions = EnumSet.noneOf(GuardianPermission.class);
		for (GuardianPermission permission : GuardianPermission.values()) {
			if (rs.getBoolean(column(permission))) {
				permissions.add(permission);
			}
		}
		return permissions;
	}

	private static GuardianGrant grant(ResultSet rs, int row) throws SQLException {
		return new GuardianGrant(Rows.uuid(rs, "id"), Rows.uuid(rs, "guardian_id"), Rows.uuid(rs, "student_id"),
				RelationshipType.valueOf(rs.getString("relationship_type")), rs.getBoolean("is_primary"),
				permissions(rs));
	}

}
