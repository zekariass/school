package com.nemaz.school.consent.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.common.persistence.Constraints;
import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.Permissions;
import com.nemaz.school.common.security.SchoolGate;
import com.nemaz.school.consent.ConsentType;
import com.nemaz.school.reference.ReferenceData;
import com.nemaz.school.tenancy.CountryResolver;
import com.nemaz.school.tenancy.TenantDirectory;
import com.nemaz.school.tenancy.TenantDirectory.SchoolRef;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Policy documents and versions (Database_Design §4.18).
 * <ul>
 * <li><b>Effective document</b> of a type for a school: the school's own document once it has a published version in
 * effect, else the default of the school's country. Without a school: the country default.</li>
 * <li><b>Current version:</b> the latest published version label already in effect ({@code effective_from <= now},
 * newest {@code effective_from} first), read in the reader's language, else English, else any language of that
 * label.</li>
 * <li><b>School wording</b> ({@code school.policies.manage}): only ACCOUNT_PARTICIPATION and MEDIA_PUBLICATION (TERMS
 * and PRIVACY are the platform's). Versions are drafts until published; a published version never changes (write a
 * new label instead). Publishing a version with {@code requires_reconsent} asks the school's guardians again.</li>
 * </ul>
 */
@Service
@Transactional
public class PolicyService {

	private final JdbcClient jdbc;

	private final SchoolGate gate;

	private final TenantDirectory directory;

	private final ReferenceData reference;

	private final AuditLog audit;

	private final ApplicationEventPublisher events;

	private final Clock clock;

	private final CountryResolver countries;

	PolicyService(JdbcClient jdbc, SchoolGate gate, TenantDirectory directory, ReferenceData reference, AuditLog audit,
			ApplicationEventPublisher events, Clock clock, CountryResolver countries) {
		this.countries = countries;
		this.jdbc = jdbc;
		this.gate = gate;
		this.directory = directory;
		this.reference = reference;
		this.audit = audit;
		this.events = events;
		this.clock = clock;
	}

	// --- resolution -------------------------------------------------------------------------

	/** The effective document of a type for a school (or, with no school, for a country). */
	@Transactional(readOnly = true)
	public Optional<Document> effectiveDocument(ConsentType type, UUID schoolId, String countryCode) {
		Instant now = this.clock.instant();
		String country = countryCode;
		if (schoolId != null) {
			SchoolRef school = this.directory.school(schoolId).orElse(null);
			if (school == null) {
				return Optional.empty();
			}
			Optional<Document> own = this.jdbc.sql(DOC + " " + """
					where d.policy_type = :t and d.school_id = :s and exists (select 1 from policy_versions v
					   where v.policy_document_id = d.id and v.published_at is not null and v.effective_from <= :now)""")
				.param("t", type.name())
				.param("s", schoolId)
				.param("now", Timestamp.from(now))
				.query(PolicyService::document)
				.optional();
			if (own.isPresent()) {
				return own;
			}
			country = school.countryCode();
		}
		if (country == null) {
			return Optional.empty();
		}
		return this.jdbc.sql(DOC + " where d.policy_type = :t and d.country_code = :c and d.school_id is null")
			.param("t", type.name())
			.param("c", country.toUpperCase(Locale.ROOT))
			.query(PolicyService::document)
			.optional();
	}

	/** The current version of a document in the reader's language (fallbacks: English, then any). */
	@Transactional(readOnly = true)
	public Optional<Version> currentVersion(UUID documentId, String languageCode) {
		return this.jdbc.sql(VERSION + " " + """
				where v.policy_document_id = :d and v.published_at is not null and v.effective_from <= :now
				   and v.version_label = (select v2.version_label from policy_versions v2 where v2.policy_document_id = :d
				                            and v2.published_at is not null and v2.effective_from <= :now
				                          order by v2.effective_from desc, v2.published_at desc, v2.created_at desc limit 1)
				 order by case when v.language_code = :lang then 0 when v.language_code = 'en' then 1 else 2 end,
				          v.language_code limit 1""")
			.param("d", documentId)
			.param("now", Timestamp.from(this.clock.instant()))
			.param("lang", (languageCode != null) ? languageCode : "en")
			.query(PolicyService::version)
			.optional();
	}

