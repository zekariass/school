package com.nemaz.school.communications.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.communications.application.AnnouncementModels.AnnouncementStatus;
import com.nemaz.school.communications.application.AnnouncementModels.AnnouncementView;
import com.nemaz.school.communications.application.AnnouncementRows.AnnRow;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading and acknowledging, which are recorded apart from delivery ({@code announcement_acknowledgements}): the first time
 * a person opens an announcement, and (when it asks for one) the moment they confirm they have read it and for which
 * revision. Both are idempotent: the first time stays, repeating is harmless. Whoever may read the announcement (a
 * recipient, a late joiner, the author, a manager) can record; nothing is recorded for a draft or a withdrawn announcement,
 * and a withdrawn one cannot be acknowledged. Not audited row by row (own data, high volume); the report counts them.
 */
@Service
public class AnnouncementReceipts {

	private final JdbcClient jdbc;

	private final AnnouncementViews views;

	private final AnnouncementAccess access;

	private final Clock clock;

	AnnouncementReceipts(JdbcClient jdbc, AnnouncementViews views, AnnouncementAccess access, Clock clock) {
		this.jdbc = jdbc;
		this.views = views;
		this.access = access;
		this.clock = clock;
	}

	@Transactional
	public void markRead(CurrentUser user, UUID schoolId, UUID id) {
		AnnRow row = readable(user, schoolId, id);
		if (row.status() == AnnouncementStatus.PUBLISHED) {
			upsert(user, row, false);
		}
	}

	@Transactional
	public AnnouncementView acknowledge(CurrentUser user, UUID schoolId, UUID id) {
		AnnRow row = readable(user, schoolId, id);
		if (row.status() == AnnouncementStatus.WITHDRAWN) {
			throw ApiException.conflict("announcement.withdrawn");
		}
		if (!row.requiresAcknowledgement()) {
			throw ApiException.conflict("announcement.ack_not_required");
		}
		upsert(user, row, true);
		return this.views.view(user, schoolId, id);
	}

	private AnnRow readable(CurrentUser user, UUID schoolId, UUID id) {
		this.access.requireRead(user, schoolId, this.access.profile(user));
		AnnRow row = this.views.load(schoolId, id);
		boolean live = row.status() == AnnouncementStatus.PUBLISHED || row.status() == AnnouncementStatus.WITHDRAWN;
		boolean own = row.authorId().equals(user.userId()) || this.access.manager(user, schoolId);
		if (!live || !(own || this.views.canRead(user, row))) {
			throw ApiException.notFound("announcement.not_found");
		}
		return row;
	}

	private void upsert(CurrentUser user, AnnRow row, boolean acknowledge) {
		Timestamp now = Timestamp.from(this.clock.instant().truncatedTo(ChronoUnit.MICROS));
		this.jdbc.sql("""
				insert into announcement_acknowledgements (announcement_id, user_id, school_id, first_read_at, acknowledged_at,
				                                           acknowledged_revision)
				values (:id, :me, :school, cast(:now as timestamptz), case when cast(:ack as boolean) then cast(:now as timestamptz) end,
				        case when cast(:ack as boolean) then cast(:rev as int) end)
				on conflict (announcement_id, user_id) do update set
				  first_read_at = coalesce(announcement_acknowledgements.first_read_at, excluded.first_read_at),
				  acknowledged_at = coalesce(announcement_acknowledgements.acknowledged_at, excluded.acknowledged_at),
				  acknowledged_revision = greatest(coalesce(announcement_acknowledgements.acknowledged_revision, 0),
				                                   coalesce(excluded.acknowledged_revision, 0))
				    * case when announcement_acknowledgements.acknowledged_at is null and excluded.acknowledged_at is null
				           then null else 1 end""")
			.param("id", row.id())
			.param("me", user.userId())
			.param("school", row.schoolId())
			.param("now", now)
			.param("ack", acknowledge)
			.param("rev", row.revision())
			.update();
	}

}
