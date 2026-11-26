package com.nemaz.school.communications.application;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.communications.application.AnnouncementAccess.Pred;
import com.nemaz.school.communications.application.AnnouncementAccess.ViewerProfile;
import com.nemaz.school.communications.application.AnnouncementModels.AnnouncementAbilities;
import com.nemaz.school.communications.application.AnnouncementModels.AnnouncementAudienceView;
import com.nemaz.school.communications.application.AnnouncementModels.AnnouncementChannel;
import com.nemaz.school.communications.application.AnnouncementModels.AnnouncementMine;
import com.nemaz.school.communications.application.AnnouncementModels.AnnouncementStatus;
import com.nemaz.school.communications.application.AnnouncementModels.AnnouncementView;
import com.nemaz.school.communications.application.AnnouncementModels.AudienceType;
import com.nemaz.school.communications.application.AnnouncementRows.AnnRow;
import com.nemaz.school.identity.UserDirectory;
import com.nemaz.school.identity.UserDirectory.UserProfileView;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the single-announcement view for one reader and answers "may this person read it". What each reader gets:
 * <ul>
 * <li>the author and school announcement managers: everything (also drafts), the audiences, delivery state and what they
 * may do;</li>
 * <li>other readers (see {@link AnnouncementAccess}): PUBLISHED and WITHDRAWN announcements, without audiences and
 * recipient counts, and a WITHDRAWN one without its body and translations (the tombstone shows title, who withdrew
 * when and the public note).</li>
 * </ul>
 */
@Component
@Transactional(readOnly = true)
public class AnnouncementViews {

	private final JdbcClient jdbc;

	private final AnnouncementAccess access;

	private final AnnouncementRows rows;

	private final UserDirectory users;

	private final Clock clock;

	AnnouncementViews(JdbcClient jdbc, AnnouncementAccess access, AnnouncementRows rows, UserDirectory users, Clock clock) {
		this.jdbc = jdbc;
		this.access = access;
		this.rows = rows;
		this.users = users;
		this.clock = clock;
	}

	/** The announcement for this reader; 404 unless the school is visible and the announcement readable. */
	public AnnouncementView view(CurrentUser user, UUID schoolId, UUID id) {
		ViewerProfile profile = this.access.profile(user);
		this.access.requireRead(user, schoolId, profile);
		AnnRow row = load(schoolId, id);
		boolean manager = this.access.manager(user, schoolId);
		boolean mayManage = manager || row.authorId().equals(user.userId());
		if (!mayManage && !(live(row) && readable(user, profile, manager, row.id()))) {
			throw ApiException.notFound("announcement.not_found");
		}
		return build(user, row, profile, mayManage);
	}

	/** True when the user may read the (PUBLISHED or WITHDRAWN) announcement as a recipient or late joiner. */
	boolean canRead(CurrentUser user, AnnRow row) {
		ViewerProfile profile = this.access.profile(user);
		return live(row) && readable(user, profile, this.access.manager(user, row.schoolId()), row.id());
	}

	/** The author and managers pass; a reader of a live announcement gets 403; everybody else 404. */
	void requireMayManage(CurrentUser user, AnnRow row) {
		if (row.authorId().equals(user.userId()) || this.access.manager(user, row.schoolId())) {
			return;
		}
		if (canRead(user, row)) {
			throw ApiException.forbidden("announcement.access_denied");
		}
		throw ApiException.notFound("announcement.not_found");
	}

	private static boolean live(AnnRow row) {
		return row.status() == AnnouncementStatus.PUBLISHED || row.status() == AnnouncementStatus.WITHDRAWN;
	}

	private boolean readable(CurrentUser user, ViewerProfile profile, boolean manager, UUID id) {
		Pred pred = this.access.readable(profile, false, manager, this.clock.instant());
		return this.jdbc.sql("select exists (select 1 from announcements an where an.id = :ann and an.deleted_at is null and "
				+ "an.status in ('PUBLISHED', 'WITHDRAWN') and " + pred.sql() + ")")
			.param("ann", id)
			.params(pred.params())
			.query(Boolean.class)
			.single();
	}

	Map<String, AnnouncementModels.AnnouncementTranslation> translationsOf(String json) {
		return this.rows.translations(json);
	}

	AnnRow load(UUID schoolId, UUID id) {
		return this.jdbc.sql("select " + AnnouncementRows.COLUMNS
				+ " from announcements an where an.id = :id and an.school_id = :school and an.deleted_at is null")
			.param("id", id)
			.param("school", schoolId)
			.query((rs, i) -> this.rows.map(rs))
			.optional()
			.orElseThrow(() -> ApiException.notFound("announcement.not_found"));
	}

