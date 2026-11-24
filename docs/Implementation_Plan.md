# Implementation Plan

*Version 1, 24 November 2026.*

This plan builds on three documents:
- [Education_Platform.md](Education_Platform.md), the product specification
- [Spec_Review.md](Spec_Review.md), with decisions D1–D7 confirmed
- [Database_Design.md](Database_Design.md), the schema

Section references: § is the specification, R-xx is a spec-review finding, and DB §x is a section of the database design.

---

## 0. How to read this plan

- **Order:** Phase 0 (foundation), then Phases 1–6, following the spec roadmap (§112–§117) with D4 applied: the payment core and video rooms move to Phase 3.
- **Phases contain milestones (M), and milestones contain numbered steps.** Steps within a milestone run in order. Milestones marked *parallel* can overlap.
- **Every feature milestone is a vertical slice.** It includes:
  - migration
  - domain and service
  - authorization policy
  - API
  - generated client
  - web UI
  - mobile UI
  - tests
  - English and Amharic strings
  - audit and analytics events
- **Estimates are rough.** They assume about 5 engineers: 2 backend, 1 web, 1–2 mobile, plus a part-time designer and QA. Re-plan once the team is known.

### Definition of done for every slice
1. The Flyway migration matches Database_Design and applies cleanly to a fresh database and to the previous release.
2. The service enforces authorization on the server, through the `authorization` module.
3. Each endpoint has an **allow/deny test** for every relevant role: student, teacher, subject teacher, guardian, restricted guardian, unrelated guardian, tutor, school admin, and admin of another school.
4. The OpenAPI spec is updated, the TypeScript client is regenerated, and CI fails if it has drifted.
5. Web and mobile screens are done in English and Amharic, with loading, empty, error and offline states, and meet accessibility basics.
6. Mutations write `audit_logs`. Domain events go to `outbox_events`. Notifications use `notification_types`.
7. Analytics events for the §133 release gates are emitted.
8. The module README describes its rules and settings keys.

### UI benchmark rule (ClassDojo)
ClassDojo is a reference for **patterns and quality** only:
- avatar-grid class view and one-tap points
- story feeds
- parent home focused on the child
- translate-in-place messaging
- the teacher toolkit
- a friendly, rounded, high-contrast visual style

We don't copy its trademarks, monster characters, illustrations, sounds or copy text. The product gets **its own** avatar system and brand (step 0.5).

---

## Phase 0 — Foundation (≈ 3–4 weeks)

Goal: every app builds, runs and deploys an empty "hello" release through CI, on the final architecture.

### M0.1 Developer environment (D1)
1. Install **JDK 25**. Create `~/.m2/toolchains.xml` with a `jdk` entry for version 25 that points at its home. Verify with `./mvnw -v` and `./mvnw verify` in `apps/backend`.
2. Correct the README so the toolchain setup is documented, not described as "already configured".
3. Add `infra/docker-compose.yml` with:
   - **PostgreSQL 18** (extensions `citext`, `btree_gist`, `pg_trgm`)
   - **Redis**
   - **SeaweedFS** (S3 API), as a local stand-in for Cloudflare R2. MinIO no longer publishes community Docker images
   - **Mailpit**, for email
4. Add `.env.example` files for each app. `.env` stays git-ignored.
5. Run `npm install` at the root. Confirm that `npm run dev`, `build`, `lint` and `test` work through Turborepo.
6. Read the versioned docs before writing any framework code, as `apps/web/AGENTS.md` and `apps/mobile/AGENTS.md` require:
   - Next.js 16: `node_modules/next/dist/docs/`
   - Expo SDK 58: docs.expo.dev/versions/v58.0.0
   - Spring Boot 4.2: reference documentation

### M0.2 Monorepo structure
1. Create the shared packages:

   | Package | Purpose |
   |---|---|
   | `packages/api-client` | TypeScript types and client generated from the backend OpenAPI spec, plus TanStack Query hooks |
   | `packages/design-tokens` | Colors, radii, spacing, typography and elevation as JSON, emitted as a Tailwind theme (web) and TS constants (mobile) |
   | `packages/i18n` | Message catalogs (`en`, `am`, then `om`, `ti`, `so`) and ICU formatting helpers |
   | `packages/calendar` | Pure-TS Gregorian↔Ethiopian conversion and formatting (§64). Display only |
   | `packages/validation` | zod schemas shared by web and mobile forms |
   | `packages/config` | Shared tsconfig and eslint presets |

2. Add Turborepo tasks: `codegen` (fetch the OpenAPI spec and generate the client), `typecheck`, and `dependsOn` wiring so apps build after packages.
3. Add a root `CLAUDE.md` / `AGENTS.md` with the conventions from this plan.

