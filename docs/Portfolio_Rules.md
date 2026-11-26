# Portfolio rule book (M12a)

Plain-language rules for children's portfolios (their own work, photos and drawings), written for the owner to review
**before** the code that enforces them. A portfolio stores a child's work and shows it to teachers and families, so privacy and
consent come first.

- Source: Implementation Plan M12, Database Design 4.16 and 4.9, Spec Review R-09, R-30, D2, D8-D13, spec 100, 124, 126, 131.
- **When a rule is silent or unclear, the most restrictive behaviour was chosen.** Every such place is marked
  **(added by the implementer)** and is listed again in "Open questions" at the end. Please confirm or relax each one.
- Every rule has at least one automated test, named after the rule (`Class.method`, all in
  `apps/backend/src/test/java/com/nemaz/school/portfolio`, except the endpoint matrix). The names below are a promise: if
  one is missing or red, the rule is not enforced.
- Collections, feedback, rubrics and learning objectives are P2 and are **not built** (`portfolio_feedback` and
  `portfolio_collections` do not exist yet).

## Words used in the rules

| Term | Meaning |
|---|---|
| Item | One piece of work (text, photo, drawing, scan, document). |
| Active | The account is ACTIVE, the school is ACTIVE, and the person's link to the school, class or child is ACTIVE and not ended. |
| Own guardians | The student's guardians whose link is ACTIVE, **not custody-restricted**, with `can_view_academics`. Read live on every request, like everywhere else. |
| Reviewer | For one item: an ACTIVE LEAD or CO_TEACHER of the item's class; an ACTIVE SUBJECT_TEACHER of that class when the item's subject is theirs; a class manager (`class.manage` over the class); a holder of `school.portfolios.review` over the school (school, organization, country and platform administrators). |
| Effective visibility | The **lower** of the item's own visibility and the school cap `portfolio.max_visibility` (default GUARDIANS). Order: STUDENT_TEACHER < GUARDIANS < CLASS < SCHOOL. |
| Beyond own guardians | Effective visibility CLASS or SCHOOL. |
| Withheld media | The item is shown but its pictures and files are not (consent, see P4). |

## P1 Creating

1. A student creates items about their **own** work only if the `portfolio` feature is on for the school, the school
   setting `portfolio.student_create.enabled` is on (default **true**) and they are an ACTIVE student of the school. Managed
   accounts sign in by class code and call the API as the student. A person who is not a member of the school gets 404
   (`school.not_found`); a member who may not create gets 403.
2. Item types now: **TEXT, PHOTO, DRAWING, SCAN, DOCUMENT**. TEXT carries text and no file; PHOTO and DRAWING carry images;
   SCAN images or PDF; DOCUMENT a PDF. 1 to 10 files per item. A file belongs to **one** item.
3. **AUDIO and VIDEO are deferred (documented decision).** The media pipeline has no audio type at all and videos are stored
   as uploaded (no re-encoding, no metadata stripping, no poster), so they cannot meet "originals never served, metadata
   stripped". The values are reserved in the database; asking for them answers 422 `portfolio.item_type_unsupported` and the
   media purpose PORTFOLIO accepts images and PDFs only. No pipeline rule was weakened.
4. Media goes through the new purpose **PORTFOLIO** (context SCHOOL). Who may read a file is decided by the item it is
   attached to, never by a separate ACL (R-30). Until it is attached, only its uploader reads it.
5. Fields: title (required, 120), description (1000), reflection "what I learned" (1000), text content (5000, TEXT only),
   work date (not in the future; default today), optional class (one the student is ACTIVE in; drives review rights) and
   optional subject (a subject of the school). **(added by the implementer)** If the class is omitted and the student is ACTIVE in
   exactly one class, that class is used; otherwise the item has no class and only holders of `school.portfolios.review`
   review it.
