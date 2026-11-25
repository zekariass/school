package com.nemaz.school.system.observability;

import com.nemaz.school.IntegrationTest;
import io.sentry.Sentry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Without SENTRY_DSN (every environment but a configured deployment) nothing is sent anywhere. */
@IntegrationTest
class ErrorTrackingDisabledTest {

	@Test
	void sentryIsDisabledWithoutADsn() {
		assertThat(Sentry.isEnabled()).isFalse();
	}

}