### M0.3 Backend skeleton (`com.nemaz.school`)
1. **Module layout** (§96, §132). One top-level package per module, each with `api` (controllers and DTOs), `application` (services), `domain` (entities and policies) and `infrastructure` subpackages:
   - Platform: `identity`, `authorization`, `tenancy`, `reference`, `people`, `academic`
   - Classroom: `attendance`, `engagement`, `media`, `social`, `messaging`, `communications`, `notifications`, `calendar`
   - Operations: `sis`, `portfolio`, `moderation`, `consent`, `audit`, `subscriptions`
   - Added in later phases: `curriculum`, `assessment`, `library`, `courses`, `video`, `payments`, `marketplace`, `finance`, `ai`
2. **Enforce module boundaries** with Spring Modulith, or ArchUnit if Modulith has no Boot-4.2-compatible release yet. Modules talk only through public application services and domain events.
3. **Shared kernel** (`common`):
   - RFC 9457 problem-details error model with stable error codes
   - cursor pagination
   - `Idempotency-Key` handling (`idempotency_keys`)
   - request id and MDC logging
   - clock abstraction
   - UUIDv7 generation
   - `TenantContext` (current user, active school, roles)
   - i18n of server messages
4. **Persistence:**
   - Flyway migrations under `db/migration`, named `V{phase}_{nn}__{module}.sql` as in DB §6.
   - JPA with `@Version` and `EnumType.STRING`. Hibernate's `ddl-auto` stays `validate`, never `update`.
5. **Outbox and jobs:**
   - Write `outbox_events` in the same transaction as the change.
   - A relay publishes them to in-process listeners and to Redis for fan-out.
   - Use a Postgres-backed scheduler (JobRunr or db-scheduler, whichever has Boot 4 support) for retries and scheduled work.
6. **OpenAPI:** springdoc, or the Boot-4-compatible equivalent, at `/v3/api-docs`. CI exports the spec into `packages/api-client`.
7. **Observability:**
   - Actuator health and readiness
   - Micrometer metrics
   - OpenTelemetry tracing
   - structured JSON logs
   - Sentry or an equivalent error tracker
8. **Tests:**
   - Testcontainers with Postgres 18 for integration tests
   - an architecture test for module boundaries
   - a baseline `contextLoads` test

### M0.4 Web skeleton (`apps/web`, Next.js 16)
1. Replace the starter page. Set up the App Router route groups:
   - `(marketing)`
   - `(auth)`
   - `(app)/[school]`, for teacher and admin
   - `(platform)`, for platform admin
2. Install **shadcn/ui** (§97) on Tailwind 4 and wire in `design-tokens`. Load the Noto Sans Ethiopic and Latin fonts.
3. **BFF auth pattern:** Next route handlers keep the access and refresh tokens in `httpOnly`, `Secure`, `SameSite=Lax` cookies. The browser never sees the tokens.
4. Set up i18n routing with the `packages/i18n` catalogs (next-intl or an equivalent that supports Next 16), RTL-safe layout primitives, and an Ethiopian calendar display toggle.
5. Add TanStack Query with the generated client, an error boundary, and toast and empty-state components.

### M0.5 Design system & brand (designer, parallel)
1. Build a token set inspired by the benchmark: bright primary, soft secondary palette, 12–20 px rounded radii, generous touch targets of 44 px or more, playful but legible typography in both Ethiopic and Latin scripts.
2. Create **original** student avatar characters and an avatar builder (`student_profiles.avatar_config`).
3. Design key screens in Figma first:
   - teacher class grid and award sheet
   - parent home with a child switcher
   - class story card
   - messaging with a Translate toggle
   - attendance roll
   - school-admin dashboard
4. Build a component library in web (shadcn-based) and mobile with matching names: Avatar, PointBubble, SkillTile, StoryCard, ChildSwitcher, EmptyState and RoleTabBar.

### M0.6 Mobile skeleton (`apps/mobile`, Expo SDK 58)
1. Remove the template screens. Create Expo Router groups `(auth)`, `(teacher)`, `(guardian)`, `(student)` and later `(tutor)`. A role switcher handles users with several roles (§6).
2. Install the needed packages with `npx expo install`:
   - secure token storage (`expo-secure-store`)
   - local database (`expo-sqlite`, for the offline store, M1.7)
   - `expo-notifications`
   - `expo-image-picker`
   - `expo-camera` (QR login)
   - `expo-localization`
3. Wire up the generated client, TanStack Query (with persistence for low-data), design tokens and i18n.
4. Set up the EAS project with `development`, `preview` and `production` profiles. Native modules need a development build.

### M0.7 CI/CD & environments
1. Set up GitHub Actions:
   - **backend:** JDK 25, `mvnw verify`, Testcontainers
   - **web:** lint, typecheck, build
   - **mobile:** lint, `tsc --noEmit`, `expo-doctor`
   - **contract:** an OpenAPI drift check
   - **migrations:** apply to an empty database, then to the previous release
