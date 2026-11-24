/**
 * Notifications (Implementation Plan M2 steps 3-4 and 6, Database_Design §4.13).
 * <ul>
 * <li>Other modules call {@link com.nemaz.school.notifications.NotificationService#notify}: one
 * transaction writes the inbox rows (title and body rendered per recipient in their language from
 * {@code notification.<type>.title/.body}) and publishes {@link com.nemaz.school.notifications.NotificationsCreated}.</li>
 * <li>After commit, the inbox item is pushed to the recipient's open apps through the realtime
 * gateway, and, where the user's preference allows PUSH, queued per push device, held back during
 * quiet hours (unless URGENT and the type allows it) and sent through a {@code PushSender}
 * ({@code expo} or {@code log}).</li>
 * <li>Notification types are data (R-26); the user can switch off PUSH per type unless the type
 * is locked.</li>
 * <li>EMAIL and SMS are not delivered yet (nothing is recorded for them).</li>
 * </ul>
 */
@ApplicationModule(displayName = "Notifications")
package com.nemaz.school.notifications;

import org.springframework.modulith.ApplicationModule;
