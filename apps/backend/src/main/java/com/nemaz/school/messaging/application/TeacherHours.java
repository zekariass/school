package com.nemaz.school.messaging.application;

import java.time.Instant;
import java.util.Optional;

import com.nemaz.school.messaging.domain.ActingAs;
import com.nemaz.school.messaging.domain.DirectKind;
import com.nemaz.school.messaging.domain.GroupKind;
import com.nemaz.school.messaging.domain.OfficeHours;
import com.nemaz.school.messaging.infrastructure.MessagingRows.ConversationRow;
import com.nemaz.school.messaging.infrastructure.MessagingSettings;

import org.springframework.stereotype.Component;

/**
 * Office hours (rule book R2.5): a message that reaches a <em>teacher</em> in a guardian-teacher conversation or a class
 * parent group outside {@code messaging.teacher_hours} is delivered silently (stored and shown, no push) and the sender is
 * told. Messages a teacher sends are not affected.
 */
@Component
public class TeacherHours {

	private final MessagingSettings settings;

	TeacherHours(MessagingSettings settings) {
		this.settings = settings;
	}

	/** Whether the conversation's teachers receive a message sent at {@code sentAt} by {@code sender} silently. */
	public boolean silentForTeachers(ConversationRow conversation, ActingAs sender, Instant sentAt) {
		boolean guardianFacing = conversation.directKind() == DirectKind.GUARDIAN_TEACHER
				|| conversation.groupKind() == GroupKind.CLASS_PARENTS;
		if (!guardianFacing || sender == ActingAs.TEACHER) {
			return false;
		}
		Optional<OfficeHours> hours = this.settings.officeHours(conversation.schoolId());
		return hours.isPresent() && !hours.get().contains(sentAt, this.settings.zone(conversation.schoolId()));
	}

}
