package com.nemaz.school.common.id;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

/**
 * Time-ordered UUIDv7 (RFC 9562) for ids created outside JPA entities (entities use
 * Hibernate's {@code @UuidGenerator(style = VERSION_7)}). Time-ordered ids keep B-tree
 * inserts append-friendly and make "newest first" keyset pagination possible.
 */
public final class Ids {

	private static final SecureRandom RANDOM = new SecureRandom();

	private Ids() {
	}

	public static UUID newId() {
		return newId(Clock.systemUTC());
	}

	static UUID newId(Clock clock) {
		long millis = clock.millis();
		long randA = RANDOM.nextInt(1 << 12);
		long randB = RANDOM.nextLong();

		// 48-bit unix ms | version 7 | 12 random bits
		long msb = (millis << 16) | (0x7L << 12) | randA;
		// variant 0b10 | 62 random bits
		long lsb = (randB & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
		return new UUID(msb, lsb);
	}

	/** Unix epoch milliseconds embedded in a UUIDv7. */
	public static long timestampMillis(UUID uuidV7) {
		if (uuidV7.version() != 7) {
			throw new IllegalArgumentException("Not a UUIDv7: " + uuidV7);
		}
		return uuidV7.getMostSignificantBits() >>> 16;
	}

}
