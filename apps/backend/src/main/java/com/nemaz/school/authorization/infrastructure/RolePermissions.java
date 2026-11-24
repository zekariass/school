package com.nemaz.school.authorization.infrastructure;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.common.security.AccessScope;
import com.nemaz.school.common.security.RoleGrant;
import com.nemaz.school.common.security.ScopeLevel;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Role -> permission lookups (role_permissions) and the grant query behind the principal. */
@Component
public class RolePermissions {

	private final JdbcTemplate jdbc;

	RolePermissions(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public Set<String> of(String roleCode) {
		return new HashSet<>(this.jdbc.queryForList(
				"select permission_code from role_permissions where role_code = ? order by permission_code", String.class,
				roleCode));
	}

	/** The user's ACTIVE assignments with each role's permissions, in grant order. */
	public List<RoleGrant> activeGrants(UUID userId) {
		List<RoleGrant> grants = new ArrayList<>();
		this.jdbc.query("""
				select ra.role_code, ra.scope_type, ra.country_code, ra.organization_id, ra.school_id, ra.campus_id,
				       array_remove(array_agg(rp.permission_code order by rp.permission_code), null) as permissions
				from role_assignments ra
				left join role_permissions rp on rp.role_code = ra.role_code
				where ra.user_id = ? and ra.status = 'ACTIVE'
				group by ra.id
				order by ra.granted_at, ra.id""", (rs) -> {
			String[] permissions = (String[]) rs.getArray("permissions").getArray();
			grants.add(new RoleGrant(rs.getString("role_code"),
					new AccessScope(ScopeLevel.valueOf(rs.getString("scope_type")), rs.getString("country_code"),
							rs.getObject("organization_id", UUID.class), rs.getObject("school_id", UUID.class),
							rs.getObject("campus_id", UUID.class)),
					new HashSet<>(Arrays.asList(permissions))));
		}, userId);
		return grants;
	}

}
