# Specification Review — Education_Platform.md

*Review of the 20 November 2026 working specification, done on 24 November 2026 before the database design.*
*Companion document: [Database_Design.md](Database_Design.md). Each finding below names the resolution the database design adopts.*

Section references (§) point to `Education_Platform.md`. Severity tells you how much the finding affects the schema:

- **High**: building the spec as written would give the wrong data model or an authorization hole.
- **Medium**: contradictions or ambiguity that must be settled before that module is built.
- **Low**: naming and clarity issues.

Items marked **⚑ Decision** need your confirmation. The design already applies the default shown.

> **Scope revision, 26 November 2026 (D32–D40, end of this file).** Findings about scope that was later removed are kept as history and no longer apply: R-04, R-05, R-22, R-23 (tutor bookings and sessions), R-20 and the course parts of R-06, R-15, R-17 and R-36 (online courses), R-21 (tutor data sharing), R-35 (recordings), R-38 (course reviews), and the AI phase in R-08 and R-24.

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
- **Conflict:** Message translation (§32) and announcement translation (§125) are needed alongside Phase-1 messaging and announcements. But "AI translation" is in Phase 6 (§117; that phase no longer exists, D34, and translation stays the provider decision of D5), and no table stores translations.
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
| D4 | Move the payment core and video rooms to Phase 3? | Yes (video later removed, D33; Phase 3 is now payments and school fees) |
| D5 | Machine translation provider in Phase 1? | Storage ready in Phase 1. Provider switched on when chosen |
| D6 | Young-student sign-in method | Managed accounts with teacher-issued QR or text codes |
| D7 | When is school fee invoicing needed? | Phase 5+ (**revised by D37: Phase 3**) |

### Guardians and consent (M5a), confirmed 25 November 2026

The owner delegated these to the recommendations below, benchmarked on ClassDojo (a teacher can invite parents immediately; parents see only their own child; schools approve connections; consent is requested, not assumed).

| # | Question | Decision |
|---|---|---|
| D8 | Policy texts (Proclamation 1321/2024) | The seeded texts stay **DRAFT PLACEHOLDERS**. Legal review (and a native-speaker Amharic review) is a **blocker for any pilot with real families**, tracked in the M1.5/pilot checklist. Schools may publish their own texts meanwhile |
| D9 | May teachers see that a guardian link is custody-restricted? | **No.** Restricted links are invisible to teachers (list, queue and flag); only `school.guardians.manage` sees them and the note. A restricted adult simply sees nothing |
| D10 | Paper consent on an older policy version, and scanned evidence | **Current version only** (409 `consent.version_outdated` otherwise). Scanned evidence is deferred: the column exists, add a `CONSENT_EVIDENCE` media purpose when schools ask |
| D11 | Consent request when the guardian turned notifications off | **Still sent.** A consent request is a required, school-initiated notice, not marketing |
| D12 | Invitations while the school is ONBOARDING | **Allowed** (ONBOARDING or ACTIVE; not SUSPENDED or ARCHIVED). Schools invite parents while setting up, as in ClassDojo. Links still need school approval unless auto-verification applies. Student code sign-in is unchanged (ACTIVE only) |
| D13 | Links are global: may the child's current school revoke or restrict a link another school verified? | **Yes.** The student's current school owns access to the child's data; every such change is audited |

### School operations scope (26 November 2026)

The owner asked whether the design covers timetables, fee management with online payment, exams and results, student transfer, student IDs, attendance reports and methods (QR, NFC/RFID, GPS, manual), academic assessments, report cards, transcripts, question banks, online and secure exams, lesson planning, communications, a school AI assistant and HR/payroll. A gap analysis (26 November 2026) found the items below missing or only partly designed. The owner then **excluded HR and payroll and accepted the recommended design for everything else**. The same day, after a review of what was unnecessary, the owner **simplified the plan further** (D32–D40): the tutor marketplace became a paid directory, online courses and hosted video were removed, the Student AI Tutor and Tutor Copilot were removed, the school AI assistant was deferred, attendance became manual and QR only, student transfer became a status, the timetable became manual, and fees were reshaped around periodic schedules and payment verification. Where a later decision changes an earlier one, the earlier row says so. Each decision carries the security requirement that makes it safe to build; `Database_Design.md` §4.24–§4.37 and `Education_Platform.md` §134–§135 hold the detail, and `Implementation_Plan.md` the milestones.

