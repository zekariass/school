package com.nemaz.school.feed.application;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.academic.ClassAccess;
import com.nemaz.school.academic.ClassAccess.ClassRef;
import com.nemaz.school.academic.ClassMembershipView;
import com.nemaz.school.academic.ClassMembershipView.Kind;
import com.nemaz.school.academic.ClassMembershipView.TeacherRole;
import com.nemaz.school.academic.ClassMemberships;
import com.nemaz.school.academic.SchoolPeople;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.security.AccessControl;
import com.nemaz.school.common.security.AccessScope;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.Permissions;
import com.nemaz.school.common.security.SchoolGate;
import com.nemaz.school.feed.application.FeedModels.FeedAuthorRole;
import com.nemaz.school.feed.application.FeedModels.FeedScope;
import com.nemaz.school.feed.application.FeedRows.PostRow;
import com.nemaz.school.guardians.GuardianAccess;
import com.nemaz.school.guardians.GuardianAccess.GuardianGrant;
import com.nemaz.school.guardians.GuardianPermission;
import com.nemaz.school.tenancy.FeatureService;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The access questions of the feed, answered through the other modules' ports ({@link ClassAccess},
 * {@link GuardianAccess}, {@link SchoolGate}); no rule is restated here except the school-wide analog of the guardian
 * class rule, which lives in {@link FeedVisibility}. The 404-or-403 rule: a school, class or post the caller cannot see
 * is 404 (exactly like an unknown id), one the caller can see but may not use for this is 403.
 *
 * <p>
 * <b>Who may do what</b> (documented decisions):
 * <ul>
 * <li><b>Post to a class:</b> an ACTIVE LEAD, CO_TEACHER or SUBJECT_TEACHER of it, an ASSISTANT only while
 * {@code feed.assistants_can_post}, and a class manager.</li>
 * <li><b>Post school-wide:</b> {@code school.feed.manage}.</li>
 * <li><b>Moderate</b> (approve or reject contributions, hide or delete anyone's content, pin anyone's post): the class's
 * ACTIVE LEAD teacher, class managers ({@code class.manage}) and holders of {@code school.feed.manage}. A CO_TEACHER or
 * SUBJECT_TEACHER moderates their own posts only.</li>
 * <li><b>Contribute:</b> a guardian whose ACTIVE, not custody-restricted link to a child ACTIVE in the class has both
 * {@code can_view_academics} and {@code can_message_staff}; a student ACTIVE in the class when
 * {@code feed.student_contributions.enabled}.</li>
 * <li><b>Comment:</b> teachers and managers who see the post; guardians with the same two flags while
 * {@code feed.comments.guardians_can_comment}; students while {@code feed.comments.students_can_comment}.</li>
 * </ul>
 */
@Component
@Transactional(readOnly = true)
class FeedAccess {

	static final String FEATURE = "feed";

	private final ClassAccess classes;

	private final ClassMemberships memberships;

	private final SchoolGate gate;

	private final SchoolPeople people;

	private final GuardianAccess guardians;

	private final FeatureService features;

	private final AccessControl access;

	private final FeedSettings settings;

	private final FeedVisibility visibility;

	FeedAccess(ClassAccess classes, ClassMemberships memberships, SchoolGate gate, SchoolPeople people,
			GuardianAccess guardians, FeatureService features, AccessControl access, FeedSettings settings,
			FeedVisibility visibility) {
		this.visibility = visibility;
		this.classes = classes;
		this.memberships = memberships;
		this.gate = gate;
		this.people = people;
		this.guardians = guardians;
		this.features = features;
		this.access = access;
		this.settings = settings;
	}

	ClassAccess classes() {
		return this.classes;
	}

	ClassMemberships memberships() {
		return this.memberships;
	}

	SchoolPeople people() {
		return this.people;
	}

	GuardianAccess guardians() {
		return this.guardians;
	}

	FeedSettings settings() {
		return this.settings;
	}

	/** 404 unless the school is visible to the caller, then 403 {@code feed.feature_disabled} when the feed is off. */
	void requireSchool(CurrentUser user, UUID schoolId) {
		// Guardians are not school members (R-02) but see the school's feed: the feed's own membership test comes first.
		if (!this.visibility.canSeeSchool(user, schoolId)) {
			this.gate.requireVisible(user, schoolId);
		}
		requireFeature(schoolId);
	}

	/**
	 * 403 for somebody who sees the school (school.read) without belonging to it or managing its feed: the school-wide feed
	 * is for its students, staff, guardians and feed managers.
	 */
	void requireSchoolMember(CurrentUser user, UUID schoolId) {
		if (!this.visibility.canSeeSchool(user, schoolId)) {
			throw ApiException.forbidden("feed.access_denied");
		}
	}

	void requireFeature(UUID schoolId) {
		if (!this.features.isEnabled(FEATURE, schoolId)) {
			throw ApiException.forbidden("feed.feature_disabled");
		}
	}

	boolean featureEnabled(UUID schoolId) {
		return this.features.isEnabled(FEATURE, schoolId);
	}

	/** The class if it belongs to the school and the caller may view it; 404 {@code class.not_found} otherwise. */
	ClassRef viewableClass(CurrentUser user, UUID schoolId, UUID classId) {
		ClassRef ref = this.classes.find(classId).filter((c) -> c.schoolId().equals(schoolId)).orElse(null);
		if (ref == null || !this.classes.canView(user, classId)) {
			throw ApiException.notFound("class.not_found");
		}
		return ref;
	}

	boolean feedManager(CurrentUser user, UUID schoolId) {
		return this.access.can(user, Permissions.SCHOOL_FEED_MANAGE, AccessScope.school(schoolId));
	}

