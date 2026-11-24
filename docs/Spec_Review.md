# Specification Review — Education_Platform.md

*Review of the 20 November 2026 working specification, done on 24 November 2026 before the database design.*
*Companion document: [Database_Design.md](Database_Design.md). Each finding below names the resolution the database design adopts.*

Section references (§) point to `Education_Platform.md`. Severity tells you how much the finding affects the schema:

- **High**: building the spec as written would give the wrong data model or an authorization hole.
- **Medium**: contradictions or ambiguity that must be settled before that module is built.
- **Low**: naming and clarity issues.

Items marked **⚑ Decision** need your confirmation. The design already applies the default shown.

---

## 1. High-severity findings

### R-01 Students are modeled as school-owned, but they must exist outside schools
- **Conflict:** §94 lists a `students` table, and §98 puts tenant isolation on `students.school_id`. That makes a student a row owned by one school. Elsewhere the spec contradicts this:
  - A student "finds a tutor" (§2).
  - There is one global identity with a student *profile* (§6).
  - "Independent students/parents can discover tutors" (§48).
  - Students move between classes and schools (§12).
- **Risk:** A learner who is only in the marketplace has no school to belong to. A transfer would force a duplicate student record and break global identity.
- **Resolution:** Split the concept in two.
  - `student_profiles` is global, one per user.
  - `school_students` is the per-school enrollment record. It carries `school_id`, the student number and the external SIS id.
  - Tenant isolation applies to `school_students`, not to the person.

### R-02 "School membership" is the root of the access model but has no table
- **Conflict:** §8 and §121 make SCHOOL MEMBERSHIP the first branch of the access model. §6 separates identity, role, membership, relationship and permission. But §94 only has `user_roles`, which has no scope: it can't say *which* school a user is SCHOOL_ADMIN of.
- **Resolution:**
  - `school_staff` records teacher and staff employment per school.
  - `school_students` records student enrollment per school.
  - `role_assignments` holds administrative roles with an explicit scope (platform, country, organization, school or campus).
  - Guardian access to a school is **derived** through the child (§11). There is no guardian membership row.

### R-03 It's unclear what `student_id`, `teacher_id`, `parent_id` and `tutor_id` point to
- **Conflict:** `class_teachers.teacher_id` (§10), `point_transactions.student_id` (§21), `booking.parent_id/tutor_id` (§54) and `tutor_reviews.tutor_id` alongside `reviewer_user_id` (§58) mix references to profile ids and user ids. The spec never says which is which.
- **Resolution:** Profile tables use a **shared primary key**: `student_profiles.user_id = users.id`. A `student_id`, `teacher_id`, `guardian_id` or `tutor_id` is therefore always the user's id, constrained to a user who has that profile. This removes the ambiguity and keeps one identity.

### R-04 Small-group tutoring contradicts "a confirmed booking creates a session"
- **Conflict:** §55 gives `tutoring_session.booking_id`, so one booking maps to one session. But §52 and §115 support SMALL_GROUP, and §94 already has `tutoring_participants`. A group session has several bookings: one per learner or family.
- **Resolution:** `tutoring_sessions` stands on its own and has a tutor time range. Many `tutor_bookings` reference one session, and participants are listed in `tutoring_participants`. Tutor double-booking is prevented at the session level with an exclusion constraint.

### R-05 Booking points at a payment, but payments hang off orders
- **Conflict:** §54 has `booking.payment_id`. §59 defines the flow as Customer → **Order** → Payment, and §94 has `orders`. A booking can also be paid in several attempts, and refunded.
- **Resolution:** `tutor_bookings.order_id` points to `orders` and `order_items`. `payments` belong to an order (or to a school-fee invoice). Retries and refunds attach to the payment.

### R-06 Several "mode" enums mix independent dimensions
- **Conflict:**
  - **Course enrollment modes (§41):** `PUBLIC, INVITE_ONLY, APPROVAL_REQUIRED, SCHOOL_ONLY, CLASS_ONLY, PAID, FREE` mixes three things: *who may see the course*, *how enrollment is approved* and *price*. A course can be CLASS_ONLY **and** APPROVAL_REQUIRED **and** PAID at the same time.
  - **Library access (§34):** `PUBLIC … PRIVATE, PAID` mixes audience with commercial terms and ownership.
