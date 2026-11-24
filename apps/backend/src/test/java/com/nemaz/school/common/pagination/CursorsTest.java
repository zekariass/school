package com.nemaz.school.common.pagination;

import java.util.List;

import com.nemaz.school.common.error.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class CursorsTest {

	@Test
	void roundTripsKeysetPositions() {
		String cursor = Cursors.encode("2026-11-24T10:15:30Z", "01a20dfb-9dd1-75da-a809-0179112c7e75");
		assertThat(cursor).matches("^[A-Za-z0-9_-]+$");
		assertThat(Cursors.decode(cursor, 2)).containsExactly("2026-11-24T10:15:30Z",
				"01a20dfb-9dd1-75da-a809-0179112c7e75");
	}

	@Test
	void rejectsMalformedCursorsAsBadRequest() {
		assertThatExceptionOfType(ApiException.class).isThrownBy(() -> Cursors.decode("%%%not-base64", 2))
			.satisfies((ex) -> assertThat(ex.code()).isEqualTo("invalid_cursor"));
		assertThatExceptionOfType(ApiException.class)
			.isThrownBy(() -> Cursors.decode(Cursors.encode("only-one"), 2));
	}

	@Test
	void clampsLimits() {
		assertThat(Cursors.clampLimit(null)).isEqualTo(Cursors.DEFAULT_LIMIT);
		assertThat(Cursors.clampLimit(0)).isEqualTo(1);
		assertThat(Cursors.clampLimit(10_000)).isEqualTo(Cursors.MAX_LIMIT);
	}

	@Test
	void buildsPagesFromOverfetchedRows() {
		CursorPage<Integer> last = CursorPage.fromOverfetch(List.of(1, 2, 3), 3, String::valueOf);
		assertThat(last.items()).containsExactly(1, 2, 3);
		assertThat(last.hasMore()).isFalse();

		CursorPage<Integer> more = CursorPage.fromOverfetch(List.of(1, 2, 3, 4), 3, String::valueOf);
		assertThat(more.items()).containsExactly(1, 2, 3);
		assertThat(more.nextCursor()).isEqualTo("3");
		assertThat(more.map((i) -> i * 10).items()).containsExactly(10, 20, 30);
	}

}
