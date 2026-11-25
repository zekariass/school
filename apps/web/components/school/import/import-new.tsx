"use client";

import { useQueryClient } from "@tanstack/react-query";
import { ArrowLeftIcon, DownloadIcon, FileSpreadsheetIcon, Loader2Icon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useId, useReducer, useRef, useState } from "react";
import { toast } from "sonner";
import { useSubmit } from "@/components/console/use-submit";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Link, useRouter } from "@/i18n/navigation";
import { errorMessage } from "@/lib/console/errors";
import { policyFor, type FileProblem } from "@/lib/media/policies";
import { useMediaPolicies } from "@/lib/media/queries";
import { UploadError, uploadMedia } from "@/lib/media/upload";
import { IDLE, isBusy, uploadReducer, type UploadFailure } from "@/lib/media/upload-state";
import { SP } from "@/lib/school/access";
import { asImportFile, checkImportFile, CSV_MIME, templateName, XLSX_MIME, type ImportFileProblem } from "@/lib/school/import/files";
import { createImport, downloadFile, ik, templatePath } from "@/lib/school/import/queries";
import { IMPORT_KINDS, type ImportKind } from "@/lib/school/import/state";
import { cn } from "@/lib/utils";
import { PageHeader } from "../page-header";
import { RequirePermission } from "../require-permission";
import { useSchool } from "../school-context";
import { ImportSteps } from "./import-bits";

const ACCEPT = `.csv,.xlsx,${CSV_MIME},${XLSX_MIME}`;
const MB = 1024 * 1024;

/** Step 1: what to import, a template to start from, and the file. Creating the job opens it at the next step. */
export function ImportNew() {
  const t = useTranslations("school.import");
  const { school } = useSchool();
  const [kind, setKind] = useState<ImportKind>("STUDENTS");
  const [uploaded, setUploaded] = useState<{ mediaId: string; name: string } | null>(null);
  const base = `/s/${encodeURIComponent(school.slug)}/import`;

  return (
    <RequirePermission anyOf={[SP.importManage]}>
      <div className="mx-auto flex max-w-3xl flex-col gap-6">
        <Link href={base} className="inline-flex min-h-11 w-fit items-center gap-2 text-sm font-semibold text-primary hover:underline">
          <ArrowLeftIcon className="size-4 rtl:rotate-180" aria-hidden />
          {t("backToList")}
        </Link>
        <PageHeader title={t("new.title")} description={t("new.subtitle")} />
        <ImportSteps current="file" />

        <KindPicker kind={kind} onChange={setKind} />
        <TemplateCard kind={kind} />
        <FileCard
          kind={kind}
          uploaded={uploaded}
          onUploaded={setUploaded}
        />
      </div>
    </RequirePermission>
  );
}

function KindPicker({ kind, onChange }: { kind: ImportKind; onChange: (kind: ImportKind) => void }) {
  const t = useTranslations("school.import");
  const legend = useId();
  return (
    <div role="radiogroup" aria-labelledby={legend} className="flex flex-col gap-2">
      <h2 id={legend} className="text-lg font-bold">
        {t("new.kindLegend")}
      </h2>
      <div className="grid gap-2 sm:grid-cols-2">
        {IMPORT_KINDS.map((option) => {
          const selected = option === kind;
          return (
            <button
              key={option}
              type="button"
              role="radio"
              aria-checked={selected}
              onClick={() => onChange(option)}
              className={cn(
                "flex min-h-16 flex-col items-start gap-0.5 rounded-2xl border bg-card p-3 text-start transition-colors outline-none hover:bg-accent focus-visible:ring-[3px] focus-visible:ring-ring/50",
                selected && "border-primary bg-primary/5 ring-2 ring-primary/40",
              )}
            >
              <span className="font-semibold">{t(`kind.${option}.name`)}</span>
              <span className="text-sm text-muted-foreground">{t(`kind.${option}.help`)}</span>
            </button>
          );
        })}
      </div>
    </div>
  );
}

