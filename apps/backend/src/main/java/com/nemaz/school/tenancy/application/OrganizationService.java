package com.nemaz.school.tenancy.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.pagination.KeysetPages;
import com.nemaz.school.reference.CountryInfo;
import com.nemaz.school.reference.ReferenceData;
import com.nemaz.school.tenancy.domain.Organization;
import com.nemaz.school.tenancy.domain.OrganizationStatus;
import com.nemaz.school.tenancy.infrastructure.OrganizationRepository;
import jakarta.persistence.criteria.Predicate;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class OrganizationService {

	private final OrganizationRepository organizations;

	private final ReferenceData reference;

	OrganizationService(OrganizationRepository organizations, ReferenceData reference) {
		this.organizations = organizations;
		this.reference = reference;
	}

	public Organization create(CreateOrganization command) {
		String countryCode = command.countryCode().toUpperCase(Locale.ROOT);
		CountryInfo country = this.reference.findCountry(countryCode)
			.orElseThrow(() -> ApiException.badRequest("country.unknown"));
		if (!country.enabled()) {
			throw ApiException.conflict("country.not_enabled");
		}
		if (this.organizations.existsBySlug(command.slug())) {
			throw ApiException.conflict("organization.slug_taken");
		}
		try {
			return this.organizations.saveAndFlush(
					new Organization(countryCode, command.name(), command.legalName(), command.slug()));
		}
		catch (DataIntegrityViolationException ex) {
			throw ApiException.conflict("organization.slug_taken");
		}
	}

	@Transactional(readOnly = true)
	public Organization get(UUID id) {
		return this.organizations.findById(id).orElseThrow(() -> ApiException.notFound("organization.not_found"));
	}

	/** Name, legal name and status are editable; the slug is a stable identifier and is not. */
	public Organization update(UUID id, String name, String legalName, OrganizationStatus status) {
		Organization organization = get(id);
		organization.update(name, legalName, status);
		return this.organizations.saveAndFlush(organization);
	}

	@Transactional(readOnly = true)
	public CursorPage<Organization> list(String countryCode, OrganizationStatus status, String cursor,
			Integer limit) {
		Specification<Organization> filter = (root, query, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			if (countryCode != null) {
				predicates.add(cb.equal(root.get("countryCode"), countryCode.toUpperCase(Locale.ROOT)));
			}
			if (status != null) {
				predicates.add(cb.equal(root.get("status"), status));
			}
			return cb.and(predicates.toArray(Predicate[]::new));
		};
		return KeysetPages.page(this.organizations, filter, cursor, limit, true);
	}

	public record CreateOrganization(String countryCode, String name, String legalName, String slug) {
	}

}
