"use client";

import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { Loader2Icon, SearchCheckIcon, Trash2Icon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { toast } from "sonner";
import { FormField } from "@/components/console/form-field";
import { EmptyNotice, ErrorNotice, LoadingRows } from "@/components/console/states";
import { useSubmit } from "@/components/console/use-submit";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { errorMessage } from "@/lib/console/errors";
import {
  applyProfile,
  cleanMapping,
  columnLetter,
  delimiterIdOf,
  delimiterValue,
  DELIMITERS,
  ENCODINGS,
  encodingIdOf,
  missingFields,
  requirementOf,
  readingQuery,
  sampleValues,
  setColumn,
  type DelimiterId,
  type Mapping,
  type ReadingChoice,
} from "@/lib/school/import/mapping";
import { importDateSystems, initialDateSystem, type ImportDateSystem } from "@/lib/school/import/date-system";
import { deleteProfile, fetchPreview, fetchProfiles, ik, saveMapping, startDryRun } from "@/lib/school/import/queries";
import type { ImportJob, ImportPreview } from "@/lib/school/import/state";
import { ConfirmDialog } from "../confirm-dialog";
import { SelectField } from "../fields";
import { useSchool } from "../school-context";

/**
 * Step 2: how the file is read, which column fills which field, how dates are written. "Check the file" saves the
 * mapping and starts the dry run in one go.
 */
export function ColumnsStage({ job, onStarted }: { job: ImportJob; onStarted: () => void }) {
  const t = useTranslations("school.import.columns");
  const { school } = useSchool();
  // null until the person changes encoding, separator or sheet: then the file is read that way (nothing saved yet).
  const [reading, setReading] = useState<ReadingChoice | null>(null);
  const query = readingQuery(reading);
  const preview = useQuery({
    queryKey: ik.preview(school.id, job.id, query),
    queryFn: () => fetchPreview(school.id, job.id, query),
    placeholderData: keepPreviousData,
    staleTime: 30_000,
    // The first rows are children's data: they are not kept around for long.
    gcTime: 30_000,
  });

  const current: ReadingChoice = reading ?? {
    encoding: encodingIdOf(job.options.encoding),
    delimiter: delimiterIdOf(job.options.delimiter),
    sheet: job.options.sheet ?? "",
  };

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h2 className="text-xl font-bold">{t("title")}</h2>
        <p className="text-muted-foreground">{t("help")}</p>
      </div>

      <ReadingCard
        preview={preview.data}
        choice={current}
        busy={preview.isFetching}
        onChange={(choice) => setReading(choice)}
      />

      {preview.isPending || preview.isPlaceholderData ? (
        <div aria-busy="true">
          <LoadingRows rows={5} />
        </div>
      ) : preview.isError ? (
        <ErrorNotice message={errorMessage(preview.error, t("loadFailed"))} onRetry={() => void preview.refetch()} />
      ) : (
        <>
          <PreviewTable preview={preview.data} />
          <MappingEditor
            // A new reading of the file has new headers: start from the mapping suggested for them.
            key={JSON.stringify(query)}
            job={job}
            preview={preview.data}
            reading={reading}
            onStarted={onStarted}
          />
        </>
      )}
    </div>
  );
}

// --- how the file is read -------------------------------------------------------------------------

