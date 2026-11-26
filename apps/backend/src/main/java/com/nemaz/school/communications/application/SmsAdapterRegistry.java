package com.nemaz.school.communications.application;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.nemaz.school.communications.SmsAdapter;
import com.nemaz.school.identity.Contacts;

import org.springframework.stereotype.Component;

/**
 * Picks the {@link SmsAdapter} for a phone number: the adapter registered for the number's country, else the one that
 * serves every other country ({@code "*"}), else none (the message is recorded as FAILED {@code no_adapter}). Two adapters
 * for the same country is a configuration error and stops the application at startup.
 */
@Component
class SmsAdapterRegistry {

	static final String ANY = "*";

	private final Map<String, SmsAdapter> byCountry = new HashMap<>();

	SmsAdapterRegistry(List<SmsAdapter> adapters) {
		for (SmsAdapter adapter : adapters) {
			for (String country : adapter.countries()) {
				SmsAdapter previous = this.byCountry.put(country.toUpperCase(java.util.Locale.ROOT), adapter);
				if (previous != null) {
					throw new IllegalStateException("Two SMS adapters serve " + country + ": " + previous.id() + " and "
							+ adapter.id());
				}
			}
		}
	}

	Optional<SmsAdapter> forNumber(String phoneE164) {
		Optional<String> region = Contacts.region(phoneE164);
		if (region.isPresent() && this.byCountry.containsKey(region.get())) {
			return Optional.of(this.byCountry.get(region.get()));
		}
		return Optional.ofNullable(this.byCountry.get(ANY));
	}

}
