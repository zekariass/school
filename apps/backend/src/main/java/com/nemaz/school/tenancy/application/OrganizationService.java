package com.nemaz.school.tenancy.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.pagination.KeysetPages;
import com.nemaz.school.common.security.AccessibleScopes;
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

	private final AuditLog audit;

	OrganizationService(OrganizationRepository organizations, ReferenceData reference, AuditLog audit) {
		this.organizations = organizations;
		this.reference = reference;
		this.audit = audit;
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
		Organization saved;
		try {
			saved = this.organizations.saveAndFlush(
					new Organization(countryCode, command.name(), command.legalName(), command.slug()));
		}
		catch (DataIntegrityViolationException ex) {
			throw ApiException.conflict("organization.slug_taken");
		}
		this.audit.record(AuditEntry.of("organization.create", AuditOutcome.SUCCESS)
			.target("organization", saved.getId())
			.metadata("countryCode", countryCode)
			.metadata("slug", saved.getSlug()));
		return saved;
	}

	@Transactional(readOnly = true)
	public Organization get(UUID id) {
		return this.organizations.findById(id).orElseThrow(() -> ApiException.notFound("organization.not_found"));
	}

	/** Name, legal name and status are editable; the slug is a stable identifier and is not. */
	public Organization update(UUID id, String name, String legalName, OrganizationStatus status) {
		Organization organization = get(id);
		java.util.Map<String, Object> before = snapshot(organization);
		organization.update(name, legalName, status);
		Organization saved = this.organizations.saveAndFlush(organization);
		this.audit.record(AuditEntry.of("organization.update", AuditOutcome.SUCCESS)
			.target("organization", id)
			.changes(before, snapshot(saved)));
		return saved;
	}

	/** Organizations newest first, restricted to {@code visible} (the caller's accessible scopes). */
	@Transactional(readOnly = true)
	public CursorPage<Organization> list(String countryCode, OrganizationStatus status, String cursor,
			Integer limit, AccessibleScopes visible) {
		Specification<Organization> filter = (root, query, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			if (!visible.platform()) {
				List<Predicate> any = new ArrayList<>();
				if (!visible.countryCodes().isEmpty()) {
					any.add(root.get("countryCode").in(visible.countryCodes()));
				}
				if (!visible.organizationIds().isEmpty()) {
					any.add(root.get("id").in(visible.organizationIds()));
				}
				predicates.add(any.isEmpty() ? cb.disjunction() : cb.or(any.toArray(Predicate[]::new)));
			}
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

	private static java.util.Map<String, Object> snapshot(Organization o) {
		java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
		values.put("name", o.getName());
		values.put("legalName", o.getLegalName());
		values.put("status", o.getStatus().name());
		return values;
	}

	public record CreateOrganization(String countryCode, String name, String legalName, String slug) {
	}

}
