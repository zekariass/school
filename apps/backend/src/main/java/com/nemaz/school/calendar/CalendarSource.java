package com.nemaz.school.calendar;

import java.util.List;

/**
 * One source of the union calendar (R-17). The calendar module asks every bean of this type for the items of the
 * caller's window and merges them; a source of a later milestone (assignment due dates, live sessions, tutoring sessions)
 * is a bean in its own module and needs no change in this one.
 *
 * <p>
 * <b>Access is the source's job:</b> {@link #items} must return only what {@link CalendarQuery#user()} may see, decided in
 * SQL or by the owning module's access rules (never "everything, the client filters": spec §100). Everything is
 * restricted to {@link CalendarQuery#schoolIds()} (the schools the caller may see whose calendar is switched on), and,
 * when {@link CalendarQuery#studentId()} is set, to what concerns that child. A source must not return more than
 * {@link CalendarQuery#maxItems()} items.
 */
public interface CalendarSource {

	/** Stable key of the source, upper snake case ({@link CalendarItem#source()}); clients filter on it. */
	String key();

	/** The items overlapping the query's window (instants {@code [from, to)}), in any order. */
	List<CalendarItem> items(CalendarQuery query);

}
