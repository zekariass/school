import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { messages } from "@school/i18n";
import { NextIntlClientProvider } from "next-intl";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import { SchoolProvider } from "@/components/school/school-context";
import { ApiRequestError } from "@/lib/api-error";
import { localDateIn } from "@/lib/attendance/dates";
import { ak } from "@/lib/attendance/queries";
import type { RollView } from "@/lib/attendance/roll";
import type { StaffDayRoll } from "@/lib/attendance/staff";
import type { ClassView } from "@/lib/school/queries";
import { sk } from "@/lib/school/queries";
import { AttendanceView } from "./attendance-view";
import { ClassAttendanceTab } from "./class-attendance-tab";
import { PeriodPickers } from "./period-pickers";
import { StaffAttendanceTab } from "./staff-tab";

// The display calendar needs the place providers of the real app: a plain formatter stands in for it.
vi.mock("@/components/calendar-preference", () => ({
  useFormatDate: () => (date: string | null | undefined) => date ?? "",
  useCalendarPreference: () => ({ calendar: "gregorian", calendars: ["gregorian"], setCalendar: () => undefined }),
}));

// next-intl's navigation helpers need Next's runtime; the screens only use `Link` for plain anchors.
vi.mock("@/i18n/navigation", () => ({
  Link: ({ href, children }: { href: string; children: React.ReactNode }) => <a href={href}>{children}</a>,
}));

// A query that already failed stays failed (the server render must not pretend to fetch it again).
const QUIET = { defaultOptions: { queries: { retryOnMount: false } } };

const SCHOOL = { id: "sch-1", slug: "sunrise", name: "Sunrise", timezone: "UTC", country: {} as never };

function page(opts: { permissions?: string[]; teacher?: boolean; client?: QueryClient }, node: React.ReactNode) {
  const client = opts.client ?? new QueryClient(QUIET);
  return renderToStaticMarkup(
    <NextIntlClientProvider locale="en" messages={messages.en}>
      <QueryClientProvider client={client}>
        <SchoolProvider school={SCHOOL} userId="u1" permissions={opts.permissions ?? []} isTeacher={!!opts.teacher} isStaff={false}>
          {node}
        </SchoolProvider>
      </QueryClientProvider>
    </NextIntlClientProvider>,
  );
}

const problem = (code: string, status: number, detail = "x") => new ApiRequestError(status, { type: "about:blank", title: "t", status, code, detail } as never);

function failQuery(client: QueryClient, key: readonly unknown[], error: unknown) {
  client.getQueryCache().build(client, { queryKey: key }).setState({ status: "error", error, fetchStatus: "idle", fetchFailureCount: 1 } as never);
}

describe("the attendance page", () => {
  it("gives a teacher the student tabs and no Staff tab", () => {
    const html = page({ teacher: true }, <AttendanceView />);
    expect(html).toContain('data-testid="tab-today"');
    expect(html).toContain('data-testid="tab-periods"');
    expect(html).not.toContain('data-testid="tab-staff"');
  });

  it("gives a staff attendance manager the Staff tab and nothing about students", () => {
    const html = page({ permissions: ["school.staff_attendance.manage"] }, <AttendanceView />);
    expect(html).toContain('data-testid="tab-staff"');
    expect(html).not.toContain('data-testid="tab-today"');
    expect(html).not.toContain('data-testid="tab-periods"');
  });

  it("gives an attendance manager the student tabs but not the Staff tab (the staff permissions are separate)", () => {
    const html = page({ permissions: ["school.attendance.manage", "school.attendance.read"] }, <AttendanceView />);
    expect(html).toContain('data-testid="tab-reports"');
    expect(html).not.toContain('data-testid="tab-staff"');
  });

  it("shows an administrator of another school (no grant that names this school) the no-access notice", () => {
    const html = page({}, <AttendanceView />);
    expect(html.replaceAll("&#x27;", "'")).toContain(messages.en.school.common.noAccessTitle);
    expect(html).not.toContain('data-testid="tab-');
  });
});

