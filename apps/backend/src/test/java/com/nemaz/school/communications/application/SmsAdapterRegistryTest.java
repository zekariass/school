package com.nemaz.school.communications.application;

import java.util.List;
import java.util.Set;

import com.nemaz.school.communications.SmsAdapter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SmsAdapterRegistryTest {

	private static SmsAdapter adapter(String id, String... countries) {
		return new SmsAdapter() {
			@Override
			public String id() {
				return id;
			}

			@Override
			public Set<String> countries() {
				return Set.of(countries);
			}

			@Override
			public SmsResult send(SmsMessage message) {
				return SmsResult.accepted(id);
			}
		};
	}

	@Test
	void theNumbersCountryPicksTheAdapterAndStarIsTheFallback() {
		SmsAdapterRegistry registry = new SmsAdapterRegistry(List.of(adapter("et", "ET"), adapter("ke", "KE"), adapter("any", "*")));
		assertThat(registry.forNumber("+251911223344")).hasValueSatisfying((a) -> assertThat(a.id()).isEqualTo("et"));
		assertThat(registry.forNumber("+254712345678")).hasValueSatisfying((a) -> assertThat(a.id()).isEqualTo("ke"));
		assertThat(registry.forNumber("+14155552671")).hasValueSatisfying((a) -> assertThat(a.id()).isEqualTo("any"));
		assertThat(registry.forNumber("not a number")).hasValueSatisfying((a) -> assertThat(a.id()).isEqualTo("any"));
	}

	@Test
	void withoutAFallbackAnUnservedCountryHasNoAdapter() {
		SmsAdapterRegistry registry = new SmsAdapterRegistry(List.of(adapter("et", "ET")));
		assertThat(registry.forNumber("+254712345678")).isEmpty();
	}

	@Test
	void twoAdaptersForOneCountryStopTheApplication() {
		assertThatThrownBy(() -> new SmsAdapterRegistry(List.of(adapter("a", "ET"), adapter("b", "ET"))))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("ET");
	}

}
