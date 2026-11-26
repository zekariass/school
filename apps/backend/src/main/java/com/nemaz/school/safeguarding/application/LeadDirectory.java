package com.nemaz.school.safeguarding.application;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.authorization.RoleCodes;
import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.safeguarding.SafeguardingLeads;
import com.nemaz.school.safeguarding.application.SafeguardingModels.LeadView;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who the safeguarding leads are, read from the ACTIVE role assignments (the role is the only source of truth: there is no
 * second list that could disagree with the permissions). A lead whose account is not ACTIVE does not count.
 */
@Component
@Transactional(readOnly = true)
class LeadDirectory implements SafeguardingLeads {

	private final JdbcClient jdbc;

	LeadDirectory(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public boolean hasLead(UUID schoolId) {
		return !leadsOf(schoolId).isEmpty();
	}

	@Override
	public Set<UUID> leadsOf(UUID schoolId) {
		if (schoolId == null) {
			return Set.of();
		}
		return new LinkedHashSet<>(this.jdbc.sql("""
				select distinct ra.user_id from role_assignments ra join users u on u.id = ra.user_id
				where ra.status = 'ACTIVE' and ra.role_code = :role and ra.scope_type = 'SCHOOL' and ra.school_id = :s
				  and u.status = 'ACTIVE' and u.deleted_at is null""")
			.param("role", RoleCodes.SAFEGUARDING_LEAD)
			.param("s", schoolId)
			.query(UUID.class)
			.list());
	}

	@Override
	public Set<UUID> alertRecipients(UUID schoolId) {
		Set<UUID> leads = leadsOf(schoolId);
		return leads.isEmpty() ? platformLeads() : leads;
	}

	Set<UUID> platformLeads() {
		return new LinkedHashSet<>(this.jdbc.sql("""
				select distinct ra.user_id from role_assignments ra join users u on u.id = ra.user_id
				where ra.status = 'ACTIVE' and ra.role_code = :role and ra.scope_type = 'PLATFORM'
				  and u.status = 'ACTIVE' and u.deleted_at is null""")
			.param("role", RoleCodes.PLATFORM_SAFEGUARDING_LEAD)
			.query(UUID.class)
			.list());
	}

	/** True when the user is a lead of the school or a platform lead (an assignee of an incident must be one). */
	boolean isLeadFor(UUID userId, UUID schoolId) {
		return this.jdbc.sql("""
				select exists (select 1 from role_assignments ra join users u on u.id = ra.user_id
				  where ra.user_id = :u and ra.status = 'ACTIVE' and u.status = 'ACTIVE' and u.deleted_at is null
				    and ((ra.role_code = :lead and ra.scope_type = 'SCHOOL' and ra.school_id = :s)
				      or (ra.role_code = :plat and ra.scope_type = 'PLATFORM')))""")
			.param("u", userId)
			.param("s", schoolId)
			.param("lead", RoleCodes.SAFEGUARDING_LEAD)
			.param("plat", RoleCodes.PLATFORM_SAFEGUARDING_LEAD)
			.query(Boolean.class)
			.single();
	}

	List<LeadView> views(UUID schoolId) {
		return this.jdbc.sql("""
				select ra.user_id, u.display_name, ra.granted_at from role_assignments ra join users u on u.id = ra.user_id
				where ra.status = 'ACTIVE' and ra.role_code = :role and ra.scope_type = 'SCHOOL' and ra.school_id = :s
				order by ra.granted_at, ra.user_id""")
			.param("role", RoleCodes.SAFEGUARDING_LEAD)
			.param("s", schoolId)
			.query((rs, i) -> new LeadView(Rows.uuid(rs, "user_id"), rs.getString("display_name"), Rows.instant(rs, "granted_at")))
			.list();
	}

	/** An ACTIVE staff member (teacher, principal, other staff) of the school. */
	boolean isActiveStaff(UUID userId, UUID schoolId) {
		return this.jdbc.sql("select exists (select 1 from school_staff where school_id = :s and user_id = :u and status = 'ACTIVE')")
			.param("s", schoolId)
			.param("u", userId)
			.query(Boolean.class)
			.single();
	}

}
