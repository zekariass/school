package com.nemaz.school.moderation.application;

import java.util.UUID;

import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.moderation.LegalHold;
import com.nemaz.school.safeguarding.IncidentHolds;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The legal-hold question for other modules (docs/Safeguarding_Rules.md S6.3). The erasure and export workflows are not built
 * yet; when they are, they call {@link #requireNotHeld} and refuse with the clear message
 * {@code legal_hold.erasure_refused}, escalating to a human.
 */
@Service
class LegalHoldService implements LegalHold {

	private final JdbcClient jdbc;

	private final IncidentHolds incidents;

	LegalHoldService(JdbcClient jdbc, IncidentHolds incidents) {
		this.jdbc = jdbc;
		this.incidents = incidents;
	}

	@Override
	@Transactional(readOnly = true)
	public boolean isHeld(UUID userId) {
		boolean inCase = this.jdbc.sql("""
				select exists (select 1 from moderation_cases c where c.legal_hold
				  and (c.subject_user_id = :u or exists (select 1 from content_reports r where r.case_id = c.id and r.reporter_id = :u)))""")
			.param("u", userId)
			.query(Boolean.class)
			.single();
		return inCase || this.incidents.userInHeldIncident(userId);
	}

	@Override
	@Transactional(readOnly = true)
	public void requireNotHeld(UUID userId) {
		if (isHeld(userId)) {
			throw ApiException.conflict("legal_hold.erasure_refused");
		}
	}

}
