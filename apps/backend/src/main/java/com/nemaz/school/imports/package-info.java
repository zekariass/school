/**
 * Roster import (Implementation Plan M4, Database_Design §4.15, spec §125): an administrator uploads a CSV or XLSX
 * file of students, staff, classes, subjects, grade levels or enrollments, maps its columns, checks it in a dry run
 * (normalized values, per-row errors, possible duplicates) and commits it as an idempotent background job.
 * <ul>
 * <li>The file is media of purpose {@code IMPORT_FILE}; this module registers its access policy. Raw and normalized
 * rows (personal data of children) live only in {@code import_job_rows} and are purged on a schedule; they never
 * reach logs, audit rows or events.</li>
 * <li>People and academic data are written through {@code people.PeopleImports} and
 * {@code academic.StructureImports} (plus {@code academic.ClassMemberships}): the same services, events and audit
 * as the manual screens. A roster file never grants an administrative role and never creates a guardian: contacts
 * named in it wait in {@code guardian_contact_candidates} until M5 turns them into invitations (R-29).</li>
 * <li>Dry run and commit are Modulith events handled by idempotent listeners; every state lives in
 * {@code import_jobs}, so a redelivered or resubmitted event, or a crashed worker, never applies a row twice.</li>
 * </ul>
 */
@ApplicationModule(displayName = "Roster import")
package com.nemaz.school.imports;

import org.springframework.modulith.ApplicationModule;
