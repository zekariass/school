package com.nemaz.school.tenancy.application;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.nemaz.school.reference.CountryInfo;
import com.nemaz.school.reference.ReferenceData;
import com.nemaz.school.tenancy.CountryContext;
import com.nemaz.school.tenancy.CountryContext.Source;
import com.nemaz.school.tenancy.CountryResolver;
import com.nemaz.school.tenancy.SettingScope;
import com.nemaz.school.tenancy.SettingsService;
import com.nemaz.school.tenancy.TenantDirectory;
import com.nemaz.school.tenancy.TenantDirectory.OrganizationRef;
import com.nemaz.school.tenancy.TenantDirectory.SchoolRef;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link CountryResolver} over the database: the school, organization and country rows and the platform setting. Nothing
 * here names a country; a missing or disabled platform default is a deployment error and fails loudly (see
 * {@link PlatformDefaultCountryCheck}, which runs {@link #platformDefault()} at startup).
 */
@Service
@Transactional(readOnly = true)
class DefaultCountryResolver implements CountryResolver {

	private final TenantDirectory directory;

	private final ReferenceData reference;

	private final SettingsService settings;

	DefaultCountryResolver(TenantDirectory directory, ReferenceData reference, SettingsService settings) {
		this.directory = directory;
		this.reference = reference;
		this.settings = settings;
	}

	@Override
	public CountryContext platformDefault() {
		String code = this.settings.getString(PLATFORM_DEFAULT_KEY, SettingScope.platform())
			.map((c) -> c.trim().toUpperCase(Locale.ROOT))
			.filter((c) -> !c.isEmpty())
			.orElseThrow(() -> new IllegalStateException("The platform default country is not configured: set the platform "
					+ "setting '" + PLATFORM_DEFAULT_KEY + "' to the code of an ENABLED country."));
		CountryInfo info = this.reference.findCountry(code)
			.orElseThrow(() -> new IllegalStateException("The platform default country '" + code + "' (setting '"
					+ PLATFORM_DEFAULT_KEY + "') does not exist in the countries table."));
		if (!info.enabled()) {
			throw new IllegalStateException("The platform default country '" + code + "' (setting '" + PLATFORM_DEFAULT_KEY
					+ "') is not ENABLED: enable the country or choose another default.");
		}
		return new CountryContext(info, Source.PLATFORM_DEFAULT, zoneOf(info.defaultTimezone(), info.code()));
	}

	@Override
	public CountryContext forCountry(String countryCode) {
		return known(countryCode, Source.EXPLICIT)
			.orElseThrow(() -> new IllegalArgumentException("Unknown country " + countryCode));
	}

	@Override
	public CountryContext forSchool(UUID schoolId) {
		return resolve(schoolId, null, null);
	}

	@Override
	public CountryContext forUser(String homeCountryCode) {
		return resolve(null, homeCountryCode, null);
	}

	@Override
	public CountryContext resolve(UUID schoolId, String userHomeCountryCode, UUID organizationId) {
		SchoolRef school = this.directory.school(schoolId).orElse(null);
		if (school != null) {
			Optional<CountryContext> fromSchool = known(school.countryCode(), Source.SCHOOL).map((context) -> {
				ZoneId zone = validZone(school.timezone());
				return (zone != null) ? new CountryContext(context.country(), Source.SCHOOL, zone) : context;
			});
			if (fromSchool.isPresent()) {
				return fromSchool.get();
			}
		}
		Optional<CountryContext> fromUser = known(userHomeCountryCode, Source.USER);
		if (fromUser.isPresent()) {
			return fromUser.get();
		}
		UUID organization = (organizationId != null) ? organizationId : (school != null) ? school.organizationId() : null;
		Optional<String> organizationCountry = this.directory.organization(organization).map(OrganizationRef::countryCode);
		if (organizationCountry.isPresent()) {
			Optional<CountryContext> fromOrganization = known(organizationCountry.get(), Source.ORGANIZATION);
			if (fromOrganization.isPresent()) {
				return fromOrganization.get();
			}
		}
		return platformDefault();
	}

	@Override
	public ZoneId schoolZone(UUID schoolId) {
		return forSchool(schoolId).zone();
	}

	@Override
	public ZoneId userZone(String userTimezone, String homeCountryCode) {
		ZoneId own = validZone(userTimezone);
		return (own != null) ? own : forUser(homeCountryCode).zone();
	}

	private Optional<CountryContext> known(String code, Source source) {
		if (code == null || code.isBlank()) {
			return Optional.empty();
		}
		return this.reference.findCountry(code.trim().toUpperCase(Locale.ROOT))
			.map((info) -> new CountryContext(info, source, zoneOf(info.defaultTimezone(), info.code())));
	}

	private static ZoneId validZone(String name) {
		if (name == null || name.isBlank()) {
			return null;
		}
		try {
			return ZoneId.of(name.trim());
		}
		catch (DateTimeException ex) {
			return null;
		}
	}

	private static ZoneId zoneOf(String name, String countryCode) {
		ZoneId zone = validZone(name);
		if (zone == null) {
			throw new IllegalStateException("Country " + countryCode + " has no valid default time zone (" + name + ").");
		}
		return zone;
	}

}
