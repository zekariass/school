import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative } from "node:path";
import { describe, expect, it } from "vitest";

/**
 * A canary over the source of the moderation, safeguarding and student sign-in screens: what they handle (reports, case
 * content, incident narratives, a child's class code) must never reach storage, the address bar, the page title, a toast
 * or a log, and what is read per view must not stay in the cache. These are source checks: they fail when a change
 * adds one of those, so the change has to explain itself.
 */
const WEB = join(import.meta.dirname, "..", "..");

function files(dir: string, out: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) files(path, out);
    else if (/\.(ts|tsx)$/.test(name) && !/\.test\.tsx?$/.test(name)) out.push(path);
  }
  return out;
}

const SCREENS = [
  ...files(join(WEB, "components", "moderation")),
  ...files(join(WEB, "components", "safeguarding")),
  ...files(join(WEB, "lib", "moderation")),
  ...files(join(WEB, "components", "auth")),
  ...files(join(WEB, "lib", "auth")),
  join(WEB, "app", "[locale]", "(auth)", "student-code", "page.tsx"),
];
/** The source without comments (a comment may talk about a toast; code may not use one). */
const read = (path: string) =>
  readFileSync(path, "utf8")
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/(^|[^:"'`])\/\/.*$/gm, "$1");

/** The source of every call `name({ ... })`, found by balancing the brackets (not by guessing where it ends). */
function calls(source: string, callee: string): string[] {
  const out: string[] = [];
  let from = 0;
  for (;;) {
    const start = source.indexOf(`${callee}(`, from);
    if (start < 0) return out;
    let depth = 0;
    let end = start + callee.length;
    for (; end < source.length; end++) {
      if (source[end] === "(") depth++;
      else if (source[end] === ")" && --depth === 0) break;
    }
    out.push(source.slice(start, end + 1));
    from = end;
  }
}
const name = (path: string) => relative(WEB, path);

describe("what the screens never do with what they read", () => {
  it.each([
    ["use browser storage", /localStorage|sessionStorage|indexedDB|document\.cookie/],
    ["write the address", /useRouter|useSearchParams|router\.(push|replace)|window\.history|location\.(search|hash)|searchParams/],
    ["change the page title", /document\.title/],
    ["show a toast", /\btoast\b/],
    ["log", /console\.(log|info|warn|error|debug)|\bdebugger\b/],
    ["use an unsafe HTML sink", /dangerouslySetInnerHTML|innerHTML/],
  ])("does not %s", (_what, pattern) => {
    for (const path of SCREENS) expect(read(path), name(path)).not.toMatch(pattern);
  });

  it("builds no link from case, incident or report text: links carry ids only", () => {
    for (const path of SCREENS.filter((p) => p.endsWith(".tsx"))) {
      for (const match of read(path).matchAll(/href=\{`([^`]*)`\}/g)) {
        expect(match[1], `${name(path)}: ${match[0]}`).toMatch(/^\$\{[A-Za-z.]*(?:Path|basePath)\}\/\$\{[A-Za-z.]+(?:id|Id)\}$|^\$\{scope\.(?:queue|incidents)Path\}\/\$\{[A-Za-z.]+\}$/);
      }
    }
  });
});

describe("what is read per view is not kept", () => {
  const holders = SCREENS.filter((p) => /(case-view|content-panel|incident-view|incident-actions|case-dialogs|report-dialog|concern-view)\.tsx$/.test(p));

  it("holds the screens that carry text people wrote", () => {
    expect(holders.length).toBe(7);
  });

  it("gives every query of those screens gcTime 0 and every mutation gcTime 0", () => {
    for (const path of holders) {
      const source = read(path);
      const queries = calls(source, "useQuery");
      for (const call of queries) {
        // The emergency information is the API's public per-country text, not anybody's words: it may be kept a while.
        if (/fetchEmergencyInfo/.test(call)) continue;
        expect(call, name(path)).toMatch(/gcTime: CONTENT_GC_MS/);
      }
      for (const call of calls(source, "useMutation")) expect(call, name(path)).toMatch(/gcTime: 0/);
    }
  });

  it("has no query anywhere else that could hold a case's detail, content or an incident", () => {
    for (const path of SCREENS.filter((p) => !holders.includes(p) && p.endsWith(".tsx"))) {
      for (const call of calls(read(path), "useQuery")) {
        expect(call, name(path)).not.toMatch(/fetchCase\(|fetchCaseContent|fetchIncident\(|openIncident/);
      }
    }
  });
});

describe("the pages", () => {
  const pages = [
    ["app", "[locale]", "(app)", "s", "[school]", "moderation", "[caseId]", "page.tsx"],
    ["app", "[locale]", "(app)", "s", "[school]", "safeguarding", "[incidentId]", "page.tsx"],
    ["app", "[locale]", "(platform)", "platform", "moderation", "[caseId]", "page.tsx"],
    ["app", "[locale]", "(platform)", "platform", "safeguarding", "[incidentId]", "page.tsx"],
    ["app", "[locale]", "(app)", "s", "[school]", "concern", "page.tsx"],
    ["app", "[locale]", "(auth)", "student-code", "page.tsx"],
  ].map((parts) => join(WEB, ...parts));

  it("take the title from a fixed label: generateMetadata has no parameters, so it cannot carry an id or a text", () => {
    for (const path of pages) expect(read(path), name(path)).toMatch(/export async function generateMetadata\(\)/);
  });

  it("check that an id from the address is a UUID before it goes into an API path", () => {
    for (const path of pages.filter((p) => /\[(caseId|incidentId)\]/.test(p))) expect(read(path), name(path)).toMatch(/isUuid\(/);
  });
});