	/**
	 * A moderator of the school (school.moderation.manage): may read the media of content that a moderation case holds, since
	 * the case-bound reader returns its media ids (the lead who hides a photo must still be able to look at it).
	 */
	boolean moderationReader(CurrentUser user, UUID schoolId) {
		return this.access.can(user, Permissions.SCHOOL_MODERATION_MANAGE, AccessScope.school(schoolId));
	}

	/** May the user post to the class (see the class comment). */
	boolean canPostToClass(CurrentUser user, ClassRef ref) {
		java.util.Optional<TeacherRole> role = teacherRole(user, ref.id());
		if (role.isPresent() && (role.get() != TeacherRole.ASSISTANT || this.settings.assistantsPost(ref.schoolId()))) {
			return true;
		}
		return this.classes.canManage(user, ref.id());
	}

	/** The author role recorded on a class post: TEACHER for the class's teachers, SCHOOL_ADMIN for a manager. */
	FeedAuthorRole classAuthorRole(CurrentUser user, UUID classId) {
		return teacherRole(user, classId).isPresent() ? FeedAuthorRole.TEACHER : FeedAuthorRole.SCHOOL_ADMIN;
	}

	java.util.Optional<TeacherRole> teacherRole(CurrentUser user, UUID classId) {
		return this.classes.membershipOf(user, classId)
			.filter((m) -> m.kind() == Kind.TEACHER)
			.map(ClassMembershipView::teacherRole);
	}

	/** Moderators of a class: its LEAD teacher, class managers, school feed managers. */
	boolean moderatesClass(CurrentUser user, UUID schoolId, UUID classId) {
		return this.classes.canLead(user, classId) || feedManager(user, schoolId);
	}

	boolean moderates(CurrentUser user, FeedScope scope, UUID schoolId, UUID classId) {
		return (scope == FeedScope.CLASS) ? moderatesClass(user, schoolId, classId) : feedManager(user, schoolId);
	}

	boolean moderates(CurrentUser user, PostRow post) {
		return moderates(user, post.scope(), post.schoolId(), post.classId());
	}

	/** Teachers and managers of the post's class (or school feed managers and teachers of the school for school posts). */
	boolean staffOf(CurrentUser user, FeedScope scope, UUID schoolId, UUID classId) {
		if (scope == FeedScope.CLASS) {
			return this.classes.canTeach(user, classId) || feedManager(user, schoolId);
		}
		return feedManager(user, schoolId) || this.people.isActiveTeacher(schoolId, user.userId());
	}

	boolean isActiveStudentOfClass(CurrentUser user, UUID classId) {
		return this.classes.membershipOf(user, classId).filter((m) -> m.kind() == Kind.STUDENT).isPresent();
	}

	/** Children whose guardian link carries both {@code can_view_academics} and {@code can_message_staff}. */
	Set<UUID> communicatingChildren(CurrentUser user) {
		Set<UUID> children = new LinkedHashSet<>();
		for (GuardianGrant grant : this.guardians.childrenOf(user.userId())) {
			if (grant.allows(GuardianPermission.VIEW_ACADEMICS) && grant.allows(GuardianPermission.MESSAGE_STAFF)) {
				children.add(grant.studentId());
			}
		}
		return children;
	}

	/** The students the caller may tag in a contribution to the class: their own children in it (a student: themself). */
	Set<UUID> contributionStudents(CurrentUser user, UUID schoolId, UUID classId) {
		Set<UUID> result = new LinkedHashSet<>();
		if (isActiveStudentOfClass(user, classId)) {
			if (this.settings.studentContributions(schoolId)) {
				result.add(user.userId());
			}
			return result;
		}
		if (!this.settings.parentContributions(schoolId)) {
			return result;
		}
		Set<UUID> inClass = this.memberships.activeStudentIds(classId);
		for (UUID child : communicatingChildren(user)) {
			if (inClass.contains(child)) {
				result.add(child);
			}
		}
		return result;
	}

	/** True when the caller is a student contributor (as opposed to a guardian) of the class. */
	boolean studentContributor(CurrentUser user, UUID schoolId, UUID classId) {
		return isActiveStudentOfClass(user, classId) && this.settings.studentContributions(schoolId);
	}

	/** The role recorded on a comment: the best of the caller's relations to the post. */
	FeedAuthorRole commentRole(CurrentUser user, PostRow post) {
		if (feedManager(user, post.schoolId()) || (post.scope() == FeedScope.CLASS && this.classes.canManage(user, post.classId()))) {
			return FeedAuthorRole.SCHOOL_ADMIN;
		}
		if (staffOf(user, post.scope(), post.schoolId(), post.classId())) {
			return FeedAuthorRole.TEACHER;
		}
		if (this.people.isActiveStudent(post.schoolId(), user.userId())) {
			return FeedAuthorRole.STUDENT;
		}
		return FeedAuthorRole.GUARDIAN;
	}

	/** May the caller (who can see the published post) write a comment on it, apart from the post's own switch? */
	boolean mayComment(CurrentUser user, PostRow post) {
		if (staffOf(user, post.scope(), post.schoolId(), post.classId())) {
			return true;
		}
		if (this.people.isActiveStudent(post.schoolId(), user.userId())) {
			return this.settings.studentsComment(post.schoolId());
		}
		if (!this.settings.guardiansComment(post.schoolId())) {
			return false;
		}
		Set<UUID> children = communicatingChildren(user);
		if (children.isEmpty()) {
			return false;
		}
		if (post.scope() == FeedScope.CLASS) {
			Set<UUID> inClass = this.memberships.activeStudentIds(post.classId());
			return children.stream().anyMatch(inClass::contains);
		}
		return !this.people.activeStudents(post.schoolId(), children).isEmpty();
	}

}
