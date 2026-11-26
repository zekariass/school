/**
 * Class Story, School Story and the feed (Implementation Plan M8, Database_Design §4.10, R-09, R-30, decision D5,
 * spec §16-§18, §100, §131). Children's photos reach families here, so the rules are restrictive:
 * <ul>
 * <li><b>Posts and stories</b> have a CLASS or SCHOOL scope. Only ACTIVE teachers of the class (LEAD, CO_TEACHER,
 * SUBJECT_TEACHER, and assistants when {@code feed.assistants_can_post}) and class managers post to a class; holders of
 * {@code school.feed.manage} post school-wide. A viewer sees only PUBLISHED content of a class they may view
 * ({@code academic.ClassAccess}, which includes the guardian rule: custody-restricted guardians and those without
 * {@code can_view_academics} see nothing) or of a school they belong to; drafts, scheduled, pending, hidden and archived
 * posts are visible to the author and the moderators (the class's LEAD, class managers, school feed managers) only.</li>
 * <li><b>Consent gate (R-09):</b> a post, story or contribution with media is published only when every tagged student
 * has MEDIA_PUBLICATION consent GRANTED ({@code consent.ConsentService}); with no tagged student the author must
 * confirm explicitly that nobody without consent appears (recorded in the audit log). The gate runs at publish time,
 * also when the scheduler publishes (a blocked post goes back to DRAFT and its author is told). After publishing, the
 * status is re-read on every request: while a tagged student's consent is not GRANTED the post's photos are withheld
 * from everybody except its author and the moderators, and media reads are refused the same way.</li>
 * <li><b>Media</b> (purposes POST and STORY) is read only through the owning post or story (R-30,
 * {@code MediaPurposePolicy}); clients receive media ids and variant names, never URLs.</li>
 * <li><b>Privacy of people:</b> students appear with BASIC data only; guardians see only their own children among the
 * tags; guardians and students are shown by name only to those who may know them, otherwise only by role label;
 * "seen by" is a count and never reveals names.</li>
 * <li><b>Translation (D5)</b> goes through {@link com.nemaz.school.feed.TranslationProvider}, is off by default, and sends
 * only the text of a published post with student names masked.</li>
 * </ul>
 * Other modules use the events of this package ({@link com.nemaz.school.feed.PostPublished},
 * {@link com.nemaz.school.feed.ContentReported}, ...) and {@link com.nemaz.school.feed.TranslationProvider}.
 */
@ApplicationModule(displayName = "Feed")
package com.nemaz.school.feed;

import org.springframework.modulith.ApplicationModule;
