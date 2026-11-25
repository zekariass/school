/**
 * Consent (Implementation Plan M5, Database_Design §4.18, R-09, spec §131): versioned policy texts per type, country
 * and school, and append-only consent decisions.
 * <ul>
 * <li><b>Policies:</b> the effective document of a type for a school is the school's own (once it has a published
 * version) or the country default; its current version is the latest published one already in effect, read in the
 * reader's language, else English, else any language.</li>
 * <li><b>Decisions:</b> guardians decide in the app for each child (only through an ACTIVE, not custody-restricted
 * link with {@code can_give_consent}); staff record paper or verbal decisions (also from guardians without an
 * account); adults accept TERMS and PRIVACY for themselves. Rows are never updated or deleted.</li>
 * <li><b>Effective status</b> ({@link com.nemaz.school.consent.ConsentService}): the most restrictive of the current
 * decisions; UNKNOWN unless somebody granted. Media of a child may be published only when MEDIA_PUBLICATION is
 * GRANTED.</li>
 * </ul>
 */
@ApplicationModule(displayName = "Consent")
package com.nemaz.school.consent;

import org.springframework.modulith.ApplicationModule;
