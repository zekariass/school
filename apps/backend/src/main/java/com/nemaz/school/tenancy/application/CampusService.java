package com.nemaz.school.tenancy.application;

import java.util.UUID;

import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.pagination.KeysetPages;
import com.nemaz.school.tenancy.domain.Campus;
import com.nemaz.school.tenancy.domain.CampusStatus;
import com.nemaz.school.tenancy.infrastructure.CampusRepository;
import com.nemaz.school.tenancy.infrastructure.SchoolRepository;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CampusService {

	private final CampusRepository campuses;

	private final SchoolRepository schools;

	private final AuditLog audit;

	CampusService(CampusRepository campuses, SchoolRepository schools, AuditLog audit) {
		this.campuses = campuses;
		this.schools = schools;
		this.audit = audit;
	}

	@Transactional(readOnly = true)
	public CursorPage<Campus> list(UUID schoolId, String cursor, Integer limit) {
		requireSchool(schoolId);
		Specification<Campus> filter = (root, query, cb) -> cb.equal(root.get("schoolId"), schoolId);
		return KeysetPages.page(this.campuses, filter, cursor, limit, false);
	}

	public Campus create(UUID schoolId, String name, String code, String timezone) {
		requireSchool(schoolId);
		if (timezone != null) {
			SchoolService.requireTimezone(timezone);
		}
		if (code != null && this.campuses.existsBySchoolIdAndCode(schoolId, code)) {
			throw ApiException.conflict("campus.code_taken");
		}
		Campus saved;
		try {
			saved = this.campuses.saveAndFlush(new Campus(schoolId, name, code, timezone));
		}
		catch (DataIntegrityViolationException ex) {
			throw ApiException.conflict("campus.code_taken");
		}
		this.audit.record(AuditEntry.of("campus.create", AuditOutcome.SUCCESS)
			.target("campus", saved.getId())
			.school(schoolId)
			.metadata("code", code));
		return saved;
	}

	public Campus update(UUID schoolId, UUID campusId, String name, String code, String timezone,
			CampusStatus status) {
		Campus campus = this.campuses.findById(campusId)
			.filter((c) -> c.getSchoolId().equals(schoolId))
			.orElseThrow(() -> ApiException.notFound("campus.not_found"));
		if (timezone != null) {
			SchoolService.requireTimezone(timezone);
		}
		if (code != null && this.campuses.existsBySchoolIdAndCodeAndIdNot(schoolId, code, campusId)) {
			throw ApiException.conflict("campus.code_taken");
		}
		java.util.Map<String, Object> before = snapshot(campus);
		campus.update(name, code, timezone, status);
		Campus saved;
		try {
			saved = this.campuses.saveAndFlush(campus);
		}
		catch (DataIntegrityViolationException ex) {
			throw ApiException.conflict("campus.code_taken");
		}
		this.audit.record(AuditEntry.of("campus.update", AuditOutcome.SUCCESS)
			.target("campus", campusId)
			.school(schoolId)
			.changes(before, snapshot(saved)));
		return saved;
	}

	private static java.util.Map<String, Object> snapshot(Campus c) {
		java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
		values.put("name", c.getName());
		values.put("code", c.getCode());
		values.put("timezone", c.getTimezone());
		values.put("status", c.getStatus().name());
		return values;
	}

	private void requireSchool(UUID schoolId) {
		if (!this.schools.existsById(schoolId)) {
			throw ApiException.notFound("school.not_found");
		}
	}

}