function TemplateCard({ kind }: { kind: ImportKind }) {
  const t = useTranslations("school.import.new.template");
  const { school } = useSchool();
  const [busy, setBusy] = useState<"csv" | "xlsx" | null>(null);

  async function download(format: "csv" | "xlsx") {
    setBusy(format);
    try {
      await downloadFile(templatePath(school.id, kind, format), templateName(kind, format));
    } catch (error) {
      toast.error(errorMessage(error, t("failed")));
    } finally {
      setBusy(null);
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("title")}</CardTitle>
        <p className="text-sm text-muted-foreground">{t("help")}</p>
      </CardHeader>
      <CardContent className="flex flex-wrap gap-2">
        {(["csv", "xlsx"] as const).map((format) => (
          <Button key={format} type="button" variant="outline" disabled={busy !== null} onClick={() => void download(format)}>
            {busy === format ? <Loader2Icon className="animate-spin" aria-hidden /> : <DownloadIcon aria-hidden />}
            {t(format)}
          </Button>
        ))}
      </CardContent>
    </Card>
  );
}

function FileCard({
  kind,
  uploaded,
  onUploaded,
}: {
  kind: ImportKind;
  uploaded: { mediaId: string; name: string } | null;
  onUploaded: (file: { mediaId: string; name: string } | null) => void;
}) {
  const t = useTranslations("school.import.new.file");
  const tMedia = useTranslations("media");
  const { school } = useSchool();
  const inputId = useId();
  const input = useRef<HTMLInputElement>(null);
  const abort = useRef<AbortController | null>(null);
  const [state, dispatch] = useReducer(uploadReducer, IDLE);
  const [problem, setProblem] = useState<string | null>(null);
  const policies = useMediaPolicies();
  const policy = policies.data ? policyFor(policies.data, "IMPORT_FILE") : undefined;
  const busy = isBusy(state);

  function describe(file: ImportFileProblem): string {
    switch (file.code) {
      case "extension":
        return t("extension");
      case "empty":
        return tMedia("errors.empty");
      case "type":
        return tMedia("errors.type", { types: "CSV, XLSX" });
      case "size":
        return tMedia("errors.size", { size: (file as Extract<FileProblem, { code: "size" }>).maxMb });
    }
  }

  function describeFailure(error: unknown, reason: UploadFailure): string {
    if (error instanceof UploadError && error.problem?.detail) return error.problem.detail;
    return errorMessage(error, tMedia(`errors.${reason === "other" ? "network" : reason}`));
  }

  async function onFile(chosen: File) {
    setProblem(null);
    onUploaded(null);
    if (!policy) {
      setProblem(tMedia("errors.policies"));
      return;
    }
    const invalid = checkImportFile(chosen, policy);
    if (invalid) {
      setProblem(describe(invalid));
      return;
    }
    const controller = new AbortController();
    abort.current = controller;
    dispatch({ type: "start" });
    try {
      const media = await uploadMedia({
        purpose: "IMPORT_FILE",
        file: asImportFile(chosen),
        context: { type: "SCHOOL", id: school.id },
        signal: controller.signal,
        onStage: (stage) => dispatch({ type: "stage", stage }),
        onProgress: (loaded, total) => dispatch({ type: "progress", loaded, total }),
      });
      dispatch({ type: "ready", mediaId: media.id });
      onUploaded({ mediaId: media.id, name: chosen.name });
    } catch (error) {
      if (error instanceof DOMException && error.name === "AbortError") {
        dispatch({ type: "reset" });
        return;
      }
      const reason: UploadFailure = error instanceof UploadError ? error.reason : "other";
      dispatch({ type: "failed", reason });
      setProblem(describeFailure(error, reason));
    } finally {
      abort.current = null;
      if (input.current) input.current.value = "";
    }
  }

  const status =
    state.phase === "running"
      ? state.stage === "uploading"
        ? tMedia("upload.uploading", { percent: state.percent })
        : tMedia(`upload.${state.stage === "requesting" ? "preparing" : state.stage === "completing" ? "checking" : "processing"}`)
      : null;
  const maxMb = policy ? Math.floor(Math.max(...Object.values(policy.maxBytesByMimeType)) / MB) : 10;

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("title")}</CardTitle>
        <p className="text-sm text-muted-foreground">{t("hint", { size: maxMb })}</p>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <div className="flex flex-wrap items-center gap-2">
          <input
            ref={input}
            id={inputId}
            type="file"
            className="sr-only"
            accept={ACCEPT}
            disabled={busy || !policy}
            onChange={(event) => {
              const chosen = event.target.files?.[0];
              if (chosen) void onFile(chosen);
            }}
          />
          <Button type="button" variant="outline" disabled={busy || !policy} onClick={() => input.current?.click()}>
            <FileSpreadsheetIcon aria-hidden />
            {uploaded ? t("change") : t("choose")}
          </Button>
          {busy && state.phase === "running" && state.stage === "uploading" ? (
            <Button type="button" variant="ghost" size="sm" onClick={() => abort.current?.abort()}>
              {tMedia("upload.cancel")}
            </Button>
          ) : null}
          {uploaded ? <span className="min-w-0 truncate text-sm font-medium">{uploaded.name}</span> : null}
        </div>

        {state.phase === "running" ? (
          <div className="flex flex-col gap-1" aria-live="polite">
            <progress
              className="h-2 w-full max-w-xs overflow-hidden rounded-full accent-primary"
              aria-label={tMedia("upload.progressLabel")}
              max={100}
              {...(state.stage === "uploading" ? { value: state.percent } : {})}
            />
            <p className="text-sm text-muted-foreground">{status}</p>
          </div>
        ) : null}

        {problem ? (
          <p role="alert" className="text-sm font-medium text-destructive">
            {problem}
          </p>
        ) : null}
        <p className="text-sm text-muted-foreground">{t("privacy")}</p>

        {uploaded ? (
          // A new file or another type is a new create call, so it gets a new Idempotency-Key.
          <CreateButton key={`${uploaded.mediaId}:${kind}`} kind={kind} mediaId={uploaded.mediaId} disabled={busy} />
        ) : (
          <div>
            <Button type="button" disabled>
              {t("continue")}
            </Button>
          </div>
        )}
      </CardContent>
    </Card>
  );
}

