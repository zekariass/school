package com.nemaz.school.consent.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.nemaz.school.academic.ClassAccess;
import com.nemaz.school.academic.ClassAccess.ClassRef;
import com.nemaz.school.academic.ClassMemberships;
import com.nemaz.school.academic.SchoolPeople;
import com.nemaz.school.academic.SchoolPeople.PersonCard;
import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.Permissions;
import com.nemaz.school.common.security.SchoolGate;
import com.nemaz.school.common.web.ClientRequests;
import com.nemaz.school.consent.ConsentChanged;
import com.nemaz.school.consent.ConsentStatus;
import com.nemaz.school.consent.ConsentType;
import com.nemaz.school.consent.application.DefaultConsentService.DecisionRow;
import com.nemaz.school.consent.application.PolicyService.Document;
import com.nemaz.school.consent.application.PolicyService.Version;
import com.nemaz.school.guardians.GuardianAccess;
import com.nemaz.school.guardians.GuardianAccess.GuardianGrant;
import com.nemaz.school.guardians.GuardianPermission;
import com.nemaz.school.identity.UserDirectory;
import com.nemaz.school.tenancy.CountryResolver;
import com.nemaz.school.tenancy.TenantDirectory;
import com.nemaz.school.tenancy.TenantDirectory.SchoolRef;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recording consent decisions (append-only: no update or delete path exists) and the views around them.
 * <ul>
 * <li><b>Guardian, in the app:</b> for a child of an ACTIVE, not custody-restricted link with {@code can_give_consent}
 * (anyone else: 404 {@code consent.child_not_found}), ACCOUNT_PARTICIPATION or MEDIA_PUBLICATION, on the current version
 * of the effective policy of the child's school (else 409 {@code consent.version_outdated}).</li>
 * <li><b>Adult, for themself:</b> TERMS or PRIVACY on the current version of the country's policy.</li>
 * <li><b>School, on paper or verbally</b> ({@code school.guardians.manage}): for a current consent-giving link of the
 * child, or a named guardian without an account; the staff member is recorded.</li>
 * <li><b>Consent summary of a class</b> (teachers of the class, class managers, {@code school.guardians.manage}): per
 * student the effective ACCOUNT_PARTICIPATION and MEDIA_PUBLICATION status, never who decided.</li>
 * </ul>
 * Every record publishes {@link ConsentChanged} and is audited without names or IP addresses.
 */
@Service
@Transactional
public class ConsentRecordService {

	private final JdbcClient jdbc;

	private final PolicyService policies;

	private final DefaultConsentService consents;

	private final GuardianAccess guardians;

	private final ClassAccess classAccess;

	private final ClassMemberships memberships;

	private final SchoolPeople people;

	private final SchoolGate gate;

	private final UserDirectory users;

	private final TenantDirectory directory;

	private final AuditLog audit;

	private final ApplicationEventPublisher events;

	private final Clock clock;

	private final CountryResolver countries;

	ConsentRecordService(JdbcClient jdbc, PolicyService policies, DefaultConsentService consents,
			GuardianAccess guardians, ClassAccess classAccess, ClassMemberships memberships, SchoolPeople people,
			SchoolGate gate, UserDirectory users, TenantDirectory directory, AuditLog audit,
			ApplicationEventPublisher events, Clock clock, CountryResolver countries) {
		this.countries = countries;
		this.jdbc = jdbc;
		this.policies = policies;
		this.consents = consents;
		this.guardians = guardians;
		this.classAccess = classAccess;
		this.memberships = memberships;
		this.people = people;
		this.gate = gate;
		this.users = users;
		this.directory = directory;
		this.audit = audit;
		this.events = events;
		this.clock = clock;
	}

	// --- the signed-in adult ----------------------------------------------------------------

