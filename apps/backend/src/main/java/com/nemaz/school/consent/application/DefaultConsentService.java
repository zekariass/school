package com.nemaz.school.consent.application;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.consent.ConsentService;
import com.nemaz.school.consent.ConsentStatus;
import com.nemaz.school.consent.ConsentStatus.Effective;
import com.nemaz.school.consent.ConsentStatus.Reason;
import com.nemaz.school.consent.ConsentType;
import com.nemaz.school.consent.application.PolicyService.Document;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The effective status rule of {@link ConsentService} in one place, for single children and whole classes.
 * <ol>
 * <li>Current deciders: every ACTIVE, not custody-restricted link of the child with {@code can_give_consent}, and every
 * named guardian without an account (paper records with {@code granted_by_name}).</li>
 * <li>Their latest decision for (child, school, type): per link (in-app or recorded by the school for that link) or per
 * name (case-insensitive). Decisions of other links (revoked, rejected, pending, restricted, consent flag off) are
 * ignored.</li>
 * <li>Any DENIED or WITHDRAWN: DENIED. Else any GRANTED that took effect no earlier than the newest published version
 * with {@code requires_reconsent} of the effective document: GRANTED. Else UNKNOWN (RECONSENT_REQUIRED when there were
 * only outdated grants, NO_DECISION otherwise).</li>
 * </ol>
 */
@Service
@Transactional(readOnly = true)
public class DefaultConsentService implements ConsentService {

	private final JdbcClient jdbc;

	private final PolicyService policies;

	DefaultConsentService(JdbcClient jdbc, PolicyService policies) {
		this.jdbc = jdbc;
		this.policies = policies;
	}

	@Override
	public ConsentStatus status(UUID studentId, UUID schoolId, ConsentType type) {
		return statuses(List.of(studentId), schoolId, type).get(studentId);
	}

	@Override
	public Map<UUID, ConsentStatus> statuses(Collection<UUID> studentIds, UUID schoolId, ConsentType type) {
		Map<UUID, ConsentStatus> result = new LinkedHashMap<>();
		if (studentIds.isEmpty()) {
			return result;
		}
		Map<UUID, List<DecisionRow>> latest = latestDecisions(studentIds, schoolId, type);
		Instant reconsentSince = this.policies.effectiveDocument(type, schoolId, null)
			.flatMap((Document d) -> this.policies.reconsentSince(d.id()))
			.orElse(null);
		for (UUID studentId : studentIds) {
			result.put(studentId, decide(latest.getOrDefault(studentId, List.of()), reconsentSince));
		}
		return result;
	}

	/** The rule itself, over the latest decision of each current decider. */
	static ConsentStatus decide(List<DecisionRow> decisions, Instant reconsentSince) {
		List<UUID> refusals = new ArrayList<>();
		boolean withdrawnOnly = true;
		List<UUID> grants = new ArrayList<>();
		boolean outdatedGrant = false;
		for (DecisionRow d : decisions) {
			switch (d.decision()) {
				case "DENIED" -> {
					refusals.add(d.recordId());
					withdrawnOnly = false;
				}
				case "WITHDRAWN" -> refusals.add(d.recordId());
				case "GRANTED" -> {
					if (outdated(d, reconsentSince)) {
						outdatedGrant = true;
					}
					else {
						grants.add(d.recordId());
					}
				}
				default -> throw new IllegalStateException("Unknown decision");
			}
		}
		if (!refusals.isEmpty()) {
			return new ConsentStatus(Effective.DENIED, withdrawnOnly ? Reason.WITHDRAWN : Reason.DENIED, refusals);
		}
		if (!grants.isEmpty()) {
			return new ConsentStatus(Effective.GRANTED, Reason.GRANTED, grants);
		}
		return new ConsentStatus(Effective.UNKNOWN, outdatedGrant ? Reason.RECONSENT_REQUIRED : Reason.NO_DECISION,
				List.of());
	}

	static boolean outdated(DecisionRow d, Instant reconsentSince) {
		return reconsentSince != null && d.versionEffectiveFrom().isBefore(reconsentSince);
	}

