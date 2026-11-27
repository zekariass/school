import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { messages } from "@school/i18n";
import { NextIntlClientProvider } from "next-intl";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import { IncidentView } from "@/components/safeguarding/incident-view";
import { IncidentsView } from "@/components/safeguarding/incidents-view";
import { ApiRequestError } from "@/lib/api-error";
import { MOD_PERM } from "@/lib/moderation/access";
import { CONTENT_GC_MS, QUEUE_GC_MS, modk } from "@/lib/moderation/keys";
import type { CaseDetail, CaseSummary, IncidentSummary, ReviewContent } from "@/lib/moderation/types";
import { CaseView, HistoryList, ReportsList } from "./case-view";
import { ContentBody, ContentPanel } from "./content-panel";
import { QueueView } from "./queue-view";
import { ReportFields } from "./report-dialog";
import { ModerationScopeProvider, type ModerationScope } from "./scope";

vi.mock("@/components/calendar-preference", () => ({
  useFormatInstant: () => (instant: string) => instant,
  useFormatDate: () => (date: string | null | undefined) => date ?? "",
}));
vi.mock("@/i18n/navigation", () => ({
  Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));
vi.mock("@/components/media/media-image", () => ({
  MediaImage: ({ mediaId, alt }: { mediaId: string; alt: string }) => <span data-media={mediaId} role="img" aria-label={alt} />,
}));

const QUIET = { defaultOptions: { queries: { retryOnMount: false } } };
const en = messages.en;
const SCHOOL = "sch-1";
const ME = "u-me";

function scope(kind: "school" | "platform", permissions: string[]): ModerationScope {
  return kind === "school"
    ? {
        kind,
        schoolId: SCHOOL,
        schoolName: "Sunrise",
        queuePath: "/s/sunrise/moderation",
        incidentsPath: "/s/sunrise/safeguarding",
        messageReviewPath: "/s/sunrise/messages/review",
        viewer: { userId: ME, permissions: new Set(permissions) },
      }
    : { kind, schoolId: null, schoolName: null, queuePath: "/platform/moderation", incidentsPath: "/platform/safeguarding", messageReviewPath: null, viewer: { userId: ME, permissions: new Set(permissions) } };
}

function page(s: ModerationScope, node: React.ReactNode, client = new QueryClient(QUIET)) {
  return renderToStaticMarkup(
    <NextIntlClientProvider locale="en" messages={en}>
      <QueryClientProvider client={client}>
        <ModerationScopeProvider scope={s}>{node}</ModerationScopeProvider>
      </QueryClientProvider>
    </NextIntlClientProvider>,
  );
}

function failQuery(client: QueryClient, key: readonly unknown[], error: unknown) {
  client.getQueryCache().build(client, { queryKey: key }).setState({ status: "error", error, fetchStatus: "idle", fetchFailureCount: 1 } as never);
}
const problem = (status: number, code = "x", detail = "The API's sentence.") => new ApiRequestError(status, { type: "about:blank", title: "t", status, code, detail } as never);
const infinite = <T,>(items: T[]) => ({ pages: [{ items }], pageParams: [undefined] });

const MODERATOR = [MOD_PERM.schoolModeration];
const LEAD = [MOD_PERM.schoolModeration, MOD_PERM.safeguardingRead, MOD_PERM.safeguardingManage];
const ADMINISTRATOR = ["school.read", "school.manage", "school.settings.manage", "school.staff.manage", "school.students.manage"];
const PLATFORM_MODERATOR = [MOD_PERM.platformModeration, MOD_PERM.platformUsersLock];

const row = (over: Partial<CaseSummary> = {}): CaseSummary => ({
  id: "case-1",
  schoolId: SCHOOL,
  queue: "SCHOOL",
  targetType: "POST_COMMENT",
  targetId: "t-1",
  priority: "HIGH",
  status: "OPEN",
  reportCount: 2,
  reporterCount: 2,
  reasons: { HARASSMENT: 2 },
  subjectRole: "STUDENT",
  legalHold: false,
  createdAt: "2026-11-26T10:00:00Z",
  ...over,
});

describe("the report form", () => {
  const draft = { reason: null, details: "" } as const;

  it("lists the reasons, asks for details up to 1,000 characters and says nothing about what happens next", () => {
    const html = page(scope("school", []), <ReportFields target="POST" draft={draft} onChange={() => undefined} problem={null} />);
    for (const reason of ["INAPPROPRIATE", "HARASSMENT", "SAFETY_CONCERN", "PRIVACY", "SPAM", "INACCURATE", "COPYRIGHT", "OTHER"]) {
      expect(html).toContain(`data-testid="report-reason-${reason}"`);
    }
    expect(html).toContain(en.moderation.report.detailsHint.replace("{max}", "1000").replaceAll("'", "&#x27;"));
    expect(html).not.toContain('data-testid="report-reason-required"');
    expect(html).not.toContain('data-testid="report-emergency"');
    // The words of the form never promise an outcome.
    expect(JSON.stringify(en.moderation.report)).not.toMatch(/decid|hidden|removed|suspend|punish|police/i);
  });

  it("does not offer copyright or 'inaccurate' for a person or a message", () => {
    const html = page(scope("school", []), <ReportFields target="USER" draft={draft} onChange={() => undefined} problem={null} />);
    expect(html).not.toContain("report-reason-COPYRIGHT");
    expect(html).not.toContain("report-reason-INACCURATE");
    expect(html).toContain("report-reason-SAFETY_CONCERN");
  });

  it("shows a missing reason and a too-long text inline", () => {
    const missing = page(scope("school", []), <ReportFields target="POST" draft={draft} onChange={() => undefined} problem="reasonMissing" />);
    expect(missing).toContain('data-testid="report-reason-required"');
    const long = page(scope("school", []), <ReportFields target="POST" draft={{ reason: "SPAM", details: "x".repeat(1001) }} onChange={() => undefined} problem="detailsLong" />);
    expect(long).toContain(en.moderation.report.detailsTooLong.replace("{max}", "1000"));
  });

  it("shows the API's emergency text next to a safety concern and marks the chosen reason", () => {
    const html = page(
      scope("school", []),
      <ReportFields target="MESSAGE" draft={{ reason: "SAFETY_CONCERN", details: "" }} onChange={() => undefined} problem={null} emergency={<p data-testid="from-api">API text</p>} />,
    );
    expect(html).toContain('data-testid="report-emergency"');
    expect(html).toContain("API text");
    expect(html).toMatch(/data-testid="report-reason-SAFETY_CONCERN"[^>]*checked|checked[^>]*data-testid="report-reason-SAFETY_CONCERN"/);
  });

  it("keeps the details out of the page's own markup until the person types them", () => {
    const html = page(scope("school", []), <ReportFields target="POST" draft={{ reason: "OTHER", details: "my private words" }} onChange={() => undefined} problem={null} />);
    // The text is in the textarea the person is typing in, and nowhere else.
    expect(html.match(/my private words/g)).toHaveLength(1);
  });
});

describe("the moderation queue per role", () => {
  const seeded = (items: CaseSummary[], key = modk.schoolQueue(SCHOOL, "OPEN", "", "")) => {
    const client = new QueryClient(QUIET);
    client.setQueryData(key, infinite(items));
    return client;
  };

  it.each([
    ["a school moderator", MODERATOR],
    ["a safeguarding lead", LEAD],
  ])("shows %s the cases of the school with references and enums only", (_who, permissions) => {
    const html = page(scope("school", permissions), <QueueView />, seeded([row(), row({ id: "case-2", priority: "NORMAL", targetType: "USER", reasons: { SPAM: 1 }, reporterCount: 1 })]));
    expect(html).toContain('data-testid="moderation-queue"');
    expect(html.match(/data-testid="case-row"/g)).toHaveLength(2);
    expect(html).toContain('href="/s/sunrise/moderation/case-1"');
    expect(html).toContain(en.moderation.queue.priority.HIGH);
    expect(html).toContain("2 people reported");
    // A list never carries who reported or what they wrote.
    expect(html).not.toMatch(/reporterId|details/);
  });

  it("shows an administrator without the moderation permission the page of an unknown address, and asks nothing", () => {
    const client = new QueryClient(QUIET);
    const html = page(scope("school", ADMINISTRATOR), <QueueView />, client);
    expect(html).toContain('data-testid="nothing-here"');
    expect(html).not.toContain('data-testid="moderation-queue"');
    expect(client.getQueryCache().getAll()).toHaveLength(0);
  });

  it.each([
    ["a teacher", []],
    ["a guardian", []],
    ["a student", []],
  ])("shows %s no queue and asks nothing", (_who, permissions) => {
    const client = new QueryClient(QUIET);
    const html = page(scope("school", permissions), <QueueView />, client);
    expect(html).toContain('data-testid="nothing-here"');
    expect(client.getQueryCache().getAll()).toHaveLength(0);
  });

  it("shows a platform moderator the platform queue, and a school moderator is not shown it", () => {
    const client = seeded([row({ queue: "PLATFORM", schoolId: undefined })], modk.platformQueue("OPEN", "", ""));
    const html = page(scope("platform", PLATFORM_MODERATOR), <QueueView />, client);
    expect(html).toContain('href="/platform/moderation/case-1"');
    expect(html).toContain(en.moderation.queue.subtitlePlatform);
    const denied = page(scope("platform", MODERATOR), <QueueView />);
    expect(denied).toContain('data-testid="nothing-here"');
  });

  it("explains an empty queue, and a failed one with the API's own sentence", () => {
    expect(page(scope("school", MODERATOR), <QueueView />, seeded([]))).toContain(en.moderation.queue.empty);
    const client = new QueryClient(QUIET);
    failQuery(client, modk.schoolQueue(SCHOOL, "OPEN", "", ""), problem(403, "forbidden", "You may not moderate here."));
    // useInfiniteQuery keeps its error state for the same key
    const html = page(scope("school", MODERATOR), <QueueView />, client);
    expect(html).toContain("You may not moderate here.");
  });

  it("keeps the lists of references for a few minutes only, in memory", () => {
    const client = new QueryClient(QUIET);
    page(scope("school", MODERATOR), <QueueView />, client);
    const query = client.getQueryCache().find({ queryKey: modk.schoolQueue(SCHOOL, "OPEN", "", "") });
    expect(query?.options.gcTime).toBe(QUEUE_GC_MS);
  });
});

describe("a case", () => {
  const detail = (over: Partial<CaseDetail> = {}, summary: Partial<CaseSummary> = {}): CaseDetail => ({
    summary: row(summary),
    subjectUserId: "u-subject",
    reports: [{ reasonCode: "HARASSMENT", reporterRole: "STUDENT", createdAt: "2026-11-26T10:00:00Z" }],
    actions: [],
    reporterIdentityVisible: false,
    ...over,
  });
  const seed = (d: CaseDetail) => {
    const client = new QueryClient(QUIET);
    client.setQueryData(modk.case("case-1"), d);
    return client;
  };
  const testids = (html: string) => [...html.matchAll(/data-testid="(case-[a-z-]+)"/g)].map((m) => m[1]);

  it("shows a school moderator the role of each reporter and the reason, never an identity or details", () => {
    const html = page(scope("school", MODERATOR), <CaseView caseId="case-1" />, seed(detail()));
    expect(html).toContain(en.moderation.queue.reason.HARASSMENT);
    expect(html).toContain("by a student");
    expect(html).toContain(en.moderation.case.reports.identityHidden);
    expect(html).not.toContain('data-testid="case-reporter-id"');
    expect(html).not.toContain('data-testid="case-report-details"');
    // Controls of a moderator: take, decide, hand up. No priority, no hold, no platform lock.
    expect(testids(html)).toEqual(expect.arrayContaining(["case-assign", "case-decide", "case-refer-up"]));
    expect(html).not.toContain('data-testid="case-priority-set"');
    expect(html).not.toContain('data-testid="case-hold-toggle"');
  });

  it("shows a lead the reporter's id and details exactly when the API sent them, plus priority and legal hold", () => {
    const d = detail({ reporterIdentityVisible: true, reports: [{ reasonCode: "SAFETY_CONCERN", reporterRole: "STUDENT", reporterId: "u-reporter", details: "she said it in the corridor" }] });
    const html = page(scope("school", LEAD), <CaseView caseId="case-1" />, seed(d));
    expect(html).toContain('data-testid="case-reporter-id"');
    expect(html).toContain("u-reporter");
    expect(html).toContain("she said it in the corridor");
    expect(html).toContain(en.moderation.case.reports.identityVisible);
    expect(html).toContain('data-testid="case-priority-set"');
    expect(html).toContain('data-testid="case-hold-toggle"');
  });

  it("never fills in a reporter the API did not name, even for a lead", () => {
    const html = page(scope("school", LEAD), <CaseView caseId="case-1" />, seed(detail({ reporterIdentityVisible: false })));
    expect(html).not.toContain('data-testid="case-reporter-id"');
  });

  it("offers the platform lock only to a holder of platform.users.lock, in the decision dialog's choices", () => {
    const html = page(scope("platform", PLATFORM_MODERATOR), <CaseView caseId="case-1" />, seed(detail({}, { queue: "PLATFORM" })));
    expect(html).toContain('data-testid="case-decide"');
    expect(html).not.toContain('data-testid="case-refer-up"');
    expect(html).toContain('data-testid="case-hold-toggle"');
  });

  it("offers reopen on a resolved case and 'show it again' after a hide, and no decision", () => {
    const d = detail({ actions: [{ id: "a1", action: "HIDE", actorId: ME, notes: "Hurtful to a child", createdAt: "2026-11-26T11:00:00Z" }] }, { status: "RESOLVED", decision: "HIDE" });
    const html = page(scope("school", MODERATOR), <CaseView caseId="case-1" />, seed(d));
    expect(testids(html)).toEqual(expect.arrayContaining(["case-reopen", "case-restore"]));
    expect(testids(html)).not.toContain("case-decide");
    // The reason a moderator wrote is in the history, as text.
    expect(html).toContain("Hurtful to a child");
    expect(html).toContain(en.moderation.case.history.you);
  });

  it("links an escalated case to its incident only when the API sent the incident's id", () => {
    const withIncident = page(scope("school", LEAD), <CaseView caseId="case-1" />, seed(detail({ incidentId: "inc-1" }, { status: "ESCALATED" })));
    expect(withIncident).toContain('href="/s/sunrise/safeguarding/inc-1"');
    const without = page(scope("school", MODERATOR), <CaseView caseId="case-1" />, seed(detail({}, { status: "ESCALATED" })));
    expect(without).not.toContain("safeguarding/");
    expect(without).not.toContain('data-testid="case-incident-link"');
  });

  it("treats a 404 as nothing here and says nothing about why", () => {
    const client = new QueryClient(QUIET);
    failQuery(client, modk.case("case-1"), problem(404, "moderation.case_not_found", "No such case."));
    const html = page(scope("school", MODERATOR), <CaseView caseId="case-1" />, client);
    expect(html).toContain('data-testid="nothing-here"');
    expect(html).toContain(en.errors.notFoundTitle);
    expect(html).not.toContain("No such case.");
    expect(html).not.toMatch(/not allowed|forbidden|permission|conflict/i);
  });

  it("shows a 403 with the API's sentence (the person is a moderator of something, just not this)", () => {
    const client = new QueryClient(QUIET);
    failQuery(client, modk.case("case-1"), problem(403, "moderation.forbidden", "Not yours to decide."));
    expect(page(scope("school", MODERATOR), <CaseView caseId="case-1" />, client)).toContain("Not yours to decide.");
  });

  it("drops the case and the content as soon as nobody is looking (gcTime 0)", () => {
    const client = new QueryClient(QUIET);
    page(scope("school", MODERATOR), <CaseView caseId="case-1" />, client);
    page(scope("school", MODERATOR), <ContentPanel caseId="case-1" />, client);
    expect(CONTENT_GC_MS).toBe(0);
    expect(client.getQueryCache().find({ queryKey: modk.case("case-1"), exact: true })?.options.gcTime).toBe(0);
    expect(client.getQueryCache().find({ queryKey: modk.content("case-1"), exact: true })?.options.gcTime).toBe(0);
  });

  it("lists reports and history without any content of the case itself", () => {
    const html = renderToStaticMarkup(
      <NextIntlClientProvider locale="en" messages={en}>
        <ReportsList reports={[]} identityVisible={false} />
        <HistoryList actions={[]} userId={ME} />
      </NextIntlClientProvider>,
    );
    expect(html).toContain(en.moderation.case.reports.none);
    expect(html).toContain(en.moderation.case.history.none);
  });
});

describe("the reported content", () => {
  it("is not read until the person asks: the panel only offers the audited read", () => {
    const client = new QueryClient(QUIET);
    const html = page(scope("school", MODERATOR), <ContentPanel caseId="case-1" />, client);
    expect(html).toContain('data-testid="content-read"');
    expect(html).toContain(en.moderation.content.audited);
    const query = client.getQueryCache().find({ queryKey: modk.content("case-1"), exact: true });
    expect(query?.state.fetchStatus).toBe("idle");
    expect(query?.state.dataUpdateCount).toBe(0);
  });

  const content: ReviewContent = {
    kind: "TEXT",
    items: [
      { type: "POST", id: "p1", authorRole: "TEACHER", status: "PUBLISHED", text: "Class trip <script>alert(1)</script> see https://example.com/a", mediaIds: ["m1"], createdAt: "2026-11-26T08:00:00Z", highlighted: false },
      { type: "COMMENT", id: "c1", authorRole: "STUDENT", status: "VISIBLE", text: "the reported words", mediaIds: [], createdAt: "2026-11-26T09:00:00Z", highlighted: true },
    ],
  };

  it("shows text as text: escaped, no links, the reported item marked, photos by id", () => {
    const html = page(scope("school", MODERATOR), <ContentBody content={content} />);
    expect(html).not.toContain("<script>");
    expect(html).toContain("&lt;script&gt;");
    expect(html).not.toContain("<a ");
    expect(html).toContain('data-highlighted="true"');
    expect(html).toContain(en.moderation.content.reported);
    expect(html).toContain('data-media="m1"');
    expect(html).toContain("the reported words");
  });

  it("points a message to the audited messaging review and never shows its text", () => {
    const pointer: ReviewContent = { kind: "MESSAGING_REVIEW", reviewConversationId: "conv-1", items: [] };
    const school = page(scope("school", MODERATOR), <ContentBody content={pointer} />);
    expect(school).toContain('data-testid="content-message-pointer"');
    expect(school).toContain('href="/s/sunrise/messages/review"');
    expect(school).not.toContain("conv-1");
    const platform = page(scope("platform", PLATFORM_MODERATOR), <ContentBody content={pointer} />);
    expect(platform).toContain('data-testid="content-message-pointer"');
    expect(platform).not.toContain("<a ");
  });
});

describe("safeguarding incidents", () => {
  const incident = (over: Partial<IncidentSummary> = {}): IncidentSummary => ({
    id: "inc-1",
    schoolId: SCHOOL,
    category: "WELLBEING",
    severity: "HIGH",
    status: "OPEN",
    legalHold: false,
    createdAt: "2026-11-26T10:00:00Z",
    updatedAt: "2026-11-26T10:00:00Z",
    ...over,
  });
  const key = modk.incidents(SCHOOL, "", "", "");

  it("lists the incidents to a safeguarding lead: metadata only, a link to each", () => {
    const client = new QueryClient(QUIET);
    client.setQueryData(key, infinite([incident(), incident({ id: "inc-2", severity: "LOW", assignedTo: ME })]));
    const html = page(scope("school", LEAD), <IncidentsView />, client);
    expect(html.match(/data-testid="incident-row"/g)).toHaveLength(2);
    expect(html).toContain('href="/s/sunrise/safeguarding/inc-1"');
    expect(html).toContain(en.safeguarding.incidents.assignedToYou);
    expect(html).not.toMatch(/narrative|note/i);
  });

  it.each([
    ["a school administrator", ADMINISTRATOR],
    ["a school moderator", MODERATOR],
    ["a platform administrator", ["platform.schools.manage", "audit.read", MOD_PERM.platformModeration]],
    ["a teacher", []],
    ["a guardian or a student", []],
  ])("shows %s the page of an unknown address and asks the API nothing", (_who, permissions) => {
    const client = new QueryClient(QUIET);
    const html = page(scope("school", permissions), <IncidentsView />, client);
    expect(html).toContain('data-testid="nothing-here"');
    expect(html).not.toContain('data-testid="incidents-view"');
    expect(client.getQueryCache().getAll()).toHaveLength(0);
  });

  it("treats a 404 of the list like an unknown page, without a word about permission", () => {
    const client = new QueryClient(QUIET);
    failQuery(client, key, problem(404, "safeguarding.not_found", "No incidents."));
    const html = page(scope("school", LEAD), <IncidentsView />, client);
    expect(html).toContain('data-testid="nothing-here"');
    expect(html).not.toContain("No incidents.");
    expect(html).not.toMatch(/not allowed|forbidden|permission|lead/i);
  });

  it("treats a 404 on one incident like an unknown page and a missing permission the same way", () => {
    const client = new QueryClient(QUIET);
    failQuery(client, modk.incident("inc-1"), problem(404, "safeguarding.not_found", "Nothing."));
    const html = page(scope("school", LEAD), <IncidentView incidentId="inc-1" />, client);
    expect(html).toContain('data-testid="nothing-here"');
    const noPermission = new QueryClient(QUIET);
    const hidden = page(scope("school", ADMINISTRATOR), <IncidentView incidentId="inc-1" />, noPermission);
    expect(hidden).toContain('data-testid="nothing-here"');
    expect(noPermission.getQueryCache().getAll()).toHaveLength(0);
  });

  it("opens an incident only after a reason from the fixed list, and shows no narrative before", () => {
    const client = new QueryClient(QUIET);
    client.setQueryData(modk.incident("inc-1"), incident({ guardianInformedAt: undefined }));
    const html = page(scope("school", LEAD), <IncidentView incidentId="inc-1" />, client);
    expect(html).toContain('data-testid="incident-open"');
    for (const reason of ["TRIAGE", "INVESTIGATION", "FOLLOW_UP", "REFERRAL", "OVERSIGHT", "LEGAL_REQUEST"]) expect(html).toContain(`data-testid="open-reason-${reason}"`);
    expect(html).not.toContain('data-testid="incident-narrative"');
    expect(html).toContain('data-testid="incident-nobody-told"');
    expect(html).toContain('data-testid="incident-hold-toggle"');
    // The button is off until a reason is picked.
    expect(html).toMatch(/<button[^>]*disabled=""[^>]*data-testid="incident-open-button"|<button[^>]*data-testid="incident-open-button"[^>]*disabled=""/);
  });

  it("keeps the incident (metadata and, once opened, the text) out of the cache once the page is left", () => {
    const client = new QueryClient(QUIET);
    page(scope("school", LEAD), <IncidentView incidentId="inc-1" />, client);
    expect(client.getQueryCache().find({ queryKey: modk.incident("inc-1"), exact: true })?.options.gcTime).toBe(0);
  });
});
