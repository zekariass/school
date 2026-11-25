package com.nemaz.school.identity.application;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.common.security.Masking;
import com.nemaz.school.identity.UserDirectory;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class DefaultUserDirectory implements UserDirectory {

	private final NamedParameterJdbcTemplate jdbc;

	DefaultUserDirectory(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Map<UUID, UserPreferences> preferences(Collection<UUID> userIds) {
		Map<UUID, UserPreferences> result = new HashMap<>();
		if (userIds.isEmpty()) {
			return result;
		}
		this.jdbc.query("select id, preferred_language_code, timezone from users where id in (:ids)",
				new MapSqlParameterSource("ids", userIds), (rs) -> {
					result.put(rs.getObject("id", UUID.class),
							new UserPreferences(rs.getString("preferred_language_code"), rs.getString("timezone")));
				});
		return result;
	}

	@Override
	public Map<UUID, UserProfileView> profiles(Collection<UUID> userIds) {
		Map<UUID, UserProfileView> result = new HashMap<>();
		if (userIds.isEmpty()) {
			return result;
		}
		this.jdbc.query("""
				select id, display_name, given_name, middle_name, family_name, avatar_media_id, date_of_birth, gender,
				       username, preferred_language_code, phone_e164, email, account_type, status, deleted_at
				from users where id in (:ids)""", new MapSqlParameterSource("ids", userIds), (rs) -> {
			java.sql.Date dob = rs.getDate("date_of_birth");
			UUID id = rs.getObject("id", UUID.class);
			result.put(id,
					new UserProfileView(id, rs.getString("display_name"), rs.getString("given_name"),
							rs.getString("middle_name"), rs.getString("family_name"),
							rs.getObject("avatar_media_id", UUID.class), (dob != null) ? dob.toLocalDate() : null,
							rs.getString("gender"), rs.getString("username"), rs.getString("preferred_language_code"),
							Masking.phone(rs.getString("phone_e164")), Masking.email(rs.getString("email")),
							"MANAGED".equals(rs.getString("account_type")),
							"ACTIVE".equals(rs.getString("status")) && rs.getTimestamp("deleted_at") == null));
		});
		return result;
	}

}
