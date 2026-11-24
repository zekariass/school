package com.nemaz.school.common.id;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class IdsTest {

	@Test
	void producesRfc9562Version7Ids() {
		UUID id = Ids.newId();
		assertThat(id.version()).isEqualTo(7);
		assertThat(id.variant()).isEqualTo(2);
	}

	@Test
	void embedsTheCreationTimestamp() {
		Instant at = Instant.parse("2026-11-24T10:15:30.123Z");
		UUID id = Ids.newId(Clock.fixed(at, ZoneOffset.UTC));
		assertThat(Ids.timestampMillis(id)).isEqualTo(at.toEpochMilli());
	}

	@Test
	void idsFromLaterMillisecondsSortAfterEarlierOnes() {
		List<UUID> ids = new ArrayList<>();
		Instant base = Instant.parse("2026-01-01T00:00:00Z");
		for (int i = 0; i < 100; i++) {
			ids.add(Ids.newId(Clock.fixed(base.plusMillis(i), ZoneOffset.UTC)));
		}
		List<String> asStrings = ids.stream().map(UUID::toString).toList();
		assertThat(asStrings).isSorted();
	}

	@Test
	void isUnique() {
		HashSet<UUID> seen = new HashSet<>();
		for (int i = 0; i < 10_000; i++) {
			assertThat(seen.add(Ids.newId())).isTrue();
		}
	}

	@Test
	void rejectsNonV7WhenReadingTimestamp() {
		assertThatIllegalArgumentException().isThrownBy(() -> Ids.timestampMillis(UUID.randomUUID()));
	}

}