2. Create `dev`, `staging` and `prod` environments:
   - managed Postgres 18
   - container hosting for the backend
   - web host for Next
   - Cloudflare R2, CDN and WAF
   - secrets in the platform secret store
3. Write the deploy pipeline (main → staging, tag → prod) and the EAS build and OTA update channels.
4. Set up nightly backups and run a restore drill, documented in a runbook.

**Phase 0 exit:** a health endpoint is deployed to staging. Web and mobile apps sign into a stub endpoint through the generated client. CI is green.

---

## Phase 1 — School & family MVP (≈ 7–9 months)

Scope: §112. Milestones M1 and M2 are the platform core and come first. From M3 onward, backend, web and mobile work on each slice in parallel.

### M1 Platform core (≈ 4 weeks)
Tables: DB §4.1–4.3, §4.19, settings and features in §4.2, plans in §4.20.

1. **Reference data migration and seed:**
   - currencies, languages and calendar systems
   - Ethiopia (ET) enabled and Kenya (KE) as a draft
   - Ethiopian administrative levels 1–2
   - notification types, roles and permissions
   - setting and feature definitions
   - subscription plans
2. **Tenancy:** organizations, schools, campuses and school languages. Platform-admin APIs to create a school and assign a plan manually.
3. **Settings and feature-flag resolver:**
   - Resolution order: school → organization → country → platform → default.
   - Values are validated against `value_schema`.
   - Entitlement check: a feature is on only if the plan includes it and the flag enables it (R-33).
   - Results are cached in Redis and invalidated on change.
4. **Identity:**
   - users and password credentials (argon2id)
   - phone OTP via an SMS provider abstraction, with a console provider in dev
   - email verification
   - federated sign-in (Google and Apple) behind a flag
   - lockout and rate limits (Redis)
5. **Tokens:**
   - access JWT valid for about 15 minutes
   - rotating refresh tokens stored as hashes in `auth_sessions`, with reuse detection by token family
   - device sign-out and sign-out everywhere
6. **Authorization module:**
   - a `can(user, action, resource)` API, plus SQL predicate builders for list queries
   - relationship resolvers: class membership, guardianship, role scope (DB §5)
   - deny by default
   - `@PreAuthorize`-style annotations that delegate to policies
   - a reusable **authorization test harness** that builds a fixture school with every persona
7. **Audit:** an `audit_logs` partitioned table with monthly partition creation, a write API, and logging of sensitive reads (e.g. `student.view`, `export`).
8. **Web:** sign-in (phone OTP or password), school picker, platform-admin console (countries, schools, plan assignment, feature flags).
9. **Mobile:** sign-in screens, role routing, session persistence.

**Exit:** a platform admin creates a school, and a school admin signs in on web and mobile. The authorization harness runs in CI.

### M2 Media, notifications & real-time infrastructure (≈ 3 weeks, parallel with late M1)
Tables: DB §4.9 and §4.13.

1. **Media:**
   - signed upload URL (`PENDING_UPLOAD`), then client upload straight to R2 (SeaweedFS locally), then a completion callback
   - the job checks MIME type and size, then makes variants: image thumbnails, compressed images, video poster plus a low-bitrate rendition, PDF preview
   - object keys come from `purpose`
2. **Signed read URLs:** issued only through the owning object's authorization (R-30), and short-lived.
3. **Notification service:**
   - one `notify(type, recipients, payload)` API
   - writes the in-app `notifications` row, then fans out to channels by preference
   - quiet hours, with URGENT bypass only for permitted types
   - delivery tracking in `notification_deliveries`
4. **Push:** `push_devices` registration from mobile, and FCM HTTP v1 for Android and iOS (via APNs). A provider abstraction allows swapping later.
5. **Real-time gateway:**
   - WebSocket endpoint (STOMP), authenticated with the access token
   - per-user queues
   - Redis pub/sub so it works across several instances
   - used for messages, typing and live notification badges (§92)
6. **Web and mobile:** notification inbox, unread badges, preferences screen, push permission flow.

### M3 School setup, people & classes (≈ 3 weeks)
Tables: DB §4.4 (without tutor) and §4.5.

1. Academic years and periods, grade levels, subjects, campuses: admin CRUD with validation (no overlapping periods; one current year).
2. **Staff:** invite a teacher by phone or email (`invitations`), accept, create `teacher_profiles` and `school_staff`, assign admin roles through `role_assignments` (CAMPUS_ADMIN and SCHOOL_STAFF included).
3. **Students:**
   - create managed accounts (`account_type = MANAGED`, R-31) and `school_students`
   - optional username and password for older students
   - **login codes:** generate, print and revoke QR and text codes per class sheet (D6)