- **Resolution:**
  - Courses get three separate fields: `audience` (PUBLIC / SCHOOL / CLASS / INVITE_ONLY), `enrollment_policy` (OPEN / APPROVAL_REQUIRED / INVITE_ONLY) and `pricing_model` (FREE / ONE_TIME).
  - Library resources get any number of `resource_access_rules` rows, and any matching rule grants access. "PRIVATE" means no rules, so only the owner has access. Paid access comes through purchase, never through the rule enum.

### R-07 Subject teachers aren't modeled, and TEACHER_ASSISTANT is defined twice
- **Conflict:**
  - §15 says a class has subjects. In Ethiopian upper-primary and secondary schools, each section (8A) is taught by several subject teachers. But §10 `class_teachers` has no subject, so a Mathematics teacher assigned to 8A would get full access to everything in 8A.
  - TEACHER_ASSISTANT appears both as a global user type (§7) and implicitly as `class_teachers.role` (§10).
- **Resolution:**
  - `class_teachers.role` is one of LEAD / CO_TEACHER / SUBJECT_TEACHER / ASSISTANT, with an optional `subject_id`.
  - TEACHER_ASSISTANT is removed as a global user type. It becomes a class-level role.
  - Authorization can narrow subject teachers by subject where that matters, such as gradebooks.

### R-08 The phase order makes later dependencies arrive too late
- **Conflict:**
  - Phase 3 (§114) ships **live** and hybrid courses, but managed video only arrives in Phase 4 (§115).
  - Phase 3 has "course pricing and payments", and Phase 4 has "parent-led booking **and payment**". But orders, payments, reconciliation and refunds are only built in Phase 5 (§116).
- **Resolution (⚑ Decision D4):**
  - Move the **video room abstraction** into Phase 3.
  - Move the **payment core** into Phase 3: orders, payments and one country provider adapter with webhooks.
  - Phase 5 keeps refund automation, commissions, tutor earnings, payouts, reconciliation tooling and school-fee billing.

### R-09 Phase 1 publishes children's media, but consent has no phase
- **Conflict:** Phase 1 (§112) includes class feed photos and videos, stories and portfolio uploads. §131 requires media-publication consent to be captured and versioned, but §131 isn't assigned to any phase.
- **Resolution:**
  - Phase 1 includes `policy_documents`, `policy_versions` and `consent_records` for **account participation** and **media publication**.
  - Posts tag students (`post_student_tags`), so the backend can block publishing media of a student whose guardian has refused media consent.
  - Recording, transcription and AI consent types are added later, in the phases where those features ship.

### R-10 PARENT and GUARDIAN are two user types, but there is one profile
- **Conflict:** §7 lists both PARENT and GUARDIAN as user types. §6 and §94 have a single "Parent/Guardian Profile" and `guardian_profiles`.
- **Resolution:** One `guardian_profiles` type. The kind of relationship (MOTHER, FATHER, GRANDPARENT, LEGAL_GUARDIAN, …) lives on `student_guardians.relationship_type`. The UI can still say "Parent".

---

## 2. Medium-severity findings

### R-11 Inconsistent membership status names
- **Conflict:** §12 uses `CURRENT / FORMER / PENDING / REMOVED` for class membership. §83 and §101 use `ACTIVE` for course enrollment.
- **Resolution:** Use one vocabulary everywhere: `PENDING / ACTIVE / FORMER / REMOVED`. Course enrollment adds REJECTED, COMPLETED, CANCELLED and EXPIRED.

### R-12 Stories are class-only in one place and school-wide in another
- **Conflict:**
  - §17: `story.class_id` with a single `media_id`.
  - §18: parent contributions to "feed **or story**" with allowed scope CLASS/SCHOOL.
  - §81: feed scopes include SCHOOL.
  - ClassDojo, the reference product, has a School Story.
