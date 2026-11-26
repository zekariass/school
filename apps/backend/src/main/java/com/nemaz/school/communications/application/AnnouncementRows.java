package com.nemaz.school.communications.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.communications.application.AnnouncementAccess.Basis;
import com.nemaz.school.communications.application.AnnouncementModels.AnnouncementChannel;
import com.nemaz.school.communications.application.AnnouncementModels.AnnouncementStatus;
import com.nemaz.school.communications.application.AnnouncementModels.AnnouncementTranslation;
import com.nemaz.school.communications.application.AnnouncementModels.AnnouncementType;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.stereotype.Component;

/** The {@code announcements} row as the services use it, and its mapper. */
@Component
class AnnouncementRows {

	static final String COLUMNS = """
			an.id, an.school_id, an.author_id, an.title, an.body, an.body_language_code, an.translations::text as translations,
			an.announcement_type, an.requires_acknowledgement, an.channels, an.status, an.scheduled_for,
			an.schedule_error_code, an.authored_as, an.revision, an.published_at, an.recipient_count,
			an.delivery_completed_at, an.withdrawn_at, an.withdrawn_by, an.withdrawal_reason, an.created_at, an.updated_at""";

	private static final TypeReference<Map<String, AnnouncementTranslation>> TRANSLATIONS = new TypeReference<>() {
	};

	private final JsonMapper json;

	AnnouncementRows(JsonMapper json) {
		this.json = json;
	}

	record AnnRow(UUID id, UUID schoolId, UUID authorId, String title, String body, String languageCode,
			Map<String, AnnouncementTranslation> translations, AnnouncementType type, boolean requiresAcknowledgement,
			List<AnnouncementChannel> channels, AnnouncementStatus status, Instant scheduledFor, String scheduleErrorCode,
			Basis basis, int revision, Instant publishedAt, Integer recipientCount, Instant deliveryCompletedAt,
			Instant withdrawnAt, UUID withdrawnBy, String withdrawalReason, Instant createdAt, Instant updatedAt) {
	}

	AnnRow map(ResultSet rs) throws SQLException {
		String[] channels = (String[]) rs.getArray("channels").getArray();
		List<AnnouncementChannel> list = new ArrayList<>();
		for (String c : channels) {
			list.add(AnnouncementChannel.valueOf(c));
		}
		return new AnnRow(Rows.uuid(rs, "id"), Rows.uuid(rs, "school_id"), Rows.uuid(rs, "author_id"), rs.getString("title"),
				rs.getString("body"), rs.getString("body_language_code"), translations(rs.getString("translations")),
				AnnouncementType.valueOf(rs.getString("announcement_type")), rs.getBoolean("requires_acknowledgement"), list,
				AnnouncementStatus.valueOf(rs.getString("status")), Rows.instant(rs, "scheduled_for"),
				rs.getString("schedule_error_code"), Basis.valueOf(rs.getString("authored_as")), rs.getInt("revision"),
				Rows.instant(rs, "published_at"), Rows.integer(rs, "recipient_count"),
				Rows.instant(rs, "delivery_completed_at"), Rows.instant(rs, "withdrawn_at"), Rows.uuid(rs, "withdrawn_by"),
				rs.getString("withdrawal_reason"), Rows.instant(rs, "created_at"), Rows.instant(rs, "updated_at"));
	}

	Map<String, AnnouncementTranslation> translations(String jsonText) {
		if (jsonText == null || jsonText.isBlank()) {
			return Map.of();
		}
		return this.json.readValue(jsonText, TRANSLATIONS);
	}

	String toJson(Map<String, AnnouncementTranslation> translations) {
		return this.json.writeValueAsString((translations == null) ? Map.of() : translations);
	}

}
