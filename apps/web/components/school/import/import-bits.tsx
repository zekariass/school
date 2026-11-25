"use client";

import { useQuery } from "@tanstack/react-query";
import { CheckIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { Badge } from "@/components/ui/badge";
import { ApiRequestError } from "@/lib/api-error";
import { fetchImport, fetchImportTypes, ik } from "@/lib/school/import/queries";
import { isStepDone, pollDelayMs, STEPS, type ImportKind, type ImportStatus, type Step } from "@/lib/school/import/state";
import { cn } from "@/lib/utils";
import { useSchool } from "../school-context";

/** One import, polled while it runs (every second at first) and left alone once it is not running any more. */
export function useImportJob(jobId: string) {
  const { school } = useSchool();
  return useQuery({
    queryKey: ik.job(school.id, jobId),
    queryFn: () => fetchImport(school.id, jobId),
    refetchInterval: (query) => {
      const error = query.state.error;
      // A refused or missing import will not get better by asking again.
      if (error instanceof ApiRequestError && error.status !== undefined && error.status >= 400 && error.status < 500) {
        return false;
      }
      return pollDelayMs(query.state.data?.status, query.state.dataUpdateCount);
    },
    // Counts and failure messages can change while the person is away.
    staleTime: 0,
    gcTime: 60_000,
  });
}

/** Field keys to the labels the API wrote in the reader's language, per import type (for problem messages). */
export function useFieldLabels(kind: ImportKind | undefined): Record<string, string> {
  const { school } = useSchool();
  const types = useQuery({
    queryKey: ik.types(school.id),
    queryFn: () => fetchImportTypes(school.id),
    staleTime: 10 * 60_000,
  });
  const labels: Record<string, string> = {};
  for (const field of types.data?.find((type) => type.type === kind)?.fields ?? []) labels[field.key] = field.label;
  return labels;
}

export function ImportStatusBadge({ status }: { status: ImportStatus }) {
  const t = useTranslations("school.import.status");
  const variant =
    status === "COMPLETED" ? "default" : status === "FAILED" ? "destructive" : status === "CANCELLED" ? "outline" : "secondary";
  return <Badge variant={variant}>{t(status)}</Badge>;
}

/** The five steps of the wizard; the current one is marked for assistive technology as well. */
export function ImportSteps({ current }: { current: Step | null }) {
  const t = useTranslations("school.import.steps");
  return (
    <ol aria-label={t("label")} className="flex flex-wrap gap-x-4 gap-y-2">
      {STEPS.map((step, index) => {
        const done = isStepDone(step, current);
        const active = step === current;
        return (
          <li key={step} aria-current={active ? "step" : undefined} className="flex items-center gap-2">
            <span
              className={cn(
                "grid size-7 shrink-0 place-items-center rounded-full border text-sm font-semibold",
                active && "border-primary bg-primary text-primary-foreground",
                done && "border-primary bg-primary/10 text-primary",
                !active && !done && "text-muted-foreground",
              )}
            >
              {done ? <CheckIcon className="size-4" aria-hidden /> : index + 1}
            </span>
            <span className={cn("text-sm", active ? "font-semibold" : "text-muted-foreground", !active && "max-sm:sr-only")}>
              {t(step)}
              {done ? <span className="sr-only"> ({t("done")})</span> : null}
            </span>
          </li>
        );
      })}
    </ol>
  );
}

export function ProgressBar({ value, label }: { value: number; label: string }) {
  return (
    <div
      role="progressbar"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={value}
      className="h-3 w-full overflow-hidden rounded-full bg-muted"
    >
      <div className="h-full rounded-full bg-primary transition-[width] duration-500" style={{ width: `${value}%` }} />
    </div>
  );
}
