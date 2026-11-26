package com.nemaz.school.communications.api;

import java.util.UUID;

import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.communications.application.AnnouncementModels.AnnouncementRecipientView;
import com.nemaz.school.communications.application.AnnouncementModels.AnnouncementReminderView;
import com.nemaz.school.communications.application.AnnouncementModels.AnnouncementReportSummary;
import com.nemaz.school.communications.application.AnnouncementModels.RecipientFilter;
import com.nemaz.school.communications.application.AnnouncementReports;
import com.nemaz.school.communications.application.AnnouncementReports.ExportFile;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The delivery report of an announcement (author and school.announcements.manage): counts, the recipient list, a CSV and the
 * reminder. A reader of the announcement who is neither gets 403, anybody else 404 announcement.not_found.
 */
@RestController
@Tag(name = "announcement-reports")
public class AnnouncementReportController {

	private static final String BASE = "/api/v1/schools/{schoolId}/announcements/{announcementId}";

	private static final MediaType CSV = new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8);

	private final AnnouncementReports reports;

	AnnouncementReportController(AnnouncementReports reports) {
		this.reports = reports;
	}

	@GetMapping(BASE + "/report")
	@Operation(operationId = "getAnnouncementReport",
			summary = "Delivered, failed, read and acknowledged counts of a published or withdrawn announcement, with per-channel (push, email, SMS) sent, failed, skipped and pending counts, late readers (outside the recipient snapshot) and reminders sent. Delivery, reading and acknowledgement are counted separately. 409 announcement.not_published for drafts")
	public AnnouncementReportSummary summary(@PathVariable UUID schoolId, @PathVariable UUID announcementId) {
		return this.reports.summary(CurrentUser.require(), schoolId, announcementId);
	}

	@GetMapping(BASE + "/report/recipients")
	@Operation(operationId = "listAnnouncementRecipients",
			summary = "The recipient snapshot with delivery state per channel, read and acknowledgement, keyset paged by recipient (names, masked contact details, and for guardians the child they were reached through). filter narrows to UNREAD, UNACKNOWLEDGED, ACKNOWLEDGED or FAILED. Audited")
	public CursorPage<AnnouncementRecipientView> recipients(@PathVariable UUID schoolId, @PathVariable UUID announcementId,
			@RequestParam(required = false) RecipientFilter filter, @RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer limit) {
		return this.reports.recipients(CurrentUser.require(), schoolId, announcementId, filter, cursor, limit);
	}

	@GetMapping(BASE + "/report.csv")
	@Operation(operationId = "exportAnnouncementReport",
			summary = "The recipient list as a CSV download: UTF-8 with a byte order mark, CRLF line ends, headers in the caller's language, spreadsheet formulas neutralized, no contact details, sorted by name. Audited, rate limited (10 per 10 minutes, 429 announcement.export_rate_limited), at most 20000 rows (422 announcement.export_too_large)",
			responses = @ApiResponse(responseCode = "200", content = @Content(mediaType = "text/csv", schema = @Schema(type = "string"))))
	public ResponseEntity<byte[]> export(@PathVariable UUID schoolId, @PathVariable UUID announcementId) {
		ExportFile file = this.reports.export(CurrentUser.require(), schoolId, announcementId);
		return ResponseEntity.ok()
			.contentType(CSV)
			.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.attachment().filename(file.filename()).build().toString())
			.cacheControl(CacheControl.noStore())
			.header("X-Content-Type-Options", "nosniff")
			.body(file.content());
	}

	@PostMapping(BASE + "/remind")
	@Operation(operationId = "remindUnacknowledged",
			summary = "Reminds the recipients who have not acknowledged (in-app and push; urgent announcements push through quiet hours). At most once every announcements.reminder.min_hours (429 announcement.reminder_too_soon with Retry-After), at most 5 per announcement (409 announcement.reminder_limit_reached), 409 announcement.nobody_to_remind, 409 announcement.ack_not_required. Audited")
	public AnnouncementReminderView remind(@PathVariable UUID schoolId, @PathVariable UUID announcementId) {
		return this.reports.remind(CurrentUser.require(), schoolId, announcementId);
	}

}
