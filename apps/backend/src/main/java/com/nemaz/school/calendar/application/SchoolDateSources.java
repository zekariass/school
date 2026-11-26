package com.nemaz.school.calendar.application;

import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.calendar.CalendarItem;
import com.nemaz.school.calendar.CalendarQuery;
import com.nemaz.school.calendar.CalendarSource;
import com.nemaz.school.common.persistence.Rows;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The two calendar sources that come from school data rather than from events (R-17): the school's <b>holidays</b> (national,
 * religious and regional holidays of its country and area, and its own breaks: all-day items, possibly several days) and the
 * <b>boundaries of its academic years and periods</b> (one all-day item per start and end date). Everybody who can see the
 * school sees them; nothing here is personal. Names are the language of the caller (their locale, else the school's default
 * language, else English, else any); holidays also carry every language.
 */
@Component
class SchoolDateSources {

	private static final TypeReference<Map<String, String>> NAMES = new TypeReference<>() {
	};

	private final JdbcClient jdbc;

	private final JsonMapper json;

	SchoolDateSources(JdbcClient jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	@Component
	static class Holidays implements CalendarSource {

		private final SchoolDateSources sources;

		Holidays(SchoolDateSources sources) {
			this.sources = sources;
		}

		@Override
		public String key() {
			return "HOLIDAY";
		}

		@Override
		public List<CalendarItem> items(CalendarQuery query) {
			return this.sources.holidays(query);
		}

	}

	@Component
	static class Periods implements CalendarSource {

		private final SchoolDateSources sources;

		Periods(SchoolDateSources sources) {
			this.sources = sources;
		}

		@Override
		public String key() {
			return "ACADEMIC_PERIOD";
		}

		@Override
		public List<CalendarItem> items(CalendarQuery query) {
			return this.sources.periods(query);
		}

	}

	private static LocalDate before(Instant from) {
		return from.atZone(ZoneOffset.UTC).toLocalDate().minusDays(1);
	}

	private static LocalDate after(Instant to) {
		return to.atZone(ZoneOffset.UTC).toLocalDate().plusDays(1);
	}

	private static ZoneId zone(String name) {
		try {
			return ZoneId.of(name);
		}
		catch (DateTimeException ex) {
			return ZoneOffset.UTC;
		}
	}

	List<CalendarItem> holidays(CalendarQuery query) {
		if (query.schoolIds().isEmpty()) {
			return List.of();
		}
		List<CalendarItem> out = new ArrayList<>();
		this.jdbc.sql("""
				select h.id, h.name_i18n::text as names, h.holiday_type, h.starts_on, h.ends_on, s.id as school_id, s.timezone,
				       s.default_language_code
				from schools s
				join holidays h on (h.school_id = s.id
				  or (h.school_id is null and h.country_code = s.country_code
				      and (h.admin_area_1_id is null
				           or h.admin_area_1_id = (select a.admin_area_1_id from addresses a where a.id = s.address_id))))
				where s.id in (:schools) and h.starts_on <= :to and h.ends_on >= :from
				order by h.starts_on, h.id, s.id limit :n""")
			.param("schools", query.schoolIds())
			.param("from", java.sql.Date.valueOf(before(query.from())))
			.param("to", java.sql.Date.valueOf(after(query.to())))
			.param("n", query.maxItems() + 1)
			.query((rs) -> {
				ZoneId zone = zone(rs.getString("timezone"));
				LocalDate start = Rows.date(rs, "starts_on");
				LocalDate end = Rows.date(rs, "ends_on");
				Instant startAt = start.atStartOfDay(zone).toInstant();
				Instant endAt = end.plusDays(1).atStartOfDay(zone).toInstant();
				if (!startAt.isBefore(query.to()) || !endAt.isAfter(query.from())) {
					return;
				}
				Map<String, String> names = this.json.readValue(rs.getString("names"), NAMES);
				UUID school = Rows.uuid(rs, "school_id");
				out.add(new CalendarItem("HOLIDAY:" + Rows.uuid(rs, "id") + ":" + school, "HOLIDAY", rs.getString("holiday_type"),
						name(names, query.user().locale(), rs.getString("default_language_code"), "Holiday"), null, null, school,
						null, null, null, null, true, startAt, endAt, start, end, zone.getId(), "SCHEDULED", null, null, false,
						false, false, null, null, names));
			});
		return out;
	}

	List<CalendarItem> periods(CalendarQuery query) {
		if (query.schoolIds().isEmpty()) {
			return List.of();
		}
		List<CalendarItem> out = new ArrayList<>();
		LocalDate from = before(query.from());
		LocalDate to = after(query.to());
		this.jdbc.sql("""
				select y.id, y.school_id, y.name, y.starts_on, y.ends_on, s.timezone
				from academic_years y join schools s on s.id = y.school_id
				where y.school_id in (:schools) and ((y.starts_on between :from and :to) or (y.ends_on between :from and :to))""")
			.param("schools", query.schoolIds())
			.param("from", java.sql.Date.valueOf(from))
			.param("to", java.sql.Date.valueOf(to))
			.query((rs) -> {
				String id = "YEAR:" + Rows.uuid(rs, "id");
				boundary(out, query, id + ":START", "YEAR_START", rs.getString("name"), Rows.uuid(rs, "school_id"),
						Rows.date(rs, "starts_on"), rs.getString("timezone"));
				boundary(out, query, id + ":END", "YEAR_END", rs.getString("name"), Rows.uuid(rs, "school_id"),
						Rows.date(rs, "ends_on"), rs.getString("timezone"));
			});
		this.jdbc.sql("""
				select p.id, p.school_id, p.name, p.starts_on, p.ends_on, s.timezone
				from academic_periods p join schools s on s.id = p.school_id
				where p.school_id in (:schools) and ((p.starts_on between :from and :to) or (p.ends_on between :from and :to))""")
			.param("schools", query.schoolIds())
			.param("from", java.sql.Date.valueOf(from))
			.param("to", java.sql.Date.valueOf(to))
			.query((rs) -> {
				String id = "PERIOD:" + Rows.uuid(rs, "id");
				boundary(out, query, id + ":START", "PERIOD_START", rs.getString("name"), Rows.uuid(rs, "school_id"),
						Rows.date(rs, "starts_on"), rs.getString("timezone"));
				boundary(out, query, id + ":END", "PERIOD_END", rs.getString("name"), Rows.uuid(rs, "school_id"),
						Rows.date(rs, "ends_on"), rs.getString("timezone"));
			});
		return out;
	}

	private static void boundary(List<CalendarItem> out, CalendarQuery query, String id, String kind, String name, UUID school,
			LocalDate date, String timezone) {
		ZoneId zone = zone(timezone);
		Instant startAt = date.atStartOfDay(zone).toInstant();
		Instant endAt = date.plusDays(1).atStartOfDay(zone).toInstant();
		if (!startAt.isBefore(query.to()) || !endAt.isAfter(query.from())) {
			return;
		}
		out.add(new CalendarItem(id, "ACADEMIC_PERIOD", kind, name, null, null, school, null, null, null, null, true, startAt,
				endAt, date, date, zone.getId(), "SCHEDULED", null, null, false, false, false, null, null, null));
	}

	/** The name for the caller: their language, the school's default language, English, any. */
	static String name(Map<String, String> names, Locale locale, String schoolLanguage, String fallback) {
		if (locale != null && names.get(locale.getLanguage()) != null) {
			return names.get(locale.getLanguage());
		}
		if (schoolLanguage != null && names.get(schoolLanguage) != null) {
			return names.get(schoolLanguage);
		}
		if (names.get("en") != null) {
			return names.get("en");
		}
		return names.values().stream().findFirst().orElse(fallback);
	}

}
