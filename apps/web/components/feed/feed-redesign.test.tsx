import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { messages } from "@school/i18n";
import { NextIntlClientProvider } from "next-intl";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import { ank } from "@/lib/announcements/queries";
import type { MyChild } from "@/lib/family/queries";
import { fk, type Post } from "@/lib/feed/queries";
import { mk } from "@/lib/messaging/keys";
import { ChildrenNav } from "./children-nav";
import { FeedBanner } from "./feed-banner";
import { FeedTabs } from "./feed-tabs";
import { MediaGrid } from "./media-grid";
import { PinnedAnnouncement } from "./pinned-announcement";
import { PostCard } from "./post-card";
import { ClassInfoCard, RecentAchievementsCard } from "./rail";

// The display calendar and the router need the real app's providers: plain stand-ins.
vi.mock("@/components/calendar-preference", () => ({
  useFormatInstant: () => (instant: string | Date) => String(instant),
  useFormatDate: () => (date: string | null | undefined) => date ?? "",
  useCalendarPreference: () => ({ calendar: "gregorian", calendars: ["gregorian"], setCalendar: () => undefined }),
}));
vi.mock("@/i18n/navigation", () => ({
  Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
  usePathname: () => "/family",
  useRouter: () => ({ push: () => undefined }),
}));

const QUIET = { defaultOptions: { queries: { retryOnMount: false } } };

function render(node: React.ReactNode, client = new QueryClient(QUIET)) {
  return renderToStaticMarkup(
    <NextIntlClientProvider locale="en" messages={messages.en}>
      <QueryClientProvider client={client}>{node}</QueryClientProvider>
    </NextIntlClientProvider>,
  );
}

const mediaItem = (n: number) => ({ mediaId: `00000000-0000-4000-8000-00000000000${n}`, kind: "IMAGE" as const, position: n });

function post(overrides: Partial<Post> = {}): Post {
  return {
    id: "p1",
    schoolId: "sch-1",
    scope: "CLASS",
    classId: "c1",
    status: "PUBLISHED",
    postType: "POST",
    author: { role: "TEACHER", displayName: "Mr. Bekele" },
    body: "Scientists in action!\nToday we planted seeds.",
    media: [],
    tags: [],
    reactionCounts: { HEART: 24, LIKE: 8 },
    rights: {},
    commentCount: 3,
    createdAt: "2026-12-01T08:00:00Z",
    publishedAt: "2026-12-01T08:00:00Z",
    ...overrides,
  } as Post;
}

describe("FeedTabs", () => {
  it("is a real tablist with the four tabs and the active one selected", () => {
    const html = render(
      <FeedTabs value="photos" onValueChange={() => undefined}>
        <p>panel</p>
      </FeedTabs>,
    );
    expect(html).toContain('role="tablist"');
    expect(html.match(/role="tab"/g)).toHaveLength(4);
    for (const label of ["Feed", "Photos", "Activities", "Achievements"]) expect(html).toContain(label);
    expect(html).toMatch(/data-testid="feed-tab-photos"[^>]*aria-selected="true"|aria-selected="true"[^>]*data-testid="feed-tab-photos"/);
    expect(html).toMatch(/data-testid="feed-tab-feed"[^>]*aria-selected="false"|aria-selected="false"[^>]*data-testid="feed-tab-feed"/);
    expect(html).toContain('role="tabpanel"');
    expect(html).toContain("panel");
  });
});

