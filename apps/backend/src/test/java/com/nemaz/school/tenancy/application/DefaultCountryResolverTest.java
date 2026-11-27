package com.nemaz.school.tenancy.application;

import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.reference.CountryInfo;
import com.nemaz.school.reference.ReferenceData;
import com.nemaz.school.tenancy.CountryContext;
import com.nemaz.school.tenancy.CountryContext.Source;
import com.nemaz.school.tenancy.CountryResolver;
import com.nemaz.school.tenancy.SettingChangeGuard;
import com.nemaz.school.tenancy.SettingScope;
import com.nemaz.school.tenancy.SettingsService;
import com.nemaz.school.tenancy.TenantDirectory;
import com.nemaz.school.tenancy.TenantDirectory.OrganizationRef;
import com.nemaz.school.tenancy.TenantDirectory.SchoolRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The resolution order school, user, organization, platform default, over invented countries (AA, BB, CC, DD): nothing
 * here (or in the resolver) names a real country, and the platform default must be an ENABLED country.
 */
class DefaultCountryResolverTest {

	private final TenantDirectory directory = mock(TenantDirectory.class);

	private final ReferenceData reference = mock(ReferenceData.class);

	private final SettingsService settings = mock(SettingsService.class);

	private final JsonMapper json = JsonMapper.builder().build();

	private DefaultCountryResolver resolver;

	private static CountryInfo country(String code, boolean enabled, String zone, String calling) {
		return new CountryInfo(code, enabled, "CUR", "xx", zone, "GREGORIAN", Set.of("xx"), Set.of("GREGORIAN"), calling,
				Map.of("en", "Country " + code, "am", "Land " + code));
	}

	@BeforeEach
	void countries() {
		this.resolver = new DefaultCountryResolver(this.directory, this.reference, this.settings);
		when(this.reference.findCountry("AA")).thenReturn(Optional.of(country("AA", true, "Europe/Paris", "+11")));
		when(this.reference.findCountry("BB")).thenReturn(Optional.of(country("BB", true, "Asia/Tokyo", "+22")));
		when(this.reference.findCountry("CC")).thenReturn(Optional.of(country("CC", true, "America/Lima", "+33")));
		when(this.reference.findCountry("DD")).thenReturn(Optional.of(country("DD", false, "Africa/Cairo", "+44")));
		when(this.settings.getString(CountryResolver.PLATFORM_DEFAULT_KEY, SettingScope.platform()))
			.thenReturn(Optional.of("AA"));
		when(this.directory.school(null)).thenReturn(Optional.empty());
		when(this.directory.organization(null)).thenReturn(Optional.empty());
	}

	private SchoolRef school(UUID id, String country, UUID organization, String timezone) {
		SchoolRef ref = new SchoolRef(id, country, organization, "School", "school", "ACTIVE", "xx", timezone);
		when(this.directory.school(id)).thenReturn(Optional.of(ref));
		return ref;
	}

	@Test
	void theSchoolsCountryComesFirstAndItsZoneIsTheSchoolsOwn() {
		UUID school = UUID.randomUUID();
		UUID organization = UUID.randomUUID();
		school(school, "BB", organization, "Asia/Seoul");
		when(this.directory.organization(organization)).thenReturn(Optional.of(new OrganizationRef(organization, "CC", "Org")));

		CountryContext context = this.resolver.resolve(school, "CC", organization);

		assertThat(context.code()).isEqualTo("BB");
		assertThat(context.source()).isEqualTo(Source.SCHOOL);
		assertThat(context.zone()).isEqualTo(ZoneId.of("Asia/Seoul"));
		assertThat(context.phoneCountryCode()).isEqualTo("+22");
		assertThat(this.resolver.schoolZone(school)).isEqualTo(ZoneId.of("Asia/Seoul"));
	}

	@Test
	void aSchoolWithAnInvalidZoneUsesItsCountrysDefaultZone() {
		UUID school = UUID.randomUUID();
		school(school, "BB", null, "Not/AZone");
		assertThat(this.resolver.forSchool(school).zone()).isEqualTo(ZoneId.of("Asia/Tokyo"));
	}

	@Test
	void theUsersHomeCountryComesBeforeTheOrganizationAndThePlatformDefault() {
		UUID organization = UUID.randomUUID();
		when(this.directory.organization(organization)).thenReturn(Optional.of(new OrganizationRef(organization, "CC", "Org")));

		CountryContext context = this.resolver.resolve(null, "bb", organization);

		assertThat(context.code()).isEqualTo("BB");
		assertThat(context.source()).isEqualTo(Source.USER);
		assertThat(context.zone()).isEqualTo(ZoneId.of("Asia/Tokyo"));
	}

	@Test
	void theOrganizationsCountryComesBeforeThePlatformDefault() {
		UUID organization = UUID.randomUUID();
		when(this.directory.organization(organization)).thenReturn(Optional.of(new OrganizationRef(organization, "CC", "Org")));

		CountryContext context = this.resolver.resolve(null, null, organization);

		assertThat(context.code()).isEqualTo("CC");
		assertThat(context.source()).isEqualTo(Source.ORGANIZATION);
	}

