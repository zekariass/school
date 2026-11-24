package com.nemaz.school.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * The application clock in tests: real time plus an adjustable offset. Tests that move time
 * must {@link #reset()} in a {@code finally} / {@code @AfterEach}, because the context (and
 * this clock) is shared by every integration test.
 */
public class MutableClock extends Clock {

	private volatile Duration offset = Duration.ZERO;

	@Override
	public ZoneId getZone() {
		return ZoneOffset.UTC;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		return Clock.offset(Clock.system(zone), this.offset);
	}

	@Override
	public Instant instant() {
		return Instant.now().plus(this.offset);
	}

	public void advance(Duration duration) {
		this.offset = this.offset.plus(duration);
	}

	public void reset() {
		this.offset = Duration.ZERO;
	}

}
