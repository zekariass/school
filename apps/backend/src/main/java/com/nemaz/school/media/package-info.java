/**
 * Media (Implementation Plan M2 steps 1-2, Database_Design §4.9): uploads straight to
 * S3-compatible storage through presigned URLs, magic-byte checks, image and PDF processing,
 * and short-lived signed read URLs.
 * <ul>
 * <li>There is no ACL table (R-30): who may create or read media is decided by the
 * {@link com.nemaz.school.media.MediaPurposePolicy} that the owning module registers for each
 * {@link com.nemaz.school.media.MediaPurpose}; this module depends on none of them.</li>
 * <li>Other modules use {@link com.nemaz.school.media.MediaService} to attach media to their
 * objects and to show a {@link com.nemaz.school.media.MediaSummary}.</li>
 * <li>Images are only ever served as re-encoded variants (metadata stripped); the original
 * stays private.</li>
 * </ul>
 */
@ApplicationModule(displayName = "Media")
package com.nemaz.school.media;

import org.springframework.modulith.ApplicationModule;
