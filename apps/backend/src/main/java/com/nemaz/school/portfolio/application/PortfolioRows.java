package com.nemaz.school.portfolio.application;

import java.sql.ResultSet;
import java.sql.SQLException;

import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.portfolio.application.PortfolioModels.ItemRow;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioItemType;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioStatus;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioVisibility;

/** The columns of {@code portfolio_items} that {@link ItemRow} reads, and the row mapper. */
final class PortfolioRows {

	static final String ITEM_COLUMNS = "i.id, i.portfolio_id, i.school_id, i.student_id, i.class_id, i.subject_id, i.created_by,"
			+ " i.item_type, i.title, i.description, i.reflection, i.text_content, i.work_date, i.status, i.visibility,"
			+ " i.is_featured, i.shows_other_children, i.media_confirmed_at, i.media_confirmed_by, i.created_at,"
			+ " i.submitted_at, i.reviewed_at, i.archived_at";

	private PortfolioRows() {
	}

	static ItemRow item(ResultSet rs, int row) throws SQLException {
		return new ItemRow(Rows.uuid(rs, "id"), Rows.uuid(rs, "portfolio_id"), Rows.uuid(rs, "school_id"),
				Rows.uuid(rs, "student_id"), Rows.uuid(rs, "class_id"), Rows.uuid(rs, "subject_id"), Rows.uuid(rs, "created_by"),
				PortfolioItemType.valueOf(rs.getString("item_type")), rs.getString("title"), rs.getString("description"),
				rs.getString("reflection"), rs.getString("text_content"), Rows.date(rs, "work_date"),
				PortfolioStatus.valueOf(rs.getString("status")), PortfolioVisibility.valueOf(rs.getString("visibility")),
				rs.getBoolean("is_featured"), rs.getBoolean("shows_other_children"), Rows.instant(rs, "media_confirmed_at"),
				Rows.uuid(rs, "media_confirmed_by"), Rows.instant(rs, "created_at"), Rows.instant(rs, "submitted_at"),
				Rows.instant(rs, "reviewed_at"), Rows.instant(rs, "archived_at"));
	}

}