6. Visibility is chosen per item: STUDENT_TEACHER, GUARDIANS, CLASS or SCHOOL. **(added by the implementer)** The default is
   GUARDIANS, the default of Database Design 4.16 (the child's own family); effective visibility is then lowered by the cap (P4).
7. A student saves a DRAFT or submits at once (`mode`).
8. **Teacher quick capture** (spec 126): an ACTIVE LEAD, CO_TEACHER or SUBJECT_TEACHER of a class creates an item **for** a student
   ACTIVE in that class. ASSISTANTs only while `portfolio.assistants_can_capture` (default **off**). Class managers,
   administrators, students and guardians cannot. `created_by` is the teacher; the item is **auto-APPROVED**, the history has
   a CAPTURED row and the audit log a `portfolio.item.quick_capture` row (ids only). It is visible only by the rules of P4.
   **(added by the implementer)** A quick capture with media says whether other children may appear (`showsOtherChildren`); if
   the teacher says nothing it counts as **true** (P4).
9. Text is plain text, cleaned like announcements (control, bidirectional and zero-width characters removed), bounded, and
   **never** put in logs, events, audit metadata or notifications.
10. **(added by the implementer)** At most 100 items a day per user (429 `portfolio.rate_limited`).

Tests: `PortfolioCreateTest.studentCreatesDraftThenSubmits`, `.submitInOneCall`, `.studentCreateSwitchedOffRefuses`,
`.featureFlagOffAnswers403`, `.onlyActiveStudentsOfTheSchoolMayCreate`, `.audioAndVideoAreRefusedWithTheDeferralError`,
`.itemTypeAndMediaMustMatch`, `.textIsCleanedAndBounded`, `.classMustBeOneTheStudentIsActiveIn`, `.classDefaultsWhenTheStudentHasExactlyOne`,
`.subjectMustBelongToTheSchool`, `.mediaMustBeReadyOwnPortfolioUpload`, `.oneFileBelongsToOneItem`, `.createIsIdempotent`,
`.portfolioIsCreatedLazilyOncePerStudentAndSchool`, `.visibilityDefaultsToGuardians`, `.workDateCannotBeInTheFuture`,
`.createIsRateLimited`; `PortfolioQuickCaptureTest.leadCoAndSubjectTeachersMayCapture`, `.assistantsOnlyWhenTheSettingIsOn`,
`.teacherOfAnotherClassIsRefused`, `.managersStudentsAndGuardiansCannotCapture`, `.studentMustBeActiveInThatClass`,
`.capturedItemIsApprovedAndAuditedWithoutText`, `.capturedItemFollowsTheVisibilityRules`,
`.groupPhotoNeedsTagsOrAConfirmation`, `.showsOtherChildrenDefaultsToTrue`; `PortfolioMediaAccessTest.uploadNeedsAnActiveStudentOrTeacher`,
`PortfolioSettingsTest.settingsFlagAndPermissionAreSeeded`.

## P2 Status workflow

```
            create                 submit                 review
 (nothing) ───────► DRAFT ──────────────────► SUBMITTED ───────────► APPROVED ──archive──► ARCHIVED
     │                                           ▲  │                    ▲                   │
     │ quick capture (teacher)                   │  ├── CHANGES_REQUESTED│                   │
     └────────────────────────► APPROVED         │  │         │ resubmit  └──── unarchive ────┘
                                                 └──┘◄────────┘         (reviewers only)
                                                    └── REJECTED (end)
```

1. DRAFT is visible to its **creator only**. The student edits a DRAFT or a CHANGES_REQUESTED item (and nobody else edits
   it) and submits it; resubmitting after CHANGES_REQUESTED makes a **new SUBMITTED row** in the history.
2. A reviewer decides on a SUBMITTED item: APPROVE, REQUEST_CHANGES (needs a note) or REJECT (needs a note). REJECTED is the end:
   the student cannot resubmit it **(added by the implementer)**. A reviewer may lower the item's visibility while approving, never raise it.
3. Only an APPROVED item can be archived (by the student or a reviewer) and unarchived (**by a reviewer only**, the
   consent gate of P4 runs again) **(added by the implementer: a student cannot undo a teacher's archiving)**. ARCHIVED is
   visible to the student and the reviewers only.
4. Every transition (including featured, archive, unarchive and delete) **appends** a row to `portfolio_approvals`: actor,
   from and to status, optional note (500). The table is append-only: a database trigger refuses UPDATE and DELETE. The
   note is visible to the student, the item's reviewers and administrators, **never to guardians or classmates**. The history
   (`GET .../history`) is for the student and the reviewers only.
5. **Reviewers** are defined under "Words". **(added by the implementer)** ASSISTANTs and subject teachers of another subject are
   not reviewers; nobody reviews a quick capture (it is approved and audited).
6. **Deleting.** A student deletes their own DRAFT, SUBMITTED or CHANGES_REQUESTED item. A reviewer deletes an item in any
   status. Soft delete (`deleted_at`): the item disappears for everybody, its files are discarded through `MediaService.discard`.
   **(added by the implementer)** The text of the item and its comments is erased at once; the row, the history and the audit
   rows remain as a tombstone. An APPROVED item is otherwise removed only on a data-subject request: the hook
   (`PortfolioItemService.erase`, the same soft delete without the reviewer check) exists, the erasure workflow is not built.
   **(added by the implementer)** Switching `portfolio.student_create.enabled` off stops students from creating, editing and
   submitting, never from deleting their own unreviewed items.
7. **Featured.** A reviewer sets or clears `is_featured` on an APPROVED item (the history gets a FEATURED or UNFEATURED row).

Tests: `PortfolioWorkflowTest.everyLegalTransitionWorks`, `.everyIllegalTransitionIsRefused`, `.draftIsVisibleToItsCreatorOnly`,
`.studentEditsOnlyDraftAndChangesRequested`, `.resubmissionAppendsANewSubmittedRow`, `.changesRequestedAndRejectedNeedANote`,
`.rejectedIsTheEnd`, `.reviewerMayLowerVisibilityNeverRaise`, `.historyIsAppendOnlyInTheDatabase`,
`.everyTransitionAppendsOneHistoryRow`, `.noteIsVisibleToStudentAndReviewersNeverGuardians`, `.historyIsForStudentAndReviewersOnly`,
`.reviewersAreExactlyTheDefinedPeople`, `.studentDeletesOnlyDraftSubmittedAndChangesRequested`,
`.reviewerDeletesAnyStatusAndTheTextIsErased`, `.deletedItemIsGoneForEveryoneAndItsMediaIsDiscarded`,
`.featuredOnlyOnApprovedItemsByReviewers`, `.archiveHidesFromEveryoneButStudentAndReviewers`, `.unarchiveIsForReviewersAndRerunsTheGate`,
`.reviewQueueListsOnlySubmittedItemsForReviewers`.

## P3 Notifications

1. `PORTFOLIO_SUBMITTED` goes to the item's class teachers who review (LEAD, CO_TEACHER, matching SUBJECT_TEACHER) when an item is
   submitted or resubmitted.
2. `PORTFOLIO_REVIEWED` goes to the **student** after a review and after a quick capture (managed accounts have no push: in-app only).
3. `PORTFOLIO_NEW_ITEM` goes to the student's own guardians **once per approved item**, when the item is APPROVED and guardian-visible
   (effective visibility GUARDIANS or higher): guardians with an ACTIVE, unrestricted link, `can_view_academics` **and**
   `can_receive_notifications`. The setting `portfolio.notify_guardians` (default on) turns it off.
4. `PORTFOLIO_COMMENT` goes to the student when somebody else comments **(added by the implementer: not to guardians, to keep
   the volume low)**.
5. Texts are generic ("A portfolio item is ready for review", "Your portfolio has an update", "A new item was added to your child's
   portfolio"): no item title, reflection, comment, note or child's name. `data.path` is `/portfolio/items/{id}`.
6. Listeners are idempotent (`alreadyNotified`); a redelivered event creates nothing new. The guardian notification is
   re-checked live when the listener runs (an item that is no longer guardian-visible, or a link that was restricted meanwhile, notifies nobody).

Tests: `PortfolioNotificationTest.submissionNotifiesReviewersGenerically`, `.reviewNotifiesTheStudentGenerically`,
`.approvalNotifiesEligibleGuardiansOncePerItem`, `.guardiansAreNotNotifiedWhenTheSettingIsOff`,
`.restrictedPendingAndNonNotifiedGuardiansGetNothing`, `.guardiansAreNotNotifiedWhenTheItemIsNotGuardianVisible`,
`.notificationsNeverContainTitleReflectionOrNames`, `.listenersAreIdempotentUnderRedelivery`, `.commentNotifiesTheStudent`.

## P4 Visibility (the core)

1. A non-creator sees an item only when its status is **APPROVED**. The student sees their own items in every status; the
   reviewers see every status except DRAFT; ARCHIVED is for the student and the reviewers only; a deleted item is seen by nobody.
2. Audiences by effective visibility:
   - **STUDENT_TEACHER**: the student and the item's reviewers.
   - **GUARDIANS**: adds the student's own guardians. No consent is needed for this audience.
   - **CLASS**: adds the ACTIVE students and teachers of the item's class (while the student is still ACTIVE in that class).
     They get BASIC student data only (name, avatar) and **no guardian data** of any child.
   - **SCHOOL**: adds every ACTIVE student and staff member of the school. Guardians of other children never see it.
3. School cap `portfolio.max_visibility` (default **GUARDIANS**): CLASS and SCHOOL are off until a school raises it. It lowers
   the effective visibility of existing items too, and raising it later restores them (the stored visibility is never rewritten).
4. A **former** student (withdrawn, left the school) and their guardians lose access at once; a student who only left the
   class keeps their own items but no longer sees the class's items. **(added by the implementer: history stays closed to former
   members, as in the feed)**
5. **Media consent (R-09).** An item with files needs MEDIA_PUBLICATION consent GRANTED for the student (`ConsentService`,
   UNKNOWN counts as no, re-read on every request) when it is visible **beyond own guardians** or when `showsOtherChildren`
   is true. Other children who appear are **tagged** (ACTIVE students of the school, at most 20); every tagged child needs consent
   too. With **no tags** the creator must send `confirmNoUnconsentedStudents: true` ("no child without consent appears"), which is
   stored with the time and audited. The gate runs when an item becomes visible (approval, unarchive) and fails with 422
   `portfolio.media_consent_missing` (with the ids, names only for callers who may see the roster) or 422
   `portfolio.media_confirmation_required`. The confirmation is dropped when the files or tags change. Text-only items never
   need consent.
6. **Withholding.** On every read the same rule is applied live: when consent is missing (withdrawn, refused, a guardian link
   ended) or the confirmation is missing, the item stays but `media` is empty and `mediaWithheld` is true, and the signed-URL
   endpoint answers 404 for those files. Not withheld from: the student and the reviewers (they see a warning flag) and, when the
   reason is the student's **own** consent, the student's own guardians. **(added by the implementer)** When the reason is
   another tagged child (or the missing confirmation about others), the student's own guardians are also withheld: they must not
   see a child whose own guardians have not agreed. Granting consent again brings the files back without any action.
   **(added by the implementer)** The same live test covers a school that raises its cap after an item was approved: an item that
   reaches CLASS or SCHOOL only now, without consent or the creator's confirmation, shows its text but withholds its files (the
   creator cannot confirm an approved item: archive it and add it again, or the school lowers the cap).
7. Media access follows the item (R-30): draft files are read by their creator only; submitted files by the student and the
   reviewers; approved files by the item's audience (minus withholding); archived files by the student and the reviewers; files
   of a deleted item by nobody. A signed URL is never issued for an image original (images only as re-encoded variants).
8. Oversight reads (**added by the implementer**): when somebody sees an item only because they hold `school.portfolios.review` (not as
   a teacher of the class), the read is audited (`portfolio.item.read_oversight`, ids only; spec 131).

Tests: `PortfolioVisibilityMatrixTest.everyStatusVisibilityAndPersonFollowsTheMatrix` (status x effective visibility x 14 personas,
single reads and list inclusion), `.theSchoolCapLowersEffectiveVisibilityAndRaisingItRestoresIt`,
`.guardianAudienceNeedsALiveActiveUnrestrictedLinkWithAcademics`, `.formerAndWithdrawnStudentsLoseAccess`,
`.classAudienceSeesBasicStudentDataAndNoGuardianData`, `.payloadsNeverCarryPrivateStudentFields`,
`.oversightReadsAreAudited`; `PortfolioConsentTest.classAndSchoolMediaNeedConsentAtApproval`, `.ownGuardiansNeedNoConsent`,
`.withdrawalWithholdsFromClassmatesButNotFromStudentReviewersOrOwnGuardians`, `.grantingConsentAgainRestoresTheMedia`,
`.aTaggedChildWithoutConsentWithholdsEvenFromOwnGuardians`, `.confirmationIsRequiredWithoutTagsAndIsAudited`,
`.confirmationIsDroppedWhenFilesOrTagsChange`, `.raisingTheCapNeverExposesUnconsentedMedia`, `.textOnlyItemsNeedNoConsent`,
`.taggedStudentsMustBeActiveInTheSchool`; `PortfolioMediaAccessTest.draftFilesAreReadByTheCreatorOnly`,
`.submittedFilesAreReadByStudentAndReviewers`, `.approvedFilesFollowTheAudience`, `.archivedFilesAreForStudentAndReviewers`,
`.deletedItemFilesAreNobodysAndAreDiscarded`, `.withheldFilesAnswer404`, `.imageOriginalsAreNeverSigned`,
`.unattachedUploadIsReadableByItsUploaderOnly`, `.mediaDeletedClearsTheAttachment`.

## P5 Comments (encouragement)

1. Comments exist only on **APPROVED** items that are guardian-visible (effective visibility GUARDIANS or higher). Who may write:
   the student, the student's own guardians (while `portfolio.comments.guardians_enabled`, default on) and the item's
   reviewers. **(added by the implementer)** A teacher who is not a reviewer of the item (an assistant, a subject teacher of another
   subject) cannot comment.
2. **Who may read:** the student, the student's own guardians and the item's reviewers, whatever the item's audience.
   **Never classmates, other families or other teachers, even on CLASS and SCHOOL items** (the audience of a comment is always these
   three groups, whoever wrote it).
3. A custody-restricted, revoked or pending guardian can neither read nor write comments, from the very next request.
4. Plain text, 1 to 500 characters, cleaned like P1; 30 comments an hour per user (429 `portfolio.rate_limited`).
5. Guardian and student authors appear by **role label only** ("Parent", "Student": `authorRole`, no name, no id); an author sees
   `mine: true`. Reviewers appear by name.
6. Reviewers hide a comment with a reason (audited; the comment stays as a **tombstone**: status HIDDEN, no text for anyone but
   the author and the reviewers). The author deletes their own (the text is erased). Nobody else can.
7. No comment text in notifications, events or audit metadata.

Tests: `PortfolioCommentTest.onlyApprovedGuardianVisibleItemsAcceptComments`, `.whoMayWriteComments`,
`.commentsAreVisibleOnlyToStudentOwnGuardiansAndReviewers`, `.commentsStayPrivateOnClassAndSchoolItems`,
`.restrictedRevokedAndPendingGuardiansLoseCommentsAtOnce`, `.guardianCommentsCanBeSwitchedOff`, `.authorsAppearByRoleLabelOnly`,
`.reviewerHidesWithAReasonAndLeavesATombstone`, `.authorDeletesOwnCommentAndTheTextIsErased`, `.otherPeopleCannotHideOrDelete`,
`.commentsAreRateLimited`, `.commentTextIsCleanedAndBounded`.

## P6 Sharing to the class story

**Deferred (documented decision).** Sharing an item to the class story needs the item's files to become files of a post, and
the media module has no copy operation (a file belongs to one owner, R-30). Adding one to `feed` and `media` is a large change to
modules that carry the consent gate of M8a, and duplicating that gate would be worse than waiting. The endpoint exists and
is for teachers (reviewers) of an APPROVED item whose effective visibility is at least CLASS; it answers **422
`portfolio.share_unsupported`**. The setting `portfolio.share_to_story.enabled` (default false) is reserved for the day it is built;
turning it on changes nothing today.

Tests: `PortfolioShareTest.shareToStoryIsUnsupportedForTeachers`, `.studentsAndOtherPeopleCannotEvenTry`,
`.shareRequiresAnApprovedClassVisibleItem`.

## P7 Reads and lists

All lists are filtered in SQL by the same predicate as the single read, keyset paged (`CursorPage`), and answer 404 to callers who
cannot see the school, student or item and 403 to members who lack the permission.

1. `GET /schools/{s}/students/{student}/portfolio`: the student's portfolio, for the student, their own guardians, their teachers,
   class managers and holders of `school.portfolios.review`; **everybody else gets 404** (classmates and other families use the
   galleries) **(added by the implementer)**. Each caller gets the items they may see (guardians: APPROVED and guardian-visible only).
2. `GET /schools/{s}/portfolio/mine`: the items the caller created or that are about them, in every status.
3. `GET /schools/{s}/classes/{c}/portfolio/review-queue`: SUBMITTED items of the class, oldest first, for reviewers only (a
   subject teacher sees only their subject's items).
4. `GET /schools/{s}/classes/{c}/portfolio/gallery`: APPROVED items of effective visibility CLASS or SCHOOL, for members of the class.
5. `GET /schools/{s}/portfolio/gallery`: APPROVED items of effective visibility SCHOOL, for ACTIVE members of the school.
6. `GET /me/portfolio`: a guardian's combined view across their children, **APPROVED and guardian-visible only**, optional `studentId`.
7. Payloads carry media ids and variant names, never URLs. Students appear with BASIC data only (id, name, avatar): date of birth,
   gender and username never appear. The review note and history only for the student and reviewers. One portfolio per
   student per school, created lazily with the first item.

Tests: `PortfolioListsTest.studentPortfolioForStudentGuardianAndReviewers`, `.mineListsEveryStatus`, `.classGalleryShowsClassVisibleApprovedItemsOnly`,
`.schoolGalleryShowsSchoolVisibleItemsToSchoolMembersOnly`, `.guardianCombinedViewAcrossChildrenWithFilter`, `.listsAreKeysetPaged`,
`.invalidCursorIs400`, `.capabilitiesDescribeTheCaller`; the single-read matrix is in P4.

## P8 Settings, permission, flag

Settings (school scope, repeatable seed `R__20`): `portfolio.student_create.enabled` true, `portfolio.max_visibility` GUARDIANS,
`portfolio.assistants_can_capture` false, `portfolio.notify_guardians` true, `portfolio.share_to_story.enabled` false (reserved),
`portfolio.comments.guardians_enabled` true. Permission `school.portfolios.review` (`R__40`, `Permissions`; PLATFORM, COUNTRY,
ORGANIZATION and SCHOOL administrators). Feature flag **`portfolio`** (the existing key; 403 `portfolio.feature_disabled`).

## Never in logs, events, audit or notifications

The title, description, reflection, text, notes and comments of a portfolio exist in the portfolio tables and nowhere else. Audit
rows carry ids, status, visibility, counts; events carry ids only; notification texts are generic.

Tests: `PortfolioNoTextLeakTest.portfolioTextIsNeverInAuditEventsNotificationsOrLogs`; endpoint rules:
`AuthorizationMatrixTest` (every portfolio endpoint x every persona, including another school's administrator, a guardian of
another child, a student and a teacher of another class).

## Open questions (for the owner)

1. May a child choose CLASS or SCHOOL visibility for their own work at all (today: yes when the school's cap allows)? Should
   the teacher confirm it at approval?
2. Default visibility GUARDIANS for new items (Database Design default) versus STUDENT_TEACHER (most restrictive).
3. A student cannot delete an approved item (not even a quick capture about them); only a reviewer can. Is that right?
4. Should guardians be told about comments (today only the student is)?
5. Audio and video: which processing (transcoding, metadata stripping, poster, audio sniffing) and which retention before they are allowed?
6. Sharing to the class story: copy the file into a post (new media operation) or let a post reference portfolio media?
7. Retention of deleted items' history rows and approval notes (the table is append-only; a purge needs a documented exception).
8. Archived and deleted items' files are discarded or kept for safeguarding evidence (M13)?
9. An item approved while the cap was GUARDIANS and without the creator's confirmation stays withheld from classmates (and from its own
   guardians, because others may appear in it) once the cap is raised: is there a way for the creator to confirm an approved item?
10. Portfolios are an entitlement of SCHOOL_BASIC and up (the existing plan matrix); schools on SCHOOL_FREE get 403 `portfolio.feature_disabled`.
    Is that the intended product decision for a feature that stores children's own work?
11. Whether SUBJECT_TEACHERs of other subjects and assistants should see CLASS-visible items (today: yes, as class members, but they cannot comment or review).