	@Test
	void aSchoolsOrganizationIsUsedWhenTheSchoolsOwnCountryIsUnknown() {
		UUID school = UUID.randomUUID();
		UUID organization = UUID.randomUUID();
		school(school, "ZZ", organization, "Europe/Paris");
		when(this.reference.findCountry("ZZ")).thenReturn(Optional.empty());
		when(this.directory.organization(organization)).thenReturn(Optional.of(new OrganizationRef(organization, "CC", "Org")));

		assertThat(this.resolver.forSchool(school).code()).isEqualTo("CC");
	}

	@Test
	void withNothingKnownThePlatformDefaultAppliesAndItsSettingNamesTheCountry() {
		CountryContext context = this.resolver.resolve(null, null, null);
		assertThat(context.code()).isEqualTo("AA");
		assertThat(context.source()).isEqualTo(Source.PLATFORM_DEFAULT);
		assertThat(this.resolver.forUser("  ").code()).isEqualTo("AA");
		assertThat(this.resolver.forUser("QQ").code()).as("an unknown home country is skipped").isEqualTo("AA");

		when(this.settings.getString(CountryResolver.PLATFORM_DEFAULT_KEY, SettingScope.platform()))
			.thenReturn(Optional.of("CC"));
		assertThat(this.resolver.platformDefault().code()).isEqualTo("CC");
	}

	@Test
	void theUsersOwnZoneWinsElseTheResolvedCountrysZone() {
		assertThat(this.resolver.userZone("Pacific/Auckland", "BB")).isEqualTo(ZoneId.of("Pacific/Auckland"));
		assertThat(this.resolver.userZone(null, "BB")).isEqualTo(ZoneId.of("Asia/Tokyo"));
		assertThat(this.resolver.userZone("garbage", null)).isEqualTo(ZoneId.of("Europe/Paris"));
	}

	@Test
	void aMissingPlatformDefaultFailsWithAMessageThatNamesTheSetting() {
		when(this.settings.getString(CountryResolver.PLATFORM_DEFAULT_KEY, SettingScope.platform())).thenReturn(Optional.empty());
		assertThatThrownBy(() -> this.resolver.platformDefault()).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("platform.default_country")
			.hasMessageContaining("not configured");
		assertThatThrownBy(() -> this.resolver.forUser(null)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void aPlatformDefaultThatIsNotAnEnabledCountryFailsLoudly() {
		when(this.settings.getString(CountryResolver.PLATFORM_DEFAULT_KEY, SettingScope.platform()))
			.thenReturn(Optional.of("DD"));
		assertThatThrownBy(() -> this.resolver.platformDefault()).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("'DD'")
			.hasMessageContaining("not ENABLED");

		when(this.settings.getString(CountryResolver.PLATFORM_DEFAULT_KEY, SettingScope.platform()))
			.thenReturn(Optional.of("QQ"));
		assertThatThrownBy(() -> this.resolver.platformDefault()).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("'QQ'")
			.hasMessageContaining("does not exist");
	}

	@Test
	void startupFailsFastWhenThereIsNoValidPlatformDefault() {
		CountryResolver broken = mock(CountryResolver.class);
		when(broken.platformDefault()).thenThrow(new IllegalStateException("The platform default country is not configured"));
		assertThatThrownBy(() -> new PlatformDefaultCountryCheck(broken).run(null)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("platform default country");

		// and it passes, logging the country, when the default is fine
		new PlatformDefaultCountryCheck(this.resolver).run(null);
	}

	@Test
	void theSettingsGuardRefusesADefaultThatIsNotAnEnabledCountry() {
		PlatformDefaultCountryGuard guard = new PlatformDefaultCountryGuard(this.reference);
		UUID actor = UUID.randomUUID();
		SettingScope platform = SettingScope.platform();
		String key = CountryResolver.PLATFORM_DEFAULT_KEY;

		guard.check(new SettingChangeGuard.Change(key, platform, this.json.valueToTree("AA"), this.json.valueToTree("BB"), actor));
		assertThatThrownBy(() -> guard.check(new SettingChangeGuard.Change(key, platform, this.json.valueToTree("AA"),
				this.json.valueToTree("DD"), actor)))
			.isInstanceOfSatisfying(ApiException.class, (ex) -> assertThat(ex.code()).isEqualTo("country.not_enabled"));
		assertThatThrownBy(() -> guard.check(new SettingChangeGuard.Change(key, platform, this.json.valueToTree("AA"),
				this.json.valueToTree("QQ"), actor)))
			.isInstanceOfSatisfying(ApiException.class, (ex) -> assertThat(ex.code()).isEqualTo("country.unknown"));
		// other settings are none of its business
		guard.check(new SettingChangeGuard.Change("engagement.badges.enabled", platform, this.json.valueToTree(true),
				this.json.valueToTree(false), actor));
	}

}
