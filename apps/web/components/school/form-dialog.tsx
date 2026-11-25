"use client";

import { Loader2Icon } from "lucide-react";
import { useTranslations } from "next-intl";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { cn } from "@/lib/utils";

/**
 * A modal form: title, fields, the API's problem inline, Cancel and a submit button.
 * `onSubmit` runs only when the browser's own checks pass (forms use `noValidate`, so
 * pages validate themselves and show messages next to the fields).
 */
export function FormDialog({
  title,
  description,
  submitLabel,
  pending,
  error,
  onSubmit,
  onClose,
  className,
  children,
}: {
  title: string;
  description?: string;
  submitLabel?: string;
  pending?: boolean;
  error?: string | null;
  onSubmit: () => void;
  onClose: () => void;
  className?: string;
  children: React.ReactNode;
}) {
  const t = useTranslations("console");
  return (
    <Dialog open onOpenChange={(open) => !open && !pending && onClose()}>
      <DialogContent className={cn("max-h-[90dvh] overflow-y-auto", className)}>
        <form
          noValidate
          className="flex flex-col gap-4"
          onSubmit={(event) => {
            event.preventDefault();
            onSubmit();
          }}
        >
          <DialogHeader>
            <DialogTitle>{title}</DialogTitle>
            {description ? <DialogDescription>{description}</DialogDescription> : null}
          </DialogHeader>
          {children}
          {error ? (
            <p role="alert" className="rounded-xl bg-destructive/10 p-3 text-sm font-medium text-destructive">
              {error}
            </p>
          ) : null}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose} disabled={pending}>
              {t("cancel")}
            </Button>
            <Button type="submit" disabled={pending}>
              {pending ? <Loader2Icon className="size-4 animate-spin" aria-hidden /> : null}
              {pending ? t("saving") : (submitLabel ?? t("save"))}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
