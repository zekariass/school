/**
 * Reports, moderation cases and their decisions (Implementation Plan M13a, Database_Design §4.17, R-18,
 * {@code docs/Safeguarding_Rules.md} S1, S2, S3, S5, S6). The pipeline is reports, then cases (one open case per target,
 * decided by a person with a reason, in an append-only history), then safeguarding incidents (the {@code safeguarding}
 * module). A case holds references and enums only, never a copy of anybody's content.
 * <ul>
 * <li><b>Other modules depend on this package, never the reverse.</b> The modules that own reportable content implement the
 * SPIs of this package: {@link com.nemaz.school.moderation.ReportTarget} (may this reporter see the target?),
 * {@link com.nemaz.school.moderation.ModerationTarget} (hold, hide, restore, read for review) and
 * {@link com.nemaz.school.moderation.SchoolSuspension} (the memberships of a suspended person). Later milestones add their
 * targets by registering another bean: nothing here changes.</li>
 * <li>Their hooks ({@code feed.ContentReported}, {@code messaging.MessageReported}) are consumed by idempotent listeners
 * that live in the owning module and call {@link com.nemaz.school.moderation.ReportIntake}: that keeps the dependency one-way.</li>
 * <li>{@link com.nemaz.school.moderation.LegalHold} is the question other modules ask before erasing or exporting a person's
 * data: held data is refused.</li>
 * <li>Nothing here ever writes text of a report, a decision reason or content to events, logs, audit rows or notifications.</li>
 * </ul>
 */
@ApplicationModule(displayName = "Moderation")
package com.nemaz.school.moderation;

import org.springframework.modulith.ApplicationModule;
