package com.nemaz.school.academic.application;

import java.util.UUID;

import com.nemaz.school.academic.ClassAccess;
import com.nemaz.school.academic.ClassAccess.ClassRef;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.security.CurrentUser;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The 404-or-403 rule for class endpoints: a class the caller cannot view (or that is not in the school of the
 * URL) is 404 {@code class.not_found}, exactly like an unknown id; a class the caller can view but may not
 * change is 403 {@code class.access_denied}.
 */
@Component
class ClassLookups {

	private final ClassAccess access;

	private final JdbcClient jdbc;

	ClassLookups(ClassAccess access, JdbcClient jdbc) {
		this.access = access;
		this.jdbc = jdbc;
	}

	ClassRef viewable(CurrentUser user, UUID schoolId, UUID classId) {
		ClassRef ref = this.access.find(classId).filter((c) -> c.schoolId().equals(schoolId)).orElse(null);
		if (ref == null || !this.access.canView(user, classId)) {
			throw ApiException.notFound("class.not_found");
		}
		return ref;
	}

	/**
	 * The roster of a class: like {@link #viewable} but for {@link ClassAccess#canViewRoster}, so a caller who may only
	 * see the class's metadata (a guardian of one of its students) gets 404, as if the roster did not exist.
	 */
	ClassRef rosterViewable(CurrentUser user, UUID schoolId, UUID classId) {
		ClassRef ref = this.access.find(classId).filter((c) -> c.schoolId().equals(schoolId)).orElse(null);
		if (ref == null || !this.access.canViewRoster(user, classId)) {
			throw ApiException.notFound("class.not_found");
		}
		return ref;
	}

	ClassRef teachable(CurrentUser user, UUID schoolId, UUID classId) {
		ClassRef ref = viewable(user, schoolId, classId);
		if (!this.access.canTeach(user, classId)) {
			throw ApiException.forbidden("class.access_denied");
		}
		return ref;
	}

	ClassRef leadable(CurrentUser user, UUID schoolId, UUID classId) {
		ClassRef ref = viewable(user, schoolId, classId);
		if (!this.access.canLead(user, classId)) {
			throw ApiException.forbidden("class.access_denied");
		}
		return ref;
	}

	ClassRef manageable(CurrentUser user, UUID schoolId, UUID classId) {
		ClassRef ref = viewable(user, schoolId, classId);
		if (!this.access.canManage(user, classId)) {
			throw ApiException.forbidden("class.access_denied");
		}
		return ref;
	}

	/** Serializes roster changes of one class (and rereads its state). 409 when archived. */
	void lockActive(ClassRef ref) {
		String status = this.jdbc.sql("select status from classes where id = :id for update")
			.param("id", ref.id())
			.query(String.class)
			.single();
		if ("ARCHIVED".equals(status)) {
			throw ApiException.conflict("class.archived");
		}
	}

}