	/**
	 * The {@code effective_from} of the newest published version in effect that requires new consent, or empty:
	 * a GRANTED decision on a version that took effect before it no longer counts.
	 */
	@Transactional(readOnly = true)
	public Optional<Instant> reconsentSince(UUID documentId) {
		return this.jdbc.sql("""
				select effective_from from policy_versions where policy_document_id = :d and requires_reconsent
				  and published_at is not null and effective_from <= :now
				order by effective_from desc limit 1""")
			.param("d", documentId)
			.param("now", Timestamp.from(this.clock.instant()))
			.query((rs, i) -> Rows.instant(rs, "effective_from"))
			.optional();
	}

	@Transactional(readOnly = true)
	public Optional<Version> version(UUID versionId) {
		return this.jdbc.sql(VERSION + " where v.id = :id").param("id", versionId).query(PolicyService::version).optional();
	}

	/**
	 * The public read: the current text of a type for a school (its own document, else its country's) or for a country (the
	 * one asked for, else the platform default country). Legal text is never served from another country: when the
	 * resolved country has no document of the type, 404 {@code policy.country_not_available}.
	 */
	@Transactional(readOnly = true)
	public PolicyView read(ConsentType type, UUID schoolId, String country, String languageCode) {
		String resolved = (schoolId != null) ? null
				: (country != null) ? country : this.countries.platformDefault().code();
		Document document = effectiveDocument(type, schoolId, resolved)
			.orElseThrow(() -> (schoolId != null && this.directory.school(schoolId).isEmpty())
					? ApiException.notFound("policy.not_found") : ApiException.notFound("policy.country_not_available"));
		Version version = currentVersion(document.id(), languageCode)
			.orElseThrow(() -> ApiException.notFound("policy.not_found"));
		return view(document, version);
	}

	static PolicyView view(Document d, Version v) {
		return new PolicyView(d.id(), d.policyType(), d.title(), d.schoolId(), d.countryCode(), v.id(), v.versionLabel(),
				v.languageCode(), v.content(), v.effectiveFrom(), v.requiresReconsent(), v.publishedAt());
	}

	// --- school wording ---------------------------------------------------------------------

	@Transactional(readOnly = true)
	public List<SchoolPolicy> list(CurrentUser user, UUID schoolId) {
		this.gate.require(user, schoolId, Permissions.SCHOOL_POLICIES_MANAGE);
		List<SchoolPolicy> result = new ArrayList<>();
		for (Document d : this.jdbc.sql(DOC + " where d.school_id = :s order by d.policy_type")
			.param("s", schoolId)
			.query(PolicyService::document)
			.list()) {
			result.add(new SchoolPolicy(d, this.jdbc.sql(VERSION + " where v.policy_document_id = :d"
					+ " order by v.effective_from desc, v.version_label, v.language_code")
				.param("d", d.id())
				.query(PolicyService::version)
				.list()));
		}
		return result;
	}

	public Document createDocument(CurrentUser user, UUID schoolId, ConsentType type, String title) {
		this.gate.require(user, schoolId, Permissions.SCHOOL_POLICIES_MANAGE);
		if (!type.forChildren()) {
			throw ApiException.badRequest("policy.type_not_allowed");
		}
		UUID id = Ids.newId();
		try {
			this.jdbc.sql("insert into policy_documents (id, policy_type, school_id, title) values (:id, :t, :s, :title)")
				.param("id", id)
				.param("t", type.name())
				.param("s", schoolId)
				.param("title", title.trim())
				.update();
		}
		catch (DataIntegrityViolationException ex) {
			if (Constraints.violated(ex, "ux_policy_documents_school")) {
				throw ApiException.conflict("policy.already_exists");
			}
			throw ex;
		}
		this.audit.record(AuditEntry.of("policy.document.create", AuditOutcome.SUCCESS)
			.target("policy_document", id)
			.school(schoolId)
			.metadata("policyType", type.name()));
		return document(schoolId, id);
	}

