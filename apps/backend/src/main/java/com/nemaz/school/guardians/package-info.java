/**
 * Guardians (Implementation Plan M5, Database_Design §4.4, R-10, R-29, spec §11, §102, §131): which adult may see
 * which child.
 * <ul>
 * <li><b>Links</b> ({@code student_guardians}) are global (a child keeps its parents across schools) and verified by a
 * school. Only an ACTIVE link without {@code custody_restricted} grants anything; PENDING, REJECTED and REVOKED links
 * grant nothing; a custody restriction overrides every permission flag.</li>
 * <li><b>Invitations</b> (SMS or email link, printed parent code) let an adult ask for a link. An invited phone or
 * email never proves guardianship: accepting creates a PENDING link that a verifier approves, unless the school
 * auto-verifies its own SMS/email invites accepted from the invited destination.</li>
 * <li><b>Access for other modules</b> goes through {@link com.nemaz.school.guardians.GuardianAccess}; the class view
 * rule is a {@code academic.ClassAccessRule} bean (guardians see their child's classes, never the roster), and
 * {@code GET /me} memberships gain GUARDIAN entries ({@code identity.AccountMemberships}).</li>
 * </ul>
 * The accept-with-one-time-code flow is hosted by identity (it signs people in) through its
 * {@code InvitationRedeemer} port, implemented here.
 */
@ApplicationModule(displayName = "Guardians")
package com.nemaz.school.guardians;

import org.springframework.modulith.ApplicationModule;
