package com.nemaz.school.tenancy.application;

import java.util.UUID;

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

	CampusService(CampusRepository campuses, SchoolRepository schools) {
		this.campuses = campuses;
		this.schools = schools;
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
		try {
			return this.campuses.saveAndFlush(new Campus(schoolId, name, code, timezone));
		}
		catch (DataIntegrityViolationException ex) {
			throw ApiException.conflict("campus.code_taken");
		}
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
		campus.update(name, code, timezone, status);
		try {
			return this.campuses.saveAndFlush(campus);
		}
		catch (DataIntegrityViolationException ex) {
			throw ApiException.conflict("campus.code_taken");
		}
	}

	private void requireSchool(UUID schoolId) {
		if (!this.schools.existsById(schoolId)) {
			throw ApiException.notFound("school.not_found");
		}
	}

}
