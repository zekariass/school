/**
 * Messaging (Implementation Plan M9, Database_Design §4.11, decision D3, R-28, spec §28-§32 and §131). Students may message
 * adults and adults may message families, so the rule book is {@code docs/Messaging_Rules.md} and the most restrictive choice
 * was taken wherever a rule was silent.
 * <ul>
 * <li><b>One policy engine</b> ({@code application.MessagingPolicy}, with the SQL of {@code infrastructure.Eligibility}) is used
 * by every write and every read: a membership row never grants access by itself; on <em>every</em> request the person and the
 * conversation must still pass the live rules (school ACTIVE, account ACTIVE, ACTIVE school and class links, guardian link
 * ACTIVE, not custody-restricted, {@code can_message_staff}, the school's {@code messaging.*} settings). Refusals the refused
 * party meets are generic ({@code messaging.not_allowed}); a conversation one cannot access is 404.</li>
 * <li><b>Safeguarding floor</b> ({@code application.Safeguarding}): a DIRECT conversation between an adult and a student gets the
 * student's guardians as read-only OBSERVER members, or is {@code admin_visible} (readable only through the audited review
 * endpoint with a mandatory reason). No setting turns this off.</li>
 * <li><b>Delivery</b>: a doorbell (ids only) on {@code /user/queue/messages}, push through the notification service with generic
 * text (never the message, the child's name or a full name), idempotent listeners.</li>
 * <li><b>Content</b>: plain text, images through the media module (purpose MESSAGE, context CONVERSATION), edit history,
 * tombstones, blocks, reports (event {@link com.nemaz.school.messaging.MessageReported}, ids only).</li>
 * </ul>
 */
@ApplicationModule(displayName = "Messaging")
package com.nemaz.school.messaging;

import org.springframework.modulith.ApplicationModule;
