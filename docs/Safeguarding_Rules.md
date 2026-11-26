# Safeguarding and moderation rules (M13): draft for the owner

*Status: **OWNER-CONFIRMED on 26 November 2026**: the owner accepted every recommended answer to the open questions (see
"Decisions" below). **S1 to S6 and S8 are implemented by M13a (backend, 26 November 2026); the choices the implementer had to
make are at the end ("Added by the implementer")**; S7 (platform administration) is the later half of M13. Legal points (child-protection reporting duties, retention periods,
breach notification under Proclamation 1321/2024) **still need counsel**: the numbers below are placeholders until counsel
confirms them, and are settings, not code.*

The pipeline (R-18) is: **reports** (anyone who sees something) → **moderation cases** (grouped by target, decided by a human)
→ **safeguarding incidents** (serious cases and direct concerns, visible only to safeguarding leads). Messaging already has its
own audited review (`docs/Messaging_Rules.md`, R5/R13) and a report hook; the feed has a `ContentReported` event and portfolios
will add one. M13 connects them.

## The principles

1. **Reporting is easy and safe.** Every signed-in person, including a student, can report anything they can see. The
   reporter is never told anything that could expose them and is never exposed to the person they reported.
2. **Humans decide.** No automated moderation, scanning, scoring or profiling of children's content is built in M13. Every
   decision is a person's, with a reason, in an append-only history.
3. **Minimum exposure.** A case stores references, not copies of children's content. A moderator reads the content through
   the owning module's audited path. Nothing sensitive is written to logs, events, notifications or audit metadata.
4. **Evidence is never destroyed by moderation.** "Hide" and "remove" make content unavailable to others; the original is kept
   (soft delete, with a retention period and a legal hold) so that an investigation can still read it.
5. **Safeguarding information is need-to-know.** Incident details are visible only to safeguarding leads, encrypted at rest,
   and every read is audited, including reads by platform staff.
6. **Nobody moderates themselves.** A person can never decide, read or be assigned a case or incident about themselves.

## Words used

- **Reporter**: the person who files a report. **Target**: what was reported (a post, comment, story, message, portfolio item,
  user, and later tutor listing, tutor conversation, library resource, media).
- **School moderator**: a holder of the new permission `school.moderation.manage` (school, organization, country and platform
  administrators by default). **Platform moderator**: the existing role `PLATFORM_MODERATOR`.
- **Safeguarding lead**: the existing role `SAFEGUARDING_LEAD`, at school scope (one or more per school) or platform scope.

## S1 Reporting

1. Any signed-in user can report a target **they are currently allowed to see**. The server checks this through the owning
   module (a `ReportTarget` port each module implements for its own target types); a report about something the reporter cannot
   see is refused with the same generic 404 as the content itself. A custody-restricted or revoked guardian therefore cannot
   report content of that child.
2. **Reason codes:** SPAM, HARASSMENT, INAPPROPRIATE, SAFETY_CONCERN, COPYRIGHT, PRIVACY, INACCURATE, OTHER. Optional details:
   plain text, bounded (1,000 characters), cleaned like announcements, **never** copied into events, logs, audit metadata or
   notifications.
3. A reporter cannot report the same target twice while a case is open (the second request answers 200 with the first
   report). Rate limit: 20 reports a day per person; students 10 (**drafted: confirm**).
4. **Students can always report**, with a plain "Tell a trusted adult / Report a problem" path. A student's report is never
   shown to the student's classmates or to the reported person.
5. The reporter gets **one** in-app acknowledgement ("thank you, the school will look at this") and, when the case is
   resolved, one generic "your report was reviewed" notice. **No outcome, decision, names or content** is ever sent to the
   reporter; they can be told verbally by a human.
6. The existing hooks are consumed, not duplicated: the feed `ContentReported` event, the messaging `MessageReported` event
   (messaging keeps its own `message_reports` and review path; M13 creates the matching case), and the portfolio and
   announcement reports as those modules add them. The listener is **idempotent** (at-least-once delivery).
7. **The reporter's identity** is stored but is visible only to safeguarding leads and platform moderators handling the case.
   School moderators see only the reporter's **role** ("a student", "a guardian", "a teacher") and the reason. **(drafted:
   confirm: school administrators arguably should not learn that a given student reported a given teacher.)**

## S2 Moderation cases and queues

