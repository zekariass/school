"use client";

import { CheckIcon, CopyIcon, PrinterIcon, TriangleAlertIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { useFormatInstant } from "@/components/calendar-preference";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { useRouter } from "@/i18n/navigation";
import type { ParentCodeSheet } from "@/lib/school/parent-codes";
import { useIssuedCodes } from "./issued-codes-context";
import { useSchool } from "./school-context";

/**
 * Shows a freshly issued parent code exactly once: it cannot be read again. Closing the dialog
 * discards the code; "Print card" hands it to the print page through the in-memory provider.
 */
export function ParentCodeDialog({ sheet, onClose }: { sheet: ParentCodeSheet; onClose: () => void }) {
  const t = useTranslations("school.families.code");
  const router = useRouter();
  const { school } = useSchool();
  const { putParent } = useIssuedCodes();
  const formatInstant = useFormatInstant();
  const [copied, setCopied] = useState(false);
  const entry = sheet.codes[0];
  if (!entry) return null;

  async function copy() {
    if (!entry) return;
    try {
      await navigator.clipboard.writeText(entry.code);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // clipboard blocked: the code is still on screen to read out
    }
  }

  function print() {
    putParent(sheet);
    onClose();
    router.push(`/s/${encodeURIComponent(school.slug)}/classes/${sheet.classId}/parent-codes/print`);
  }

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t("title", { name: entry.childFirstName })}</DialogTitle>
          <DialogDescription>{t("howTo")}</DialogDescription>
        </DialogHeader>
        <p className="flex items-start gap-2 rounded-xl bg-warning/15 p-3 text-sm font-medium" role="note">
          <TriangleAlertIcon className="mt-0.5 size-4 shrink-0" aria-hidden />
          {t("warning")}
        </p>
        <div className="flex flex-col items-center gap-2 rounded-2xl border p-4 text-center">
          <p
            className="font-mono text-3xl font-bold tracking-widest select-all"
            aria-label={t("label")}
            data-testid="issued-parent-code"
          >
            {entry.code}
          </p>
          <p className="text-sm text-muted-foreground">{t("expires", { date: formatInstant(entry.expiresAt) })}</p>
          <p className="text-sm text-muted-foreground">{t("uses", { count: entry.maxUses })}</p>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => void copy()}>
            {copied ? <CheckIcon className="size-4" aria-hidden /> : <CopyIcon className="size-4" aria-hidden />}
            {copied ? t("copied") : t("copy")}
          </Button>
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