4. **Classes:**
   - a teacher or admin creates a class (ClassDojo-style quick create: name, grade, icon)
   - add students from the school roster or create them inline
   - co-teachers and subject teachers through `class_teachers.role` and `subject_id` (R-07)
   - move students between classes, which ends one membership and starts another (§12)
5. **School-admin web:**
   - Dashboard
   - Students
   - Teachers
   - Classes
   - Academic Year
   - Settings
   - Feature Configuration (§78)
6. **Teacher mobile and web:** "My classes" list, class roster, add-students flow, student profile showing their avatar.
7. **Student mobile:** QR or text-code sign-in, home screen with their classes.

**Exit:** a pilot school's structure can be set up by hand, and students can sign in with codes.

### M4 Roster import (≈ 2 weeks, parallel with M5)
Tables: DB §4.15.

1. Upload a CSV or XLSX file (stored as `media`, purpose IMPORT_FILE). The backend parses it with Apache POI for XLSX and a CSV parser for CSV.
2. Column mapping UI with saved `import_mapping_profiles`, and downloadable templates for each import type.
3. **Dry run:**
   - normalize data (phone numbers to E.164, dates, names)
   - validate each row
   - detect duplicates through `external_entity_mappings`, then student number, then name and date of birth
   - preview the create/update/skip counts
   - produce a downloadable error report
4. **Commit:**
   - replay a validated dry run idempotently inside a job
   - record counts and changes
   - guardian contacts create **invitations only**, never active links (§125)
5. Import order: academic years → subjects → classes → staff → students → enrollments → guardian contacts.
6. Run with real data from the pilot schools before launch.

### M5 Families: guardian linking, permissions & consent (≈ 3 weeks)
Tables: `student_guardians` and `invitations` from DB §4.4, plus DB §4.18 (policy documents, versions and consent records).

1. **Invitation flows:**
   - teacher or admin sends a parent invite by SMS or email from the roster
   - printed parent codes per student (ClassDojo-style home connection)
   - accepting creates a PENDING link
2. **Verification:**
   - a school admin queue to approve or reject links, recording `verification_method`
   - optional auto-verification via the setting `guardians.auto_verify_invite`
3. **Permissions UI:**
   - per-guardian flags
   - `custody_restricted` with a note, visible to admins only
   - multiple guardians per child
4. **Consent:**
   - policy documents and versions for ACCOUNT_PARTICIPATION and MEDIA_PUBLICATION, in each school language
   - consent prompt during guardian onboarding
   - admins can record paper consent
   - a "consent status" column on rosters
   - an API `hasConsent(student, type)` used by M8
5. **Guardian mobile onboarding:**
   - accept invite
   - verify phone with OTP
   - choose language (§111)
   - give consent
   - see the child switcher
6. Notifications: GUARDIAN_LINK_APPROVED and CONSENT_REQUESTED.

**Exit:** a parent can link to a child, give consent, and see that child's classes, and only those. Allow/deny tests pass for unrelated and restricted guardians.

### M6 Classroom engagement: points, badges & toolkit (≈ 3 weeks)
Tables: DB §4.7, §4.8, and `student_groups` in §4.5.

1. **Point categories:**
   - school templates are seeded, and classes can add their own categories
   - POSITIVE and NEEDS_WORK polarity, with needs-work on by default and a school toggle (D2)
   - HOME categories for guardians
2. **Award API:**
   - one student, several students, or the whole class (`award_batch_id`)
   - offline-safe through `client_op_id`
   - the teacher must be ACTIVE in the class; a guardian must have `can_award_points` and can award only in HOME context (DB CHECK)
3. **Reversal and undo:** creates a REVERSAL row. There is a short "undo" window in the UI.
4. **Balances projection:**
   - updated from outbox events
   - a nightly rebuild job checks it against the ledger
   - **home points are excluded from class and school aggregates** (D2)
5. **Badges:** a rule engine on point and attendance events, evaluating CLASS and SCHOOL context only. Manual badges. Notifications.
6. **Teacher class view (mobile and web), the signature screen:**
   - avatar grid with point bubbles
   - tap opens the award sheet with skill tiles
   - multi-select and whole-class award
   - feedback sound and animation (original assets)
7. **Toolkit (client-side):**
   - timer and stopwatch
   - random student picker
   - group maker, with an option to save to `student_groups`
   - routines and checklists (`classroom_activity_templates`)
8. **Student and parent views:**
   - the child's points timeline and donut chart by category
   - the parent sees school points and home points as separate sections
9. **Reports:** points by student, category and date range for teachers and admins, with CSV export (audited).

### M7 Attendance with offline sync (≈ 3 weeks)
Tables: DB §4.6.

