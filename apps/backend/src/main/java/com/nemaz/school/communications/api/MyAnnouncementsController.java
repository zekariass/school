package com.nemaz.school.communications.api;

import java.util.UUID;

import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.communications.application.AnnouncementModels.AnnouncementCard;
import com.nemaz.school.communications.application.AnnouncementModels.AnnouncementUnreadSummary;
import com.nemaz.school.communications.application.AnnouncementModels.CommunicationChannelsRequest;
import com.nemaz.school.communications.application.AnnouncementModels.CommunicationChannelsView;
import com.nemaz.school.communications.application.AnnouncementModels.InboxFilter;
import com.nemaz.school.communications.application.AnnouncementModels.InboxSort;
import com.nemaz.school.communications.application.AnnouncementQueries;
import com.nemaz.school.communications.application.ChannelPreferences;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The recipient's side: the inbox of announcements delivered to the caller, the unread badge, and the email/SMS opt-outs. */
@RestController
@Tag(name = "my-announcements")
public class MyAnnouncementsController {

	private final AnnouncementQueries queries;

	private final ChannelPreferences preferences;

	MyAnnouncementsController(AnnouncementQueries queries, ChannelPreferences preferences) {
		this.queries = queries;
		this.preferences = preferences;
	}

	@GetMapping("/api/v1/me/announcements")
	@Operation(operationId = "listMyAnnouncements",
			summary = "The announcements delivered to the caller (the recipient snapshot) across schools, optionally one school, keyset paged: newest first or sort=UNREAD_FIRST; filter UNREAD or NEEDS_ACKNOWLEDGEMENT. A guardian's custody-restricted, pending or revoked links, and students when the school does not address them, contribute nothing. Withdrawn announcements stay in the list as withdrawn")
	public CursorPage<AnnouncementCard> inbox(@RequestParam(required = false) UUID schoolId,
			@RequestParam(required = false) InboxFilter filter, @RequestParam(required = false) InboxSort sort,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
		return this.queries.inbox(CurrentUser.require(), schoolId, filter, sort, cursor, limit);
	}

	@GetMapping("/api/v1/me/announcements/summary")
	@Operation(operationId = "getMyAnnouncementSummary",
			summary = "How many delivered announcements are unread and how many still wait for the caller's acknowledgement (for a badge)")
	public AnnouncementUnreadSummary summary() {
		return this.queries.unread(CurrentUser.require());
	}

	@GetMapping("/api/v1/me/communication-channels")
	@Operation(operationId = "getMyCommunicationChannels",
			summary = "Whether the caller accepts announcements by email and SMS (both on until switched off). Push and in-app are the notification preferences. Urgent announcements never override these two")
	public CommunicationChannelsView channels() {
		return this.preferences.view(CurrentUser.require().userId());
	}

	@PutMapping("/api/v1/me/communication-channels")
	@Operation(operationId = "updateMyCommunicationChannels",
			summary = "Switches email and/or SMS announcements on or off (a null field stays). 409 announcement.sms_blocked_by_provider when the number opted out with the SMS provider")
	public CommunicationChannelsView updateChannels(@Valid @RequestBody CommunicationChannelsRequest request) {
		return this.preferences.change(CurrentUser.require().userId(), request.email(), request.sms());
	}

}