describe("the staff tab", () => {
  const day = (over: Partial<StaffDayRoll> = {}, staff: StaffDayRoll["staff"]["items"] = []): StaffDayRoll => ({
    date: "2026-11-25",
    timeZone: "UTC",
    staff: { items: staff },
    ...over,
  });
  const people: StaffDayRoll["staff"]["items"] = [
    { staffId: "a", displayName: "Abebe Kebede", staffType: "TEACHER" },
    {
      staffId: "b",
      displayName: "Birtukan Alemu",
      staffType: "SUPPORT_STAFF",
      record: { id: "r1", staffId: "b", staffName: "Birtukan Alemu", date: "2026-11-25", status: "LATE", minutesLate: 12, note: "bus", method: "MANUAL", recordedAt: "2026-11-25T05:00:00Z", recordedBy: "u", version: 1, corrections: 1 },
    },
  ];

  function withDay(data: StaffDayRoll) {
    const client = new QueryClient(QUIET);
    const today = localDateIn(SCHOOL.timezone, new Date());
    client.setQueryData(ak.staffDay(SCHOOL.id, today), { pages: [data], pageParams: [undefined] });
    return client;
  }

  it("never offers a teacher anything: no data is asked for and nothing is shown", () => {
    const client = new QueryClient(QUIET);
    const html = page({ teacher: true, client }, <StaffAttendanceTab />);
    expect(html).toContain(messages.en.school.attendance.staff.notForYou);
    expect(html).not.toContain("staff-day");
    expect(client.getQueryCache().getAll()).toHaveLength(0);
  });

  it("lets a manager mark people without a record and correct those who have one", () => {
    const html = page({ permissions: ["school.staff_attendance.manage"], client: withDay(day({}, people)) }, <StaffAttendanceTab />);
    expect(html).toContain("Abebe Kebede");
    expect(html).toContain('data-testid="staff-LATE-a"');
    expect(html).toContain('data-testid="staff-save"');
    expect(html).toContain('data-testid="staff-mark-rest"');
    // Birtukan has a record: no marking buttons, a correction and her history instead.
    expect(html).not.toContain('data-testid="staff-LATE-b"');
    expect(html).toContain('data-testid="staff-correct-b"');
    expect(html).toContain('data-testid="staff-history-b"');
    expect(html).toContain("Late · 12 min");
    expect(html).toContain("bus");
  });

  it("lets a reader look but not mark or correct", () => {
    const html = page({ permissions: ["school.staff_attendance.read"], client: withDay(day({}, people)) }, <StaffAttendanceTab />);
    expect(html).toContain("Birtukan Alemu");
    expect(html).toContain(messages.en.school.attendance.staff.readOnly);
    expect(html).toContain(messages.en.school.attendance.staff.notRecorded);
    expect(html).not.toContain('data-testid="staff-save"');
    expect(html).not.toContain('data-testid="staff-LATE-a"');
    expect(html).not.toContain('data-testid="staff-correct-b"');
    expect(html).toContain('data-testid="staff-history-b"');
  });

  it("explains a switched-off setting from the API's answer instead of showing data", () => {
    const client = new QueryClient(QUIET);
    const today = localDateIn(SCHOOL.timezone, new Date());
    failQuery(client, ak.staffDay(SCHOOL.id, today), problem("staff_attendance.disabled", 403));
    const html = page({ permissions: ["school.staff_attendance.manage"], client }, <StaffAttendanceTab />);
    expect(html).toContain('data-testid="staff-disabled"');
    expect(html).toContain(messages.en.school.attendance.staff.disabled.title);
    expect(html).not.toContain('data-testid="staff-save"');
  });

  it("explains a refusal as no access (403 or 404) and shows other failures with the API's own words", () => {
    const today = localDateIn(SCHOOL.timezone, new Date());
    const denied = new QueryClient(QUIET);
    failQuery(denied, ak.staffDay(SCHOOL.id, today), problem("staff_attendance.access_denied", 403));
    expect(page({ permissions: ["school.staff_attendance.read"], client: denied }, <StaffAttendanceTab />)).toContain('data-testid="staff-denied"');

    const broken = new QueryClient(QUIET);
    failQuery(broken, ak.staffDay(SCHOOL.id, today), problem("internal", 500, "The database is on fire."));
    expect(page({ permissions: ["school.staff_attendance.read"], client: broken }, <StaffAttendanceTab />)).toContain("The database is on fire.");
  });
});