1. **Attendance API:** take or update a roll (`attendance_sessions` plus `attendance_records`). Slots are configured per school. Sessions lock after a configurable window.
2. **Sync endpoint:**
   - a batch of operations, with `(device_id, client_operation_id)` for idempotency
   - `base_version` for conflict detection
   - returns the result for each operation (APPLIED, DUPLICATE, CONFLICT, REJECTED) and the server state
3. **Mobile offline store (SQLite):**
   - caches the roster, today's attendance, recent points, recent messages metadata, and calendar and course metadata (§72)
   - a sync queue with retry and backoff
   - conflict UI for the rare case where two teachers mark the same student
4. **Quick attendance UI:** everyone present by default, tap to cycle status, late minutes, notes.
5. **Absence reports:** a parent reports an absence ahead of time, it shows on the roll, and the teacher acknowledges it.
6. **Notifications:** ATTENDANCE_MARKED to guardians, absent and late only by default, configurable per school.
7. **Admin:** daily attendance dashboard and summary reports.
8. **Tests:** offline replay, duplicate submits, two-device conflicts, timezone and date boundaries.

**Exit:** a teacher in airplane mode marks attendance and awards points. Everything syncs correctly when the device reconnects.

### M8 Class Story, School Story & feed (≈ 3 weeks)
Tables: DB §4.10.

1. **Posts:**
   - CLASS and SCHOOL scope
   - text plus several media items
   - student tagging (`post_student_tags`)
   - scheduling and pinning
2. **Consent gate (R-09):** publishing fails, with a clear message, if a tagged student lacks MEDIA_PUBLICATION consent. Untagged media needs the teacher to confirm that no unconsented students appear.
3. **Contributions (§18):**
   - parent and student submissions, controlled by school settings: on/off, approval required, allowed media, scope
   - an approval queue for teachers and admins
4. **Stories:** 24-hour stories for a class or school, view tracking, an expiry job.
5. **Interactions:**
   - comments (toggle per post)
   - one reaction per user
   - a "seen by" count
   - report content (M13)
6. **Translation (D5):**
   - a "See translation" action calls a translation provider adapter and caches the result in `content_translations`, labelled "Machine translated"
   - the **adapter interface and cache ship now**; the provider is switched on with a feature flag once it's chosen and its quality is checked for am, om, ti and so
7. **Feeds:**
   - teacher: class feed
   - parent home: a combined feed across their children's classes and schools, filtered by child
   - student: class feed
   - all paginated with cursors, server-filtered (§100), and using low-data image variants

### M9 Messaging (≈ 3–4 weeks)
Tables: DB §4.11.

1. **Conversation types:** DIRECT, GROUP, CLASS and SCHOOL. A parent↔teacher thread carries `context_student_id`.
2. **Policy engine:**
   - checks relationships (§29) and the school's `messaging.*` settings (D3)
   - applies the **safeguarding floor:** adult↔student direct messages add guardian observers or give admins visibility, and are audited
   - honours `user_blocks`
3. **Sending:**
   - an idempotent send API (`client_message_id`)
   - WebSocket delivery
   - push to offline members, respecting quiet hours and teacher hours
   - scheduled messages
4. **Read state:** the `last_read_message_id` watermark, plus per-message receipts in DIRECT conversations ("Seen").
5. **Content:**
   - attachments through media
   - reactions
   - edit and delete with history
   - translate in place, the same as M8
6. **Groups:** class parent groups and staff groups. Who can create and moderate them comes from settings (§31).
7. **Mobile:**
   - inbox grouped by child for parents
   - offline outbox for messages
   - voice notes behind a flag

### M10 School communications centre (≈ 2 weeks)
Tables: DB §4.12.

1. Compose an announcement with an explicit audience: school, campus, grade, class or specific users, and target roles.
2. Draft, schedule, publish, edit (with revisions) and withdraw. The audit trail is complete.
3. Resolve recipients at publish into `announcement_recipients`, reaching guardians through their children.
4. Delivery through the notification service: in-app and push at launch. Email and SMS through adapters for each country, behind flags (§125).
5. Read and acknowledgement tracking is kept separate from delivery. An admin report shows delivered, failed, read and acknowledged, with a "remind unacknowledged" action.
6. URGENT and SAFETY types use their own quiet-hours policy.

### M11 Calendar & events (≈ 1–2 weeks)
Tables: DB §4.14.

1. Native events with scope, recurrence rules (RRULE) and RSVP. Parent-meeting events have `regarding_student_id`.
2. The calendar API combines native events with holidays and academic period boundaries (and later assignments, sessions and tutoring) and applies access filtering (R-17).
3. Month, week and agenda views on web and mobile, with a Gregorian or Ethiopian display toggle that uses `packages/calendar`.
4. UPCOMING_EVENT reminders through scheduled jobs.

