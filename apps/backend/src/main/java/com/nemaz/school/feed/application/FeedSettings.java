package com.nemaz.school.feed.application;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.nemaz.school.media.MediaKind;
import com.nemaz.school.tenancy.SettingScope;
import com.nemaz.school.tenancy.SettingsService;

import org.springframework.stereotype.Component;

/**
 * The feed settings of a school (R__20_definitions.sql). Every default is the restrictive one: parent and student
 * contributions and translation are off, contributions are approved by a teacher, students cannot comment, only the
 * guardians of tagged students are notified.
 */
@Component
class FeedSettings {

	static final String PARENT_CONTRIBUTIONS = "feed.parent_contributions.enabled";

	static final String PARENT_APPROVAL_REQUIRED = "feed.parent_contributions.approval_required";

	static final String PARENT_ALLOWED_MEDIA = "feed.parent_contributions.allowed_media";

	static final String STUDENT_CONTRIBUTIONS = "feed.student_contributions.enabled";

	static final String COMMENTS_DEFAULT = "feed.comments.default_enabled";

	static final String GUARDIANS_COMMENT = "feed.comments.guardians_can_comment";

	static final String STUDENTS_COMMENT = "feed.comments.students_can_comment";

	static final String PIN_LIMIT = "feed.pin_limit";

	static final String ASSISTANTS_POST = "feed.assistants_can_post";

	static final String NOTIFY_CLASS = "feed.notify_class_members";

	static final String TRANSLATION = "feed.translation.enabled";

	private final SettingsService settings;

	FeedSettings(SettingsService settings) {
		this.settings = settings;
	}

	boolean parentContributions(UUID schoolId) {
		return this.settings.getBoolean(PARENT_CONTRIBUTIONS, SettingScope.school(schoolId));
	}

	boolean parentApprovalRequired(UUID schoolId) {
		return this.settings.getBoolean(PARENT_APPROVAL_REQUIRED, SettingScope.school(schoolId));
	}

	/** The media kinds guardians may contribute, restricted to what the feed supports now (photos). */
	Set<MediaKind> parentMedia(UUID schoolId) {
		List<String> configured = this.settings.getStringList(PARENT_ALLOWED_MEDIA, SettingScope.school(schoolId));
		Set<MediaKind> kinds = configured.stream()
			.map((k) -> "VIDEO".equals(k) ? MediaKind.VIDEO : MediaKind.IMAGE)
			.collect(Collectors.toSet());
		kinds.retainAll(FeedMedia.SUPPORTED_KINDS);
		return kinds;
	}

	boolean studentContributions(UUID schoolId) {
		return this.settings.getBoolean(STUDENT_CONTRIBUTIONS, SettingScope.school(schoolId));
	}

	boolean commentsDefault(UUID schoolId) {
		return this.settings.getBoolean(COMMENTS_DEFAULT, SettingScope.school(schoolId));
	}

	boolean guardiansComment(UUID schoolId) {
		return this.settings.getBoolean(GUARDIANS_COMMENT, SettingScope.school(schoolId));
	}

	boolean studentsComment(UUID schoolId) {
		return this.settings.getBoolean(STUDENTS_COMMENT, SettingScope.school(schoolId));
	}

	int pinLimit(UUID schoolId) {
		return this.settings.getInt(PIN_LIMIT, SettingScope.school(schoolId)).orElse(3);
	}

	boolean assistantsPost(UUID schoolId) {
		return this.settings.getBoolean(ASSISTANTS_POST, SettingScope.school(schoolId));
	}

	boolean notifyClassMembers(UUID schoolId) {
		return this.settings.getBoolean(NOTIFY_CLASS, SettingScope.school(schoolId));
	}

	boolean translation(UUID schoolId) {
		return this.settings.getBoolean(TRANSLATION, SettingScope.school(schoolId));
	}

}
