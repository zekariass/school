package com.nemaz.school.tenancy.infrastructure;

import java.util.List;

import com.nemaz.school.tenancy.SettingScope;
import jakarta.persistence.criteria.Predicate;

import org.springframework.data.jpa.domain.Specification;

/**
 * Row filters for the scoped tables (settings, feature_overrides), which share the columns
 * {@code scopeType}, {@code countryCode}, {@code organizationId} and {@code schoolId}.
 */
public final class ScopeSpecifications {

	private ScopeSpecifications() {
	}

	/** Rows at exactly this scope. */
	public static <T> Specification<T> at(SettingScope scope) {
		return (root, query, cb) -> {
			Predicate type = cb.equal(root.get("scopeType"), scope.type());
			return switch (scope.type()) {
				case PLATFORM -> type;
				case COUNTRY -> cb.and(type, cb.equal(root.get("countryCode"), scope.countryCode()));
				case ORGANIZATION -> cb.and(type, cb.equal(root.get("organizationId"), scope.organizationId()));
				case SCHOOL -> cb.and(type, cb.equal(root.get("schoolId"), scope.schoolId()));
			};
		};
	}

	/** Rows at any of these scopes (a resolution chain). */
	public static <T> Specification<T> atAny(List<SettingScope> scopes) {
		return Specification.anyOf(scopes.stream().map(ScopeSpecifications::<T>at).toList());
	}

}
