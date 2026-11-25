package com.nemaz.school.consent;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * The effective consent of a child for one type in one school, and why. {@code decidingRecordIds} are the consent
 * records that decided it (the denials, or the grants); empty for UNKNOWN without decisions.
 */
public record ConsentStatus(@NotNull Effective status, @NotNull Reason reason, @NotNull List<UUID> decidingRecordIds) {

	public ConsentStatus {
		decidingRecordIds = List.copyOf(decidingRecordIds);
	}

	public boolean granted() {
		return this.status == Effective.GRANTED;
	}

	/** GRANTED, DENIED or UNKNOWN. For MEDIA_PUBLICATION, UNKNOWN means "not allowed". */
	public enum Effective {

		GRANTED, DENIED, UNKNOWN

	}

	@Schema(description = "GRANTED: at least one current guardian granted and nobody refused; DENIED / WITHDRAWN: a current guardian (or a paper record) refused or withdrew; RECONSENT_REQUIRED: the only grants are on a version superseded by one that requires new consent; NO_DECISION: nobody decided yet")
	public enum Reason {

		GRANTED, DENIED, WITHDRAWN, RECONSENT_REQUIRED, NO_DECISION

	}

}