function ReadingCard({
  preview,
  choice,
  busy,
  onChange,
}: {
  preview: ImportPreview | undefined;
  choice: ReadingChoice;
  busy: boolean;
  onChange: (choice: ReadingChoice) => void;
}) {
  const t = useTranslations("school.import.columns.reading");
  const detected = preview?.detected;
  const isCsv = detected ? detected.format === "CSV" : true;
  const sheets = detected?.sheetNames ?? [];
  const detectedDelimiter = delimiterIdOf(detected?.delimiter);

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("title")}</CardTitle>
        {detected ? (
          <p className="text-sm text-muted-foreground" aria-live="polite">
            {isCsv
              ? t("detectedCsv", {
                  encoding: detected.encoding ?? "?",
                  delimiter: detectedDelimiter ? t(`delimiter.${detectedDelimiter}`) : (detected.delimiter ?? "?"),
                })
              : t("detectedSheet", { sheet: detected.sheet ?? "?" })}
          </p>
        ) : null}
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {isCsv ? (
          <div className="grid gap-3 sm:grid-cols-2">
            <SelectField
              label={t("encoding")}
              value={choice.encoding}
              onChange={(encoding) => onChange({ ...choice, encoding })}
              noneLabel={t("auto")}
              options={ENCODINGS.map((e) => ({ value: e, label: e }))}
              disabled={busy}
            />
            <SelectField
              label={t("delimiter.label")}
              value={choice.delimiter}
              onChange={(delimiter) => onChange({ ...choice, delimiter: delimiter as DelimiterId | "" })}
              noneLabel={t("auto")}
              options={DELIMITERS.map((d) => ({ value: d.id, label: t(`delimiter.${d.id}`) }))}
              disabled={busy}
            />
            <p className="text-sm text-muted-foreground sm:col-span-2">{t("encodingHint")}</p>
          </div>
        ) : sheets.length > 1 ? (
          <SelectField
            label={t("sheet")}
            value={choice.sheet}
            onChange={(sheet) => onChange({ ...choice, sheet })}
            noneLabel={t("firstSheet")}
            options={sheets.map((s) => ({ value: s, label: s }))}
            disabled={busy}
          />
        ) : null}
        {detected && detected.formulaCells > 0 ? (
          <p className="text-sm text-muted-foreground" role="note">
            {t("formulas", { count: detected.formulaCells })}
          </p>
        ) : null}
        {detected && detected.mergedRegions > 0 ? (
          <p className="text-sm text-muted-foreground" role="note">
            {t("merged", { count: detected.mergedRegions })}
          </p>
        ) : null}
      </CardContent>
    </Card>
  );
}

// --- the first rows ---------------------------------------------------------------------------------

