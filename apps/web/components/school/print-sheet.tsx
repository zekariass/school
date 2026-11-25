"use client";

import { ArrowLeftIcon, PrinterIcon, RefreshCwIcon, TriangleAlertIcon } from "lucide-react";
import { useLocale, useTranslations } from "next-intl";
import { useState } from "react";
import { messages, SUPPORTED_LOCALES, type Locale } from "@school/i18n";
import { useFormatInstant } from "@/components/calendar-preference";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Link } from "@/i18n/navigation";
import { CARDS_PER_PAGE, earliestExpiry, formatTextCode, paginate, qrPayload, sheetForClass, splitPrintable, type CodeEntry, type CodeKind } from "@/lib/school/codes";
import { SelectField } from "./fields";
import { useIssuedCodes } from "./issued-codes-context";
import { QrCode } from "./qr-code";
import { useSchool } from "./school-context";
import { StudentAvatar } from "./student-avatar";

/**
 * The printable login-code sheet: one cut-out card per student (name, QR code, text code,
 * how to sign in), eight per A4 page. It renders from the in-memory sheet only; after a
 * reload the codes are gone by design and the page offers to issue them again.
 */
export function PrintSheetView({ classId }: { classId: string }) {
  const t = useTranslations("school.print");
  const { school } = useSchool();
  const { sheet: held, clear } = useIssuedCodes();
  const locale = useLocale() as Locale;
  const [language, setLanguage] = useState<Locale>(locale);
  const formatInstant = useFormatInstant();
  const sheet = sheetForClass(held, classId);
  const back = `/s/${encodeURIComponent(school.slug)}/classes/${classId}`;

  if (!sheet) {
    return (
      <Card className="mx-auto max-w-lg">
        <CardContent className="flex flex-col items-center gap-4 py-12 text-center">
          <span className="grid size-14 place-items-center rounded-2xl bg-muted text-muted-foreground">
            <RefreshCwIcon className="size-7" aria-hidden />
          </span>
          <h1 className="text-xl font-bold">{t("lostTitle")}</h1>
          <p className="max-w-sm text-muted-foreground">{t("lostBody")}</p>
          <Button asChild>
            <Link href={back}>{t("issueAgain")}</Link>
          </Button>
        </CardContent>
      </Card>
    );
  }

  const { printable, missing } = splitPrintable(sheet.codes, sheet.kind);
  const pages = paginate(printable, CARDS_PER_PAGE);
  const copy = messages[language].school.print;
  const expiry = earliestExpiry(printable);

  return (
    <div className="mx-auto flex max-w-[210mm] flex-col gap-4 print:max-w-none print:gap-0">
      {/* A4, small margins; the workspace chrome is hidden in print by the shell */}
      <style>{`@page { size: A4; margin: 8mm; } @media print { html, body { background: #fff !important; } }`}</style>

      <div className="flex flex-col gap-4 print:hidden">
        <Link href={back} className="inline-flex min-h-11 items-center gap-2 self-start text-sm font-semibold text-primary hover:underline">
          <ArrowLeftIcon className="size-4 rtl:rotate-180" aria-hidden />
          {t("back", { class: sheet.className })}
        </Link>
        <div className="flex flex-wrap items-end justify-between gap-3">
          <div>
            <h1 className="text-2xl font-bold">{t("heading", { class: sheet.className })}</h1>
            <p className="text-muted-foreground">
              {t("summary", { count: printable.length, pages: pages.length })}
              {expiry ? ` · ${t("validUntil", { date: formatInstant(expiry) })}` : ""}
            </p>
          </div>
          <div className="flex flex-wrap items-end gap-3">
            <SelectField
              className="w-44"
              label={t("language")}
              value={language}
              onChange={(v) => setLanguage(v as Locale)}
              options={SUPPORTED_LOCALES.map((l) => ({ value: l.code, label: l.nativeName }))}
            />
            <Button size="lg" onClick={() => window.print()}>
              <PrinterIcon className="size-4" aria-hidden />
              {t("print")}
            </Button>
            <Button size="lg" variant="outline" onClick={clear} asChild>
              <Link href={back}>{t("done")}</Link>
            </Button>
          </div>
        </div>
        <p role="note" className="flex items-start gap-2 rounded-xl bg-warning/15 p-3 text-sm font-medium">
          <TriangleAlertIcon className="mt-0.5 size-4 shrink-0" aria-hidden />
          {t("warning")}
        </p>
        {missing.length > 0 ? (
          <p role="status" className="rounded-xl bg-muted p-3 text-sm">
            {t("missing", { names: missing.map((m) => m.displayName).join(", ") })}
          </p>
        ) : null}
      </div>

      <div data-testid="print-pages" className="flex flex-col gap-6 print:gap-0">
        {pages.map((page, pageIndex) => (
          <section
            key={pageIndex}
            aria-label={t("page", { number: pageIndex + 1 })}
            className="grid grid-cols-2 gap-0 border border-dashed border-neutral-400 bg-white text-black print:h-[281mm] print:break-after-page print:border-0 [&:last-child]:print:break-after-auto"
            style={{ gridTemplateRows: "repeat(4, minmax(0, 1fr))" }}
          >
            {page.map((entry) => (
              <LoginCard
                key={entry.studentId}
                entry={entry}
                kind={sheet.kind}
                schoolName={school.name}
                className={sheet.className}
                copy={copy}
                expires={formatInstant(entry.expiresAt)}
              />
            ))}
          </section>
        ))}
      </div>
    </div>
  );
}

function LoginCard({
  entry,
  kind,
  schoolName,
  className,
  copy,
  expires,
}: {
  entry: CodeEntry;
  kind: CodeKind;
  schoolName: string;
  className: string;
  copy: { instruction: string; instructionQr: string; instructionText: string; validUntilShort: string; qrCaption: string; textCaption: string };
  expires: string;
}) {
  const qr = kind !== "TEXT" ? qrPayload(entry) : null;
  const text = kind !== "QR" && entry.textCode ? formatTextCode(entry.textCode) : null;
  const instruction = qr && text ? copy.instruction : qr ? copy.instructionQr : copy.instructionText;
  return (
    <article
      className="flex min-h-[62mm] break-inside-avoid flex-col justify-between gap-1 border border-dashed border-neutral-400 p-[4mm]"
      data-testid="login-card"
    >
      <header className="flex items-center gap-2">
        <StudentAvatar id={entry.studentId} name={entry.displayName} size={36} />
        <div className="min-w-0">
          <p className="truncate text-[11pt] leading-tight font-bold">{entry.displayName}</p>
          <p className="truncate text-[8pt] text-neutral-600">
            {schoolName} · {className}
          </p>
        </div>
      </header>
      <div className="flex items-center justify-center gap-3">
        {qr ? (
          <div className="flex flex-col items-center gap-0.5">
            <QrCode value={qr} size={104} label={`${copy.qrCaption}: ${entry.displayName}`} />
            <span className="text-[7pt] text-neutral-600">{copy.qrCaption}</span>
          </div>
        ) : null}
        {text ? (
          <div className="flex flex-col items-center gap-0.5">
            <span className="font-mono text-[17pt] leading-none font-bold tracking-[0.08em]" data-testid="card-text-code">
              {text}
            </span>
            <span className="text-[7pt] text-neutral-600">{copy.textCaption}</span>
          </div>
        ) : null}
      </div>
      <footer className="flex flex-col gap-0.5">
        <p className="text-[8pt] leading-snug">{instruction}</p>
        <p className="text-[7pt] text-neutral-600">
          {copy.validUntilShort}: {expires}
        </p>
      </footer>
    </article>
  );
}
