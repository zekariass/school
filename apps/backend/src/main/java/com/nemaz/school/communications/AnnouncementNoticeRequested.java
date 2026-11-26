package com.nemaz.school.communications;

import java.util.UUID;

/**
 * "Tell the recipients again" was requested: a material edit (UPDATED), a reminder to the unacknowledged (REMINDER) or a
 * withdrawal (WITHDRAWN); {@code noticeId} is the row of {@code announcement_notices}. Ids only.
 */
public record AnnouncementNoticeRequested(UUID noticeId, UUID announcementId, UUID schoolId) {
}
