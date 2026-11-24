package com.nemaz.school.common.pagination;

import java.util.List;
import java.util.function.Function;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One page of a keyset-paginated list. {@code nextCursor} is opaque to clients and null
 * on the last page. Offset pagination is not used: feeds and inboxes change while being
 * read, and offsets skip or repeat items.
 */
public record CursorPage<T>(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<T> items,
		@Schema(description = "Opaque cursor of the next page; absent on the last page") String nextCursor) {

	public CursorPage {
		items = List.copyOf(items);
	}

	public boolean hasMore() {
		return this.nextCursor != null;
	}

	/**
	 * Builds a page from a query that fetched {@code limit + 1} rows: the extra row only
	 * signals that another page exists and is not returned.
	 */
	public static <T> CursorPage<T> fromOverfetch(List<T> rows, int limit, Function<T, String> cursorOf) {
		if (rows.size() <= limit) {
			return new CursorPage<>(rows, null);
		}
		List<T> page = rows.subList(0, limit);
		return new CursorPage<>(page, cursorOf.apply(page.getLast()));
	}

	public <R> CursorPage<R> map(Function<T, R> mapper) {
		return new CursorPage<>(this.items.stream().map(mapper).toList(), this.nextCursor);
	}

}