	/** What I decided and what is waiting: per child (consent-giving links) and school, and my own TERMS/PRIVACY. */
	@Transactional(readOnly = true)
	public MyConsents mine(CurrentUser user) {
		String language = language(user);
		List<ChildConsents> children = new ArrayList<>();
		List<GuardianGrant> links = this.guardians.childrenOf(user.userId())
			.stream()
			.filter((g) -> g.allows(GuardianPermission.GIVE_CONSENT))
			.toList();
		Map<UUID, PersonCard> cards = this.people.cards(links.stream().map(GuardianGrant::studentId).toList());
		UUID firstSchool = null;
		for (GuardianGrant link : links) {
			PersonCard card = cards.get(link.studentId());
			for (UUID schoolId : activeSchools(link.studentId())) {
				SchoolRef school = this.directory.school(schoolId).orElse(null);
				if (card == null || school == null) {
					continue;
				}
				firstSchool = (firstSchool != null) ? firstSchool : schoolId;
				List<ConsentItem> items = new ArrayList<>();
				for (ConsentType type : List.of(ConsentType.ACCOUNT_PARTICIPATION, ConsentType.MEDIA_PUBLICATION)) {
					Optional<Document> document = this.policies.effectiveDocument(type, schoolId, null);
					Optional<Version> current = document.flatMap((d) -> this.policies.currentVersion(d.id(), language));
					Instant since = document.flatMap((d) -> this.policies.reconsentSince(d.id())).orElse(null);
					Optional<DecisionRow> mine = this.consents.latestForLink(link.linkId(), link.studentId(), schoolId, type);
					items.add(item(type, document.orElse(null), current.orElse(null), mine.orElse(null), since,
							this.consents.status(link.studentId(), schoolId, type)));
				}
				children.add(new ChildConsents(link.studentId(), link.linkId(), card.displayName(), schoolId,
						school.name(), items));
			}
		}
		String country = accountCountry(user.userId(), firstSchool);
		List<ConsentItem> account = new ArrayList<>();
		for (ConsentType type : List.of(ConsentType.TERMS, ConsentType.PRIVACY)) {
			Optional<Document> document = this.policies.effectiveDocument(type, null, country);
			Optional<Version> current = document.flatMap((d) -> this.policies.currentVersion(d.id(), language));
			Instant since = document.flatMap((d) -> this.policies.reconsentSince(d.id())).orElse(null);
			account.add(item(type, document.orElse(null), current.orElse(null),
					this.consents.latestOwn(user.userId(), type).orElse(null), since, null));
		}
		return new MyConsents(children, account);
	}

	public RecordedConsent decide(CurrentUser user, MyDecision r) {
		if (r.studentId() == null) {
			return decideForMyself(user, r);
		}
		if (!r.consentType().forChildren()) {
			throw ApiException.badRequest("consent.type_not_allowed");
		}
		GuardianGrant link = this.guardians.childrenOf(user.userId())
			.stream()
			.filter((g) -> g.studentId().equals(r.studentId()) && g.allows(GuardianPermission.GIVE_CONSENT))
			.findFirst()
			.orElseThrow(() -> ApiException.notFound("consent.child_not_found"));
		List<UUID> schools = activeSchools(r.studentId());
		UUID schoolId = r.schoolId();
		if (schoolId == null) {
			if (schools.size() != 1) {
				throw (schools.isEmpty()) ? ApiException.notFound("consent.child_not_found")
						: ApiException.badRequest("consent.school_required");
			}
			schoolId = schools.getFirst();
		}
		else if (!schools.contains(schoolId)) {
			throw ApiException.notFound("consent.child_not_found");
		}
		Version version = currentOrConflict(r.consentType(), schoolId, null, r.policyVersionId());
		UUID id = insert(version, r.consentType(), r.studentId(), user.userId(), null, link.linkId(), schoolId,
				r.decision(), "IN_APP", null);
		this.audit.record(AuditEntry.of("consent.record", AuditOutcome.SUCCESS)
			.target("consent_record", id)
			.school(schoolId)
			.metadata("studentId", r.studentId().toString())
			.metadata("consentType", r.consentType().name())
			.metadata("decision", r.decision().name())
			.metadata("method", "IN_APP")
			.metadata("policyVersionId", version.id().toString()));
		return recorded(id, r.consentType(), r.decision(), version, "IN_APP", r.studentId(), schoolId,
				this.consents.status(r.studentId(), schoolId, r.consentType()));
	}