	public Document updateDocument(CurrentUser user, UUID schoolId, UUID documentId, String title) {
		this.gate.require(user, schoolId, Permissions.SCHOOL_POLICIES_MANAGE);
		document(schoolId, documentId);
		this.jdbc.sql("update policy_documents set title = :title, updated_at = now(), version = version + 1 where id = :id")
			.param("title", title.trim())
			.param("id", documentId)
			.update();
		this.audit.record(AuditEntry.of("policy.document.update", AuditOutcome.SUCCESS)
			.target("policy_document", documentId)
			.school(schoolId));
		return document(schoolId, documentId);
	}

	public Version addVersion(CurrentUser user, UUID schoolId, UUID documentId, VersionDraft r) {
		this.gate.require(user, schoolId, Permissions.SCHOOL_POLICIES_MANAGE);
		document(schoolId, documentId);
		if (!this.reference.languageExists(r.languageCode())) {
			throw ApiException.badRequest("language.unknown");
		}
		UUID id = Ids.newId();
		Instant now = this.clock.instant();
		try {
			this.jdbc.sql("""
					insert into policy_versions (id, policy_document_id, version_label, language_code, content, effective_from,
					                             requires_reconsent, created_at)
					values (:id, :d, :label, :lang, :content, :from, :reconsent, :now)""")
				.param("id", id)
				.param("d", documentId)
				.param("label", r.versionLabel().trim())
				.param("lang", r.languageCode())
				.param("content", r.content())
				.param("from", Timestamp.from((r.effectiveFrom() != null) ? r.effectiveFrom() : now))
				.param("reconsent", Boolean.TRUE.equals(r.requiresReconsent()))
				.param("now", Timestamp.from(now))
				.update();
		}
		catch (DataIntegrityViolationException ex) {
			if (Constraints.violated(ex, "ux_policy_versions_label")) {
				throw ApiException.conflict("policy.version_exists");
			}
			throw ex;
		}
		this.audit.record(AuditEntry.of("policy.version.create", AuditOutcome.SUCCESS)
			.target("policy_version", id)
			.school(schoolId)
			.metadata("documentId", documentId.toString())
			.metadata("versionLabel", r.versionLabel().trim())
			.metadata("languageCode", r.languageCode()));
		return version(id).orElseThrow();
	}

	public Version updateVersion(CurrentUser user, UUID schoolId, UUID documentId, UUID versionId, VersionChange r) {
		this.gate.require(user, schoolId, Permissions.SCHOOL_POLICIES_MANAGE);
		Version v = draft(schoolId, documentId, versionId);
		this.jdbc.sql("""
				update policy_versions set content = :content, effective_from = :from, requires_reconsent = :reconsent
				where id = :id and published_at is null""")
			.param("content", (r.content() != null) ? r.content() : v.content())
			.param("from", Timestamp.from((r.effectiveFrom() != null) ? r.effectiveFrom() : v.effectiveFrom()))
			.param("reconsent", (r.requiresReconsent() != null) ? r.requiresReconsent() : v.requiresReconsent())
			.param("id", versionId)
			.update();
		this.audit.record(AuditEntry.of("policy.version.update", AuditOutcome.SUCCESS)
			.target("policy_version", versionId)
			.school(schoolId));
		return version(versionId).orElseThrow();
	}

