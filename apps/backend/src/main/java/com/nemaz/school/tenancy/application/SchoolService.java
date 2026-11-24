package com.nemaz.school.tenancy.application;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.pagination.KeysetPages;
import com.nemaz.school.common.security.AccessibleScopes;
import com.nemaz.school.reference.CountryInfo;
import com.nemaz.school.reference.ReferenceData;
import com.nemaz.school.tenancy.SchoolCreated;
import com.nemaz.school.tenancy.SchoolStatusChanged;
import com.nemaz.school.tenancy.domain.Organization;
import com.nemaz.school.tenancy.domain.OwnershipType;
import com.nemaz.school.tenancy.domain.School;
import com.nemaz.school.tenancy.domain.SchoolLanguage;
import com.nemaz.school.tenancy.domain.SchoolStatus;
import com.nemaz.school.tenancy.domain.SchoolType;
import com.nemaz.school.tenancy.infrastructure.OrganizationRepository;
import com.nemaz.school.tenancy.infrastructure.SchoolLanguageRepository;
import com.nemaz.school.tenancy.infrastructure.SchoolRepository;
import jakarta.persistence.criteria.Predicate;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class SchoolService {

	private static final String ENGLISH = "en";

	private final SchoolRepository schools;

	private final SchoolLanguageRepository schoolLanguages;

	private final OrganizationRepository organizations;

	private final ReferenceData reference;

	private final ApplicationEventPublisher events;

	private final AuditLog audit;

	SchoolService(SchoolRepository schools, SchoolLanguageRepository schoolLanguages,
			OrganizationRepository organizations, ReferenceData reference, ApplicationEventPublisher events,
			AuditLog audit) {
		this.schools = schools;
		this.schoolLanguages = schoolLanguages;
		this.organizations = organizations;
		this.reference = reference;
		this.events = events;
		this.audit = audit;
	}

	/**
	 * Creates a school in an ENABLED country, inheriting language, timezone, calendar and
	 * currency from it unless given. The school starts ONBOARDING. Its languages are the
	 * default language plus English (when the country offers it) unless {@code languageCodes}
	 * says otherwise.
	 */
	public SchoolDetails create(CreateSchool c) {
		String countryCode = c.countryCode().toUpperCase(Locale.ROOT);
		CountryInfo country = this.reference.findCountry(countryCode)
			.orElseThrow(() -> ApiException.badRequest("country.unknown"));
		if (!country.enabled()) {
			throw ApiException.conflict("country.not_enabled");
		}
		if (c.organizationId() != null) {
			Organization organization = this.organizations.findById(c.organizationId())
				.orElseThrow(() -> ApiException.badRequest("organization.unknown"));
			if (!organization.getCountryCode().equals(countryCode)) {
				throw ApiException.badRequest("organization.country_mismatch");
			}
		}

		String defaultLanguage = orDefault(c.defaultLanguageCode(), country.defaultLanguageCode());
		requireLanguage(defaultLanguage, country, "school.default_language_not_in_country");
		String currency = orDefault(c.currencyCode(), country.defaultCurrencyCode());
		if (!this.reference.currencyExists(currency)) {
			throw ApiException.badRequest("currency.unknown");
		}
		String calendar = orDefault(c.defaultCalendarCode(), country.defaultCalendarCode());
		if (!this.reference.calendarExists(calendar)) {
			throw ApiException.badRequest("calendar.unknown");
		}
		String timezone = orDefault(c.timezone(), country.defaultTimezone());
		requireTimezone(timezone);

		Set<String> languageCodes = new LinkedHashSet<>();
		languageCodes.add(defaultLanguage);
		if (c.languageCodes() == null) {
			if (country.languageCodes().contains(ENGLISH) && this.reference.languageExists(ENGLISH)) {
				languageCodes.add(ENGLISH);
			}
		}
		else {
			for (String code : c.languageCodes()) {
				requireLanguage(code, country, "school.language_not_in_country");
				languageCodes.add(code);
			}
		}

		if (this.schools.existsBySlug(c.slug())) {
			throw ApiException.conflict("school.slug_taken");
		}
		School school = School
			.create(countryCode, c.organizationId(), c.name(), c.slug(), defaultLanguage, timezone, calendar, currency)
			.profile(c.shortName(), c.schoolType(), c.ownershipType(), c.phoneE164(), c.email(), c.website(),
					c.brandColor());
		try {
			school = this.schools.saveAndFlush(school);
		}
		catch (DataIntegrityViolationException ex) {
			throw ApiException.conflict("school.slug_taken");
		}
		UUID schoolId = school.getId();
		this.schoolLanguages.saveAll(languageCodes.stream()
			.map((code) -> new SchoolLanguage(schoolId, code, code.equals(defaultLanguage)))
			.toList());
		this.events.publishEvent(new SchoolCreated(schoolId, countryCode, school.getOrganizationId(), school.getSlug()));
		this.audit.record(AuditEntry.of("school.create", AuditOutcome.SUCCESS)
			.target("school", schoolId)
			.school(schoolId)
			.metadata("countryCode", countryCode)
			.metadata("organizationId", (school.getOrganizationId() != null) ? school.getOrganizationId().toString() : null)
			.metadata("slug", school.getSlug()));
		return details(school);
	}

	@Transactional(readOnly = true)
	public SchoolDetails get(UUID id) {
		return details(find(id));
	}

	/** ONBOARDING to ACTIVE/SUSPENDED/ARCHIVED, ACTIVE and SUSPENDED between themselves; ARCHIVED is final. */
	public SchoolDetails changeStatus(UUID id, SchoolStatus target) {
		School school = find(id);
		SchoolStatus previous = school.getStatus();
		if (previous == target) {
			return details(school);
		}
		if (!previous.canChangeTo(target)) {
			throw ApiException.conflict("school.invalid_status_transition");
		}
		school.changeStatus(target);
		school = this.schools.saveAndFlush(school);
		this.events.publishEvent(new SchoolStatusChanged(id, previous.name(), target.name()));
		this.audit.record(AuditEntry.of("school.status_change", AuditOutcome.SUCCESS)
			.target("school", id)
			.school(id)
			.changes(Map.of("status", previous.name()), Map.of("status", target.name())));
		return details(school);
	}

	/** Schools newest first, restricted to {@code visible} (the caller's accessible scopes). */
	@Transactional(readOnly = true)
	public CursorPage<SchoolDetails> list(String countryCode, UUID organizationId, SchoolStatus status, String cursor,
			Integer limit, AccessibleScopes visible) {
		Specification<School> filter = (root, query, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			if (!visible.platform()) {
				List<Predicate> any = new ArrayList<>();
				if (!visible.countryCodes().isEmpty()) {
					any.add(root.get("countryCode").in(visible.countryCodes()));
				}
				if (!visible.organizationIds().isEmpty()) {
					any.add(root.get("organizationId").in(visible.organizationIds()));
				}
				if (!visible.schoolIds().isEmpty()) {
					any.add(root.get("id").in(visible.schoolIds()));
				}
				predicates.add(any.isEmpty() ? cb.disjunction() : cb.or(any.toArray(Predicate[]::new)));
			}
			if (countryCode != null) {
				predicates.add(cb.equal(root.get("countryCode"), countryCode.toUpperCase(Locale.ROOT)));
			}
			if (organizationId != null) {
				predicates.add(cb.equal(root.get("organizationId"), organizationId));
			}
			if (status != null) {
				predicates.add(cb.equal(root.get("status"), status));
			}
			return cb.and(predicates.toArray(Predicate[]::new));
		};
		CursorPage<School> page = KeysetPages.page(this.schools, filter, cursor, limit, true);
		Map<UUID, List<SchoolLanguage>> languages = this.schoolLanguages
			.findBySchoolIdInOrderBySchoolIdAscLanguageCodeAsc(page.items().stream().map(School::getId).toList())
			.stream()
			.collect(Collectors.groupingBy(SchoolLanguage::getSchoolId));
		return page.map((s) -> new SchoolDetails(s, languages.getOrDefault(s.getId(), List.of())));
	}

	private School find(UUID id) {
		return this.schools.findById(id).orElseThrow(() -> ApiException.notFound("school.not_found"));
	}

	private SchoolDetails details(School school) {
		return new SchoolDetails(school, this.schoolLanguages
			.findBySchoolIdInOrderBySchoolIdAscLanguageCodeAsc(List.of(school.getId())));
	}

	private void requireLanguage(String code, CountryInfo country, String notInCountryCode) {
		if (!this.reference.languageExists(code)) {
			throw ApiException.badRequest("language.unknown");
		}
		if (!country.languageCodes().contains(code)) {
			throw ApiException.badRequest(notInCountryCode);
		}
	}

	static void requireTimezone(String timezone) {
		try {
			ZoneId.of(timezone);
		}
		catch (DateTimeException ex) {
			throw ApiException.badRequest("timezone.invalid");
		}
	}

	private static String orDefault(String value, String fallback) {
		return (value != null && !value.isBlank()) ? value : fallback;
	}

	public record CreateSchool(String countryCode, UUID organizationId, String name, String shortName, String slug,
			SchoolType schoolType, OwnershipType ownershipType, String defaultLanguageCode, String timezone,
			String defaultCalendarCode, String currencyCode, String phoneE164, String email, String website,
			String brandColor, List<String> languageCodes) {
	}

	public record SchoolDetails(School school, List<SchoolLanguage> languages) {
	}

}