- **Resolution:** Stories get a `scope_type` of CLASS or SCHOOL, with a typed FK, the same way posts do.

### R-13 The GROUP feed scope is undefined
- **Conflict:** §81 lists a `GROUP` feed scope, but no "group" entity exists apart from chat groups (§30) and in-class student groups (§126).
- **Resolution:** Leave GROUP out of feed scopes for now. Groups communicate through `conversations` of type GROUP. It can be added if a concrete need appears.

### R-14 It's unclear whether announcements are posts
- **Conflict:**
  - §112 lists "Class feed, stories, teacher posts and school announcements" together.
  - §125 gives announcements GRADE-wide and *targeted* audiences, scheduling, withdrawal, acknowledgement and delivery tracking. Feed posts have none of these.
  - §132 makes `school_communications` a separate domain.
- **Resolution:** `announcements` is its own entity, with an audience table and a resolved recipient snapshot. The app's home feed can *show* announcements next to posts, but they are stored and authorized separately.

### R-15 Assignments, assessments and quizzes overlap
- **Conflict:**
  - §24 includes Quizzes and Projects as assignment types.
  - §25 includes Assignment and Project as assessment types.
  - §94 puts `quizzes` only under COURSES, but Phase 2 (§113) needs school quizzes.
  - The AI tutor (§130) needs practice questions too.
- **Resolution:**
  - `assignments` is work that students hand in, with `assignment_submissions`. It can be scoped to a class **or** a course.
  - `assessments` is the gradable record, with `assessment_results`. An assessment may grade an assignment or a quiz, or be offline (exam, participation).
  - `quizzes`, `quiz_questions` and `quiz_attempts` form **one shared engine**, used by school assessments, course lessons and AI practice.

### R-16 Points: positive-only or not, and parent points ⚑ Decision D2
- **Conflict:**
  - §112 and §126 say "**positive** behaviour points".
  - §21 requires corrections and reversals, which are negative entries.
  - ClassDojo, the benchmark, also has "needs work" points.
  - Parents can award points (§19), but categories are school-defined (§20). The spec doesn't say whether home points count toward class totals, badges (§23) or leaderboards.
- **Resolution:**
  - `point_categories.polarity` is POSITIVE or NEEDS_WORK. NEEDS_WORK is **enabled by default** (confirmed D2), and a school can switch it off.
  - `point_transactions.context` is CLASS, SCHOOL or HOME. Home points show on the child's profile and the parent's view. They **never** count toward class or school totals, leaderboards, school badges or reports. This is a fixed rule, not a setting (confirmed D2).
  - Reversals are separate negative transactions that reference the original.

### R-17 The calendar stores data that already lives elsewhere
- **Conflict:** §63's event types include ASSIGNMENT_DEADLINE, COURSE_SESSION, TUTOR_SESSION and HOLIDAY. That data already exists in `assignments`, `course_live_sessions`, `tutoring_sessions` and `holidays` (§94). Copying it creates two sources of truth that will drift apart.
- **Resolution:** The `events` table holds only *native* events: school, class, exam, parent meeting and other. The calendar API returns a **union**: native events, plus assignment due dates, live sessions, tutoring sessions and holidays, each filtered by the user's access.

### R-18 Three overlapping safety tables
- **Conflict:** SOCIAL `reports` (§94), PLATFORM `moderation_cases` (§94) and the incident management of §131 aren't related to each other anywhere in the spec.
- **Resolution:** Arrange them as a pipeline.
  - `content_reports` holds user reports about any object.
  - Reports group into `moderation_cases`. Each case has a reviewer, a decision and an action history.
  - Serious cases escalate to `safeguarding_incidents`, which have restricted access and are visible only to safeguarding leads.

