package com.nemaz.school.communications;

import java.util.UUID;

/**
 * An announcement was published and its recipients snapshotted (published in the publisher's transaction). Delivery
 * (in-app, push, email, SMS) happens after commit in chunks; it is at-least-once and idempotent. Carries ids only: the
 * text of an announcement may name children and never travels in events.
 */
public record AnnouncementPublished(UUID announcementId, UUID schoolId) {
}