### M12 Student portfolios — basic (≈ 2 weeks)
Tables: DB §4.16 (without collections).

1. **Students create items:** photo, drawing (an in-app canvas), document scan, text, short video or audio, with a reflection field.
2. **Teacher quick capture** from the class view (§126).
3. **Review workflow:** submitted → approved, changes requested or rejected, with history in `portfolio_approvals`.
4. **Visibility:** resolved from school settings and item visibility. Guardians see approved items and can leave encouraging comments.
5. Approved items can optionally be shared to the class story. This needs consent and approval.

### M13 Moderation, safeguarding & platform admin (≈ 2–3 weeks)
Tables: DB §4.17.

1. A "Report" action on posts, comments, stories, messages and users creates `content_reports`, which are grouped into `moderation_cases`.
2. **Moderation queues:** one per school for school admins, and one for the platform (PLATFORM_MODERATOR). Decisions include hide, remove, warn and suspend, with history in `moderation_actions`.
3. **Safeguarding incidents:**
   - restricted access for SAFEGUARDING_LEAD
   - encrypted narrative
   - notes
   - escalation from a case
   - every read is audited
4. **Platform admin:**
   - users (search, lock, unlock)
   - schools
   - plans
   - flags
   - an audit log viewer
   - support view (read-only, audited)
   - system health

### M14 Localization, low-data & release-gate instrumentation (≈ 2 weeks, ongoing from M3)
1. Complete Amharic UI strings and get them reviewed by native speakers. Add Afaan Oromo, Tigrinya and Somali catalogs as translations become available, with a per-school language list (§110).
2. **Low-data pass:**
   - image variants everywhere
   - pagination limits
   - compressed requests
   - "data saver" mode (no autoplay, thumbnails only)
   - measure payload sizes on 3G profiles
3. **Analytics events for the §133 gates:**
   - weekly active teachers
   - classes created
   - import success
   - guardian link completion
   - announcement read rate
   - attendance completion time
   - offline sync success
   - report response time

   Send them to a privacy-respecting analytics store. Analytics are not used to profile children.
4. **Accessibility:** screen-reader labels, dynamic type, colour contrast.

### M15 Hardening & pilot launch (≈ 3–4 weeks)
1. **Security:**
   - threat model
   - dependency and container scanning
   - OWASP ASVS L2 checklist
   - external penetration test focused on authorization, IDOR and tenant isolation
2. **Authorization audit:** generate the role × endpoint matrix from the tests, then review it manually against DB §5.
3. **Load test:** pilot-scale traffic plus 10× headroom, covering feed, messaging and attendance sync. Tune indexes and set up partitioning.
4. **Privacy:**
   - publish the privacy notice
   - review data processing agreements (R2, FCM, SMS and translation providers)
   - review Ethiopian data-protection requirements with counsel (§131)
5. **Operations:** runbooks, on-call, alerting, backup and restore drill, incident process.
6. **Launch:**
   - app store submissions (EAS)
   - staged rollout
   - pilot schools onboarded with training material for teachers and parents in Amharic and English
7. Review the release gates after 4–6 weeks of pilot use before starting Phase 2 (§112 says: prove regular use first).

---

## Phase 2 — School learning (≈ 4–5 months)

Scope: §113, §124 (full) and §127. Tables: DB §4.21–4.23, plus the P2 columns (`grade_levels.academic_level_id`, `subjects.curriculum_subject_id`, `schools.education_system_id`).

### M16 Curriculum foundation
1. Education systems, curriculum versions, levels, subjects, topics and objectives. A platform-admin editor, plus CSV import of the Ethiopian national curriculum (with source, license and owner recorded).
2. A school adopts a curriculum version and maps its grades and subjects to it.
3. A tagging component (pick objectives) that can be reused in assignments, assessments, questions, lessons, portfolio items and resources.

### M17 Assignments & submissions
1. Assignments scoped to a class (and later a course), with attachments, targets, scheduling, due dates and late policy.
2. Student submissions: text, photos and files. Offline drafts. Guardians can help young children submit (`submitted_by`).
3. Teacher review: return, request resubmission, feedback. Notifications for NEW_ASSIGNMENT and returned work. Due dates appear on the calendar.

### M18 Assessment, grading & quizzes
1. Grading schemes and bands (templates per country, overrides per school), and rubrics.
2. Assessments: offline exams and participation, or graded assignments or quizzes. A gradebook grid on web with keyboard entry. Subject teachers are limited to their own subject.
3. **Shared quiz engine:** question types, attempts, auto-grading, and manual grading for essays. Correct answers are never sent to students before results are released.
4. Result publication, `period_grades` computed from weights, teacher overrides, report-card PDF generation (`report_jobs`), and parent access to published results.
5. Learning evidence comes from results. Mastery is recorded only by teachers (§127).