### R-19 Naming collisions and undefined tables
| Spec | Problem | Design |
|---|---|---|
| `message_attachments` (§28) vs `message_media` (§94) | Same concept, two names | `message_attachments` |
| `booking` (§54) vs `tutor_bookings` (§94) | Same concept, two names | `tutor_bookings` |
| IDENTITY `sessions` (§94) | Collides with tutoring and live sessions | `auth_sessions` |
| `badges` vs `achievements` (§94) | Difference undefined | `badges` = definitions, `student_badges` = awards |
| `attendance` vs `attendance_records` | Undefined | `attendance_sessions` (class + date + slot) and `attendance_records` (per student) |
| CLASSROOM `progress` | Undefined | Replaced by `period_grades`, `lesson_progress`, `learning_evidence` and `mastery_records` |
| `TUTOR_SESSION` (§63) vs `TUTORING` (§28) | Inconsistent naming | `TUTORING_SESSION` |
| `post_media`, `story.media_id`, `message_media`, `portfolio_attachments` | Several media link styles | One `media` table plus one link table per owner |

### R-20 Is a course in the "marketplace world" or the "learning world"?
- **Conflict:**
  - §36 and §95 put courses in the MARKETPLACE WORLD.
  - §122 introduces a third LEARNING WORLD that holds courses.
  - Phase 3 is called "Online Learning *Marketplace*".
  - §42's example, a teacher's "Grade 10 Mathematics Live **Tutoring**" course restricted to SCHOOL_ONLY or CLASS_ONLY, is neither a marketplace offer nor a tutoring service (§37).
  - §39 lets a TEACHER own a course, but §40 keeps teacher (school) and tutor (marketplace) identities separate. Who owns a course that a teacher builds for their school?
- **Resolution:**
  - Courses form a *learning* domain with an explicit `owner_type` (SCHOOL, TEACHER, TUTOR, ORGANIZATION or PLATFORM) and an `audience`.
  - A course appears in the marketplace only if its audience is PUBLIC and it is published.
  - A course with audience SCHOOL or CLASS must be owned by the SCHOOL, with the teacher as an instructor, so it stays school property when the teacher leaves.
  - "Live Tutoring" in §42 is a *live course*, not a tutoring service.

### R-21 Tutor access to learner data has no entity to grant it
- **Conflict:** §85 and §103 say a tutor gets no school data "unless an explicit permission model allows it". §128 shares more learner information "after an authorised booking and with appropriate guardian permission". No table records that permission.
- **Resolution:**
  - `tutoring_relationships` links a tutor to a learner and a guardian.
  - `data_sharing_grants` records what the guardian has shared with the tutor, by category (e.g., GRADES, ATTENDANCE, PORTFOLIO, AI_PROGRESS), with an expiry and revocation.

### R-22 Missing booking states and concepts
- **Conflict:** §54 has 6 states. But §115 and §128 require guardian approval for minors, payment before confirmation, rescheduling, trial lessons, and recurring services such as "Grade 8 Weekly Tutoring" (§50).
- **Resolution:**
  - New states: REQUESTED, PENDING_GUARDIAN_APPROVAL, PENDING_PAYMENT, CONFIRMED, DECLINED, CANCELLED, COMPLETED, NO_SHOW, REFUNDED.
  - `is_trial` flag.
  - `rescheduled_from_booking_id`.
  - `booking_series` for recurring bookings.
  - `booking_status_history`.

### R-23 Tutor availability is weekly only
- **Conflict:** §53's weekly pattern can't express holidays, time off or one-off extra slots. Nothing prevents double booking.
- **Resolution:** Add `tutor_availability_exceptions`. A Postgres exclusion constraint on the tutor's session time ranges prevents double booking.

### R-24 Machine translation is required before the AI phase ⚑ Decision D5
- **Conflict:** Message translation (§32) and announcement translation (§125) are needed alongside Phase-1 messaging and announcements. But "AI translation" is in Phase 6 (§117), and no table stores translations.
- **Resolution:** A provider-agnostic `content_translations` table, built in Phase 1, for user content: posts, comments, messages and announcements. It keeps the original and flags machine output. Whether a machine-translation *provider* is switched on in Phase 1 is your call. The table works either way.

### R-25 Subscriptions and school fees aren't in any phase
- **Conflict:**
  - §105 defines plans and feature limits, and §80 feature flags are used from Phase 1. But subscriptions aren't in Phases 1–6.
  - School fee invoicing (§60) is described, but §116 only says to "keep it separate". It never schedules it.