| # | Question | Decision |
|---|---|---|
| D14 | HR and payroll | **Out of scope.** No employee records beyond `school_staff`, no leave, payroll, tax, pension or payslips, and no hooks for them. Staff attendance (D20) stays and carries no pay data. Revisit only as a separate product decision with its own security design (salaries, tax ids and bank accounts are the most sensitive adult data in the system) |
| D15 | "School financial books" vs. §119 ("do not overbuild school ERP") | **No ledger.** The financial reporting of a school is the **collection summary, arrears and ageing, cash book, receipts register, claims log and student statements**, built on immutable payments, allocations and receipts. No double-entry accounts, journals, period closing or trial balance (revised by D37; payroll, tax, purchasing and assets stay out, D14) |
| D16 | Fee scope (D7 said Phase 5+) | A school sets up **periodic payment** (fee structures, billing cycle, schedule), guardians **pay in the app** or **claim a payment made elsewhere that the platform verifies by reference**, the bursar can record cash, and every confirmed payment gets an immutable receipt with QR verification (D37 sets the detail and moves the timing to Phase 3) |
| D17 | How do QR codes relate to the attendance roll? | They are **evidence sources**. Every method ends in `RollService`, so the lock window, class membership, school-local date and conflict rules apply to all of them; a scan only ever creates PRESENT or LATE, never ABSENT, and never overwrites a different stored mark. **Manual marking** stays the default and the fallback. Only manual and QR are supported (D38) |
| D18 | Location privacy | **Superseded by D38.** GPS check-in is not built, so no location of any check-in is requested, stored or logged |
| D19 | QR credentials | **Separate from login codes** (a login code is a password; an attendance credential is an identifier). 128 random bits, no personal data inside, stored as an HMAC, revocable, reissued on loss. The **rotating QR** (teacher shows it, student scans) is derived from the server key and a time window, accepts the current and previous window, one check-in per student per session. The documented residual risk is a student sending a photo of the code to a friend; the mitigations are the short window and the teacher's live list. A **static student QR** (teacher scans the card) can be photographed and reused; repeated scans from two scanners raise an alert. NFC and RFID are not supported (D38) |
| D20 | Staff (teacher) attendance | Its own tables, readable by the staff member and `school.staff_attendance.read/manage` only; corrections need a reason and are append-only; no leave or payroll link (D14) |
| D21 | Student ID generation and cards | Student numbers are generated per school from a format (prefix, year, zero-padded sequence, optional check digit) with a locked counter, never reused. The number is an identifier only: **never a credential and never in a URL**. The card's QR token is a separate random credential (D19; NFC is not supported, D38) |
| D22 | Student transfer | **Superseded by D39** (a status only: no request flow, consent, package or prior-school records) |
| D23 | Question banks | Owned by a school (or platform-curated, read-only), organised by subject and grade, visibility PRIVATE / SUBJECT_TEAM / SCHOOL, tagged to curriculum objectives. A quiz **copies** the questions it uses, so later edits never change an exam already taken. **No cross-school sharing** in the first version |
| D24 | Secure online exams | **No webcam, microphone, screen-recording or biometric proctoring** (children's images and audio, §131). "Secure" means a scheduled window, a server-side timer, one active attempt and device, per-student seeded shuffle, optional access code and school-network allowlist, and **integrity signals shown to the teacher as evidence, never as an automatic penalty**. Answers never reach student clients before release (a DTO-level architecture test enforces it). Camera proctoring may be reconsidered only with a data-protection impact assessment and a new consent type |
| D25 | Assessment plans, marklists, semester results | A plan per class, subject and period with weighted components adding to 100; a marklist workflow DRAFT, SUBMITTED, (APPROVED), PUBLISHED; **after publication every change is an amendment** with a reason and a second approver, and the guardian is told that a result changed. Semester results are `period_results` computed by an idempotent job from the grades, never by deltas |
| D26 | Student ranking | A school setting `grading.rank.visibility` = `NONE`, `STAFF` or `OWN`; default `NONE`, and the Ethiopia configuration defaults to `OWN` because Ethiopian report cards print a rank. `OWN` shows a student and their guardians **only their own rank and class size, never a list**; no endpoint lists students by rank for families; rank is never in a push. Ties use competition ranking |
| D27 | Report cards and transcripts | **Immutable issued documents** generated from a snapshot, with a content hash and a QR verification token (128 bits, stored as an HMAC). The public verify page answers only type, school, issue date and status, with no name, marks or rank, in constant time, rate-limited. Corrections supersede or revoke, never edit. A transcript is signed by a second person. **Records are not withheld over unpaid fees by default** (`fees.hold_records_on_arrears` is an audited opt-in) |
| D28 | Lesson plans and the Ministry of Education format | Levels ANNUAL, TERM, WEEKLY, DAILY, chained, with a review workflow (reviewer is never the author) and revisions. **The format is data** (`lesson_plan_templates.fields`), so the Ministry layout is a template row. **The owner must supply the official Ministry of Education plan formats**; until then the platform ships a generic template. Plans are staff-only and carry no student reference |
| D29 | Timetable | **Versioned**: DRAFT, PUBLISHED (immutable), SUPERSEDED, with per-date overrides for substitutions. Clash-freedom is enforced by **unique indexes in the database** (class, teacher, room per weekday and period), so every hand edit is bound. Published timetables are a calendar source (R-17). Per-period attendance rolls come from timetable entries. **No automatic generator** (D40) |
| D30 | Payments, cash and receipts | The client never marks a payment paid: a payment succeeds only after a **verified signature, a provider status query, and an amount/currency/reference match**, once per event id. Cheques, deposit slips and larger cash amounts need a **second person**, and a second person signs off every cash session; a one-person school may switch this to a **single-person mode** that is flagged in the audit (D37). Records are **corrected by reversal, never edited**. Receipts are gapless per school and year, immutable, and verified by QR (D27 rules, plus amount) |
| D31 | School AI assistant | **Deferred (D34).** The design (tools that run as the signed-in user, no copy of children's records, read-only, off by default) is kept in `Database_Design.md` §4.37 and `Education_Platform.md` §134.9, with no milestone scheduled |
| D32 | Tutor marketplace | **Replaced by a tutor directory.** Tutors list themselves (subjects, levels, languages, area, weekly availability, rate, verified badge); families find a tutor and **message** them through the platform. **Removed:** services, bookings, sessions, payments for lessons, reviews and ratings, commissions, payouts, refunds, matching, recurring series, session notes, trials, and any access of a tutor to school data (`tutoring_relationships`, `data_sharing_grants`). Lessons are arranged and paid outside the platform, which says so everywhere. Reviews can be revisited with real demand because nothing proves a lesson happened |
| D33 | Online courses and recorded content | **Removed.** No courses, lessons, enrollments, live sessions, hosted video, recordings or transcripts, and no Agora. Class learning material is the digital library (M19) and assignments (M17); a student's progress is their assignments, assessments and portfolio. The quiz engine stays for school assessments |
| D34 | Student AI Tutor, Tutor Copilot, school AI assistant | **Student AI Tutor and Tutor Copilot removed**, together with the AI platform built for them (M32). **The school AI assistant is deferred**: nothing is scheduled and its AI platform would have to be designed when it is wanted. The AI-related consent types in the built schema stay unused |
| D35 | How do tutors pay to be listed? | A **listing subscription** (the `TUTOR_PRO` plan and `TUTOR` subscriptions that already exist), billed through the payment core with `platform_invoices`; a trial may be configured. A tutor is visible only while **verified, LISTED and subscribed** (a 7-day grace after a missed payment, then hidden, data kept). **Paying buys a listing, not rank**: results are ordered by relevance and recent activity, with the factors shown, and there is no paid placement. The platform takes no part in lesson payments and no commission |
| D36 | Who may contact a tutor, and how? | Only a **verified adult** (`users.adult_confirmed_at`: a phone verified by OTP and a date of birth showing 18 or over, or a guardian link a school verified) may start a conversation (`TUTOR_INQUIRY`), asking for a child ("Parent") or for themself ("Learner"); no school link is needed, so families outside platform schools can use it; **students under 18 never contact a tutor, directly or from their own account** (confirmed by the owner, 26 November 2026; Messaging_Rules R4.5 stays; adult status needs a verified phone and a date of birth showing 18 or over, `users.adult_confirmed_at`) and a tutor can never start a conversation. Student accounts have no directory (R4.6). The tutor sees "Parent" or "Learner" and the child's **age band and subject only**. Reports go to the platform moderation queue (normal reason codes); a platform moderator reads a tutor conversation only through a reported case. Limits: 5 new inquiries a day per user, one live inquiry per tutor and user, 30 new people a week per tutor, text only, no attachments in a first message. Either side can block and report; a case can suspend a listing at once. Search and profiles never expose phone, e-mail or an exact address. Messaging_Rules gets rule R14 for this |
| D37 | Fee payment: the owner's requirement | (1) A school sets up **periodic payment** (monthly, termly, yearly or once) and the platform issues invoices on the schedule; (2) guardians **pay in the app** through a payment provider; or (3) pay elsewhere and **submit the transaction reference**, which the platform **verifies through a verification API: Veritas (https://veritas.et), chosen by the owner on 26 November 2026** (a third-party service that looks up Ethiopian bank and wallet receipts; it is in Beta and publishes no SLA or privacy terms, see open items; check.et is a possible second adapter). A verified claim needs the receiver account to match the school's account, the currency, a sane date, an unused reference and a verified amount; anything doubtful, or any field the service does not return, goes to a person. Only the reference and the second identifier a method needs (an account suffix, or a phone number for CBE Birr) leave the platform. Cash recording and receipts stay; the **ledger is dropped** (D15); second approval is **configurable** with a single-person mode for small schools. **Fees move to Phase 3** because nothing remains in Phase 5 to wait for |
| D38 | Attendance methods | **Manual marking and QR only** (rotating QR the student scans, static QR the teacher scans). **No NFC, RFID, GPS, kiosks, gate readers or location of any kind.** Staff attendance uses the same two QR methods. Supersedes D18; narrows D17 and D19 |
| D39 | Student transfer | **A status only.** A school administrator marks a student TRANSFERRED with a date (no destination is recorded); class memberships end, login codes, credentials and cards are revoked, guardians and teachers see "Transferred", and an administrator can undo it. No request flow, no consent type, no records package, no `prior_school_records`. A transcript (own results only) can be issued on request. Supersedes D22 |
| D40 | Timetable | **Manual builder with database-enforced clash rules and a weekly-period coverage check; the automatic generator is deferred** until a school with many classes asks. The annual academic plan stays |
| D41 | May students sign in with a class code on the web? | **Yes (owner, 27 November 2026).** The web gets the same student sign-in as the phone: the teacher-issued QR code (scanned with the device camera) or the text code, with the same rules (school ACTIVE, codes revocable and expiring, every failure looks like a wrong code, rate limits), so the student screens of the web (portfolio, feed, attendance history) are reachable by managed students too. Tokens stay in httpOnly cookies through a dedicated Route Handler (the BFF refuses token-issuing endpoints) |
| D42 | Country configuration, no country hard-coding | The platform is multi-country (spec §65, §107: a new country is data, not a codebase), so **no code names a country**. There is **no single global "active country"**: several countries run at once. The country that applies is resolved by `tenancy.CountryResolver` in this order: **the school's country, the user's home country (`users.home_country_code`), the organization's country, the platform default country**. The last serves flows before any school or user is known (phone sign-in, one-time codes, invitation accept, `GET /meta`); a phone number with a `+` prefix always uses its own country. **The platform default country is a platform-scope setting in the database** (`platform.default_country`, seeded `ET`), which must name an **ENABLED** country: changing it to anything else is refused, and the application **refuses to start** when it is missing or not enabled (no literal fallback in code). Zones (the school's own `schools.timezone`, else the country's default), phone calling codes and parsing, calendars, languages, currency, address and name formats, payment methods, and which policy and safety text applies all come from the `countries` tables. **What stays code:** the calendar conversion algorithms and the `DateSystem` enum (a country *offers* a calendar through `country_calendars`; an import in the Ethiopian calendar is refused with 422 `import.date_system_not_available` for a school whose country does not), UI message bundles (files, detected by `SupportedLocales`), and provider adapters (SMS, payments), which the database enables per country. **Consent and safety text is never borrowed:** a school or adult whose country has no policy document gets 404 `policy.country_not_available` (a decision on another country's text is refused), and an emergency text is asked for per country and answers 404 `safety.emergency_info_not_found` when the country has none, because this is children's consent and a child's helpline. **A country is enabled only with its own published TERMS and PRIVACY** (owner, 27 November 2026: those two are enough for now; the account-participation and media-publication texts are not required, and without a media text a country's schools simply cannot show children's photos): a database trigger refuses the change of `countries.status` to ENABLED without them (`V16_01`), and the application refuses to start when an enabled country lacks them (`consent.CountryPoliciesCheck`, which covers a country inserted already enabled by a seed). A guard test (`NoCountryHardcodingTest`) fails the build on the literals `"ET"`, `"Africa/Addis_Ababa"`, `"ETB"`, `"+251"` and on literal language lists with `"am"` in Java. Fee payment methods become a `payment_methods` table (Database_Design §4.36) instead of a fixed list of Ethiopian banks and wallets |

**Open items for the owner**
1. The official **Ministry of Education lesson-plan and report-card formats** (D28, D27), and whether the Ethiopian grading bands and promotion rules are national or per school.
2. **Veritas terms before M31c starts (D37).** The owner chose Veritas (https://veritas.et). Its public pages (read 26 November 2026) show a Beta product with a hosted API on a third-party domain, a changelog that mentions a fallback proxy, **no SLA, terms of service, privacy policy, data-retention statement or bank-affiliation statement**, no documented response fields, and no statement about calling Telebirr from outside Ethiopia. Before M31c: read or request its terms and privacy policy (what it stores about a payer's name and amount), ask for an SLA or at least a status commitment, choose a plan and price (Pro is 199 ETB a month for 2,000 lookups) and who pays (the plan assumes the platform holds one key and caps each school), and run the M31c spike with a real key to record each method's actual response. check.et (https://check.et, free, similar API) is the fallback candidate for a second adapter.
3. The Ethiopian **payment provider(s)** for paying in the app (provider decision at M22).
4. The **price and trial** of the tutor listing plan, and whether the directory may be browsed without an account (the design says authenticated adults only).
5. The **legal position** on a public directory of tutors who teach children, and the terms that say the platform does not run or guarantee lessons.

**Confirmations (owner, 26 November 2026, second round).** (1) Use **Veritas** (https://veritas.et) for payment verification (D37). (2) **Students under 18 do not chat with tutors** (D36). (3) The judgment calls of the previous round stand: **no ledger**, cash recording and receipts kept, second approval **configurable** with a single-person mode (D15, D30, D37). (4) **Online courses stay removed**: class material is the digital library plus assignments (D33).

**Confirmations (owner, 27 November 2026, after the consistency review).** (1) **Attendance is manual and QR only**; NFC, RFID and GPS are not needed (D38). (2) The fixes of the consistency review are accepted, including that **any verified adult** may contact a tutor, for a child or for themself, without a school link (D36), the two finance roles `SCHOOL_BURSAR` and `SCHOOL_FINANCE_MANAGER` (D30), and in-app fee digests with counts only (D37).