	private AnnouncementView build(CurrentUser user, AnnRow row, ViewerProfile profile, boolean mayManage) {
		UUID id = row.id();
		String schoolName = this.jdbc.sql("select name from schools where id = :id")
			.param("id", row.schoolId())
			.query(String.class)
			.single();
		Map<UUID, UserProfileView> authors = this.users.profiles(List.of(row.authorId()));
		UserProfileView author = authors.get(row.authorId());
		boolean withdrawn = row.status() == AnnouncementStatus.WITHDRAWN;
		boolean showBody = mayManage || !withdrawn;
		java.time.Instant editedAt = this.jdbc.sql("select max(edited_at) from announcement_revisions where announcement_id = :id")
			.param("id", id)
			.query((rs, i) -> (rs.getTimestamp(1) != null) ? rs.getTimestamp(1).toInstant() : null)
			.optional()
			.orElse(null);
		AnnouncementMine mine = mine(user, row, profile);
		boolean live = live(row);
		boolean open = row.status() == AnnouncementStatus.DRAFT || row.status() == AnnouncementStatus.SCHEDULED;
		AnnouncementAbilities can = new AnnouncementAbilities(mayManage && row.status() != AnnouncementStatus.WITHDRAWN,
				mayManage && open, mayManage && open, mayManage && row.status() == AnnouncementStatus.PUBLISHED,
				mayManage && open, mayManage && live,
				mayManage && row.status() == AnnouncementStatus.PUBLISHED && row.requiresAcknowledgement());
		List<AnnouncementChannel> channels = mayManage ? row.channels() : null;
		return new AnnouncementView(id, row.schoolId(), schoolName, row.authorId(),
				(author != null) ? author.displayName() : null, row.type(), row.status(), row.title(),
				showBody ? row.body() : null, "PLAIN_TEXT", row.languageCode(), showBody ? row.translations() : Map.of(),
				row.requiresAcknowledgement(), channels, mayManage ? audienceViews(id) : null, row.scheduledFor(),
				mayManage ? row.scheduleErrorCode() : null, row.publishedAt(), row.revision(), editedAt, row.withdrawnAt(),
				row.withdrawalReason(), mayManage ? row.recipientCount() : null,
				mayManage ? (row.deliveryCompletedAt() != null) : null, row.createdAt(), mine, can);
	}

	private AnnouncementMine mine(CurrentUser user, AnnRow row, ViewerProfile profile) {
		record Ack(java.time.Instant read, java.time.Instant acknowledged, Integer revision) {
		}
		Ack ack = this.jdbc.sql("""
				select first_read_at, acknowledged_at, acknowledged_revision from announcement_acknowledgements
				where announcement_id = :id and user_id = :me""")
			.param("id", row.id())
			.param("me", user.userId())
			.query((rs, i) -> new Ack(Rows.instant(rs, "first_read_at"), Rows.instant(rs, "acknowledged_at"),
					Rows.integer(rs, "acknowledged_revision")))
			.optional()
			.orElse(new Ack(null, null, null));
		boolean recipient = this.jdbc.sql("select exists (select 1 from announcement_recipients where announcement_id = :id and user_id = :me)")
			.param("id", row.id())
			.param("me", user.userId())
			.query(Boolean.class)
			.single();
		return new AnnouncementMine(ack.read() != null, ack.read(), ack.acknowledged() != null, ack.acknowledged(),
				ack.revision(), recipient);
	}

	List<AnnouncementAudienceView> audienceViews(UUID id) {
		return this.jdbc.sql("""
				select audience_type, campus_id, grade_level_id, class_id, user_id, recipient_roles
				from announcement_audiences where announcement_id = :id order by id""").param("id", id).query((rs, i) -> {
			java.util.Set<AnnouncementModels.RecipientRole> roles = new java.util.TreeSet<>();
			for (String role : (String[]) rs.getArray("recipient_roles").getArray()) {
				roles.add(AnnouncementModels.RecipientRole.valueOf(role));
			}
			return new AnnouncementAudienceView(AudienceType.valueOf(rs.getString("audience_type")),
					Rows.uuid(rs, "campus_id"), Rows.uuid(rs, "grade_level_id"), Rows.uuid(rs, "class_id"),
					Rows.uuid(rs, "user_id"), roles);
		}).list();
	}

}