	private RecordedConsent decideForMyself(CurrentUser user, MyDecision r) {
		if (r.consentType().forChildren()) {
			throw ApiException.badRequest("consent.student_required");
		}
		// School-managed (children's) accounts do not accept adult terms for themselves.
		if (!this.users.verifiedContacts(user.userId())
			.map(UserDirectory.VerifiedContacts::activeStandardAccount)
			.orElse(false)) {
			throw ApiException.forbidden("consent.account_not_eligible");
		}
		Version version = this.policies.version(r.policyVersionId())
			.orElseThrow(() -> ApiException.notFound("policy.not_found"));
		// Only the text of the country that applies to this adult can be accepted, never another country's.
		String country = accountCountry(user.userId(), firstSchoolOf(user));
		Document document = this.policies.effectiveDocument(r.consentType(), null, country)
			.filter((d) -> d.id().equals(version.documentId()))
			.orElseThrow(() -> ApiException.badRequest("consent.version_invalid"));
		requireCurrent(document, version);
		UUID id = insert(version, r.consentType(), user.userId(), user.userId(), null, null, null, r.decision(), "IN_APP",
				null);
		this.audit.record(AuditEntry.of("consent.record", AuditOutcome.SUCCESS)
			.target("consent_record", id)
			.metadata("consentType", r.consentType().name())
			.metadata("decision", r.decision().name())
			.metadata("method", "IN_APP")
			.metadata("policyVersionId", version.id().toString()));
		return recorded(id, r.consentType(), r.decision(), version, "IN_APP", null, null, null);
	}

	// --- the school ------------------------------------------------------------------------------

	/** A decision the school received on paper or verbally ({@code school.guardians.manage}). */
	public RecordedConsent recordForStudent(CurrentUser user, UUID schoolId, UUID studentId, StaffDecision r) {
		this.gate.require(user, schoolId, Permissions.SCHOOL_GUARDIANS_MANAGE);
		requireActiveStudent(schoolId, studentId);
		if (!r.consentType().forChildren()) {
			throw ApiException.badRequest("consent.type_not_allowed");
		}
		if (r.method() == null || "IN_APP".equals(r.method().name())) {
			throw ApiException.badRequest("consent.method_invalid");
		}
		boolean byLink = r.guardianLinkId() != null;
		String name = (r.guardianName() != null && !r.guardianName().isBlank()) ? r.guardianName().trim() : null;
		if (byLink == (name != null)) {
			throw ApiException.badRequest("consent.guardian_required");
		}
		UUID guardianUser = null;
		if (byLink) {
			GuardianGrant link = this.guardians.activeLink(r.guardianLinkId())
				.filter((g) -> g.studentId().equals(studentId) && g.allows(GuardianPermission.GIVE_CONSENT))
				.orElseThrow(() -> ApiException.conflict("consent.guardian_not_eligible"));
			guardianUser = link.guardianId();
		}
		Version version = currentOrConflict(r.consentType(), schoolId, null, r.policyVersionId());
		UUID id = insert(version, r.consentType(), studentId, guardianUser, name, r.guardianLinkId(), schoolId,
				r.decision(), r.method().name(), user.userId());
		this.audit.record(AuditEntry.of("consent.record", AuditOutcome.SUCCESS)
			.target("consent_record", id)
			.school(schoolId)
			.metadata("studentId", studentId.toString())
			.metadata("consentType", r.consentType().name())
			.metadata("decision", r.decision().name())
			.metadata("method", r.method().name())
			.metadata("guardianLinkId", byLink ? r.guardianLinkId().toString() : null)
			.metadata("namedGuardian", !byLink)
			.metadata("policyVersionId", version.id().toString()));
		return recorded(id, r.consentType(), r.decision(), version, r.method().name(), studentId, schoolId,
				this.consents.status(studentId, schoolId, r.consentType()));
	}

