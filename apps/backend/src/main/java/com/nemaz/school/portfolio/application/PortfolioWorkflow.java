package com.nemaz.school.portfolio.application;

import java.util.Optional;

import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioStatus;

/**
 * The status state machine of an item (rule book P2), the only place the legal moves are written down:
 * <pre>
 * DRAFT --submit--> SUBMITTED --approve--> APPROVED --archive--> ARCHIVED --unarchive--> APPROVED
 * CHANGES_REQUESTED --submit--> SUBMITTED;  SUBMITTED --request changes--> CHANGES_REQUESTED;  SUBMITTED --reject--> REJECTED (end)
 * </pre>
 * Quick capture creates an item directly as APPROVED. Who may make a move is {@link PortfolioItemService} and
 * {@link PortfolioReviewService}'s business; this class only says whether the move exists.
 */
public final class PortfolioWorkflow {

	/** A move between statuses. */
	public enum Move {

		SUBMIT, APPROVE, REQUEST_CHANGES, REJECT, ARCHIVE, UNARCHIVE

	}

	private PortfolioWorkflow() {
	}

	/** The status {@code move} leads to from {@code from}, empty when the move is not legal there. */
	public static Optional<PortfolioStatus> next(PortfolioStatus from, Move move) {
		return Optional.ofNullable(switch (move) {
			case SUBMIT -> (from == PortfolioStatus.DRAFT || from == PortfolioStatus.CHANGES_REQUESTED) ? PortfolioStatus.SUBMITTED : null;
			case APPROVE -> (from == PortfolioStatus.SUBMITTED) ? PortfolioStatus.APPROVED : null;
			case REQUEST_CHANGES -> (from == PortfolioStatus.SUBMITTED) ? PortfolioStatus.CHANGES_REQUESTED : null;
			case REJECT -> (from == PortfolioStatus.SUBMITTED) ? PortfolioStatus.REJECTED : null;
			case ARCHIVE -> (from == PortfolioStatus.APPROVED) ? PortfolioStatus.ARCHIVED : null;
			case UNARCHIVE -> (from == PortfolioStatus.ARCHIVED) ? PortfolioStatus.APPROVED : null;
		});
	}

	/** The target of a move that must be legal; 409 {@code portfolio.invalid_state} otherwise. */
	static PortfolioStatus require(PortfolioStatus from, Move move) {
		return next(from, move).orElseThrow(() -> com.nemaz.school.common.error.ApiException.conflict("portfolio.invalid_state"));
	}

}
