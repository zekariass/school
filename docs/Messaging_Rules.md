# Messaging rule book (M9a)

Plain-language rules for who may message whom on the platform, written for the owner to review **before** the code
that enforces them. Students may message adults and adults may message students' families, so this is the most
safety-critical part of the product.

- Source: Implementation Plan M9, Database Design 4.11, Spec Review R-28 and D3, spec 28-32 and 131.
- **When a rule is silent or unclear, the most restrictive behaviour was chosen.** Every such place is marked
  **(added by the implementer)** and is listed again in "Open questions" at the end. Please confirm or relax each one.
- Every rule has at least one automated test, named after the rule (`Class.method`, all in
  `apps/backend/src/test/java/com/nemaz/school/messaging`). The test names below are a promise: if one is missing
  or red, the rule is not enforced.
- Words: **adult** = teacher, school staff member or guardian. **Active** = the account is ACTIVE, the school is
  ACTIVE, and the person's link to the school, class or child is ACTIVE and not ended. **Observer** = a guardian who
  can read (never write in) a conversation of their child.

## Words used in the rules

| Term | Meaning |
|---|---|
| DIRECT | A conversation of exactly two people. Four kinds only: guardian-teacher, guardian-guardian, teacher-student, student-student. |
| CLASS | The class conversation (students + the class's teachers) or the class parent group (guardians of the class + teachers, no students). Members follow the class roster automatically. |
| GROUP | Chosen members: a staff group, or a student group of one class. |
| SCHOOL | An announcement-style conversation for school staff. Only moderators may post (default). |
| Moderator | The creating teacher, the class's LEAD and CO_TEACHER, class managers, and anyone holding `school.messaging.moderate`. |
| Reviewer | Anyone holding `school.messaging.review` (school, organization, country and platform administrators, and the SAFEGUARDING_LEAD role). |

## R1 Who may message at all

1. Both people must be Active users of the **same school** and the school must be ACTIVE (not onboarding, suspended or archived).
2. A **student** takes part only if they are an ACTIVE student of the school and `messaging.student.enabled` is on.
   If `messaging.student.min_age` is set, the server computes the age from the student's date of birth itself. The
   date of birth is never sent to a client. **(added by the implementer)** A student whose date of birth is unknown
   does not pass an age limit.
3. A **guardian** takes part only through an ACTIVE link that is **not custody-restricted** and has `can_message_staff`.
4. The check runs **on every send and on every read**, not only when a conversation is created. A revoked or
   restricted guardian, a student who left the school or the class, a removed teacher, a deactivated account or a
   suspended school loses access on the very next request. A stale membership row never grants access.
5. A DIRECT conversation is usable only while **both** people still pass the check and the school setting for
   that kind of conversation is still on. **(added by the implementer)** If it stops being usable, nobody can read
   or write it (not even the other person); reviewers can still read it if it is safeguarded (R5).
6. **(added by the implementer)** ASSISTANT teachers are not messaging participants; only LEAD, CO_TEACHER and
   SUBJECT_TEACHER are.
7. **(added by the implementer)** The whole feature is behind the `messaging` feature flag: when the school has it
   off, every messaging endpoint answers 403 `messaging.feature_disabled`. The audited review (R13) is not behind the flag: a school cannot switch off its safeguarding.
8. **(added by the implementer)** Refusals are deliberately vague. The person being refused only learns
   "not allowed" (403 `messaging.not_allowed`) for starting or sending, never whether the reason was a block, a
   restricted link, a school setting or an unknown person. A conversation the caller cannot access is 404
   `messaging.conversation_not_found`, exactly like an id that does not exist.

Tests: `MessagingAccessTest.restrictedGuardianLosesAccessImmediately`, `.revokedGuardianLosesAccessImmediately`,
`.guardianWithoutMessageFlagHasNoAccess`, `.studentWhoLeftTheSchoolLosesAccess`, `.studentWhoLeftTheClassLosesTheClassConversation`,
`.removedTeacherLosesAccess`, `.suspendedSchoolBlocksEverything`, `.deactivatedAccountLosesAccess`,
`.staleMembershipRowNeverGrantsAccess`, `.studentMessagingSwitchedOffBlocksStudents`,
`.studentMinimumAgeIsComputedFromTheDateOfBirth`, `.featureFlagOffAnswers403`, `.deadDirectConversationIsUnusableForBothSides`,
`.assistantTeacherIsNotAParticipant`, `.refusalsDoNotRevealTheReason`; `MessagingPolicyMatrixTest.everyCombinationFollowsTheRules`
(15 sender roles x 15 recipient roles x 11 settings combinations) and `.everyLinkAndMembershipStateEndsAccess` (15 changes of
the world, checked before any event runs). A restricted guardian looks like a stranger to the school (404 `school.not_found`),
so the restriction is not revealed **(added by the implementer)**.

## R2 Parent and teacher (DIRECT)

1. A guardian may start a DIRECT conversation with an ACTIVE teacher (LEAD, CO_TEACHER or SUBJECT_TEACHER) of a
   class in which their child is ACTIVE, **about that child**. The child is stored as `context_student_id` and is
   required (400 `messaging.context_student_required` without it).
2. There is **one** thread per (guardian, teacher, child). Starting it again returns the existing thread.
3. A teacher may start one with a guardian of a student they teach.
4. It requires `messaging.parent_teacher.enabled` (default on).
5. `messaging.teacher_hours` (optional office hours in the school's time zone). A message sent to a teacher outside
   them is delivered **silently**: it is stored and shown in the app, but no push is sent. The sender is told
   (`afterHours: true` in the send response). **(added by the implementer)** This applies to messages whose
   recipient is a teacher in a guardian-teacher conversation or a class parent group. Only same-day windows
   (start before end) are supported. Messages a teacher sends are not affected.
6. The teacher sees the guardian only as a **role label** ("Abebe's mother": the child's first name and the
   relationship). **(added by the implementer)** This is true for everybody who is not that guardian: teachers get
   "Abebe's mother", other guardians get "Parent", students get "Your guardian". A guardian's own name, phone and
   email never appear in messaging.
7. **(added by the implementer)** A guardian cannot be looked up by name. A teacher gets guardian ids from the
   contacts endpoint for a student they teach (labels only).
8. **(added by the implementer)** DIRECT between two staff members, between a school administrator and a guardian,
   or between an administrator and a student is **not supported** in M9a (the rules list four kinds only). Staff use
   staff groups.

Tests: `StartConversationTest.guardianStartsDirectWithTeacherOfTheChild`, `.contextStudentIsRequired`,
`.oneThreadPerGuardianTeacherAndChild`, `.teacherStartsWithGuardianOfOwnStudent`, `.subjectAndCoTeachersAreAllowedAssistantsAreNot`,
`.teacherOfAnotherClassIsRefused`, `.parentTeacherSettingOffRefuses`, `.teacherSeesOnlyTheGuardiansRoleLabel`,
`.staffToStaffAndAdminDirectsAreNotSupported`; `DeliveryTest.outsideTeacherHoursIsSilentAndTheSenderIsTold`,
`.insideTeacherHoursPushes`.

## R3 Parent and parent (DIRECT)

1. Only if `messaging.parent_parent.enabled` (default **off**).
2. Only between guardians of children who are ACTIVE in the **same class**. Both guardians need an ACTIVE,
   unrestricted link with `can_message_staff` **(added by the implementer: the flag is applied to both sides)**.
3. It never reveals phone or email (nothing in messaging ever does), and the other guardian is shown as "Parent".
4. Each side can always block (R7).

Tests: `StartConversationTest.parentParentIsOffByDefault`, `.parentParentNeedsChildrenInTheSameClass`,
`.parentParentNeverRevealsContactDetails`.

## R4 Students

1. A student can **never start** a DIRECT conversation by default.
2. Teacher-student DIRECT exists only if `messaging.student.teacher_direct.enabled`. Who may start it:
   `messaging.student.teacher_direct.initiator` = `TEACHER_ONLY` (default) or `EITHER` (the student may start it too).
3. Student-student DIRECT only if `messaging.student.student_direct.enabled`, and only between students ACTIVE in the
   same class.
4. Otherwise students talk only in CLASS and GROUP conversations their teacher created (R6).
5. A student can never message a guardian, a non-teaching staff member, a tutor or a stranger.
6. A student has **no search and no directory** of people. No endpoint lets a student list or look up users. The
   contacts endpoint refuses students. **(added by the implementer)** A student can only name someone they already
   see in their own class; any other id gets the same generic refusal.
7. **(added by the implementer)** Students cannot send images unless the new setting `messaging.student.media_enabled`
   is on (default off).
8. Every adult-student DIRECT conversation is covered by the safeguarding floor (R5).

Tests: `StudentMessagingTest.studentCannotStartDirectByDefault`, `.teacherToStudentNeedsTheSetting`,
`.initiatorSettingDecidesWhoStarts`, `.studentToStudentNeedsSettingAndSameClass`, `.studentCannotMessageGuardiansStaffOrStrangers`,
`.studentCannotEnumerateUsers`, `.studentImagesAreOffByDefault`.

## R5 Safeguarding floor (cannot be switched off)

For any DIRECT conversation between an adult and a student:

a. The student's ACTIVE, unrestricted guardians are added as **OBSERVER** members: read-only, told in the student's
   view ("your parent or guardian can see this conversation"), notified when they are added (`MESSAGE_OBSERVER_ADDED`),
   and **their reading never creates a "Seen" receipt**. **(added by the implementer)** Observers do not need
   `can_message_staff` (observing protects the child; it is not a messaging right) but the child must still be an
   ACTIVE student of the school.
b. If the student has **no** eligible guardian, the conversation is flagged `admin_visible` and can be read by
   reviewers (`school.messaging.review`) **only through an audited read endpoint**: every access writes an audit row with
   the reviewer, the conversation id and a mandatory reason. Administrators are never added as members.
c. Every such conversation creation is audited (ids only, no text).
d. A guardian who is restricted or revoked later stops being an observer **immediately** (the check is live, the
   cleanup follows). If that leaves no observer, the conversation is `admin_visible`. A guardian who becomes ACTIVE
   later is added as an observer and notified.
e. The floor applies even if the teacher is the guardian of the student: the teacher cannot observe their own
   conversation, so it is `admin_visible` (unless another guardian exists).
f. No combination of settings turns the floor off. It is not a setting.
g. **(added by the implementer)** Student-student DIRECT has no adult, so the floor does not apply. It is off by default
   and listed under open questions.
h. **(added by the implementer)** Review reads cover only conversations that are `admin_visible` (live) or have a
   report. A "dead" safeguarded conversation (R1.5) has no eligible observer, so it is review-only.

Tests: `SafeguardingFloorTest.everySettingsCombinationKeepsTheFloor`, `.observersAreAddedAndNotified`,
`.studentIsToldGuardiansCanSee`, `.noGuardianMakesTheConversationAdminVisible`, `.teacherWhoIsTheOnlyGuardianCannotObserveHerself`,
`.restrictedGuardianStopsObservingImmediately`, `.lastObserverLostMakesItAdminVisible`, `.guardianActivatedLaterBecomesObserver`,
`.creationIsAuditedWithoutText`, `.adminsAreNeverAddedAsMembers`, `.observersAreReadOnly`; `ReadStateTest.observerReadsNeverCreateReceipts`;
`RosterSyncTest.guardianLinkEventsMaintainTheObservers`.

### R5 addendum: small groups with students are reviewable (added by the reviewer of M9a)

The guardian floor covers DIRECT conversations only. A group of one teacher and two or three students has no guardian
observers and, without this rule, nobody could read it unless a child filed a report: in practice a private chat with
children. So **a group (not a DIRECT) that has at least one student and at most 8 active participants is reviewable**: a
reviewer holding `school.messaging.review` may read it through the same audited endpoint (reason of 10 to 300 characters,
rate limited, every read audited, reviewers never become members). Whole-class conversations are larger, have many
witnesses and stay reviewable only once reported. The limit is `Eligibility.SMALL_GROUP_WITH_STUDENTS_MAX_PARTICIPANTS`.
Test: `ReviewTest.onlyAdminVisibleOrReportedConversationsAreReviewable`. Open question for the owner: is 8 the right number,
and should the guardians of the students in such a group also be told that it exists?

## R6 Groups

1. Types: CLASS conversation, class **parent group**, staff group, student group, SCHOOL conversation.
2. Who creates (from `messaging.group_create.roles`, default TEACHER and SCHOOL_ADMIN): the CLASS conversation and the
   parent group by a LEAD or CO_TEACHER of the class or a class manager; staff groups by a staff member in the
   allowed roles; SCHOOL conversations by `school.messaging.moderate` holders (**added by the implementer**: staff
   members only, explicit membership, posting by moderators only).
3. Student groups are created by teachers only (`messaging.student.group_create = TEACHER_ONLY`). With
   `STUDENTS_WITH_APPROVAL` a student may propose a group of classmates; it stays `PENDING_APPROVAL` (nobody can use
   it) until a LEAD or CO_TEACHER of the class (or a class manager) approves it, and the approving teacher
   becomes its owner. **(added by the implementer)** A group with students always has a teacher moderator.
4. Membership of class conversations **follows the roster automatically** (listeners on class and guardian-link
   events, idempotent under redelivery). **(added by the implementer)** A member a moderator removed is not added back
   by the roster sync.
5. Owners and moderators (the creating teacher, the LEAD, class managers, `school.messaging.moderate`) can add and
   remove members (removal needs a reason and is audited), mute, change the posting policy, lock or archive, and hide
   messages. People who are not members cannot read a group; moderators who are not members act on it without
   reading it.
6. Students never see guardians' contact details or full names. Students and guardians are never in the same
   group in M9a (class conversation: students + teachers; parent group: guardians + teachers). Guardians appear as role labels.
7. **(added by the implementer)** A conversation with students needs **at least two** students who are ACTIVE in its class:
   with one it would be a private adult-student conversation without the safeguarding floor. Creating a student group with
   fewer is refused (400 `messaging.group_too_small`), removing the second-to-last student too, and a class conversation of a
   class with one student cannot be used until a second one joins.
8. **(added by the implementer)** Any ACTIVE teacher of the school (assistants included) may create a staff group when
   `TEACHER` is in `messaging.group_create.roles`; the class conversation and parent group need a LEAD or CO_TEACHER of the
   class or a class manager.

Tests: `GroupConversationTest.classConversationIsCreatedByLeadAndFollowsTheRoster`, `.parentGroupHasGuardiansAndTeachersOnly`,
`.creationRolesComeFromTheSetting`, `.studentsCannotCreateGroupsByDefault`, `.studentGroupNeedsApproval`,
`.moderatorsManageMembersAndSettings`, `.nonMembersCannotReadAGroup`, `.removalNeedsAReasonAndIsAudited`,
`.removedMemberIsNotAddedBackByTheRoster`, `.schoolConversationIsModeratorOnly`, `.mixedGroupsShowGuardiansAsLabelsOnly`,
`.groupsWithStudentsNeedAtLeastTwoStudents`;
`RosterSyncTest.studentJoiningAndLeavingTheClassUpdatesTheConversations`, `.guardianLinkChangesUpdateTheParentGroup`,
`.redeliveredEventsChangeNothing`.

## R7 Blocks

1. If A blocks B, B cannot start or continue a DIRECT conversation with A, and gets the same generic refusal as any
   other policy refusal. A, who made the block, is told "you blocked this person" (403 `messaging.you_blocked`).
2. In groups, a blocked user's messages are **hidden from the blocker on the server** (also their images and
   unread counts). Blocking is by conversation member, never by user id (`POST /me/blocks {conversationId, memberId}`).
3. Blocks never override the floor, announcements, consent or emergency notices: a student cannot block their
   observers (or any guardian of theirs), observers are never filtered by their own blocks, and announcements are
   unaffected. **(added by the implementer)** A guardian can block a teacher, which ends the direct thread for both.
4. A block of an adult by a student is **audited** and listed (ids and counts, no text) for reviewers.

Tests: `BlockTest.blockedUserCannotStartOrContinueDirect`, `.refusalIsTheSameGenericCode`, `.blockerKnowsTheyBlocked`,
`.blockedUsersMessagesAreHiddenFromTheBlockerInGroups`, `.studentCannotBlockTheirObservers`, `.observersSeeEverythingWhateverTheyBlocked`,
`.blocksDoNotAffectAnnouncements`, `.studentBlockOfAnAdultIsAuditedAndListedForReviewers`, `.blocksUseMemberIdsNotUserIds`.

## R8 Sending

1. **Idempotent**: `client_message_id` is unique per sender. A repeat returns the original message with 200 (new
   messages are 201), also when two requests race. A repeat of someone who lost access is refused like any send.
2. **Plain text only**: control, bidirectional and zero-width characters are stripped (same cleaner rules as
   announcements). **(added by the implementer)** 1 to 2000 characters. Message types: TEXT, MEDIA (1 to 4 images)
   and SYSTEM (server only). VOICE exists as a column and is refused (`messaging.type_not_supported`).
3. **Rate limits** per user: **(added by the implementer)** adults 30 messages per minute and 600 per hour,
   students 10 per minute and 200 per hour (429 `messaging.rate_limited`).
4. **Images** use the media module (purpose MESSAGE, context CONVERSATION): readable only by current members and
   observers, only while the message is not deleted or hidden and the conversation is valid; only re-encoded variants,
   never signed originals; reads are audited **(added by the implementer)**.
5. **Scheduled messages** (`sendAt`, adults only): sent by a ShedLock job that returns `Integer`, is idempotent, and
   **rechecks R1 and R5 at send time**; if the sender may no longer send it, it is not sent and the sender is
   notified. **(added by the implementer)** 1 minute to 90 days ahead. The recipients' quiet hours apply to the push as for any message.
6. **Reactions**: one per user per emoji, from a fixed set of emoji **(added by the implementer)**. Observers cannot react.
7. **Edit** within `messaging.edit_window_minutes` (default 15, new setting; 0 switches editing off), author only,
   text only. Every earlier version is kept in history. **Delete** leaves a tombstone for members; the original text
   moves to history and is readable only by reviewers through the audited endpoint (R5b/R13).
   **(added by the implementer)** In an adult-student DIRECT conversation (the safeguarded ones, R5) the **adult's** messages can be
   neither edited nor deleted (409 `messaging.record_kept`): what the student's guardians saw and what a reviewer may read can never
   be rewritten afterwards. A scheduled message can still be withdrawn; students keep their rights over their own messages.
8. **Reply-to** another message of the same conversation (the reply carries the id only, no quoted text **(added by
   the implementer)**). **Hidden** messages (moderator) show a tombstone.
9. Closed conversations (LOCKED, ARCHIVED) accept no messages; with `posting_policy = MODERATORS_ONLY` only owners and
   moderators post.

Tests: `SendMessageTest.repeatedClientMessageIdReturnsTheOriginal`, `.concurrentRepeatsStoreOneMessage`,
`.controlAndBidiCharactersAreStripped`, `.emptyAndTooLongTextIsRefused`, `.voiceNotesAreNotBuilt`, `.rateLimitsAreStricterForStudents`,
`.reactionsAreOnePerUserPerEmoji`, `.editKeepsHistoryAndRespectsTheWindow`, `.deleteLeavesATombstoneAndKeepsTheOriginalInHistory`, `.adultsCannotEditOrDeleteInSafeguardedConversations`,
`.replyToMustBeInTheSameConversation`, `.hiddenMessagesShowATombstone`, `.lockedAndModeratorsOnlyConversationsRefuse`;
`ScheduledMessageTest.dueMessagesAreSentOnceAndRecheckThePolicy`, `.adultsOnly`, `.blockedScheduleNotifiesTheSender`,
`.scheduledMessageIsInvisibleUntilSent`, `.jobIsIdempotent`; `MessageMediaTest.imageReadableByMembersAndObserversOnly`,
`.deletedHiddenOrRevokedMessageImageIsNotReadable`, `.neverIssuesAnOriginalSignedUrl`, `.studentsCannotUploadByDefault`,
`.reviewersReadTheImagesOfAnAdminVisibleConversationAndItIsAudited`. Once an image is attached, even its sender reads it only
while they may use the conversation **(added by the implementer)**.

## R9 Delivery

1. **Real time**: after commit, each current member and observer's open apps get a "doorbell" on
   `/user/queue/messages`. **(added by the implementer)** It carries ids only (`conversationId`, `messageId`); content is
   always fetched through the API, which applies every rule. Delivery is best effort; everything is stored.
2. **Push** through the notification service to members who are not currently reading, respecting the recipient's
   quiet hours (normal messages are held until they end), mute (`muted_until`) and teacher hours (R2).
   **(added by the implementer)** "Currently reading" means the member marked the conversation read in the last 30
   seconds. At most one unread message notification per conversation per person (follow-up messages do not ring again
   until it is read). Observers are notified like members.
3. **Notification text never contains the message body, the child's name or the sender's full name.** It says "New
   message" and, at most, a generic role. `data.path` is a relative app path.
4. Listeners are idempotent (redelivery notifies once). **(added by the implementer)** Deliveries of one conversation run one
   after the other, and opening a conversation marks its message notifications read, so the next message can ring again.

Tests: `DeliveryTest.membersGetARealtimeDoorbellWithIdsOnly`, `.pushRespectsQuietHoursMuteAndReading`, `.notificationsNeverContainTheTextOrNames`,
`.redeliveryNotifiesOnce`, `.silentDeliveryHasNoPush`, `.noDoorbellForPeopleWhoLostAccess`, `.insideTeacherHoursPushes`,
`.quietHoursHoldBackThePushButNotTheMessage`.

## R10 Read state

1. A watermark `last_read_message_id` per member; marking read is idempotent and never moves backwards.
2. Per-message "Seen" receipts (`message_reads`) exist **only in DIRECT conversations between the two non-observers**,
   and only the sender sees them.
3. Unread counts for the inbox. **(added by the implementer)** They exclude your own messages, tombstoned or hidden messages and
   messages of people you blocked.
4. Observers never produce receipts.

Tests: `ReadStateTest.watermarkIsIdempotentAndNeverMovesBack`, `.seenReceiptsOnlyInDirectBetweenNonObservers`, `.observerReadsNeverCreateReceipts`,
`.unreadCountsFollowTheRules`, `.onlyTheSenderSeesReceipts`.

## R11 Reporting and moderation hooks

1. Any member (observers too) can report a message: an audit row plus the event `MessageReported` carrying ids only
   (for M13). **(added by the implementer)** 20 reports a day per user; reviewers and the conversation's moderators get a `MESSAGE_REPORTED` notice.
2. Moderators hide messages and remove members with a reason, audited. Nobody edits other people's messages.
3. **No message text** in audit metadata, events, logs or notification payloads.
4. **Translation** reuses the feed's `TranslationProvider` and `content_translations`. **(added by the implementer)** New
   setting `messaging.translation.enabled`, default off. Never for DIRECT adult-student conversations; names, e-mails and phone numbers are masked first.

Tests: `ModerationAndReportTest.anyMemberCanReportAndOnlyIdsLeaveTheModule`, `.moderatorsHideWithAReason`, `.nobodyEditsOthersMessages`,
`.removalAuditsTheReason`, `.reportsAreRateLimited`; `NoTextLeakTest.messageTextIsNeverInAuditEventsNotificationsOrLogs`, `.requestObjectsAreRedactedInLogs`;
`TranslationTest.offByDefaultAndNeverForAdultStudentDirect`, `.masksNamesBeforeTheProvider`.

## R12 Reads

1. Inbox (conversations you belong to or observe, keyset paged, unread counts, last-message preview **without the body
   for tombstoned or hidden messages**), the messages of a conversation (keyset paged, newest first) and a single message.
2. 404 for people not in the conversation, 403 for members lacking a permission (for example posting).
3. For guardians the inbox can be grouped by child (`onBehalfOfStudentId` and `contextStudent` in the payload).
4. Student payloads keep the BASIC/FULL split: no date of birth, gender or username is ever returned; the other people are shown as named teachers and classmates, guardians as labels.
5. Everything is filtered by SQL, never on the client. Membership **and** current eligibility (R1) are both required.

Tests: `ConversationReadsTest.inboxIsKeysetPagedWithUnreadCounts`, `.previewHasNoBodyForTombstones`, `.guardianInboxCarriesTheChild`,
`.nonMembersGet404MembersWithoutPermissionGet403`, `.studentPayloadsHaveNoPersonalData`, `.messagesPageNewestFirst`.

## R13 Administration and safeguarding

1. Permissions `school.messaging.moderate` (moderate groups) and `school.messaging.review` (audited review of admin-visible
   threads and reported messages). Both are held by school, organization, country and platform administrators
   (not campus administrators). **(added by the implementer)** The existing SAFEGUARDING_LEAD role also gets `school.messaging.review`.
   M13 builds the safeguarding workflow around it.
2. A review read needs a reason (10 to 300 characters), is limited to 30 per reviewer per hour, writes an audit row
   (`messaging.review.read`: reviewer, conversation, reason, number of messages; no text) and returns the full history including
   deleted originals, hidden messages and earlier versions of edited ones.
3. Reviews are audited and listable by platform administrators (`GET /platform/audit-logs?actionPrefix=messaging.review.`).

Tests: `ReviewTest.reviewNeedsPermissionAndAReason`, `.everyReadIsAudited`, `.onlyAdminVisibleOrReportedConversationsAreReviewable`,
`.deletedAndEditedOriginalsAreOnlyInTheReview`, `.reviewIsRateLimited`, `.platformAdminsCanListReviews`, `.safeguardingLeadCanReview`,
`.permissionsAreSeeded` (`RolesAndPermissionsSeedTest` keeps code and seeds equal); every endpoint is also in
`AuthorizationMatrixTest` ("messaging" cases).

## Settings of this milestone

Existing (DB 4.11): `messaging.parent_teacher.enabled` (true), `messaging.parent_parent.enabled` (false),
`messaging.student.enabled` (true), `messaging.student.min_age` (null), `messaging.student.teacher_direct.enabled` (false),
`messaging.student.teacher_direct.initiator` (TEACHER_ONLY), `messaging.student.student_direct.enabled` (false),
`messaging.student.group_create` (TEACHER_ONLY), `messaging.group_create.roles` ([TEACHER, SCHOOL_ADMIN]),
`messaging.teacher_hours` (null). Added: `messaging.edit_window_minutes` (15), `messaging.student.media_enabled` (false,
**added by the implementer**), `messaging.translation.enabled` (false, **added by the implementer**).

## Open questions for the owner

1. Staff-staff, administrator-guardian and administrator-student DIRECT are not supported. Should teachers be able to message each other directly?
2. Student-student DIRECT (off by default) has no observers. Acceptable when a school switches it on?
3. Guardians are labelled "Abebe's mother" to the teachers who teach the child. Should the guardian's own name be shown to teachers?
4. May assistants take part in class conversations? (Excluded today.)
5. Reviewers can read a whole reported conversation, not only the reported message. Narrower?
6. The reason text of a review is stored in the audit log. Reviewers must not paste message content into it. Is a pick-list better?
7. Should the SCHOOL conversation include guardians (today staff only)?
8. Message retention: nothing is purged yet (deleted originals stay in history). What are the retention periods (M13/M21)?
9. Emoji reactions are a fixed list of 12; content moderation of free emoji is avoided.
10. Voice notes and video are not built.