### M19 Digital library
1. Resources with ownership, licence and a rights attestation (§35). A takedown workflow through moderation.
2. Access rules (R-06), evaluated on the server. Browse by grade and subject, with search (`pg_trgm`).
3. Reader and viewer (PDF, audio, video) with low-data streaming. Downloads only where allowed.

### M20 Portfolios (full) & teacher preparation
1. Collections, objective tags, formal teacher feedback with rubrics, and featured work.
2. Lesson preparation templates (`classroom_activity_templates`, LESSON_PREP) linked to objectives.

### M21 Data governance
1. Retention policies and jobs, legal holds, data-subject export and deletion requests, and an admin UI for them (§131).

---

## Phase 3 — Online learning (≈ 4 months)

Scope: §114, plus the payment core and video moved here by D4. Tables: DB §4.24–4.26.

### M22 Payment core (moved from Phase 5)
1. A `PaymentService` provider abstraction (§61). The first Ethiopian provider adapter is chosen after commercial evaluation (for example Chapa or telebirr).
2. Orders and order items, then payments, then webhooks. Webhook intake is idempotent and verifies signatures. A status-polling fallback handles providers whose webhooks aren't reliable.
3. A reconciliation report (manual at first), and a sandbox mode for test environments.

### M23 Courses & enrollment
1. **Course builder:** sections, lessons (video, text, PDF, audio, quiz, assignment, live), materials, preview lessons.
2. Ownership and audience rules, including the rule that SCHOOL and CLASS audiences mean the course is school-owned (R-20). Enrollment policies and pricing are separate fields.
3. **Enrollment flows:**
   - open
   - approval required (§42: request → teacher approves)
   - invite
   - school-assigned
   - paid (order → payment → ACTIVE)
4. Course player with lesson progress, offline download where allowed, and completion.
5. Course discussions (COURSE-scoped posts) and learner notifications.

### M24 Video & live sessions
1. A video adapter (Agora) and `video_rooms`. Short-lived join tokens are issued only to the host and ACTIVE enrolled students. Each issue is audited.
2. Live session scheduling, a calendar entry, reminders, and join from web and mobile. Attendance is recorded in `course_live_participants`.
3. Recording only after every participant's RECORDING consent has been checked (R-35). A visible indicator. A stop control. Retention policy. The recording is published to enrolled students as a lesson.

---

## Phase 4 — Tutor marketplace (≈ 4–5 months)

Scope: §115 and §128. Tables: DB §4.27, plus `tutor_profiles` and `data_sharing_grants`.

### M25 Tutor onboarding & verification
1. Create a tutor profile. A teacher can opt in with a **separate** profile (§40). Subjects, languages, qualifications, intro video.
2. Verification requirements for each country, document upload, a platform review queue, and status changes. Only VERIFIED tutors can be listed.

### M26 Services, availability & discovery
1. Services (one-to-one or small group, online or in person, trial), cancellation policies.
2. A weekly availability pattern plus exceptions. A slot generator that respects notice period, booking window and existing sessions.
3. **Discovery:** filters (§48) and **transparent matching** (`tutor_match_requests` and `results` with factor explanations, §128). Nothing from school records is used in discovery.

### M27 Booking, sessions & safety
1. **Booking state machine (R-22):**
   - request
   - guardian approval for minors
   - payment (M22)
   - confirmed: session created or joined, with the exclusion constraint preventing double booking
   - reschedule, cancel (policy-based refunds are manual until M30), no-show, complete
2. Tutoring relationships and guardian **data-sharing grants** (R-21). Tutoring conversations follow the messaging policy, with guardian observers for minors.
3. Session delivery: video rooms for online sessions, location for in-person ones. Attendance. Session notes with visibility.
4. **Trials:** a post-trial assessment shared with the family. Follow-up booking or a series.
5. Reviews tied to completed bookings. A report flow into moderation.

---

## Phase 5 — Marketplace finance, tutor insights & billing (≈ 3–4 months)

Scope: §116. Tables: DB §4.28, plus subscription billing.

### M28 Commissions, earnings & payouts
1. Platform commission and tutor earnings created on order payment. They become available after the session completes and a hold period passes.
2. Payout accounts (tokenized), a payout batch job, payout records and a tutor earnings dashboard.

### M29 Refunds & reconciliation
1. Policy-driven refunds (cancellation, no-show, dispute), provider refund calls, and reversal of commissions and earnings.
2. Automated reconciliation against provider reports, with an exception queue.

### M30 Tutor insights & parent tutoring history
1. Tutor dashboard: upcoming and completed sessions, cancellations, earnings, reliability metrics. These are kept separate from teaching-quality signals and are not punitive (§128).
2. Parent view: tutoring history, shared notes, progress summaries.

