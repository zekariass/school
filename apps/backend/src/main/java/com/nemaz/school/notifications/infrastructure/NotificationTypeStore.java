package com.nemaz.school.notifications.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import com.nemaz.school.notifications.domain.NotificationType;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class NotificationTypeStore {

	private final JdbcTemplate jdbc;

	NotificationTypeStore(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public Optional<NotificationType> find(String code) {
		return this.jdbc
			.query("select code, category, default_channels, can_be_urgent, user_can_disable from notification_types where code = ?",
					(rs, i) -> map(rs), code)
			.stream()
			.findFirst();
	}

	/** Every type, grouped by category and code (stable order for the preferences screens). */
	public List<NotificationType> all() {
		return this.jdbc.query("""
				select code, category, default_channels, can_be_urgent, user_can_disable from notification_types
				order by category, code""", (rs, i) -> map(rs));
	}

	private static NotificationType map(ResultSet rs) throws SQLException {
		String[] channels = (String[]) rs.getArray("default_channels").getArray();
		return new NotificationType(rs.getString("code"), rs.getString("category"), List.of(channels),
				rs.getBoolean("can_be_urgent"), rs.getBoolean("user_can_disable"));
	}

}
