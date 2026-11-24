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
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.media.MediaContext;
import com.nemaz.school.media.MediaPurpose;
import com.nemaz.school.media.MediaService;
import com.nemaz.school.media.MediaSummary;
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

	private final MediaService media;

	OrganizationService(OrganizationRepository organizations, ReferenceData reference, AuditLog audit,
			MediaService media) {
		this.organizations = organizations;
		this.reference = reference;
		this.audit = audit;
		this.media = media;
	}

	/** The READY logos of these organizations, by organization id. */
	@Transactional(readOnly = true)
	public java.util.Map<UUID, MediaSummary> logos(java.util.Collection<Organization> organizations) {
		java.util.Map<UUID, UUID> mediaIds = new java.util.HashMap<>();
		organizations.stream().filter((o) -> o.getLogoMediaId() != null).forEach((o) -> mediaIds.put(o.getId(), o.getLogoMediaId()));
		java.util.Map<UUID, MediaSummary> summaries = this.media.summaries(mediaIds.values());
		java.util.Map<UUID, MediaSummary> logos = new java.util.HashMap<>();
		mediaIds.forEach((organizationId, mediaId) -> {
			MediaSummary summary = summaries.get(mediaId);
			if (summary != null) {
				logos.put(organizationId, summary);
			}
		});
		return logos;
	}

	/** Makes a READY BRANDING upload for this organization its logo; the previous file is deleted. */
	public Organization setLogo(UUID id, UUID mediaId, CurrentUser user) {
		Organization organization = get(id);
		this.media.requireAttachable(mediaId, MediaPurpose.BRANDING, MediaContext.organization(id), user);
		UUID previous = organization.getLogoMediaId();
		if (!mediaId.equals(previous)) {
			organization.setLogoMediaId(mediaId);
			this.organizations.saveAndFlush(organization);
			if (previous != null) {
				this.media.discard(previous);
			}
			this.audit.record(AuditEntry.of("organization.logo.set", AuditOutcome.SUCCESS)
				.target("organization", id)
				.metadata("mediaId", mediaId.toString()));
		}
		return organization;
	}

	/** Removes the logo and deletes its file. */
	public Organization clearLogo(UUID id) {
		Organization organization = get(id);
		UUID previous = organization.getLogoMediaId();
		if (previous != null) {
			organization.setLogoMediaId(null);
			this.organizations.saveAndFlush(organization);
			this.media.discard(previous);
			this.audit.record(AuditEntry.of("organization.logo.clear", AuditOutcome.SUCCESS)
				.target("organization", id)
				.metadata("mediaId", previous.toString()));
		}
		return organization;
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