1. Reports about the same target in a school join **one open case** (unique per target while OPEN or ASSIGNED). More reports
   raise the case's count and, past a threshold (3 reporters, **drafted**), its priority.
2. **Priority:** SAFETY_CONCERN, any report **by a student about an adult**, and any report about a message in a safeguarded
   (adult-student) conversation start at **HIGH** and notify the school's safeguarding leads at once (generic text, no
   content). HARASSMENT starts at HIGH; everything else NORMAL; a safeguarding lead can set CRITICAL.
3. **Queues:** one queue per school (school moderators), one platform queue (platform moderators) for platform-scope targets
   (tutor listings and tutor conversations, library) and for cases escalated by a school or raised against school administrators. A case
   about a school administrator or a moderator **skips the school queue** and goes to the platform queue (**drafted: confirm**).
4. **Decisions** (each needs a reason of 10 to 500 characters and is a row in the append-only `moderation_actions`):
   NO_ACTION, HIDE (reversible), REMOVE, WARN_USER, SUSPEND_USER, ESCALATE (to a safeguarding incident).
   - HIDE and REMOVE are carried out by the owning module through a `ModerationTarget` port (hide the post/comment/story/
     message/portfolio item). The original stays (principle 4). HIDE can be undone by the same or a more senior moderator.
   - WARN_USER records a warning on the account and sends a generic in-app notice ("your content did not follow the school's
     rules"); it never quotes the content.
   - SUSPEND_USER at **school level** ends the person's membership activity in that school (reversible, audited). A **platform
     lock** (`lock user`) is only for platform moderators/administrators: it revokes every session through `TokenService`
     so `SessionsRevoked` closes sockets and push devices. Neither deletes anything.
5. **No self-moderation, no conflict:** a person cannot decide, be assigned or read a case about themselves, about their
   child (as guardian) or about their own content; such a case is reassigned to the next level.
6. Assignment, reassignment and re-opening are audited. A resolved case can be re-opened with a reason.
7. **Queue visibility is server-filtered** (SQL predicates, keyset paged): a moderator sees only cases of schools they moderate;
   a school moderator never sees platform-queue or safeguarding data; a student, guardian or ordinary teacher sees no queue.

## S3 Reading the reported content

1. A case holds **references and enums only** (target type and id, reason codes, counts, timestamps), never a copy of text or
   media. To decide, a moderator opens the content through the owning module's **reviewer path**: messages through the existing
   audited, reason-bound messaging review (R5/R13); posts, comments, stories and portfolio items through a new read that
   requires the case, writes an audit row (reviewer, case, target, no text) and is rate limited.
2. A moderator can read only the **reported target and its immediate context** (the reported message and the surrounding
   messages through the messaging review; the post and its comments), not a student's whole portfolio or inbox.
3. Hidden or removed content stays readable by safeguarding leads and the platform for the retention period (S6).

## S4 Safeguarding incidents

1. **Who can raise one:** any ACTIVE staff member or teacher ("raise a concern", with category, severity and a narrative), a
   moderator escalating a case, and automatically a SAFETY_CONCERN report of CRITICAL priority. Students and guardians raise
   concerns through a **report** (S1), never by writing to staff, and a human converts it.
2. **Who can read one:** **only** safeguarding leads of that school and platform safeguarding leads. **School administrators,
   principals who are not leads, moderators, platform administrators and support agents cannot read incidents** and do not
   see that one exists. (The principal may be designated a lead; that is a deliberate act.) **(decision needed: is a platform
   administrator without the lead role allowed to see incident metadata for oversight? Drafted: no; they see only audited counts.)**
3. **Fields:** category (CONDUCT, ONLINE_SAFETY, WELLBEING, TUTOR_CONDUCT, DATA_BREACH, OTHER), severity (LOW, MEDIUM, HIGH,
   CRITICAL), the concerned student (optional), involved people (optional, bounded), status (OPEN, INVESTIGATING,
   ACTION_TAKEN, REFERRED, CLOSED), assignee (a safeguarding lead only), `referred_to` (free text such as an authority, bounded).
4. **The narrative and every note are encrypted at the application level** (AEAD such as AES-GCM, a fresh nonce per record,
   the key id stored with the ciphertext so keys can rotate; keys come from the environment, **never the repository**; the
   deployed-profile validator refuses to start without a key). Plain text exists only in memory while a lead reads it. Lists
   and search show **ids, category, severity, status, school, dates, never narrative text**; there is no text search over
   narratives (**drafted: confirm**). Attachments go through a new media purpose readable only by safeguarding leads.
5. **Every read is audited** (who, when, which incident, why: a short reason is required to open an incident) and rate
   limited. The audit row never contains text. Platform administrators can list the audit rows of safeguarding reads
   (oversight of the overseers) without reading incidents.
6. **Workflow:** OPEN → INVESTIGATING → ACTION_TAKEN or REFERRED → CLOSED. Closing needs a closing note; re-opening needs a
   reason; every change is a note or a status row, append-only. Assignment is only to safeguarding leads of the school or the
   platform.
7. **Escalation and notification:** HIGH and CRITICAL notify every safeguarding lead of the school at once; if the school has
   none, the platform safeguarding leads (generic text, no names, no content). **A school must designate at least one safeguarding
   lead before it can switch on student messaging (`messaging.student.*`) or student contributions: the settings change is refused
   with a clear message until a lead is designated (decided: enforced).**
8. **Nobody is told automatically.** Guardians of the concerned student, the school administrator, the reported adult and the
   reporter are **not** notified by the system about an incident. Telling the child's guardians, referring to an authority or
   reporting a data breach is a **human decision recorded in the incident** (`guardian_informed_at`, `referred_at`). **Whether
   and when guardians or authorities must be told is a legal question for counsel (child-protection reporting duties; breach
   notification windows under Proclamation 1321/2024).**
9. A DATA_BREACH incident is the place the school records a breach; the platform provides the record, not the legal process.

## S5 Users and student accounts

1. **Reports about people** (target USER) are allowed for any adult or student who can see that person in a shared context
   (the same class, school or conversation). They create a case like any other and are never visible to the person reported.
2. **The reported person is never told who reported them.** A warning or suspension says only that content or behaviour broke
   the rules.
3. **A student is never subject to a platform lock by a school moderator**: school-level restriction only (**drafted: confirm**);
   a platform moderator can lock any account.
4. Moderation of a **student** always has a human review and never an automatic sanction. Disciplinary consequences at school
   are outside the platform.
5. Blocks (messaging R7) stay as they are. A student's block of an adult remains visible to safeguarding leads.

## S6 Retention, evidence and legal hold

1. Reports, cases, actions and incident rows are kept for a configurable period (**decision needed: counsel; drafted default
   7 years for incidents, 2 years for ordinary cases**). Hidden and removed content is kept for the same period as the case.
2. A **legal hold** flag on a case or incident blocks every purge and every erasure job until a safeguarding lead or platform
   administrator lifts it (audited).
3. A purge job (ShedLock, idempotent) removes only expired, unheld rows and records counts. Data-subject erasure and export
   requests against held data are **refused with a clear message and escalated to a human** (the process itself is owner/counsel
   work).

## S7 Platform administration (second half of M13, separate PR)

1. **Users:** search by **exact** phone, email or id (no free-text browsing of every user), results masked as everywhere else;
   lock and unlock (platform administrators and moderators; a reason; every session revoked on lock; audited).
2. **Schools, plans, features:** the endpoints from M1 stay; M13 adds the missing screens, not new rules.
3. **Audit log viewer:** filters by actor, action, target, school and time; **no text is stored in audit rows, so none can
   be shown**; safeguarding reads are listable (counts and ids).
4. **Support view** (`SUPPORT_AGENT`): a **read-only, time-boxed, audited** view of one account (status, memberships, devices,
   recent audit events about the account, notification delivery status) opened with a **mandatory reason**; it never shows
   messages, posts, portfolio, narrative, children's names beyond the account's own, or any secret. Every open is an audit row
   visible to platform administrators.
5. **System health:** readiness, last run and last result of each scheduled job, count of incomplete event publications,
   failed notification deliveries, import jobs in failure; no personal data.

## S8 What M13 deliberately does NOT do

- No automated content scanning, classification or "risk score" of children or their messages (a later, separately reviewed
  decision; the spec forbids emotion/face inference).
- No reading of private conversations except through the audited review paths.
- No automatic notification of guardians, authorities or the media about an incident.
- No deletion of accounts, content or incident data from the moderation screens.
- No chat or messaging with the reporter from inside the moderation tool.

## Settings, permissions, roles (planned)

- Permissions: `school.moderation.manage` (school queue), `school.safeguarding.read` and `school.safeguarding.manage` (held by
  `SAFEGUARDING_LEAD` only, **not** by administrators), `platform.moderation.manage`, `platform.support.view`,
  `platform.users.lock`. Seeded in `R__40`; the role-permission equality test must pass.
- Settings: `safeguarding.report.daily_limit` (20), `safeguarding.report.student_daily_limit` (10),
  `safeguarding.case.priority_threshold` (3), `safeguarding.retention.case_years` (2), `safeguarding.retention.incident_years`
  (7), `safeguarding.require_lead_for_student_messaging` (true).
- Feature flag: `moderation`.

## Decisions (owner-confirmed 26 November 2026: the recommended answers)

| # | Question | Decision |
|---|---|---|
| 1 | May school administrators see who reported? | **No.** They see the reporter's role only; identity is for safeguarding leads and platform moderators |
| 2 | May a platform administrator read incident contents? | **No.** Audited counts and the audit rows of safeguarding reads only |
| 3 | Must a school designate a safeguarding lead before student messaging or student contributions? | **Yes, enforced.** The settings change is refused until a lead is designated (the principal may be the lead) |
| 4 | Legal duties, retention | **Counsel.** Placeholders (incidents 7 years, ordinary cases 2 years) stay settings until counsel confirms |
| 5 | Are guardians of the concerned student notified automatically? | **No.** Only by a recorded human decision |
| 6 | Student report about an adult | **HIGH priority, safeguarding leads notified at once** |
| 7 | Case about a school administrator or moderator | **Skips the school queue** and goes to the platform queue |
| 8 | Encryption keys | **One platform key with a key id and rotation**; a key per school later if schools ask |
| 9 | Screenshots on a student's report | **Not in M13** |
| 10 | Emergency line per country | The screens read a **per-country configurable text**; the Ethiopia default is a placeholder ("if a child is in danger contact the local authorities") flagged **unreviewed**; the owner supplies the final wording and numbers |
| 11 | Amharic wording | Draft; **native-speaker and, ideally, child-protection review before any pilot** |

## Original open questions (answered above, kept for the record)

1. **Reporter identity:** may school administrators see who reported (S1.7)? Drafted: no, role only.
2. **Platform administrators and incidents (S4.2):** no access to incident contents; only audited counts. Agree?
3. **Who is a school's safeguarding lead?** Appointed by the school administrator through a role grant; may be the principal.
   Should the platform **refuse** to enable student messaging without one (S4.7), or only warn?
4. **Legal duties:** does Ethiopian law require schools to report certain concerns to an authority, and within what time?
   Which retention periods apply to incident records? (Counsel.)
5. **Guardians of the concerned student:** never notified automatically (S4.8). Agree?
6. **Student reports about an adult** start at HIGH and notify the safeguarding leads (S2.2). Agree?
7. **Case about a school administrator** skips the school queue (S2.3). Agree?
8. **Encryption keys:** one platform key with rotation, or a key per school? (Drafted: one platform key with a key id; a key per
   school later if schools ask.)
9. **A student's report text** is plain text, bounded: should a student be able to attach a screenshot? (Drafted: no, in M13.)
10. **Emergency information:** the screens should show a country-specific line ("if a child is in danger contact ..."). The
    owner supplies the wording and numbers per country.
11. **Amharic wording** of every reporting and safety screen needs a native-speaker and, ideally, a child-protection reviewer.

## Tests required of the implementation (one per rule, named after it)

Report visibility (a report about something the reporter cannot see is a 404; a restricted guardian cannot report), duplicate
and rate-limit rules, student reports, idempotent event consumption (redelivery changes nothing), reporter-identity
visibility per role, one open case per target and the priority thresholds, queue routing (school, platform, case about an
administrator), each decision and its effect through the owning module (hide keeps the original), reversal, self-moderation
refusal, legal hold, the incident visibility matrix (safeguarding lead, school administrator, principal who is not a lead,
platform administrator, support agent, moderator, teacher, student, guardian, another school's lead), encryption (ciphertext
in the database, a different nonce per record, key rotation reads old and new, startup refused without a key), every read
audited with no text, no text anywhere it must not be (a canary test over audit rows, events, notifications, logs, the case
and incident list endpoints), nobody auto-notified, the platform lock revokes sessions, the support view (read-only,
reason required, audited, no content), and every endpoint in `AuthorizationMatrixTest`.


## Added by the implementer (M13a, 26 November 2026)

The rule book was silent or ambiguous in the places below; **the most restrictive behaviour was chosen** and each choice has a
test. Please confirm or relax each one.

### Who sees what

1. **Reporter identity (S1.7, decision 1) is defined exactly:** the identity (`reporterId`) **and the free-text details** of a
   report are shown only to holders of `school.safeguarding.read` at the case's school (the two lead roles) and to holders of the role
   `PLATFORM_MODERATOR`. School moderators, school and platform **administrators** see the reporter's role and the reason only. The
   details are treated like the identity because a child's text ("I am Abel in 5B...") identifies the child. Test:
   `CaseWorkflowTest.reporterIdentityIsVisibleOnlyToLeadsAndPlatformModerators`.
2. **A new role `PLATFORM_SAFEGUARDING_LEAD`** (platform scope) is the "platform safeguarding lead" of S4: the existing
   `SAFEGUARDING_LEAD` is a school-scope role and a role is granted at exactly its scope. It is granted by hand (it cannot be granted
   through the platform-roles API: a granter must hold every permission of the role, and nobody who is not a lead does).
3. **Platform administrators no longer hold "every permission"**: they hold every permission **except**
   `school.safeguarding.read` and `school.safeguarding.manage` (decision 2). `RolesAndPermissionsSeedTest` enforces it.
4. **"School moderator"** = holder of `school.moderation.manage`: school, organization, country and platform administrators **and the
   safeguarding leads** (both roles), so that a lead can work the cases that alert them. **"Platform moderator"** = holder of
   `platform.moderation.manage` (`PLATFORM_MODERATOR`, platform administrators, platform leads). A platform moderator works the
   platform queue only; the school queue is not theirs (a school case reaches them only when a school moderator hands it up).
5. **"Administrator or moderator" for the routing rule (S2.3, decision 7)** is: an ACTIVE role with `school.manage`,
   `school.moderation.manage` or `school.safeguarding.read` covering the school (school, organization, country, platform), or ACTIVE
   staff of type ADMINISTRATOR. A case about **a safeguarding lead** therefore also skips the school queue (a lead must not be judged by
   the school's own moderators and leads).
6. **Conflicts (S2.5)** also cover **a case the moderator reported** (404). The subject, the guardian of the subject (PENDING or ACTIVE
   link) and, for incidents, an involved person, the concerned student and the guardian of the concerned student all get 404.
7. **Moderators never learn that an incident exists**: after an ESCALATE the case shows only `status: ESCALATED`; the link to the
   incident (`incidentId`) is returned only to holders of `school.safeguarding.read`.
8. **Who can report a person (S5.1):** the same ACTIVE class (students and teachers), or ACTIVE staff reporting staff or students of
   their school, or a guardian (ACTIVE, not custody-restricted link) reporting **staff** of their child's school (not other children).
   Shared conversations count too (messaging). Knowing a person's id is not enough.

### Reports and cases

9. **Event-path reports carry no text** (the feed and messaging events carry ids and a code only), so their `details` are empty; the
   student daily limit (10) applies to the new endpoint only, the feed's and messaging's own limit (20 a day) applies to their endpoints.
10. **The reason of a decision lives only in `moderation_actions.notes`.** The audit row of a decision carries the decision code and
    the level, never the reason. The reason of **ESCALATE becomes the encrypted narrative of the incident and is not kept in the case
    history** (the history row has no notes).
11. **Evidence hold.** When a case opens on a post, comment, story or message, the owner marks it (`moderation_case_id`): a delete by
    its author keeps the files, the story expiry job keeps the photo, and the school's moderators and leads can still read its media
    (`PostMediaPolicy`, `StoryMediaPolicy`). **A HIDE or REMOVE additionally sets `moderation_hidden_at`**: the owner's own un-hide
    then answers 409 `moderation.content_held`, and only the case can restore it. REMOVE cannot be restored by the case at all.
    **Not frozen (open question):** an author can still edit the text of a reported feed post (the feed keeps no edit history, unlike
    messaging).
12. **Reversal.** HIDE is undone by `POST .../restore` by a moderator of the case's queue; a school case that needs a more senior
    moderator is first handed up (`refer-up`) to the platform queue. The platform lock is undone by `lift-suspension` by a holder of
    `platform.users.lock`.
13. **School-level suspension (S2.4) is implemented in the modules that own memberships** (`SchoolSuspension` SPI): staff become
    SUSPENDED, students become SUSPENDED (a new `school_students` status), their ACTIVE class rows become FORMER, and a guardian's ACTIVE
    links to children of the school become custody-restricted (a restriction that was there before is never touched or lifted). The ids
    changed are recorded so a lift restores exactly them. A suspended student is signed out of every session. Nothing is deleted.
14. **Platform lock** = account status LOCKED + every session family revoked (`USER_INACTIVE`) through the token service; also
    available directly as `POST /platform/users/{id}/lock|unlock` (`platform.users.lock`, reason 10 to 500 characters, in the audit row).
15. **A report is closed and its reporter told ("reviewed") at every decision, ESCALATE included.** The notice never says which.
16. **Alerts** (HIGH): never to the subject or to a reporter of the case; once per case (`lead_alerted_at`).

### Incidents

17. **The reason to open an incident is a pick-list** (TRIAGE, INVESTIGATION, FOLLOW_UP, REFERRAL, OVERSIGHT, LEGAL_REQUEST), not free
    text: audit rows are listable by platform administrators and a free text would put a child's story there (answers the open
    question 6 of `Messaging_Rules.md` for this module).
18. **`referred_to` is encrypted** like the narrative (it names an authority or a person). Lists show the metadata of S4.4 and the
    concerned student's id, never text.
19. **Who may raise a concern:** an ACTIVE staff member (teacher or other staff) of the school, or a lead; anybody else gets 404.
    A school administrator without a staff row cannot (a deliberate, restrictive reading of "ACTIVE staff member or teacher").
20. **Re-opening goes to INVESTIGATING**, never back to OPEN. Closing from any status needs the closing note (a false alarm can be
    closed at once).
21. **Designating a lead** is `POST /schools/{id}/safeguarding/leads` by a holder of `school.staff.manage` (the school administrator);
    the lead must be an ACTIVE staff member; the grant evicts the grant cache so the new lead reads at once. **The last lead cannot be
    removed while the school has student messaging or contributions on** (409 `safeguarding.last_lead_required`).
22. **Attachments** are possible on incidents of a school only (a platform incident has no school to upload into), readable only by
    leads, each read audited.
23. **Automatic incident from a CRITICAL SAFETY_CONCERN:** raised when a lead sets the case to CRITICAL (once per case); its narrative
    is a fixed generic sentence (no content), category OTHER.
24. **Legal hold of an incident by a platform moderator or administrator** goes through a separate endpoint that answers the id and
    the flag only (they cannot read incidents).

### Settings and enforcement

25. **The safeguarding settings are platform- and country-level only** (not school or organization): a school cannot loosen its own
    safeguarding. `safeguarding.require_lead_for_student_messaging` can therefore be switched off only by the platform.
26. **Enforcement covers** `messaging.student.enabled`, `.teacher_direct.enabled`, `.student_direct.enabled`, `.media_enabled`,
    `.group_create` = STUDENTS_WITH_APPROVAL and `feed.student_contributions.enabled`, when the **effective value goes from off to on**
    (also by clearing an override that hid a higher "on"). Switching on **above the school** (organization, country, platform) is
    refused (`safeguarding.lead_required_scope`) because it would apply to schools that may have no lead.
    **OPEN QUESTION (child safety): `messaging.student.enabled` is ON by default**, so a school that never touched it has student
    class messaging without a lead; the rule only blocks the switch-on. Making the default OFF is a one-line seed change plus test
    updates, recommended before any pilot. The capability endpoint reports `studentFeaturesOnWithoutLead` so the screens can warn.

### Retention and platform data

27. **The purge removes only RESOLVED/ESCALATED cases and CLOSED incidents past the period, not under hold,** and a case with a
    suspension still in force is kept. The reported content (posts, messages, media) is **not** purged by moderation: hidden and
    removed content stays with its owner for the period by the evidence hold, and a later retention job of the owning modules must
    release it (not built).
28. **`GET /safety/emergency-info` needs sign-in** (every user of the apps is signed in); it serves the text in the caller's language,
    English as the fallback. The Ethiopian text is a placeholder, flagged `reviewed: false` in the table, the API and the seed.
29. **Rate limits** (all fixed windows): reports 20/day (students 10), case-bound reads 60/hour, incident opens 60/hour, incident lists
    120/hour, concerns 30/day. Each answers 429 with its own stable code.