	/** The child's effective statuses and every record (newest first), for {@code school.guardians.manage}. */
	@Transactional(readOnly = true)
	public StudentConsents forStudent(CurrentUser user, UUID schoolId, UUID studentId) {
		this.gate.require(user, schoolId, Permissions.SCHOOL_GUARDIANS_MANAGE);
		requireActiveStudent(schoolId, studentId);
		List<ConsentRecordView> records = this.jdbc.sql("""
				select cr.id, cr.consent_type, cr.decision, cr.method, cr.student_guardian_id, cr.granted_by_name,
				       coalesce(u.display_name, cr.granted_by_name) as decided_by_name, cr.recorded_by, cr.created_at,
				       pv.id as version_id, pv.version_label
				from consent_records cr join policy_versions pv on pv.id = cr.policy_version_id
				left join users u on u.id = cr.granted_by_user_id
				where cr.subject_user_id = :st and cr.school_id = :s order by cr.created_at desc, cr.id desc limit 200""")
			.param("st", studentId)
			.param("s", schoolId)
			.query((rs, i) -> new ConsentRecordView(Rows.uuid(rs, "id"), ConsentType.valueOf(rs.getString("consent_type")),
					Decision.valueOf(rs.getString("decision")), Method.valueOf(rs.getString("method")),
					Rows.uuid(rs, "student_guardian_id"), rs.getString("decided_by_name"), Rows.uuid(rs, "recorded_by"),
					Rows.uuid(rs, "version_id"), rs.getString("version_label"), Rows.instant(rs, "created_at")))
			.list();
		return new StudentConsents(studentId,
				this.consents.status(studentId, schoolId, ConsentType.ACCOUNT_PARTICIPATION),
				this.consents.status(studentId, schoolId, ConsentType.MEDIA_PUBLICATION), records);
	}

	/** The roster's consent column: per ACTIVE student, no guardian identities. */
	@Transactional(readOnly = true)
	public List<StudentConsentSummary> classSummary(CurrentUser user, UUID schoolId, UUID classId) {
		ClassRef ref = this.classAccess.find(classId).filter((c) -> c.schoolId().equals(schoolId)).orElse(null);
		boolean manager = this.gate.can(user, schoolId, Permissions.SCHOOL_GUARDIANS_MANAGE);
		if (ref == null || (!manager && !this.classAccess.canView(user, classId))) {
			throw ApiException.notFound("class.not_found");
		}
		if (!manager && !this.classAccess.canTeach(user, classId)) {
			throw ApiException.forbidden("class.access_denied");
		}
		List<UUID> students = new ArrayList<>(this.memberships.activeStudentIds(classId));
		Map<UUID, PersonCard> cards = this.people.cards(students);
		Map<UUID, ConsentStatus> participation = this.consents.statuses(students, schoolId,
				ConsentType.ACCOUNT_PARTICIPATION);
		Map<UUID, ConsentStatus> media = this.consents.statuses(students, schoolId, ConsentType.MEDIA_PUBLICATION);
		List<StudentConsentSummary> result = new ArrayList<>();
		for (UUID studentId : students) {
			PersonCard card = cards.get(studentId);
			result.add(new StudentConsentSummary(studentId, (card != null) ? card.displayName() : "",
					participation.get(studentId), media.get(studentId)));
		}
		result.sort(java.util.Comparator.comparing((StudentConsentSummary s) -> s.displayName().toLowerCase(Locale.ROOT))
			.thenComparing(StudentConsentSummary::studentId));
		return result;
	}

	// --- helpers --------------------------------------------------------------------------------

