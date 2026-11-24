package com.nemaz.school.identity.application;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

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

}
