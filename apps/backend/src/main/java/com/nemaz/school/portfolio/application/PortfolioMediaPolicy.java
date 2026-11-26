package com.nemaz.school.portfolio.application;

import java.util.Optional;
import java.util.UUID;

import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.media.ContextType;
import com.nemaz.school.media.MediaContext;
import com.nemaz.school.media.MediaInfo;
import com.nemaz.school.media.MediaPurpose;
import com.nemaz.school.media.MediaPurposePolicy;
import com.nemaz.school.portfolio.application.PortfolioModels.ItemRow;
import com.nemaz.school.portfolio.application.PortfolioModels.Withheld;
import com.nemaz.school.portfolio.application.PortfolioViews.Relation;

import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who may upload and read media of purpose PORTFOLIO (R-30: there is no media ACL; reads derive from the owning item, rule book P4).
 * <ul>
 * <li><b>Create</b> (an upload for the school, before the item exists): an ACTIVE student while {@code portfolio.student_create.enabled},
 * or a teacher who may quick-capture in a class of the school; the feature must be on and the school ACTIVE.</li>
 * <li><b>Read</b> (signed URLs): the file must belong to an item that is not deleted, the caller must see the item now
 * ({@link PortfolioAudience}: status, effective visibility, live links), and the files must not be withheld from them for lack of
 * consent ({@link PortfolioConsent}). Until it is attached, a file is readable by its uploader alone; once attached the uploader is
 * not special any more.</li>
 * <li><b>Manage</b> (view in any state, delete through the media API): the uploader while the file is not attached; an attached file
 * is removed with its item.</li>
 * </ul>
 * Images are served only as re-encoded variants (the media module never signs an image original).
 */
/*
 * Collaborators are injected lazily: the media module collects every policy while it is being built, and these beans reach the media
 * module through the people module.
 */
@Component
@Transactional(readOnly = true)
class PortfolioMediaPolicy implements MediaPurposePolicy {

	private final JdbcClient jdbc;

	private final PortfolioAudience audience;

	private final PortfolioConsent consent;

	private final PortfolioViews views;

	private final PortfolioAccess access;

	private final PortfolioSettings settings;

	PortfolioMediaPolicy(JdbcClient jdbc, @Lazy PortfolioAudience audience, @Lazy PortfolioConsent consent, @Lazy PortfolioViews views,
			@Lazy PortfolioAccess access, @Lazy PortfolioSettings settings) {
		this.jdbc = jdbc;
		this.audience = audience;
		this.consent = consent;
		this.views = views;
		this.access = access;
		this.settings = settings;
	}

	@Override
	public MediaPurpose purpose() {
		return MediaPurpose.PORTFOLIO;
	}

	@Override
	public boolean canCreate(CurrentUser user, MediaContext context) {
		if (context.type() != ContextType.SCHOOL) {
			return false;
		}
		UUID schoolId = context.id();
		if (!this.settings.featureEnabled(schoolId) || !this.access.schoolActive(schoolId)) {
			return false;
		}
		if (this.access.people().isActiveStudent(schoolId, user.userId())) {
			return this.settings.studentCreate(schoolId);
		}
		return this.jdbc.sql("""
				select exists (select 1 from class_teachers ct join classes c on c.id = ct.class_id and c.school_id = :school
				  where ct.teacher_id = :user and ct.status = 'ACTIVE' and c.status <> 'ARCHIVED'
				    and (ct.role in ('LEAD', 'CO_TEACHER', 'SUBJECT_TEACHER') or (ct.role = 'ASSISTANT' and :assistants)))""")
			.param("school", schoolId)
			.param("user", user.userId())
			.param("assistants", this.settings.assistantsCapture(schoolId))
			.query(Boolean.class)
			.single();
	}

	private Optional<UUID> itemOf(UUID mediaId) {
		return this.jdbc.sql("select item_id from portfolio_attachments where media_id = :m")
			.param("m", mediaId)
			.query(UUID.class)
			.optional();
	}

	@Override
	public boolean ownerAlwaysReads(MediaInfo media) {
		return itemOf(media.id()).isEmpty();
	}

	@Override
	public boolean canRead(CurrentUser user, MediaInfo media) {
		UUID itemId = itemOf(media.id()).orElse(null);
		if (itemId == null) {
			return false;
		}
		ItemRow item = this.audience.raw(itemId).orElse(null);
		if (item == null || !this.settings.featureEnabled(item.schoolId())) {
			return false;
		}
		if (this.audience.find(user, itemId).isEmpty()) {
			return false;
		}
		Withheld reason = this.consent.withheld(item);
		if (reason == Withheld.NONE) {
			return true;
		}
		Relation rel = this.views.relations(user, java.util.List.of(item)).getFirst();
		return rel.self() || rel.reviewer() || (reason == Withheld.OWN_CONSENT && rel.ownGuardian());
	}

	@Override
	public boolean canManage(CurrentUser user, MediaInfo media) {
		return media.ownerId().equals(user.userId()) && itemOf(media.id()).isEmpty();
	}

}