	/**
	 * The country whose TERMS and PRIVACY apply to an adult: that of the first school of a child they may decide for, else
	 * their home country, else the platform default country (the resolution order of {@code tenancy.CountryResolver}).
	 */
	private String accountCountry(UUID userId, UUID firstSchool) {
		UserDirectory.UserPreferences preferences = this.users.preferences(List.of(userId)).get(userId);
		return this.countries.resolve(firstSchool, (preferences != null) ? preferences.homeCountryCode() : null, null).code();
	}

	private UUID firstSchoolOf(CurrentUser user) {
		for (GuardianGrant link : this.guardians.childrenOf(user.userId())) {
			if (!link.allows(GuardianPermission.GIVE_CONSENT)) {
				continue;
			}
			for (UUID schoolId : activeSchools(link.studentId())) {
				if (this.directory.school(schoolId).isPresent()) {
					return schoolId;
				}
			}
		}
		return null;
	}

	private Version currentOrConflict(ConsentType type, UUID schoolId, String country, UUID versionId) {
		Version version = this.policies.version(versionId).orElseThrow(() -> ApiException.notFound("policy.not_found"));
		// no document at all for the school's country: never fall back to another country's text
		Document document = this.policies.effectiveDocument(type, schoolId, country)
			.orElseThrow(() -> ApiException.conflict("policy.country_not_available"));
		if (!document.id().equals(version.documentId())) {
			throw ApiException.conflict("consent.version_outdated");
		}
		requireCurrent(document, version);
		return version;
	}

	/** The version must be published, in effect and of the current label (any language). */
	private void requireCurrent(Document document, Version version) {
		Version current = this.policies.currentVersion(document.id(), version.languageCode())
			.orElseThrow(() -> ApiException.conflict("consent.version_outdated"));
		if (version.publishedAt() == null || !current.versionLabel().equals(version.versionLabel())) {
			throw ApiException.conflict("consent.version_outdated");
		}
	}

	private UUID insert(Version version, ConsentType type, UUID subject, UUID grantedBy, String grantedByName,
			UUID linkId, UUID schoolId, Decision decision, String method, UUID recordedBy) {
		UUID id = Ids.newId();
		this.jdbc.sql("""
				insert into consent_records (id, policy_version_id, consent_type, subject_user_id, granted_by_user_id,
				                             granted_by_name, student_guardian_id, school_id, decision, method, recorded_by,
				                             ip, created_at)
				values (:id, :v, :type, :subject, :by, :name, :link, :school, :decision, :method, :recorded,
				        cast(:ip as inet), :now)""")
			.param("id", id)
			.param("v", version.id())
			.param("type", type.name())
			.param("subject", subject)
			.param("by", grantedBy)
			.param("name", grantedByName)
			.param("link", linkId)
			.param("school", schoolId)
			.param("decision", decision.name())
			.param("method", method)
			.param("recorded", recordedBy)
			.param("ip", "IN_APP".equals(method) ? ClientRequests.ip() : null)
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
		this.events.publishEvent(new ConsentChanged(id, subject, schoolId, type, decision.name(),
				(recordedBy != null) ? recordedBy : grantedBy));
		return id;
	}

	private void requireActiveStudent(UUID schoolId, UUID studentId) {
		if (!activeSchools(studentId).contains(schoolId)) {
			throw ApiException.notFound("student.not_found");
		}
	}

	private List<UUID> activeSchools(UUID studentId) {
		return this.jdbc.sql("""
				select ss.school_id from school_students ss join schools s on s.id = ss.school_id
				where ss.student_id = :st and ss.status = 'ACTIVE' order by ss.created_at, ss.id""")
			.param("st", studentId)
			.query(UUID.class)
			.list();
	}

	private String language(CurrentUser user) {
		String language = this.users.preferences(List.of(user.userId()))
			.values()
			.stream()
			.map(UserDirectory.UserPreferences::languageCode)
			.filter(java.util.Objects::nonNull)
			.findFirst()
			.orElse(null);
		return (language != null) ? language : LocaleContextHolder.getLocale().getLanguage();
	}

