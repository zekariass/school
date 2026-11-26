/**
 * Student portfolios, basic (Implementation Plan M12, Database_Design §4.16, R-09, R-30, spec §100, §124, §126, §131). A portfolio
 * holds a child's own work and photos, so the rule book is {@code docs/Portfolio_Rules.md} and the most restrictive choice was
 * taken wherever a rule was silent.
 * <ul>
 * <li><b>One visibility predicate</b> ({@code application.PortfolioVisibility}, SQL) answers "may this user see this item" for
 * single reads, lists, galleries and the media policy alike. A non-creator sees an item only when it is APPROVED, within its
 * <em>effective</em> visibility: the lower of the item's visibility and the school cap {@code portfolio.max_visibility}.</li>
 * <li><b>Consent gate</b> ({@code application.PortfolioConsent}): files visible beyond the student's own guardians (or showing
 * other children) need MEDIA_PUBLICATION consent for the student and every tagged child, or the creator's explicit confirmation;
 * consent is re-read on every request and withdrawal withholds the files at once.</li>
 * <li><b>Workflow</b> ({@code application.PortfolioWorkflow}): DRAFT, SUBMITTED, APPROVED, CHANGES_REQUESTED, REJECTED, ARCHIVED, with
 * every transition appended to the append-only {@code portfolio_approvals}.</li>
 * <li><b>Media</b> (purpose PORTFOLIO) is read only through the owning item (R-30, {@code PortfolioMediaPolicy}). Audio and video
 * are deferred; sharing to the class story is deferred (the endpoint answers 422).</li>
 * <li><b>Text</b> (title, reflection, notes, comments) is plain text and never appears in logs, events, audit metadata or
 * notifications.</li>
 * </ul>
 * Other modules use the events of this package ({@link com.nemaz.school.portfolio.PortfolioItemSubmitted},
 * {@link com.nemaz.school.portfolio.PortfolioItemReviewed}, {@link com.nemaz.school.portfolio.PortfolioCommentPosted}), which carry ids only.
 */
@ApplicationModule(displayName = "Portfolio")
package com.nemaz.school.portfolio;

import org.springframework.modulith.ApplicationModule;