describe("FeedBanner", () => {
  it("shows the class, the school and no brand art", () => {
    const html = render(<FeedBanner title="Grade 5A" subtitle="Riverside" icon="🌱" titleId="t" />);
    expect(html).toContain("Grade 5A");
    expect(html).toContain("Riverside");
    expect(html).toContain("bg-primary");
    expect(html).not.toMatch(/#[0-9a-f]{6}/i);
  });
});

describe("PostCard variants", () => {
  it("shows a plain post with the role chip as a translated label and the first line in bold", () => {
    const html = render(<PostCard post={post()} />);
    expect(html).toContain('data-variant="standard"');
    expect(html).toMatch(/data-testid="role-chip"[^>]*>Teacher</);
    expect(html).not.toContain("TEACHER");
    expect(html).toMatch(/data-testid="post-first-line">Scientists in action!</);
    // The text is exactly what the API sent.
    expect(html.replace(/<[^>]+>/g, "")).toContain("Scientists in action!\nToday we planted seeds.");
    expect(html).not.toContain("achievement-badge");
    expect(html).not.toContain("activity-chip");
  });

  it("shows an activity chip with its icon", () => {
    const html = render(<PostCard post={post({ postType: "ACTIVITY" })} />);
    expect(html).toContain('data-variant="activity"');
    expect(html).toContain('data-testid="activity-chip"');
    expect(html).toContain("Activity");
  });

  it("shows an achievement with the trophy badge, an accent title and the tagged students as the API sent them", () => {
    const tags = [
      { studentId: "s1", displayName: "Hana Negese" },
      { studentId: "s2", displayName: "Dawit Negese" },
    ];
    const html = render(<PostCard post={post({ postType: "ACHIEVEMENT", tags })} />);
    expect(html).toContain('data-variant="achievement"');
    expect(html).toContain('data-testid="achievement-badge"');
    expect(html).toMatch(/data-testid="achievement-title">Achievement</);
    expect(html.match(/data-testid="post-tag"/g)).toHaveLength(2);
    expect(html).toContain("Hana Negese");
    expect(html).toContain("Dawit Negese");
  });

  it("lists tags of an ordinary post as a line of text, and shows no tag chips when the API sent none", () => {
    const html = render(<PostCard post={post({ tags: [{ studentId: "s1", displayName: "Hana Negese" }] })} />);
    expect(html).toContain("With:");
    expect(html).not.toContain('data-testid="post-tag"');
    expect(render(<PostCard post={post({ postType: "ACHIEVEMENT" })} />)).not.toContain('data-testid="post-tags"');
  });

  it("shows the four reactions as pills with counts, highlights mine and has a comment chip", () => {
    const html = render(<PostCard post={post({ myReaction: "HEART" })} />);
    for (const r of ["LIKE", "HEART", "CLAP", "STAR"]) expect(html).toContain(`data-testid="react-${r}"`);
    expect(html).toMatch(/data-testid="react-count-HEART">24</);
    expect(html).toMatch(/aria-pressed="true"[^>]*data-testid="react-HEART"/);
    expect(html).toContain('data-testid="comments-toggle"');
    expect(html).toContain("3 comments");
  });

  it("shows the pin chip and the withheld placeholder as the API says", () => {
    const html = render(<PostCard post={post({ pinned: true, mediaWithheld: true })} />);
    expect(html).toContain('data-testid="pinned-badge"');
    expect(html).toContain('data-testid="media-withheld"');
    expect(html).not.toContain('data-testid="post-photo"');
  });
});

describe("MediaGrid collage", () => {
  it("is one wide photo for one", () => {
    const html = render(<MediaGrid items={[mediaItem(1)] as never} />);
    expect(html).toContain('data-shape="one"');
    expect(html.match(/data-testid="post-photo"/g)).toHaveLength(1);
  });
  it("puts two side by side", () => {
    const html = render(<MediaGrid items={[mediaItem(1), mediaItem(2)] as never} />);
    expect(html).toContain('data-shape="two"');
    expect(html.match(/data-testid="post-photo"/g)).toHaveLength(2);
  });
  it("is one large and two stacked for three, without a +n", () => {
    const html = render(<MediaGrid items={[mediaItem(1), mediaItem(2), mediaItem(3)] as never} />);
    expect(html).toContain('data-shape="three"');
    expect(html).toContain("col-span-3 row-span-2");
    expect(html).not.toContain("photo-extra");
  });
  it("shows only three tiles for more and a +n on the last", () => {
    const html = render(<MediaGrid items={[1, 2, 3, 4, 5, 6].map(mediaItem) as never} />);
    expect(html.match(/data-testid="post-photo"/g)).toHaveLength(3);
    expect(html).toMatch(/data-testid="photo-extra">\+3</);
  });
});

describe("ChildrenNav", () => {
  const kid = (id: string, name: string, className: string): MyChild =>
    ({
      studentId: id,
      displayName: name,
      givenName: name.split(" ")[0],
      permissions: { canViewAcademics: true },
      schools: [{ schoolId: "sch-1", schoolName: "Riverside", schoolSlug: "r", classes: [{ classId: `c-${id}`, name: className, teachers: [] }] }],
    }) as unknown as MyChild;

  it("lists the children with their class, highlights the chosen one and offers all children", () => {
    const html = render(<ChildrenNav kids={[kid("a", "Hana Negese", "Grade 5A"), kid("b", "Dawit Negese", "Grade 2B")]} selected="b" onSelect={() => undefined} />);
    expect(html).toContain("My children");
    expect(html).toContain("Hana Negese");
    expect(html).toContain("Grade 5A");
    expect(html).toContain('data-testid="filter-all"');
    expect(html).toMatch(/aria-pressed="true"[^>]*data-testid="filter-b"/);
    expect(html).toMatch(/aria-pressed="false"[^>]*data-testid="filter-a"/);
    expect(html).toContain('href="/announcements"');
    expect(html).toContain('href="/calendar"');
  });

  it("offers no all-children row for one child", () => {
    const html = render(<ChildrenNav kids={[kid("a", "Hana Negese", "Grade 5A")]} selected={null} onSelect={() => undefined} />);
    expect(html).not.toContain('data-testid="filter-all"');
    expect(html).toMatch(/aria-pressed="true"[^>]*data-testid="filter-a"/);
  });
});

describe("PinnedAnnouncement", () => {
  const card = { id: "an1", schoolId: "sch-1", title: "Parent-Teacher Meeting", type: "NORMAL", status: "PUBLISHED", requiresAcknowledgement: false, revision: 1, createdAt: "2026-12-01T08:00:00Z", publishedAt: "2026-12-01T08:00:00Z", authorId: "u", schoolName: "Riverside" };

  it("shows the newest published announcement as text with a link to it", () => {
    const client = new QueryClient(QUIET);
    client.setQueryData(ank.latest(null), { items: [{ ...card, status: "WITHDRAWN", id: "old" }, card] });
    const html = render(<PinnedAnnouncement schoolId={null} hrefFor={(c) => `/announcements/${c.schoolId}/${c.id}`} />, client);
    expect(html).toContain("Parent-Teacher Meeting");
    expect(html).toContain('href="/announcements/sch-1/an1"');
    expect(html).toContain("View details");
    expect(html).not.toContain("/old");
  });

  it("escapes markup in the title (plain text only)", () => {
    const client = new QueryClient(QUIET);
    client.setQueryData(ank.latest(null), { items: [{ ...card, title: "<img src=x onerror=alert(1)>" }] });
    const html = render(<PinnedAnnouncement schoolId={null} hrefFor={() => "/x"} />, client);
    expect(html).not.toContain("<img");
    expect(html).toContain("&lt;img");
  });

  it("is nothing without an announcement", () => {
    const client = new QueryClient(QUIET);
    client.setQueryData(ank.latest(null), { items: [] });
    expect(render(<PinnedAnnouncement schoolId={null} hrefFor={() => "/x"} />, client)).toBe("");
  });
});

describe("rail cards", () => {
  it("class info lists the teachers, shows counts only when the API sent them and the message link only when allowed", () => {
    const teachers = [{ id: "t1", name: "Mr. Bekele", role: "LEAD" as const }];
    const plain = render(<ClassInfoCard name="Grade 5A" schoolId="sch-1" teachers={teachers} />);
    expect(plain).toContain("Grade 5A");
    expect(plain).toContain("Mr. Bekele");
    expect(plain).toContain("Lead teacher");
    expect(plain).not.toContain("rail-counts");
    expect(plain).not.toContain("rail-message");

    const withCounts = render(<ClassInfoCard name="Grade 5A" schoolId="sch-1" teachers={teachers} studentCount={28} teacherCount={3} />);
    expect(withCounts).toContain('data-testid="rail-counts"');
    expect(withCounts).toContain(">28<");

    const denied = new QueryClient(QUIET);
    denied.setQueryData(mk.capabilities("sch-1"), { featureEnabled: true, canStartParentTeacher: false });
    expect(render(<ClassInfoCard name="Grade 5A" schoolId="sch-1" teachers={teachers} allowMessage />, denied)).not.toContain("rail-message");

    const allowed = new QueryClient(QUIET);
    allowed.setQueryData(mk.capabilities("sch-1"), { featureEnabled: true, canStartParentTeacher: true });
    const html = render(<ClassInfoCard name="Grade 5A" schoolId="sch-1" teachers={teachers} allowMessage />, allowed);
    expect(html).toContain('data-testid="rail-message"');
    expect(html).toContain('href="/messages/new"');
  });

  it("recent achievements show the tagged student and the first words, linking to the post by ids only", () => {
    const client = new QueryClient(QUIET);
    const key = fk.recentMine(null);
    client.setQueryData(key, [post({ id: "pa", postType: "ACHIEVEMENT", body: "Star readers of the week are Hana and Dawit", tags: [{ studentId: "s1", displayName: "Hana Negese" }] })]);
    const html = render(<RecentAchievementsCard queryKey={key} fetchRecent={async () => []} onViewAll={() => undefined} />, client);
    expect(html).toContain("Recent achievements");
    expect(html).toContain("Hana Negese");
    expect(html).toContain("Star readers of the…");
    expect(html).toContain('href="/feed/posts/sch-1/pa"');
    expect(html).not.toContain("Dawit");
  });

  it("recent achievements are no card when there are none", () => {
    const client = new QueryClient(QUIET);
    const key = fk.recentMine("s9");
    client.setQueryData(key, []);
    expect(render(<RecentAchievementsCard queryKey={key} fetchRecent={async () => []} />, client)).toBe("");
  });
});
