package com.nemaz.school.calendar.application;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.calendar.CalendarItem;
import com.nemaz.school.calendar.CalendarQuery;
import com.nemaz.school.calendar.CalendarSource;
import com.nemaz.school.calendar.application.CalendarModels.CalendarPage;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.pagination.Cursors;
import com.nemaz.school.common.security.CurrentUser;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The union calendar (R-17): asks every {@link CalendarSource} for the items of a window and merges them in start order.
 * Each source filters by the caller's access itself; this service only resolves <em>which schools</em> the request covers
 * ({@link CalendarAccess#target}: 404 for a school the caller cannot see, 403 when its calendar is off, silently skipped
 * when no school was named) and applies the caps:
 * <ul>
 * <li>a window is at most {@value #MAX_WINDOW_DAYS} days;</li>
 * <li>a source returns at most {@value #MAX_ITEMS_PER_SOURCE} items for a window (then {@code truncated} is set: ask for a
 * shorter window), a recurring event at most {@value EventSources#MAX_OCCURRENCES_PER_SERIES} occurrences;</li>
 * <li>pages are keyset pages over (start, id), 200 items by default, 500 at most.</li>
 * </ul>
 * Without {@code sources}, every source answers except {@code PERSONAL_EVENT} when a school or child was named (a school's
 * calendar is not the place for the caller's private notes; ask for them explicitly).
 */
@Service
@Transactional(readOnly = true)
public class CalendarService {

	static final int MAX_WINDOW_DAYS = 366;

	static final int MAX_ITEMS_PER_SOURCE = 2000;

	static final int DEFAULT_LIMIT = 200;

	static final int MAX_LIMIT = 500;

	private final List<CalendarSource> sources;

	private final CalendarAccess access;

	CalendarService(List<CalendarSource> sources, CalendarAccess access) {
		this.sources = sources;
		this.access = access;
	}

	public CalendarPage calendar(CurrentUser user, Instant from, Instant to, UUID schoolId, UUID studentId, String sourceList,
			String cursor, Integer limit) {
		if (from == null || to == null || !from.isBefore(to)) {
			throw ApiException.badRequest("calendar.window_invalid");
		}
		if (Duration.between(from, to).compareTo(Duration.ofDays(MAX_WINDOW_DAYS)) > 0) {
			throw ApiException.badRequest("calendar.window_too_large");
		}
		Set<String> chosen = chosenSources(sourceList, schoolId != null || studentId != null);
		CalendarAccess.Target target = this.access.target(user, schoolId, studentId);
		CalendarQuery query = new CalendarQuery(user, from, to, target.schools(), studentId, MAX_ITEMS_PER_SOURCE);
		List<CalendarItem> all = new ArrayList<>();
		boolean truncated = false;
		for (CalendarSource source : this.sources) {
			if (!chosen.contains(source.key())) {
				continue;
			}
			List<CalendarItem> items = source.items(query);
			if (items.size() > MAX_ITEMS_PER_SOURCE) {
				truncated = true;
				items = items.subList(0, MAX_ITEMS_PER_SOURCE);
			}
			all.addAll(items);
		}
		all.sort(Comparator.comparing(CalendarItem::startAt).thenComparing(CalendarItem::id));
		int size = (limit == null) ? DEFAULT_LIMIT : Math.max(1, Math.min(MAX_LIMIT, limit));
		int start = 0;
		if (cursor != null) {
			List<String> parts = Cursors.decode(cursor, 2);
			Instant at = instant(parts.get(0));
			String id = parts.get(1);
			while (start < all.size() && !after(all.get(start), at, id)) {
				start++;
			}
		}
		List<CalendarItem> page = all.subList(Math.min(start, all.size()), Math.min(all.size(), start + size));
		String next = (start + size < all.size() && !page.isEmpty())
				? Cursors.encode(page.getLast().startAt().toString(), page.getLast().id()) : null;
		return new CalendarPage(page, next, truncated, from, to);
	}

	private static boolean after(CalendarItem item, Instant at, String id) {
		int byStart = item.startAt().compareTo(at);
		return byStart > 0 || (byStart == 0 && item.id().compareTo(id) > 0);
	}

	private Set<String> chosenSources(String list, boolean narrowed) {
		Set<String> known = new LinkedHashSet<>();
		this.sources.forEach((s) -> known.add(s.key()));
		if (list == null || list.isBlank()) {
			Set<String> all = new LinkedHashSet<>(known);
			if (narrowed) {
				all.remove("PERSONAL_EVENT");
			}
			return all;
		}
		Set<String> chosen = new LinkedHashSet<>();
		for (String key : list.split(",")) {
			String name = key.strip().toUpperCase(java.util.Locale.ROOT);
			if (!known.contains(name)) {
				throw ApiException.badRequest("calendar.source_invalid");
			}
			chosen.add(name);
		}
		return chosen;
	}

	private static Instant instant(String text) {
		try {
			return Instant.parse(text);
		}
		catch (DateTimeParseException ex) {
			throw ApiException.badRequest("invalid_cursor");
		}
	}

}
