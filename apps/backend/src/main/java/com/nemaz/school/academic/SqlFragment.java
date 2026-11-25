package com.nemaz.school.academic;

import java.util.Map;

/** A SQL boolean expression with its named parameters, to be combined into a larger WHERE clause. */
public record SqlFragment(String sql, Map<String, Object> params) {

	public SqlFragment {
		params = Map.copyOf(params);
	}

	public static SqlFragment always() {
		return new SqlFragment("true", Map.of());
	}

	public static SqlFragment never() {
		return new SqlFragment("false", Map.of());
	}

}