- **Resolution:**
  - Phase 1: plans and entitlements are seeded, and a plan is assigned to each school manually by a platform admin. There is no billing.
  - Phase 5: subscription billing.
  - School fee invoicing (`fee_items`, `invoices`, `invoice_lines`) is designed but scheduled as Phase 5+. It needs your confirmation as a priority.

### R-26 The notification event list is a closed enum
- **Conflict:** §93 is a fixed list, and it doesn't cover the newer domains: announcements, acknowledgement requests, portfolio review, import completion, consent requests, booking cancel/reschedule, guardian link approval. §70 already says such lists must be data, not code.
- **Resolution:** A `notification_types` table, seeded by migrations.

### R-27 Offline sync has no data support
- **Conflict:** §72 and §104 require idempotency and conflict handling, but §94 has no structure for either.
- **Resolution:**
  - `sync_operations` keeps one row per device operation, unique per `(device, client_operation_id)`.
  - `version` columns provide optimistic concurrency.
  - Offline-capable rows (attendance, points, submissions) store their `client_op_id`.

### R-28 Messaging rules for students are undefined ⚑ Decision D3
- **Conflict:**
  - §29 defines only parent↔teacher messaging.
  - §31 lets students join "approved student groups".
  - §131 restricts adult→child direct messages by default.
  - Teacher↔student and student↔student direct messages aren't decided. A user-block table (§131 "block") is also missing.
- **Resolution (confirmed: configurable at school level):**
  - Each school sets the rules through `messaging.*` settings. The keys and defaults are listed in Database_Design §4.11.
  - The defaults are:
    - Students can't start direct messages.
    - Teacher↔student messaging happens only in class or group conversations.
    - No student↔student direct messages.
  - There is one safety floor a school can't switch off (§131): when adult→student direct messages are enabled, the student's active guardians are added as observers, or the thread is visible to school admins. Every such thread is audited.
  - `user_blocks` is added.

### R-29 The guardian link has no verification or permissions
- **Conflict:** §125 and §131 require school-approved verification, multiple guardians and "permission changes", such as a parent with restricted access. §94 `student_guardians` has no columns for any of this.
- **Resolution:** `student_guardians` gets:
  - `status` and `verification_method` / `verified_by` / `verified_at`
  - permission flags (view academics, message staff, award points, book tutors, pay, give consent)
  - `is_primary`
  - `custody_restricted`

  Imported contacts produce `invitations`, never active links (§125).

### R-30 Media access control table vs access derived from the parent object
- **Conflict:** §94 lists `media_access` as an ACL. §82 and §100 say access derives from the content's scope.
- **Resolution:** No ACL table. Media is always reached **through** the object that owns it (a post, message, portfolio item, etc.), which is authorized first. The API then issues short-lived signed URLs. Two independent permission systems would drift apart.

### R-31 Young students without email or phone ⚑ Decision D6
- **Conflict:** §131 calls for "school-managed student accounts where children do not have independent contact details", but no way to sign in is defined. ClassDojo uses class QR codes and text codes.
- **Resolution:**
  - `users.account_type = MANAGED` can exist without email or phone.
  - Sign-in uses `student_login_codes`: QR or text codes that a teacher issues, each revocable and expiring.
  - Older students can get their own credentials.

### R-32 Campus has an admin role but no data
- **Conflict:** CAMPUS_ADMIN exists (§7), but nothing in §94 is tied to a campus.
- **Resolution:** Add a nullable `campus_id` to `classes`, `school_staff`, `school_students` and `role_assignments`.

### R-33 Three configuration mechanisms with no precedence rule
- **Conflict:**
  - Feature flags by platform, country, organization and school (§80).
  - Plan feature limits (§105).
  - Behavior settings (§18 parent contributions, §23 leaderboards, §124 portfolio visibility, §125 quiet hours).

  The spec never says which one wins.
- **Resolution:**
  - A feature is available only if the plan includes it **and** the most specific feature flag enables it. Flags resolve school → organization → country → platform.
  - Behavior inside an enabled feature comes from `settings`, resolved with the same precedence and checked against `setting_definitions`.

