import { messages } from "@school/i18n";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { NextIntlClientProvider } from "next-intl";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import { ConcernView } from "@/components/safeguarding/concern-view";
import { SchoolProvider } from "@/components/school/school-context";
import { QrScanner } from "./qr-scanner";
import { StudentCodeView } from "./student-code-form";

vi.mock("@/i18n/navigation", () => ({
  Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

const en = messages.en;
const wrap = (node: React.ReactNode) =>
  renderToStaticMarkup(
    <NextIntlClientProvider locale="en" messages={en}>
      <QueryClientProvider client={new QueryClient()}>{node}</QueryClientProvider>
    </NextIntlClientProvider>,
  );

const noop = () => undefined;
const view = (over: Partial<React.ComponentProps<typeof StudentCodeView>> = {}) =>
  wrap(<StudentCodeView text="" onText={noop} onSubmit={noop} onScanned={noop} pending={false} failure={null} blockedFor={0} {...over} />);
/** React writes a disabled button as `disabled=""` (the class names also contain "disabled:", which is not it). */
const disabledSubmit = (html: string) => /<button[^>]*disabled=""[^>]*data-testid="student-code-submit"|<button[^>]*data-testid="student-code-submit"[^>]*disabled=""/.test(html);
/** The markup writes an apostrophe as an entity. */
const esc = (text: string) => text.replaceAll("'", "&#x27;");

describe("the student sign-in page", () => {
  it("offers the camera (asked for only on a press) and the typed code, with the teacher link", () => {
    const html = view();
    expect(html).toContain('data-testid="qr-idle"');
    expect(html).toContain('data-testid="qr-start"');
    expect(html).not.toContain('data-testid="qr-scanner"');
    expect(html).toContain('data-testid="student-code-input"');
    expect(html).toContain('href="/sign-in"');
    expect(html).toContain(esc(en.auth.studentCode.type.hint));
    // The code field does not invite the browser to remember or correct what a child types.
    expect(html).toContain('autoComplete="off"');
    expect(html).toContain('spellCheck="false"');
  });

  it("keeps the sign-in button off until the typed code is complete", () => {
    expect(disabledSubmit(view({ text: "" }))).toBe(true);
    expect(disabledSubmit(view({ text: "AHR4P-A4WS" }))).toBe(true);
    expect(disabledSubmit(view({ text: "AHR4P-A4WS9" }))).toBe(false);
  });

  it("says 'signing in' while a try is out, and no more tries meanwhile", () => {
    const html = view({ text: "AHR4P-A4WS9", pending: true });
    expect(html).toContain(en.auth.studentCode.signingIn);
    expect(disabledSubmit(html)).toBe(true);
  });

  it("shows every wrong, expired or revoked code as the API's one generic sentence", () => {
    const html = view({ text: "", failure: { kind: "invalid", detail: "That code did not work." } });
    expect(html).toContain('data-testid="student-code-error"');
    expect(html).toContain("That code did not work.");
    // Without a sentence from the API the page has one of its own, and it is the same for every kind of failure.
    const fallback = view({ failure: { kind: "invalid" } });
    expect(fallback).toContain(esc(en.auth.studentCode.errors.invalid));
    expect(fallback).not.toMatch(/expired|revoked|inactive|not active|school/i);
  });

  it("shows how long to wait after a rate limit and keeps the button off while waiting", () => {
    const html = view({ text: "AHR4P-A4WS9", failure: { kind: "rateLimited", seconds: 42 }, blockedFor: 37 });
    expect(html).toContain(en.auth.studentCode.errors.rateLimited.replace("{seconds}", "37"));
    expect(html).toContain(en.auth.studentCode.tryAgainIn.replace("{seconds}", "37"));
    expect(disabledSubmit(html)).toBe(true);
    // The wait is over: the message goes, the button is back.
    const after = view({ text: "AHR4P-A4WS9", failure: { kind: "rateLimited", seconds: 42 }, blockedFor: 0 });
    expect(after).not.toContain('data-testid="student-code-error"');
    expect(disabledSubmit(after)).toBe(false);
  });

  it("says the service is unreachable apart from a wrong code", () => {
    expect(view({ failure: { kind: "unavailable" } })).toContain(esc(en.auth.studentCode.errors.unavailable));
  });

  it("puts the typed code only in the field, never anywhere else in the markup", () => {
    const html = view({ text: "AHR4P-A4WS9" });
    expect(html.match(/AHR4P-A4WS9/g)).toHaveLength(1);
  });

  it("never puts the code in a link", () => {
    expect(view({ text: "AHR4P-A4WS9" })).not.toMatch(/href="[^"]*AHR4P/);
  });
});

describe("the camera", () => {
  it("is idle until the child presses the button, and says nothing is recorded", () => {
    const html = wrap(<QrScanner onCode={noop} />);
    expect(html).toContain(en.auth.studentCode.scan.permission);
    expect(html).toContain('data-testid="qr-start"');
    expect(html).not.toContain("<video");
  });
});

describe("raising a concern", () => {
  const school = { id: "s1", slug: "sunrise", name: "Sunrise", timezone: "UTC", country: {} as never };
  const render = (roles: { isTeacher: boolean; isStaff: boolean }) =>
    wrap(
      <SchoolProvider school={school} userId="u1" permissions={[]} {...roles}>
        <ConcernView />
      </SchoolProvider>,
    );

  it("is a form for a teacher or other staff, with no text in it before they write", () => {
    for (const roles of [{ isTeacher: true, isStaff: false }, { isTeacher: false, isStaff: true }]) {
      const html = render(roles);
      expect(html).toContain('data-testid="concern-view"');
      expect(html).toContain('data-testid="concern-narrative"');
      expect(html).toContain(esc(en.safeguarding.concern.subtitle));
    }
  });

  it("is the page of an unknown address for anybody else (a student, a guardian, an administrator without a staff row)", () => {
    const html = render({ isTeacher: false, isStaff: false });
    expect(html).toContain('data-testid="nothing-here"');
    expect(html).not.toContain('data-testid="concern-view"');
  });
});
