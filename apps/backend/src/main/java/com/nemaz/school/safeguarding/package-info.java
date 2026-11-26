/**
 * Safeguarding incidents (Implementation Plan M13a, Database_Design §4.17, R-18, {@code docs/Safeguarding_Rules.md} S4, S6):
 * the most sensitive data of the platform, about children. The rules, in short:
 * <ul>
 * <li><b>Need to know.</b> Only safeguarding leads of the school and platform safeguarding leads can read an incident
 * ({@code school.safeguarding.read}); school administrators, principals who are not leads, moderators, platform
 * administrators and support agents get 404, as if nothing existed.</li>
 * <li><b>Encrypted at rest.</b> The narrative, every note and the referral text are AES-256-GCM envelopes with the key id
 * stored beside them ({@code infrastructure.IncidentCipher}); lists and searches show ids, category, severity, status, school
 * and dates and never any text; there is no text search.</li>
 * <li><b>Every read is audited</b> (who, when, which incident, a reason from a pick-list) and rate limited; no audit row, event,
 * log line or notification ever contains text.</li>
 * <li><b>Nobody is told automatically.</b> HIGH and CRITICAL incidents alert the school's leads (the platform leads when it has
 * none) with a generic text; guardians, administrators, the reported adult and the reporter are never notified, and
 * {@code guardian_informed_at} and {@code referred_at} are manual, audited fields.</li>
 * <li><b>A school needs a lead</b> before it can switch on student messaging or student contributions: this module registers
 * the {@link com.nemaz.school.tenancy.SettingChangeGuard} that refuses the change.</li>
 * </ul>
 * Other modules use {@link com.nemaz.school.safeguarding.SafeguardingLeads}, {@link com.nemaz.school.safeguarding.IncidentIntake}
 * and {@link com.nemaz.school.safeguarding.IncidentHolds}.
 */
@ApplicationModule(displayName = "Safeguarding")
package com.nemaz.school.safeguarding;

import org.springframework.modulith.ApplicationModule;
