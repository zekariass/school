package com.nemaz.school.common.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Column readers for hand-written SQL (JdbcClient row mappers): null-safe instants, dates and ids. */
public final class Rows {

	private Rows() {
	}

	public static UUID uuid(ResultSet rs, String column) throws SQLException {
		return rs.getObject(column, UUID.class);
	}

	public static Instant instant(ResultSet rs, String column) throws SQLException {
		java.sql.Timestamp value = rs.getTimestamp(column);
		return (value != null) ? value.toInstant() : null;
	}

	public static LocalDate date(ResultSet rs, String column) throws SQLException {
		java.sql.Date value = rs.getDate(column);
		return (value != null) ? value.toLocalDate() : null;
	}

	public static Integer integer(ResultSet rs, String column) throws SQLException {
		int value = rs.getInt(column);
		return rs.wasNull() ? null : value;
	}

}
