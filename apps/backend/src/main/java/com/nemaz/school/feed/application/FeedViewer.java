package com.nemaz.school.feed.application;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.feed.application.FeedModels.FeedAuthorRole;
import com.nemaz.school.feed.application.FeedModels.FeedScope;
import com.nemaz.school.feed.application.FeedRows.PostRow;
import com.nemaz.school.guardians.GuardianAccess.GuardianGrant;

/**
 * One request's view of "who am I to this content", with the answers cached for the page being built. It decides what a
 * viewer may be told about people (the student privacy split, spec §100, §131):
 * <ul>
 * <li><b>A tagged student</b> is named to: the student, their ACTIVE guardians, those who may see the class roster
 * (the class's students, teachers and managers) for a class post, and for a school-wide post to school feed managers,
 * the student's teachers and classmates. Everybody else does not see that tag at all.</li>
 * <li><b>An author or commenter</b> is named when they are staff (teachers and administrators are public to those who
 * see the content), the viewer themself, or, for a guardian or student, when the viewer is staff of the content's
 * class or school; a student is also named to their own guardians and to classmates. Others see the role label only.</li>
 * </ul>
 */
final class FeedViewer {

	private final CurrentUser user;

	private final FeedAccess access;

	private final Map<String, Boolean> moderates = new HashMap<>();

	private final Map<String, Boolean> staff = new HashMap<>();

	private final Map<String, Boolean> roster = new HashMap<>();

	private final Map<String, Boolean> comment = new HashMap<>();

	private final Map<UUID, Boolean> classmates = new HashMap<>();

	private Set<UUID> ownStudents;

	private Set<UUID> taughtStudents;

	FeedViewer(CurrentUser user, FeedAccess access) {
		this.user = user;
		this.access = access;
	}

	CurrentUser user() {
		return this.user;
	}

	UUID id() {
		return this.user.userId();
	}

	private static String key(FeedScope scope, UUID schoolId, UUID classId) {
		return scope + ":" + schoolId + ":" + classId;
	}

	boolean moderates(FeedScope scope, UUID schoolId, UUID classId) {
		return this.moderates.computeIfAbsent(key(scope, schoolId, classId),
				(k) -> this.access.moderates(this.user, scope, schoolId, classId));
	}

	boolean moderates(PostRow post) {
		return moderates(post.scope(), post.schoolId(), post.classId());
	}

	boolean staff(FeedScope scope, UUID schoolId, UUID classId) {
		return this.staff.computeIfAbsent(key(scope, schoolId, classId),
				(k) -> this.access.staffOf(this.user, scope, schoolId, classId));
	}

	boolean mayComment(PostRow post) {
		return this.comment.computeIfAbsent(key(post.scope(), post.schoolId(), post.classId()),
				(k) -> this.access.mayComment(this.user, post));
	}

	/** The viewer themself (if a student) and their ACTIVE, not custody-restricted children. */
	Set<UUID> ownStudents() {
		if (this.ownStudents == null) {
			Set<UUID> own = new LinkedHashSet<>();
			own.add(this.user.userId());
			for (GuardianGrant grant : this.access.guardians().childrenOf(this.user.userId())) {
				own.add(grant.studentId());
			}
			this.ownStudents = own;
		}
		return this.ownStudents;
	}

	private boolean rosterOf(UUID classId) {
		return this.roster.computeIfAbsent(String.valueOf(classId),
				(k) -> this.access.classes().canViewRoster(this.user, classId));
	}

	private boolean teaches(UUID studentId) {
		if (this.taughtStudents == null) {
			this.taughtStudents = this.access.memberships().activeStudentIdsOfTeacher(this.user.userId());
		}
		return this.taughtStudents.contains(studentId);
	}

	private boolean classmate(UUID otherUserId) {
		return this.classmates.computeIfAbsent(otherUserId,
				(k) -> this.access.memberships().shareActiveClass(this.user.userId(), otherUserId));
	}

	boolean canSeeTag(FeedScope scope, UUID schoolId, UUID classId, UUID studentId) {
		if (ownStudents().contains(studentId)) {
			return true;
		}
		if (scope == FeedScope.CLASS) {
			return rosterOf(classId);
		}
		return this.access.feedManager(this.user, schoolId) || teaches(studentId) || classmate(studentId);
	}

	boolean canSeeName(FeedAuthorRole role, UUID personId, FeedScope scope, UUID schoolId, UUID classId) {
		if (personId.equals(this.user.userId())) {
			return true;
		}
		return switch (role) {
			case TEACHER, SCHOOL_ADMIN -> true;
			case GUARDIAN -> staff(scope, schoolId, classId);
			case STUDENT -> staff(scope, schoolId, classId) || ownStudents().contains(personId) || classmate(personId);
		};
	}

}