### R-34 Operational roles are missing
- **Conflict:** Moderators (§87), support (§79) and safeguarding leads (§131) all have work assigned, as do non-teaching school staff such as a registrar running imports (§125). None of them appears in §7.
- **Resolution:** Add these roles to `roles` and assign them with `role_assignments`:
  - PLATFORM_MODERATOR
  - SUPPORT_AGENT
  - SAFEGUARDING_LEAD (school or platform scope)
  - SCHOOL_STAFF, with permissions granted per role

### R-35 Recording is "optional" in one section and consent-gated in others
- **Conflict:** §46 makes recording optional with no consent check. §115, §129 and §131 require opt-in consent, a visible indicator and a way to stop.
- **Resolution:** `video_rooms.recording_enabled` can only be set after consent has been verified for every participant, using `consent_records` of type RECORDING. `video_recordings` stores `consent_verified` and `retention_until`.

---

## 3. Low-severity findings

- **R-36: Tenant column rule (§98).** "`posts.school_id`" can't hold for COURSE-scoped posts on marketplace courses. Resolution: `school_id` is nullable on scope-polymorphic tables, with a CHECK that it is set whenever the scope is school-owned.
- **R-37: Person names.** Ethiopian naming is given name + father's name + grandfather's name, with no family name. The spec covers country-neutral addresses (§68) but not names. Resolution: `given_name`, `middle_name` and `family_name`, all optional except the given name, plus a `display_name`. The UI labels them per country, the same way addresses are labeled.
- **R-38: Course reviews.** §87 lists reviews and courses as moderatable, but only tutor reviews are modeled. Resolution: course reviews are left out and noted as a future addition.
- **R-39: Media key layout (§90).** It has no paths for portfolios, submissions, verification documents or recordings. Resolution: the key layout is derived from `media.purpose`. See the design doc.

---

## 4. Spec vs repository

- **R-40: Java version ⚑ Decision D1.**
  - §97 says **Java 21**.
  - The repo targets **Java 25**: `pom.xml` `java.version=25` and commit `3c512c5` (Maven Toolchains).
  - This machine has **only JDK 21** installed, and `~/.m2/toolchains.xml` **doesn't exist**, even though the README says it is configured. **The backend won't build as-is.**
  - Spring Boot 4.2 supports both versions. Options: install JDK 25 and create the toolchains file, or change the pom to 21.
- **R-41: Package name.** §96 uses `com.platform.*`, while the repo uses `com.nemaz.school`. Resolution: keep `com.nemaz.school` and use §96 and §132 as the module names beneath it.
- **R-42: Stack items not yet installed.**
  - shadcn/ui (web), Redis, R2, FCM/APNs and Agora are all listed in §97 but not set up. That's expected at this stage.
  - Background jobs: §91 recommends a transactional outbox, while §97 says "Redis for queues". Resolution: use a Postgres outbox as the source of truth, with Redis as the delivery queue and cache only.

---

## 5. Decisions (confirmed 24 November 2026)

| # | Question | Decision |
|---|---|---|
| D1 | Java 21 (as in the spec) or Java 25 (as in the repo)? | **Java 25.** Install JDK 25 and create `~/.m2/toolchains.xml`. §97 of the spec is superseded |
| D2 | Allow "needs work" points? Do parent (home) points count? | Needs-work points **allowed** (on by default, school can switch off). Home points **never** count toward class or school totals, leaderboards or badges |
| D3 | Student messaging policy | **Configurable per school** via `messaging.*` settings. Defaults: no direct messages started by students, teacher↔student only in class or group conversations, no student↔student direct messages. The safeguarding floor always applies |
| D4 | Move the payment core and video rooms to Phase 3? | Yes |
| D5 | Machine translation provider in Phase 1? | Storage ready in Phase 1. Provider switched on when chosen |
| D6 | Young-student sign-in method | Managed accounts with teacher-issued QR or text codes |
| D7 | When is school fee invoicing needed? | Phase 5+ |