	/** Per child, the latest decision of each current decider (see the class comment). */
	Map<UUID, List<DecisionRow>> latestDecisions(Collection<UUID> studentIds, UUID schoolId, ConsentType type) {
		Set<UUID> deciders = new HashSet<>(this.jdbc.sql("""
				select id from student_guardians where student_id in (:st) and status = 'ACTIVE' and not custody_restricted
				  and can_give_consent""")
			.param("st", studentIds)
			.query(UUID.class)
			.list());
		Map<UUID, List<DecisionRow>> result = new HashMap<>();
		this.jdbc.sql("""
				select * from (
				  select cr.id, cr.subject_user_id, cr.student_guardian_id, cr.granted_by_name, cr.decision, cr.created_at,
				         cr.policy_version_id, pv.effective_from,
				         row_number() over (partition by cr.subject_user_id,
				                            coalesce(cr.student_guardian_id::text, 'name:' || lower(trim(cr.granted_by_name)))
				                            order by cr.created_at desc, cr.id desc) as rn
				  from consent_records cr join policy_versions pv on pv.id = cr.policy_version_id
				  where cr.school_id = :school and cr.consent_type = :type and cr.subject_user_id in (:st)
				    and cr.scope_ref_type is null
				    and (cr.student_guardian_id is not null or cr.granted_by_name is not null)
				) latest where rn = 1""")
			.param("school", schoolId)
			.param("type", type.name())
			.param("st", studentIds)
			.query((rs, i) -> new DecisionRow(Rows.uuid(rs, "id"), Rows.uuid(rs, "subject_user_id"),
					Rows.uuid(rs, "student_guardian_id"), rs.getString("granted_by_name"), rs.getString("decision"),
					Rows.uuid(rs, "policy_version_id"), Rows.instant(rs, "effective_from"), Rows.instant(rs, "created_at")))
			.list()
			.stream()
			.filter((d) -> d.linkId() == null || deciders.contains(d.linkId()))
			.forEach((d) -> result.computeIfAbsent(d.studentId(), (k) -> new ArrayList<>()).add(d));
		return result;
	}

	/** The latest decision recorded for one guardian link (any method). */
	Optional<DecisionRow> latestForLink(UUID linkId, UUID studentId, UUID schoolId, ConsentType type) {
		return this.jdbc.sql("""
				select cr.id, cr.subject_user_id, cr.student_guardian_id, cr.granted_by_name, cr.decision, cr.created_at,
				       cr.policy_version_id, pv.effective_from
				from consent_records cr join policy_versions pv on pv.id = cr.policy_version_id
				where cr.student_guardian_id = :link and cr.subject_user_id = :st and cr.school_id = :school
				  and cr.consent_type = :type and cr.scope_ref_type is null
				order by cr.created_at desc, cr.id desc limit 1""")
			.param("link", linkId)
			.param("st", studentId)
			.param("school", schoolId)
			.param("type", type.name())
			.query((rs, i) -> new DecisionRow(Rows.uuid(rs, "id"), Rows.uuid(rs, "subject_user_id"),
					Rows.uuid(rs, "student_guardian_id"), rs.getString("granted_by_name"), rs.getString("decision"),
					Rows.uuid(rs, "policy_version_id"), Rows.instant(rs, "effective_from"), Rows.instant(rs, "created_at")))
			.optional();
	}

	/** The latest decision of an adult about themself (TERMS, PRIVACY). */
	Optional<DecisionRow> latestOwn(UUID userId, ConsentType type) {
		return this.jdbc.sql("""
				select cr.id, cr.subject_user_id, cr.student_guardian_id, cr.granted_by_name, cr.decision, cr.created_at,
				       cr.policy_version_id, pv.effective_from
				from consent_records cr join policy_versions pv on pv.id = cr.policy_version_id
				where cr.subject_user_id = :u and cr.granted_by_user_id = :u and cr.consent_type = :type
				  and cr.school_id is null and cr.scope_ref_type is null
				order by cr.created_at desc, cr.id desc limit 1""")
			.param("u", userId)
			.param("type", type.name())
			.query((rs, i) -> new DecisionRow(Rows.uuid(rs, "id"), Rows.uuid(rs, "subject_user_id"), null, null,
					rs.getString("decision"), Rows.uuid(rs, "policy_version_id"), Rows.instant(rs, "effective_from"),
					Rows.instant(rs, "created_at")))
			.optional();
	}

	static Timestamp ts(Instant instant) {
		return Timestamp.from(instant);
	}

	/** One consent record as the status rule sees it. */
	record DecisionRow(UUID recordId, UUID studentId, UUID linkId, String guardianName, String decision, UUID versionId,
			Instant versionEffectiveFrom, Instant decidedAt) {
	}

}