### M31 Subscription billing & school fees (D7)
1. Automated school subscription billing (plan prices per country), usage limits from `plan_entitlements`, dunning.
2. **School fees:** fee items, invoices, invoice lines, guardian payment through the payment core, receipts, an arrears report. This domain stays separate from marketplace orders.

---

## Phase 6 — AI Tutor Copilot & Student AI Tutor (≈ 4–6 months)

Scope: §117 and §129–§131. Tables: DB §4.29, plus `session_transcripts`. The model provider is chosen at this point.

### M32 AI platform & governance
1. An AI gateway service:
   - model routing (`ai_model_configs`)
   - versioned prompt templates
   - data minimization (references, not raw records)
   - logging to `ai_generations`
   - usage metering and quotas, kept separate from billing
2. Benchmark sets and evaluation runs for each subject × language. Enablement records gate every combination (§130). A kill switch and suspension.
3. AI_PROCESSING and TRANSCRIPTION consent types. AI-output reports flow into moderation.

### M33 Tutor Copilot
1. Before a lesson: lesson plan, questions and activities from curriculum objectives and authorized notes.
2. During a lesson (optional): tutor-only prompts. Transcription only with consent and a visible indicator. No emotion inference.
3. After a lesson: summary, homework suggestions and a parent-report draft. **The tutor approves before anything is shared or recorded** (`tutoring_session_notes.ai_generation_id`).

### M34 Student AI Tutor
1. A guided-learning chat (explain, hint, practice), grounded in curriculum and library content, with citations and clearly labelled AI-generated content.
2. Practice attempts feed `learning_evidence`, never mastery. Teacher and guardian activity view. Session limits, age-appropriate filters, escalation.
3. Launch with Mathematics, Science and Reading in Amharic and English only, after benchmarks pass (§130). Expand only through the release gates (§133).

---

## Cross-cutting tracks (run throughout)

| Track | Practice |
|---|---|
| **Security** | Deny-by-default authorization. Tenant-isolation tests for every endpoint. Rate limits. Secrets in a vault. Dependency scanning. Yearly external pen test, plus one before each phase launch. |
| **Testing** | Unit tests for domain policies. Integration tests with Testcontainers. Authorization allow/deny matrix. Contract tests against the generated client. Playwright end-to-end for web. Maestro end-to-end for mobile. Offline and sync scenario tests. |
| **Performance** | p95 API < 300 ms for feed, inbox and roster. Mobile cold start < 3 s on a mid-range Android. Low-data budgets per screen. |
| **Localization** | Every string in catalogs. Native-speaker review for each language. Ethiopian calendar display. Country-labelled name and address fields (R-37). |
| **Accessibility** | WCAG 2.2 AA target on web. Platform accessibility APIs on mobile. |
| **Observability** | Traces across web/mobile → API → jobs. Dashboards for sync success, notification delivery, payment webhooks. |
| **Documentation** | Module READMEs. ADRs for major choices (auth, jobs, offline store, providers). Runbooks. A seed script for a demo school. |
| **Compliance** | Consent coverage reporting. Data processing agreements with every processor. Country legal review before each new market (§131). |

---

## Provider decisions to make (and when)

| Decision | Needed by | Recommendation |
|---|---|---|
| Cloud and hosting region | M0.7 | Managed Postgres plus containers, in a region with low latency to Ethiopia. Cloudflare in front |
| SMS / OTP provider (Ethiopia) | M1 | Evaluate local aggregators on delivery rate and price. Keep it behind the adapter |
| Push | M2 | FCM HTTP v1 (covers iOS through APNs) |
| Machine translation (D5) | M8 | Run a blind quality test for am, om, ti and so on real school messages, then enable the winner with a flag |
| Analytics store | M14 | Self-hosted or EU-region product analytics with no child profiling |
| Payment provider(s) | M22 | One Ethiopian provider first (§119: don't build many providers initially) |
| Video | M24 | Agora, as in §56 |
| AI model provider | M32 | Choose after evaluation on benchmark sets |

---

## Timeline summary (rough, team of ~5)

| Phase | Duration | Cumulative |
|---|---|---|
| 0 Foundation | 3–4 weeks | ~1 month |
| 1 School & family MVP, then pilot | 7–9 months | ~9–10 months |
| Pilot evaluation window | 1–2 months (Phase 2 design runs in parallel) | |
| 2 School learning | 4–5 months | |
| 3 Online learning (payments core, video) | ~4 months | |
| 4 Tutor marketplace | 4–5 months | |
| 5 Finance, insights, billing, school fees | 3–4 months | |
| 6 AI | 4–6 months | |

Phase 1's critical path is M1 → M3 → M5 → M8/M9, because guardian linking and consent gate the feed and messaging. M4, M6, M7, M10–M12 run in parallel tracks once M3 is done.