describe("the period pickers", () => {
  const choices = {
    subjects: [{ id: "m", name: "Mathematics" }],
    groups: [{ id: "g1", name: "French", size: 12 }],
  };
  const props = { choices, subjectId: null, groupId: null, onSubject: () => undefined, onGroup: () => undefined };

  it("offers the subject on a period roll nobody has taken, and the group at any time", () => {
    const html = renderToStaticMarkup(
      <NextIntlClientProvider locale="en" messages={messages.en}>
        <PeriodPickers {...props} slot="P2" />
      </NextIntlClientProvider>,
    );
    expect(html).toContain(messages.en.school.attendance.periods.subject);
    expect(html).toContain(messages.en.school.attendance.periods.group);
  });

  it("offers no subject on the whole day", () => {
    const html = renderToStaticMarkup(
      <NextIntlClientProvider locale="en" messages={messages.en}>
        <PeriodPickers {...props} slot="DAY" />
      </NextIntlClientProvider>,
    );
    expect(html).not.toContain(messages.en.school.attendance.periods.subject);
    expect(html).toContain(messages.en.school.attendance.periods.group);
  });

  it("shows the subject of a roll that exists as fixed text, not a choice", () => {
    const html = renderToStaticMarkup(
      <NextIntlClientProvider locale="en" messages={messages.en}>
        <PeriodPickers {...props} slot="P2" sessionId="s1" rollSubjectName="Chemistry (retired)" />
      </NextIntlClientProvider>,
    );
    expect(html).toContain('data-testid="roll-subject-fixed"');
    expect(html).toContain("Chemistry (retired)");
  });

  it("renders nothing for a class without subjects and groups", () => {
    const html = renderToStaticMarkup(
      <NextIntlClientProvider locale="en" messages={messages.en}>
        <PeriodPickers {...props} choices={{ subjects: [], groups: [] }} slot="P2" />
      </NextIntlClientProvider>,
    );
    expect(html).toBe("");
  });
});

describe("the class roll per slot", () => {
  const cls = { id: "c1", status: "ACTIVE", myMembership: "TEACHER", myTeacherRole: "LEAD", subjectIds: ["m"] } as unknown as ClassView;
  const roll = (slot: string, names: string[], extra: Partial<RollView> = {}): RollView =>
    ({
      classId: "c1",
      date: "2026-11-25",
      slot,
      slots: ["DAY", "P1", "P2"],
      timeZone: "UTC",
      locksAt: "2026-11-26T00:00:00Z",
      counts: { students: names.length, marked: 0, unmarked: names.length, present: 0, absent: 0, late: 0, excused: 0, leftEarly: 0 },
      students: names.map((n, i) => ({ studentId: `${slot}-${i}`, displayName: n })),
      ...extra,
    }) as RollView;

  it("loads the roll of the slot asked for and never shows another slot's", () => {
    const client = new QueryClient(QUIET);
    client.setQueryData(ak.roll(SCHOOL.id, "c1", "2026-11-25", "P1", null), roll("P1", ["Almaz P1"]));
    client.setQueryData(ak.roll(SCHOOL.id, "c1", "2026-11-25", "P2", null), roll("P2", ["Bekele P2"], { sessionId: "s2", subjectId: "m", subjectName: "Mathematics" }));
    client.setQueryData(sk.subjects(SCHOOL.id), [{ id: "m", name: "Mathematics", active: true }]);

    const p1 = page({ teacher: true, client }, <ClassAttendanceTab cls={cls} initialDate="2026-11-25" initialSlot="P1" />);
    expect(p1).toContain("Almaz P1");
    expect(p1).not.toContain("Bekele P2");

    const p2 = page({ teacher: true, client }, <ClassAttendanceTab cls={cls} initialDate="2026-11-25" initialSlot="P2" />);
    expect(p2).toContain("Bekele P2");
    expect(p2).not.toContain("Almaz P1");
    // The roll of P2 exists with a subject: it is shown as fixed.
    expect(p2).toContain('data-testid="roll-subject-fixed"');
    expect(p2).toContain("Mathematics");
  });

  it("offers the subject choice on a period roll that was not taken yet, and none on the whole day", () => {
    const client = new QueryClient(QUIET);
    client.setQueryData(sk.subjects(SCHOOL.id), [{ id: "m", name: "Mathematics", active: true }]);
    client.setQueryData(ak.roll(SCHOOL.id, "c1", "2026-11-25", "P1", null), roll("P1", ["Almaz"]));
    client.setQueryData(ak.roll(SCHOOL.id, "c1", "2026-11-25", "DAY", null), roll("DAY", ["Almaz"]));
    const period = page({ teacher: true, client }, <ClassAttendanceTab cls={cls} initialDate="2026-11-25" initialSlot="P1" />);
    expect(period).toContain('data-testid="period-pickers"');
    const day = page({ teacher: true, client }, <ClassAttendanceTab cls={cls} initialDate="2026-11-25" initialSlot="DAY" />);
    expect(day).not.toContain('data-testid="period-pickers"');
  });
});
