package com.nemaz.school.portfolio.application;

import java.util.List;
import java.util.UUID;

import com.nemaz.school.academic.SchoolPeople;
import com.nemaz.school.academic.SchoolPeople.PersonCard;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.portfolio.application.PortfolioModels.ItemRow;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioAuthorRole;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioHistoryEntry;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioStatus;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The status history of an item: {@code portfolio_approvals} is append-only (a database trigger refuses UPDATE and DELETE), and
 * every transition, featuring, archiving and deletion appends one row with the actor, from and to status and an optional note.
 */
@Component
class PortfolioLedger {

	private final JdbcClient jdbc;

	private final SchoolPeople people;

	PortfolioLedger(JdbcClient jdbc, SchoolPeople people) {
		this.jdbc = jdbc;
		this.people = people;
	}

	/** Appends a row; returns its id. {@code from} and {@code to} may be null (creation, deletion). */
	UUID append(UUID itemId, String action, PortfolioStatus from, PortfolioStatus to, UUID actorId, String note) {
		UUID id = Ids.newId();
		this.jdbc.sql("""
				insert into portfolio_approvals (id, item_id, action, from_status, to_status, actor_id, note)
				values (:id, :item, :action, :from, :to, :actor, :note)""")
			.param("id", id)
			.param("item", itemId)
			.param("action", action)
			.param("from", (from != null) ? from.name() : null)
			.param("to", (to != null) ? to.name() : null)
			.param("actor", actorId)
			.param("note", note)
			.update();
		return id;
	}

	/** The history, oldest first. Reviewers are named, the student appears by role only. */
	List<PortfolioHistoryEntry> history(ItemRow item) {
		record Row(UUID id, String action, String from, String to, UUID actor, String note, java.time.Instant at) {
		}
		List<Row> rows = this.jdbc.sql("""
				select id, action, from_status, to_status, actor_id, note, created_at from portfolio_approvals
				where item_id = :item order by id""")
			.param("item", item.id())
			.query((rs, i) -> new Row(Rows.uuid(rs, "id"), rs.getString("action"), rs.getString("from_status"),
					rs.getString("to_status"), Rows.uuid(rs, "actor_id"), rs.getString("note"), Rows.instant(rs, "created_at")))
			.list();
		java.util.Map<UUID, PersonCard> cards = this.people.cards(rows.stream().map(Row::actor).distinct().toList());
		return rows.stream().map((r) -> {
			boolean student = r.actor().equals(item.studentId());
			PersonCard card = cards.get(r.actor());
			return new PortfolioHistoryEntry(r.id(), r.action(), r.from(), r.to(),
					student ? PortfolioAuthorRole.STUDENT : PortfolioAuthorRole.TEACHER,
					(!student && card != null) ? card.displayName() : null, r.note(), r.at());
		}).toList();
	}

}
