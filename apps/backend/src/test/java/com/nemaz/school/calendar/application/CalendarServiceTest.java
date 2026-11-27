package com.nemaz.school.calendar.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.nemaz.school.calendar.CalendarItem;
import com.nemaz.school.calendar.CalendarQuery;
import com.nemaz.school.calendar.CalendarSource;
import com.nemaz.school.calendar.application.CalendarModels.CalendarPage;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.security.CurrentUser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The union over the {@link CalendarSource} SPI with fake sources: this is how assignment due dates (M17) and the published
 * timetable (M37) will plug in later, with no change to the calendar module.
 */
class CalendarServiceTest {

	private static final Instant FROM = Instant.parse("2027-02-01T00:00:00Z");

	private static final Instant TO = Instant.parse("2027-03-01T00:00:00Z");

	private final UUID school = UUID.randomUUID();

	private final CurrentUser user = new CurrentUser(UUID.randomUUID(), UUID.randomUUID(), Locale.ENGLISH, List.of());

	private static CalendarItem item(String id, String source, Instant start) {
		return new CalendarItem(id, source, "KIND", id, null, null, null, null, null, null, null, false, start, start.plusSeconds(60),
				null, null, "UTC", "SCHEDULED", null, null, false, false, false, null, null, null);
	}

	private static CalendarSource source(String key, AtomicReference<CalendarQuery> seen, List<CalendarItem> items) {
		return new CalendarSource() {
			@Override
			public String key() {
				return key;
			}

			@Override
			public List<CalendarItem> items(CalendarQuery query) {
				seen.set(query);
				return items;
			}
		};
	}

	private CalendarService service(AtomicReference<CalendarQuery> seen, CalendarSource... sources) {
		CalendarAccess access = mock(CalendarAccess.class);
		when(access.target(any(), any(), any())).thenReturn(
				new CalendarAccess.Target(this.user.userId(), null, Set.of(this.school), Set.of(),
						new CalendarAccess.Scope(Set.of(), Set.of(), Set.of(), Set.of(), Set.of())));
		return new CalendarService(List.of(sources), access);
	}

	@Test
	void aLaterSourceJoinsTheUnionWithoutAnyChangeHere() {
		AtomicReference<CalendarQuery> seen = new AtomicReference<>();
		CalendarSource assignments = source("ASSIGNMENT", seen, List.of(item("due-2", "ASSIGNMENT", Instant.parse("2027-02-10T10:00:00Z")),
				item("due-1", "ASSIGNMENT", Instant.parse("2027-02-05T10:00:00Z"))));
		CalendarSource events = source("EVENT", new AtomicReference<>(), List.of(item("event", "EVENT", Instant.parse("2027-02-08T10:00:00Z"))));
		CalendarPage page = service(seen, events, assignments).calendar(this.user, FROM, TO, null, null, null, null, null);
		assertThat(page.items()).extracting(CalendarItem::id).containsExactly("due-1", "event", "due-2");
		assertThat(page.truncated()).isFalse();
		// the source is told who asks, the window, and the schools it may look in (already checked), never "everything"
		CalendarQuery query = seen.get();
		assertThat(query.user()).isEqualTo(this.user);
		assertThat(query.from()).isEqualTo(FROM);
		assertThat(query.to()).isEqualTo(TO);
		assertThat(query.schoolIds()).containsExactly(this.school);
		assertThat(query.maxItems()).isEqualTo(CalendarService.MAX_ITEMS_PER_SOURCE);
	}

	@Test
	void callersChooseSourcesByKeyAndUnknownKeysAreRefused() {
		AtomicReference<CalendarQuery> assignmentsSeen = new AtomicReference<>();
		AtomicReference<CalendarQuery> eventsSeen = new AtomicReference<>();
		CalendarService service = service(new AtomicReference<>(), source("ASSIGNMENT", assignmentsSeen, List.of()),
				source("EVENT", eventsSeen, List.of()));
		service.calendar(this.user, FROM, TO, null, null, "assignment", null, null);
		assertThat(assignmentsSeen.get()).isNotNull();
		assertThat(eventsSeen.get()).isNull();
		assertThatThrownBy(() -> service.calendar(this.user, FROM, TO, null, null, "EVENT,TUTORING", null, null))
			.isInstanceOfSatisfying(ApiException.class, (ex) -> assertThat(ex.code()).isEqualTo("calendar.source_invalid"));
	}

	@Test
	void aSourceOverTheCapIsCutAndMarkedTruncated() {
		List<CalendarItem> many = new ArrayList<>();
		for (int i = 0; i < CalendarService.MAX_ITEMS_PER_SOURCE + 1; i++) {
			many.add(item(String.format("e%05d", i), "EVENT", FROM.plusSeconds(i)));
		}
		CalendarService service = service(new AtomicReference<>(), source("EVENT", new AtomicReference<>(), many));
		CalendarPage page = service.calendar(this.user, FROM, TO, null, null, null, null, 500);
		assertThat(page.truncated()).isTrue();
		assertThat(page.items()).hasSize(500);
		assertThat(page.nextCursor()).isNotNull();
		// the next page continues after the last item and ends without a cursor
		String cursor = page.nextCursor();
		int pages = 1;
		int total = page.items().size();
		while (cursor != null) {
			CalendarPage next = service.calendar(this.user, FROM, TO, null, null, null, cursor, 500);
			assertThat(next.items().getFirst().id()).isGreaterThan(page.items().getLast().id());
			total += next.items().size();
			cursor = next.nextCursor();
			pages++;
		}
		assertThat(total).isEqualTo(CalendarService.MAX_ITEMS_PER_SOURCE);
		assertThat(pages).isEqualTo(4);
	}

	@Test
	void windowsAreChecked() {
		CalendarService service = service(new AtomicReference<>());
		for (Instant[] bad : new Instant[][] { { TO, FROM }, { FROM, FROM }, { null, TO }, { FROM, null } }) {
			assertThatThrownBy(() -> service.calendar(this.user, bad[0], bad[1], null, null, null, null, null))
				.isInstanceOfSatisfying(ApiException.class, (ex) -> assertThat(ex.code()).isEqualTo("calendar.window_invalid"));
		}
		assertThatThrownBy(() -> service.calendar(this.user, FROM, FROM.plusSeconds(367L * 86400), null, null, null, null, null))
			.isInstanceOfSatisfying(ApiException.class, (ex) -> assertThat(ex.code()).isEqualTo("calendar.window_too_large"));
		service.calendar(this.user, FROM, FROM.plusSeconds(366L * 86400), null, null, null, null, null);
	}

}
