package com.nemaz.school.system.observability;

import java.util.List;

import com.nemaz.school.common.error.ApiException;
import io.sentry.Breadcrumb;
import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.protocol.Message;
import io.sentry.protocol.Request;
import io.sentry.protocol.SentryException;
import io.sentry.protocol.User;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;

class SentryScrubberTest {

	private final SentryScrubber scrubber = new SentryScrubber();

	@Test
	void scrubsEmailsPhonesDatabaseValuesAndTokens() {
		assertThat(PiiScrubber.scrub("send to abebe.kebede@example.org failed")).isEqualTo("send to [redacted] failed");
		assertThat(PiiScrubber.scrub("phone +251 911 223 344 invalid")).isEqualTo("phone [redacted] invalid");
		assertThat(PiiScrubber.scrub("call 0911223344 now")).isEqualTo("call [redacted] now");
		assertThat(PiiScrubber.scrub("ERROR: duplicate key value violates unique constraint \"uq_users_phone\"\n"
				+ "  Detail: Key (phone)=(+251911223344) already exists."))
			.contains("Key (phone)=([redacted])")
			.doesNotContain("251911223344");
		assertThat(PiiScrubber.scrub("Failing row contains (1, Abebe Kebede, 2016-04-01)."))
			.isEqualTo("Failing row contains ([redacted]).");
		assertThat(PiiScrubber.scrub("Authorization: Bearer eyJhbGciOiJFUzI1NiJ9.abc.def")).doesNotContain("eyJ");
		assertThat(PiiScrubber.scrub("token a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0 leaked")).isEqualTo("token [redacted] leaked");
	}

	@Test
	void keepsUsefulDiagnosticText() {
		String text = "Connection to db.internal:5432 refused (attempt 3) in com.nemaz.school.media.MediaService line 42";
		assertThat(PiiScrubber.scrub(text)).isEqualTo(text);
		String uuid = "class 0192f1c4-7a3b-7c3e-8a52-6f4e0c1d2b3a not found";
		assertThat(PiiScrubber.scrub(uuid)).isEqualTo(uuid);
		assertThat(PiiScrubber.scrub(null)).isNull();
	}

	@Test
	void serverErrorsAreKeptButStrippedOfRequestUserAndBreadcrumbs() {
		SentryEvent event = new SentryEvent(new IllegalStateException("boom for abebe@example.org"));
		Request request = new Request();
		request.setUrl("https://api.example.org/api/v1/students?phone=0911223344");
		request.setData("{\"name\":\"Child Name\"}");
		event.setRequest(request);
		User user = new User();
		user.setEmail("abebe@example.org");
		user.setIpAddress("203.0.113.9");
		event.setUser(user);
		event.setServerName("vm-01");
		event.setBreadcrumbs(List.of(new Breadcrumb("GET /api/v1/students?phone=0911223344")));
		SentryException exception = new SentryException();
		exception.setValue("boom for abebe@example.org");
		event.setExceptions(List.of(exception));
		Message message = new Message();
		message.setFormatted("student 0911223344 failed");
		event.setMessage(message);

		SentryEvent result = this.scrubber.execute(event, new Hint());

		assertThat(result).isNotNull();
		assertThat(result.getRequest()).isNull();
		assertThat(result.getUser()).isNull();
		assertThat(result.getServerName()).isNull();
		assertThat(result.getBreadcrumbs()).isNullOrEmpty();
		assertThat(result.getExceptions().getFirst().getValue()).isEqualTo("boom for [redacted]");
		assertThat(result.getMessage().getFormatted()).isEqualTo("student [redacted] failed");
	}

	@Test
	void clientErrorsAreDropped() {
		assertThat(this.scrubber.execute(new SentryEvent(ApiException.forbidden("class.access_denied")), new Hint()))
			.isNull();
		assertThat(this.scrubber.execute(new SentryEvent(ApiException.notFound("school.not_found")), new Hint()))
			.isNull();
		assertThat(this.scrubber.execute(new SentryEvent(new AccessDeniedException("no")), new Hint())).isNull();
		assertThat(this.scrubber.execute(new SentryEvent(new ResponseStatusException(HttpStatus.BAD_REQUEST)),
				new Hint()))
			.isNull();
		// wrapped client error
		assertThat(this.scrubber.execute(new SentryEvent(new RuntimeException(ApiException.conflict("x.y"))), new Hint()))
			.isNull();
		// server-side ApiException and unknown failures are kept
		assertThat(this.scrubber.execute(
				new SentryEvent(new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "system.failure")), new Hint()))
			.isNotNull();
		assertThat(this.scrubber.execute(new SentryEvent(new NullPointerException()), new Hint())).isNotNull();
	}

	@Test
	void breadcrumbsAreNeverSent() {
		assertThat(this.scrubber.execute(new Breadcrumb("anything"), new Hint())).isNull();
	}

}