	/** Publishes a draft: it becomes immutable and takes effect at {@code effective_from} (never before now). */
	public Version publish(CurrentUser user, UUID schoolId, UUID documentId, UUID versionId) {
		this.gate.require(user, schoolId, Permissions.SCHOOL_POLICIES_MANAGE);
		Version v = draft(schoolId, documentId, versionId);
		Instant now = this.clock.instant();
		Instant effective = v.effectiveFrom().isBefore(now) ? now : v.effectiveFrom();
		this.jdbc.sql("update policy_versions set published_at = :now, effective_from = :from where id = :id")
			.param("now", Timestamp.from(now))
			.param("from", Timestamp.from(effective))
			.param("id", versionId)
			.update();
		Document d = document(schoolId, documentId);
		this.events.publishEvent(new PolicyVersionPublished(versionId, documentId, schoolId, d.policyType(),
				v.requiresReconsent()));
		this.audit.record(AuditEntry.of("policy.version.publish", AuditOutcome.SUCCESS)
			.target("policy_version", versionId)
			.school(schoolId)
			.metadata("documentId", documentId.toString())
			.metadata("requiresReconsent", v.requiresReconsent()));
		return version(versionId).orElseThrow();
	}

	private Document document(UUID schoolId, UUID documentId) {
		return this.jdbc.sql(DOC + " where d.id = :id and d.school_id = :s")
			.param("id", documentId)
			.param("s", schoolId)
			.query(PolicyService::document)
			.optional()
			.orElseThrow(() -> ApiException.notFound("policy.not_found"));
	}

	private Version draft(UUID schoolId, UUID documentId, UUID versionId) {
		document(schoolId, documentId);
		Version v = version(versionId).filter((x) -> x.documentId().equals(documentId))
			.orElseThrow(() -> ApiException.notFound("policy.not_found"));
		if (v.publishedAt() != null) {
			throw ApiException.conflict("policy.version_published");
		}
		return v;
	}

	private static final String DOC = """
			select d.id, d.policy_type, d.title, d.school_id, d.country_code from policy_documents d""";

	private static final String VERSION = """
			select v.id, v.policy_document_id, v.version_label, v.language_code, v.content, v.effective_from,
			       v.requires_reconsent, v.published_at
			from policy_versions v""";

	private static Document document(ResultSet rs, int i) throws SQLException {
		return new Document(Rows.uuid(rs, "id"), ConsentType.valueOf(rs.getString("policy_type")), rs.getString("title"),
				Rows.uuid(rs, "school_id"), rs.getString("country_code"));
	}

	private static Version version(ResultSet rs, int i) throws SQLException {
		return new Version(Rows.uuid(rs, "id"), Rows.uuid(rs, "policy_document_id"), rs.getString("version_label"),
				rs.getString("language_code"), rs.getString("content"), Rows.instant(rs, "effective_from"),
				rs.getBoolean("requires_reconsent"), Rows.instant(rs, "published_at"));
	}

	public record Document(@NotNull UUID id, @NotNull ConsentType policyType, @NotNull String title, UUID schoolId,
			String countryCode) {
	}

	public record Version(@NotNull UUID id, @NotNull UUID documentId, @NotNull String versionLabel,
			@NotNull String languageCode, @NotNull String content, @NotNull Instant effectiveFrom,
			@NotNull boolean requiresReconsent, @Schema(description = "Null while a draft") Instant publishedAt) {
	}

	public record SchoolPolicy(@NotNull Document document,
			@NotNull @Schema(description = "Drafts and published versions, newest first") List<Version> versions) {
	}

	public record VersionDraft(String versionLabel, String languageCode, String content, Instant effectiveFrom,
			Boolean requiresReconsent) {
	}

	public record VersionChange(String content, Instant effectiveFrom, Boolean requiresReconsent) {
	}

	/** The text a reader sees: the effective document's current version in their language (or the fallback). */
	public record PolicyView(@NotNull UUID documentId, @NotNull ConsentType policyType, @NotNull String title,
			UUID schoolId, String countryCode, @NotNull UUID versionId, @NotNull String versionLabel,
			@NotNull String languageCode, @NotNull String content, @NotNull Instant effectiveFrom,
			@NotNull boolean requiresReconsent, @NotNull Instant publishedAt) {
	}

}
