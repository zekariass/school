package com.nemaz.school.common.pagination;

import java.util.List;
import java.util.UUID;

import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.persistence.IdentifiedEntity;
import jakarta.persistence.criteria.Predicate;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Keyset pagination over the UUIDv7 primary key of an entity: ids are time-ordered, so
 * ordering by id is creation order, and the last id of a page is the cursor of the next.
 * Compared in the database, never in Java ({@code UUID.compareTo} orders differently).
 */
public final class KeysetPages {

	private KeysetPages() {
	}

	public static <T extends IdentifiedEntity> CursorPage<T> page(JpaSpecificationExecutor<T> repository,
			Specification<T> filter, String cursor, Integer limit, boolean newestFirst) {
		int pageSize = Cursors.clampLimit(limit);
		UUID after = (cursor == null) ? null : parse(cursor);
		Specification<T> spec = (root, query, cb) -> {
			Predicate base = (filter == null) ? cb.conjunction() : filter.toPredicate(root, query, cb);
			if (after == null) {
				return base;
			}
			Predicate position = newestFirst ? cb.lessThan(root.<UUID>get("id"), after)
					: cb.greaterThan(root.<UUID>get("id"), after);
			return cb.and(base, position);
		};
		Sort sort = newestFirst ? Sort.by(Sort.Direction.DESC, "id") : Sort.by(Sort.Direction.ASC, "id");
		List<T> rows = repository.findBy(spec, (q) -> q.sortBy(sort).limit(pageSize + 1).all());
		return CursorPage.fromOverfetch(rows, pageSize, (row) -> Cursors.encode(row.getId().toString()));
	}

	private static UUID parse(String cursor) {
		try {
			return UUID.fromString(Cursors.decode(cursor, 1).getFirst());
		}
		catch (IllegalArgumentException ex) {
			throw ApiException.badRequest("invalid_cursor");
		}
	}

}