function CreateButton({ kind, mediaId, disabled }: { kind: ImportKind; mediaId: string; disabled: boolean }) {
  const t = useTranslations("school.import.new.file");
  const router = useRouter();
  const queryClient = useQueryClient();
  const { school } = useSchool();
  const create = useSubmit({
    mutationFn: (_: void, key: string) => createImport(school.id, { importType: kind, mediaId }, key),
    invalidate: [ik.list(school.id, {})],
    errorFallback: t("createFailed"),
    toastOnError: false,
    onSuccess: (preview) => {
      // The first look at the file is already in hand: the job page starts from it.
      queryClient.setQueryData(ik.job(school.id, preview.job.id), preview.job);
      queryClient.setQueryData(ik.preview(school.id, preview.job.id, null), preview);
      router.push(`/s/${encodeURIComponent(school.slug)}/import/${preview.job.id}`);
    },
  });
  return (
    <div className="flex flex-col gap-2">
      {create.isError ? (
        <p role="alert" className="text-sm font-medium text-destructive">
          {errorMessage(create.error, t("createFailed"))}
        </p>
      ) : null}
      <div>
        <Button type="button" disabled={disabled || create.isPending} onClick={() => create.mutate()}>
          {create.isPending ? <Loader2Icon className="animate-spin" aria-hidden /> : null}
          {create.isPending ? t("reading") : t("continue")}
        </Button>
      </div>
    </div>
  );
}