function PreviewTable({ preview }: { preview: ImportPreview }) {
  const t = useTranslations("school.import.columns.preview");
  const rows = preview.previewRows.slice(0, 6);
  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("title")}</CardTitle>
      </CardHeader>
      <CardContent>
        {rows.length === 0 ? (
          <EmptyNotice>{t("empty")}</EmptyNotice>
        ) : (
          <div className="overflow-x-auto rounded-xl border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="w-16">{t("row")}</TableHead>
                  {preview.headers.map((header, index) => (
                    <TableHead key={index} className="min-w-28">
                      <span className="text-xs font-normal text-muted-foreground">{columnLetter(index)}</span>
                      <span className="block">{header || t("noHeading")}</span>
                    </TableHead>
                  ))}
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((row) => (
                  <TableRow key={row.rowNumber} className={row.example ? "text-muted-foreground" : undefined}>
                    <TableCell className="whitespace-nowrap">
                      {row.rowNumber}
                      {row.example ? <Badge variant="outline">{t("example")}</Badge> : null}
                    </TableCell>
                    {preview.headers.map((_, index) => (
                      <TableCell key={index} className="max-w-56 truncate">
                        {row.cells[index] ?? ""}
                      </TableCell>
                    ))}
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
      </CardContent>
    </Card>
  );
}

// --- the column mapping --------------------------------------------------------------------------

function MappingEditor({
  job,
  preview,
  reading,
  onStarted,
}: {
  job: ImportJob;
  preview: ImportPreview;
  reading: ReadingChoice | null;
  onStarted: () => void;
}) {
  const t = useTranslations("school.import.columns");
  const { school } = useSchool();
  const queryClient = useQueryClient();
  const [mapping, setMapping] = useState<Mapping>(() => cleanMapping(preview.suggestedMapping, preview.headers.length));
  // Date systems the school's country has (D42): Ethiopian only where `calendarCodes` lists it. The API refuses
  // the others (422 import.date_system_not_available) and its message is shown as it is.
  const dateSystems = importDateSystems(school.country.calendarCodes);
  const [dateSystem, setDateSystem] = useState<ImportDateSystem>(() => initialDateSystem(job.options.dateSystem, dateSystems));
  const [profileName, setProfileName] = useState("");
  const [profileId, setProfileId] = useState(preview.appliedProfile?.id ?? "");
  // The API applies the newest saved match of this type when the import is created.
  const [applied, setApplied] = useState<{ name: string; unmatched: number } | null>(
    preview.appliedProfile ? { name: preview.appliedProfile.name, unmatched: 0 } : null,
  );
  const [deleting, setDeleting] = useState(false);

  const profiles = useQuery({
    queryKey: ik.profiles(school.id, job.importType),
    queryFn: () => fetchProfiles(school.id, job.importType),
    staleTime: 60_000,
  });
  const labels = Object.fromEntries(preview.fields.map((f) => [f.key, f.label]));
  const missing = missingFields(preview.requiredFields, mapping);
  const nameOf = (entry: string) =>
    entry
      .split("|")
      .map((key) => labels[key] ?? key)
      .join(` ${t("or")} `);

  const check = useSubmit({
    mutationFn: async () => {
      await saveMapping(school.id, job.id, {
        columnMapping: mapping,
        options: {
          encoding: reading ? reading.encoding || undefined : (job.options.encoding ?? undefined),
          delimiter: reading ? delimiterValue(reading.delimiter) || undefined : (job.options.delimiter ?? undefined),
          sheet: reading ? reading.sheet || undefined : (job.options.sheet ?? undefined),
          dateSystem,
        },
        ...(profileName.trim() ? { saveAsProfile: profileName.trim() } : {}),
      });
      return startDryRun(school.id, job.id);
    },
    invalidate: [ik.list(school.id, {}), ik.profiles(school.id, job.importType)],
    errorFallback: t("saveFailed"),
    toastOnError: false,
    onSuccess: (started) => {
      queryClient.setQueryData(ik.job(school.id, job.id), started);
      onStarted();
    },
  });

  const removeProfile = useSubmit({
    mutationFn: () => deleteProfile(school.id, profileId),
    invalidate: [ik.profiles(school.id, job.importType)],
    errorFallback: t("profiles.deleteFailed"),
    successMessage: t("profiles.deleted"),
    onSuccess: () => {
      setProfileId("");
      setApplied(null);
      setDeleting(false);
    },
  });

  function chooseProfile(id: string) {
    setProfileId(id);
    const profile = profiles.data?.find((p) => p.id === id);
    if (!profile) {
      setApplied(null);
      return;
    }
    const result = applyProfile(profile.columnMapping, preview.headers, mapping);
    setMapping(result.mapping);
    setApplied({ name: profile.name, unmatched: result.unmatched.length });
    if (result.unmatched.length > 0) toast.message(t("profiles.unmatched", { count: result.unmatched.length }));
  }

  const columnOptions = preview.headers.map((header, index) => ({
    value: String(index),
    label: t("columnOption", { letter: columnLetter(index), header: header || t("preview.noHeading") }),
  }));
  const rowsForSamples = preview.previewRows.map((r) => ({ cells: r.cells, example: r.example }));

  return (
    <>
    <form
      noValidate
      className="flex flex-col gap-6"
      onSubmit={(event) => {
        event.preventDefault();
        if (missing.length === 0) check.mutate();
      }}
    >
      <Card>
        <CardHeader>
          <CardTitle>{t("mapping.title")}</CardTitle>
          <p className="text-sm text-muted-foreground">{t("mapping.help")}</p>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          {(profiles.data?.length ?? 0) > 0 ? (
            <div className="flex flex-wrap items-end gap-2">
              <SelectField
                className="min-w-56 flex-1"
                label={t("profiles.apply")}
                value={profileId}
                onChange={chooseProfile}
                noneLabel={t("profiles.choose")}
                options={(profiles.data ?? []).map((p) => ({ value: p.id, label: p.name }))}
              />
              {profileId ? (
                <Button type="button" variant="ghost" onClick={() => setDeleting(true)}>
                  <Trash2Icon aria-hidden />
                  {t("profiles.delete")}
                </Button>
              ) : null}
            </div>
          ) : null}
          {applied ? (
            <p className="text-sm text-muted-foreground" role="status">
              {t("profiles.applied", { name: applied.name })}
            </p>
          ) : null}

          <ul className="flex flex-col divide-y rounded-xl border">
            {preview.fields.map((field) => {
              const requirement = requirementOf(field, preview.requiredFields);
              const index = mapping[field.key];
              const samples = index === undefined ? [] : sampleValues(rowsForSamples, index);
              return (
                <li key={field.key} className="grid gap-2 p-3 sm:grid-cols-[minmax(0,1fr)_minmax(0,1.2fr)] sm:items-start">
                  <div className="flex flex-wrap items-center gap-2 sm:min-h-11">
                    <span className="font-semibold">{field.label}</span>
                    {requirement === "required" ? <Badge variant="secondary">{t("mapping.required")}</Badge> : null}
                    {requirement === "oneOf" ? <Badge variant="outline">{t("mapping.oneOf")}</Badge> : null}
                  </div>
                  <SelectField
                    label={t("mapping.columnFor", { field: field.label })}
                    value={index === undefined ? "" : String(index)}
                    onChange={(value) => setMapping((m) => setColumn(m, field.key, value === "" ? null : Number(value)))}
                    noneLabel={t("mapping.none")}
                    options={columnOptions}
                    hint={samples.length > 0 ? t("mapping.sample", { values: samples.join(", ") }) : undefined}
                  />
                </li>
              );
            })}
          </ul>

          {missing.length > 0 ? (
            <p role="alert" className="rounded-xl bg-warning/15 p-3 text-sm font-medium">
              {t("mapping.missing", { fields: missing.map(nameOf).join(", ") })}
            </p>
          ) : null}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>{t("options.title")}</CardTitle>
        </CardHeader>
        <CardContent className="grid gap-4 sm:grid-cols-2">
          {dateSystems.length > 1 ? (
            <SelectField
              label={t("options.dateSystem")}
              value={dateSystem}
              onChange={(value) => setDateSystem(dateSystems.find((d) => d === value) ?? "GREGORIAN")}
              options={dateSystems.map((value) => ({ value, label: t(`options.${value}`) }))}
              hint={t("options.dateHint")}
            />
          ) : null}
          <FormField label={t("profiles.saveAs")} hint={t("profiles.saveHint")}>
            {(props) => (
              <Input {...props} value={profileName} maxLength={100} onChange={(e) => setProfileName(e.target.value)} />
            )}
          </FormField>
        </CardContent>
      </Card>

      {check.isError ? (
        <p role="alert" className="rounded-xl bg-destructive/10 p-3 text-sm font-medium text-destructive">
          {errorMessage(check.error, t("saveFailed"))}
        </p>
      ) : null}

      <div>
        <Button type="submit" disabled={missing.length > 0 || check.isPending}>
          {check.isPending ? <Loader2Icon className="animate-spin" aria-hidden /> : <SearchCheckIcon aria-hidden />}
          {check.isPending ? t("checking") : t("check")}
        </Button>
      </div>
    </form>

      {deleting ? (
        <ConfirmDialog
          title={t("profiles.deleteTitle")}
          description={t("profiles.deleteBody")}
          confirmLabel={t("profiles.delete")}
          pending={removeProfile.isPending}
          error={removeProfile.isError ? errorMessage(removeProfile.error, t("profiles.deleteFailed")) : null}
          onConfirm={() => removeProfile.mutate()}
          onClose={() => setDeleting(false)}
        />
      ) : null}
    </>
  );
}