	private static ConsentItem item(ConsentType type, Document document, Version current, DecisionRow mine,
			Instant reconsentSince, ConsentStatus status) {
		MyDecisionView decision = (mine != null) ? new MyDecisionView(Decision.valueOf(mine.decision()), mine.versionId(),
				mine.decidedAt()) : null;
		boolean outdated = mine != null && "GRANTED".equals(mine.decision())
				&& DefaultConsentService.outdated(mine, reconsentSince);
		return new ConsentItem(type, (document != null && current != null) ? PolicyService.view(document, current) : null,
				decision, outdated, mine == null || outdated, status);
	}

	private RecordedConsent recorded(UUID id, ConsentType type, Decision decision, Version version, String method,
			UUID studentId, UUID schoolId, ConsentStatus status) {
		return new RecordedConsent(id, type, decision, version.id(), version.versionLabel(), Method.valueOf(method),
				studentId, schoolId, this.clock.instant(), status);
	}

	public enum Decision {

		GRANTED, DENIED, WITHDRAWN

	}

	public enum Method {

		IN_APP, PAPER_RECORDED_BY_SCHOOL, VERBAL_RECORDED_BY_SCHOOL

	}

	public record MyDecision(UUID studentId, UUID schoolId, ConsentType consentType, UUID policyVersionId,
			Decision decision) {
	}

	public record StaffDecision(ConsentType consentType, UUID policyVersionId, Decision decision, UUID guardianLinkId,
			String guardianName, Method method) {

		@Override
		public String toString() {
			return "StaffDecision[redacted]";
		}

	}

	public record MyConsents(@NotNull List<ChildConsents> children,
			@NotNull @Schema(description = "The adult's own TERMS and PRIVACY") List<ConsentItem> account) {
	}

	public record ChildConsents(@NotNull UUID studentId, @NotNull UUID linkId, @NotNull String childName,
			@NotNull UUID schoolId, @NotNull String schoolName, @NotNull List<ConsentItem> items) {
	}

	public record MyDecisionView(@NotNull Decision decision, @NotNull UUID policyVersionId, @NotNull Instant decidedAt) {
	}

	public record ConsentItem(@NotNull ConsentType consentType,
			@Schema(description = "The text to decide on; absent when no policy is published") PolicyService.PolicyView currentVersion,
			@Schema(description = "My latest decision (for a child: the latest decision of my link, also when the school recorded it)") MyDecisionView myDecision,
			@NotNull @Schema(description = "My GRANTED decision is on a version superseded by one that requires new consent") boolean outdated,
			@NotNull @Schema(description = "No decision yet, or an outdated one") boolean needsDecision,
			@Schema(description = "The child's effective status (children only)") ConsentStatus effectiveStatus) {
	}

	public record RecordedConsent(@NotNull UUID id, @NotNull ConsentType consentType, @NotNull Decision decision,
			@NotNull UUID policyVersionId, @NotNull String versionLabel, @NotNull Method method, UUID studentId,
			UUID schoolId, @NotNull Instant recordedAt,
			@Schema(description = "The child's effective status after this decision (children only)") ConsentStatus effectiveStatus) {
	}

	public record ConsentRecordView(@NotNull UUID id, @NotNull ConsentType consentType, @NotNull Decision decision,
			@NotNull Method method, UUID guardianLinkId, @Schema(description = "The guardian (account name or the name written on paper)") String decidedByName,
			@Schema(description = "Staff member who recorded a paper or verbal decision") UUID recordedBy,
			@NotNull UUID policyVersionId, @NotNull String versionLabel, @NotNull Instant createdAt) {
	}

	public record StudentConsents(@NotNull UUID studentId, @NotNull ConsentStatus accountParticipation,
			@NotNull ConsentStatus mediaPublication, @NotNull List<ConsentRecordView> records) {
	}

	public record StudentConsentSummary(@NotNull UUID studentId, @NotNull String displayName,
			@NotNull ConsentStatus accountParticipation, @NotNull ConsentStatus mediaPublication) {
	}

}
