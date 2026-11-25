"use client";

import { CheckIcon, CopyIcon, PrinterIcon, TriangleAlertIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { useFormatInstant } from "@/components/calendar-preference";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { useRouter } from "@/i18n/navigation";
import { formatTextCode, qrPayload, type CodeSheet } from "@/lib/school/codes";
import { useIssuedCodes } from "./issued-codes-context";
import { QrCode } from "./qr-code";
import { useSchool } from "./school-context";

/**
 * Shows a freshly issued code exactly once (it cannot be read again): the text code to
 * read out or copy, the QR code, and a printable card. Closing it discards the code.
 */
export function IssuedCodeDialog({ sheet, onClose }: { sheet: CodeSheet; onClose: () => void }) {
  const t = useTranslations("school.codes.issued");
  const router = useRouter();
  const { school } = useSchool();
  const { put } = useIssuedCodes();
  const formatInstant = useFormatInstant();
  const [copied, setCopied] = useState(false);
  const entry = sheet.codes[0];
  if (!entry) return null;
  const text = entry.textCode ? formatTextCode(entry.textCode) : null;
  const qr = qrPayload(entry);

  async function copy() {
    if (!text) return;
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // clipboard blocked: the code is still on screen to read out
    }
  }

  function print() {
    put(sheet);
    onClose();
    router.push(`/s/${encodeURIComponent(school.slug)}/classes/${sheet.classId}/login-codes/print`);
  }

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t("title", { name: entry.displayName })}</DialogTitle>
          <DialogDescription>{t("expires", { date: formatInstant(entry.expiresAt) })}</DialogDescription>
        </DialogHeader>
        <p className="flex items-start gap-2 rounded-xl bg-warning/15 p-3 text-sm font-medium" role="note">
          <TriangleAlertIcon className="mt-0.5 size-4 shrink-0" aria-hidden />
          {t("warning")}
        </p>
        <div className="flex flex-col items-center gap-4 rounded-2xl border p-4">
          {text ? (
            <p
              className="font-mono text-3xl font-bold tracking-widest select-all"
              aria-label={t("textCodeLabel")}
              data-testid="issued-text-code"
            >
              {text}
            </p>
          ) : null}
          {qr ? <QrCode value={qr} size={160} label={t("qrLabel", { name: entry.displayName })} /> : null}
        </div>
        <DialogFooter>
          {text ? (
            <Button variant="outline" onClick={() => void copy()}>
              {copied ? <CheckIcon className="size-4" aria-hidden /> : <CopyIcon className="size-4" aria-hidden />}
              {copied ? t("copied") : t("copy")}
            </Button>
          ) : null}
          <Button variant="outline" onClick={print}>
            <PrinterIcon className="size-4" aria-hidden />
            {t("print")}
          </Button>
          <Button onClick={onClose}>{t("done")}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
