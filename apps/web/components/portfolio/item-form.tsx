"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Loader2Icon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useRef, useState } from "react";
import { toast } from "sonner";
import { FormField } from "@/components/console/form-field";
import { ErrorNotice, LoadingRows } from "@/components/console/states";
import { StudentTags } from "@/components/feed/student-tags";
import { CheckboxField, SelectField } from "@/components/school/fields";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { errorMessage } from "@/lib/console/errors";
import { newIdempotencyKey } from "@/lib/console/idempotency";
import {
  MAX_DESCRIPTION,
  MAX_REFLECTION,
  MAX_TAGS,
  MAX_TEXT,
  MAX_TITLE,
  buildCreateBody,
  buildQuickCaptureBody,
  buildUpdateBody,
  carriesFiles,
  formFromItem,
  formProblems,
  initialForm,
  needsConfirmation,
  offeredTypes,
  submissionFingerprint,
  withFiles,
  withStudents,
  withType,
  withoutStudents,
  type FormProblem,
  type ItemFormState,
} from "@/lib/portfolio/composer";
import { parseGateError, type PortfolioGateError } from "@/lib/portfolio/consent-error";
import { createItem, fetchMyClasses, pfk, PORTFOLIO_GC_MS, quickCapture, submitItem, updateItem, type PortfolioItem, type PortfolioItemType } from "@/lib/portfolio/queries";
import { visibilityChoices } from "@/lib/portfolio/view";
import { fetchRoster, fetchSubjects, sk } from "@/lib/school/queries";
import { cn } from "@/lib/utils";
import { FileField } from "./file-field";
import { GateNotice } from "./gate-notice";
import { usePortfolioCapabilities } from "./use-portfolio";

export type ItemFormMode =
  /** A student adds an item about their own work. */
  | { kind: "create"; selfStudentId: string }
  /** The student edits a draft, or an item the teacher asked to change. */
  | { kind: "edit"; item: PortfolioItem; selfStudentId: string }
  /** A teacher captures an item for a student of the class (approved at once). */
  | { kind: "capture"; classId: string };

interface Props {
  schoolId: string;
  mode: ItemFormMode;
  onClose: () => void;
  /** Called with the saved item after the dialog's own refresh. */
  onDone?: (item: PortfolioItem) => void;
}

/**
 * The item form: a student's new item or edit, and a teacher's quick capture. Text and files stay where they are when
 * the API refuses (a 422 from the consent gate creates nothing), and the dialog explains what to change.
 */
export function ItemFormDialog(props: Props) {
  const t = useTranslations("school.portfolio.form");
  const caps = usePortfolioCapabilities(props.schoolId);
  return (
    <Dialog open onOpenChange={(open) => !open && props.onClose()}>
      <DialogContent className="max-h-[92dvh] overflow-y-auto sm:max-w-xl" data-testid="portfolio-form">
        {caps.isPending ? (
          <>
            <DialogTitle>{t("loading")}</DialogTitle>
            <LoadingRows rows={3} />
          </>
        ) : caps.isError ? (
          <>
            <DialogTitle>{t("unavailable")}</DialogTitle>
            <ErrorNotice message={errorMessage(caps.error, t("loadFailed"))} onRetry={() => void caps.refetch()} />
          </>
        ) : (
          <FormBody {...props} apiTypes={caps.data.itemTypes} maxVisibility={caps.data.maxVisibility} />
        )}
      </DialogContent>
    </Dialog>
  );
}

function FormBody({
  schoolId,
  mode,
  onClose,
  onDone,
  apiTypes,
  maxVisibility,
}: Props & { apiTypes: readonly string[]; maxVisibility: PortfolioItem["visibility"] }) {
  const t = useTranslations("school.portfolio.form");
  const tp = useTranslations("school.portfolio.problems");
  const tt = useTranslations("school.portfolio.types");
  const tv = useTranslations("school.portfolio.visibility");
  const queryClient = useQueryClient();
  const editing = mode.kind === "edit";
  const capturing = mode.kind === "capture";
  const [state, setState] = useState<ItemFormState>(() =>
    mode.kind === "edit" ? formFromItem(mode.item) : initialForm({ visibility: "GUARDIANS" }),
  );
  const [studentId, setStudentId] = useState("");
  const [gate, setGate] = useState<PortfolioGateError | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState<"draft" | "submit" | null>(null);
  const key = useRef<{ fingerprint: string; key: string } | null>(null);

  const subjects = useQuery({ queryKey: sk.subjects(schoolId), queryFn: () => fetchSubjects(schoolId), staleTime: 5 * 60_000 });
  const myClasses = useQuery({
    queryKey: pfk.myClasses,
    queryFn: fetchMyClasses,
    enabled: !capturing,
    staleTime: 60_000,
    gcTime: PORTFOLIO_GC_MS,
  });
  const classOptions = (myClasses.data ?? []).filter((c) => c.schoolId === schoolId && c.role === "STUDENT" && c.status === "ACTIVE");
  // A student of exactly one class: the API uses it by itself, and the roster of that class is the tag list.
  const soleClass = classOptions.length === 1 ? classOptions[0]!.id : "";
  const classId = capturing ? mode.classId : state.classId || soleClass;
  const roster = useQuery({
    queryKey: sk.classRoster(schoolId, classId || "none"),
    queryFn: () => fetchRoster(schoolId, classId),
    enabled: !!classId,
    staleTime: 60_000,
  });
  const selfId = mode.kind === "capture" ? null : mode.selfStudentId;
  const taggable = (roster.data ?? []).filter((s) => s.studentId !== selfId && s.studentId !== studentId);

  const typeChoices = offeredTypes(apiTypes);
  const audiences = visibilityChoices(maxVisibility);
  if (!audiences.includes(state.visibility)) audiences.push(state.visibility);

  const body: ItemFormState = { ...state, classId };

  const save = useMutation({
    mutationFn: async (intent: "draft" | "submit") => {
      if (mode.kind === "capture") {
        const request = buildQuickCaptureBody(body, studentId);
        const fingerprint = submissionFingerprint(request);
        if (key.current?.fingerprint !== fingerprint) key.current = { fingerprint, key: newIdempotencyKey() };
        return quickCapture(schoolId, mode.classId, request, key.current.key);
      }
      if (mode.kind === "edit") {
        const saved = await updateItem(schoolId, mode.item.id, buildUpdateBody(body));
        return intent === "submit" ? submitItem(schoolId, saved.id) : saved;
      }
      const request = buildCreateBody(body, intent === "submit" ? "SUBMIT" : "DRAFT");
      const fingerprint = submissionFingerprint(request);
      if (key.current?.fingerprint !== fingerprint) key.current = { fingerprint, key: newIdempotencyKey() };
      return createItem(schoolId, request, key.current.key);
    },
    onSuccess: async (saved, intent) => {
      await queryClient.invalidateQueries({ queryKey: pfk.all });
      toast.success(capturing ? t("captured") : intent === "submit" ? t("submitted") : t("saved"));
      onClose();
      onDone?.(saved);
    },
    onError: (caught) => {
      const refused = parseGateError(caught);
      setGate(refused);
      setError(refused ? null : errorMessage(caught, t("failed")));
    },
  });

  const confirmShown = needsConfirmation(state) && carriesFiles(state.itemType);
  // A submission (and a capture, which is approved at once) needs the text or the files; a draft needs only a title.
  const problemsFor = (intent: "draft" | "submit"): FormProblem[] => formProblems(state, intent === "submit" || capturing);

  function send(intent: "draft" | "submit") {
    setGate(null);
    setError(null);
    setAttempt(intent);
    if (problemsFor(intent).length > 0) return;
    if (capturing && !studentId) return;
    save.mutate(intent);
  }

  const busy = save.isPending;
  const shownProblems = attempt ? problemsFor(attempt) : [];
  const titleProblem = shownProblems.find((p) => p.startsWith("title"));
  const otherProblems = shownProblems.filter((p) => !p.startsWith("title"));

  return (
    <form
      noValidate
      className="flex flex-col gap-4"
      onSubmit={(event) => {
        event.preventDefault();
        send(capturing ? "submit" : "draft");
      }}
    >
      <DialogHeader>
        <DialogTitle>{capturing ? t("captureTitle") : editing ? t("editTitle") : t("newTitle")}</DialogTitle>
        <DialogDescription>{capturing ? t("captureHelp") : editing ? t("editHelp") : t("newHelp")}</DialogDescription>
      </DialogHeader>

      {capturing ? (
        roster.isError ? (
          <ErrorNotice message={errorMessage(roster.error, t("rosterFailed"))} onRetry={() => void roster.refetch()} />
        ) : (
          <SelectField
            label={t("student")}
            value={studentId}
            onChange={(id) => {
              setStudentId(id);
              setState(withStudents(state, state.taggedStudentIds.filter((tag) => tag !== id)));
            }}
            options={(roster.data ?? []).map((s) => ({ value: s.studentId, label: s.displayName }))}
            placeholder={t("studentPlaceholder")}
            error={attempt && !studentId ? tp("studentRequired") : null}
          />
        )
      ) : null}

      {!editing ? (
        <div className="flex flex-col gap-2">
          <span className="text-sm font-medium" id="portfolio-type-label">
            {t("type")}
          </span>
          <div role="group" aria-labelledby="portfolio-type-label" className="flex flex-wrap gap-2">
            {typeChoices.map((type: PortfolioItemType) => (
              <button
                key={type}
                type="button"
                aria-pressed={state.itemType === type}
                disabled={busy}
                onClick={() => {
                  setState(withType(state, type));
                  setGate(null);
                }}
                className={cn(
                  "min-h-10 rounded-full border px-4 text-sm font-medium",
                  state.itemType === type ? "border-primary bg-primary text-primary-foreground" : "hover:bg-accent",
                )}
                data-testid={`portfolio-type-${type}`}
              >
                {tt(type)}
              </button>
            ))}
          </div>
        </div>
      ) : (
        <p className="text-sm text-muted-foreground">{t("typeFixed", { type: tt(state.itemType) })}</p>
      )}

      <FormField label={t("title")} error={titleProblem ? tp(titleProblem) : null}>
        {(props) => (
          <Input
            {...props}
            value={state.title}
            maxLength={MAX_TITLE}
            onChange={(event) => setState({ ...state, title: event.target.value })}
            placeholder={t("titlePlaceholder")}
            data-testid="portfolio-title"
          />
        )}
      </FormField>

      {state.itemType === "TEXT" ? (
        <FormField label={t("text")} hint={t("count", { count: state.textContent.length, max: MAX_TEXT })}>
          {(props) => (
            <Textarea
              {...props}
              rows={7}
              value={state.textContent}
              maxLength={MAX_TEXT}
              onChange={(event) => setState({ ...state, textContent: event.target.value })}
              data-testid="portfolio-text"
            />
          )}
        </FormField>
      ) : (
        <FileField
          schoolId={schoolId}
          itemType={state.itemType}
          files={state.files}
          onChange={(files) => {
            setState(withFiles(state, files));
            setGate(null);
          }}
          disabled={busy}
        />
      )}

      <FormField label={t("description")} hint={t("count", { count: state.description.length, max: MAX_DESCRIPTION })}>
        {(props) => (
          <Textarea
            {...props}
            rows={2}
            value={state.description}
            maxLength={MAX_DESCRIPTION}
            onChange={(event) => setState({ ...state, description: event.target.value })}
            data-testid="portfolio-description"
          />
        )}
      </FormField>

      <FormField label={t("reflection")} hint={t("reflectionHint", { count: state.reflection.length, max: MAX_REFLECTION })}>
        {(props) => (
          <Textarea
            {...props}
            rows={2}
            value={state.reflection}
            maxLength={MAX_REFLECTION}
            onChange={(event) => setState({ ...state, reflection: event.target.value })}
            data-testid="portfolio-reflection"
          />
        )}
      </FormField>

      <div className="grid gap-4 sm:grid-cols-2">
        {!capturing && classOptions.length > 0 ? (
          <SelectField
            label={t("class")}
            value={state.classId || soleClass}
            onChange={(id) => setState({ ...withStudents(state, []), classId: id })}
            options={classOptions.map((c) => ({ value: c.id, label: c.name }))}
            noneLabel={t("noClass")}
            disabled={busy}
          />
        ) : null}
        <SelectField
          label={t("subject")}
          value={state.subjectId}
          onChange={(id) => setState({ ...state, subjectId: id })}
          options={(subjects.data ?? []).filter((s) => s.active || s.id === state.subjectId).map((s) => ({ value: s.id, label: s.name }))}
          noneLabel={t("noSubject")}
          disabled={busy}
        />
        <FormField label={t("workDate")}>
          {(props) => (
            <Input {...props} type="date" value={state.workDate} onChange={(event) => setState({ ...state, workDate: event.target.value })} data-testid="portfolio-date" />
          )}
        </FormField>
        <SelectField
          label={t("audience")}
          value={state.visibility}
          onChange={(value) => setState({ ...state, visibility: value as ItemFormState["visibility"] })}
          options={audiences.map((v) => ({ value: v, label: tv(v) }))}
          hint={t("audienceHelp", { cap: tv(maxVisibility) })}
          disabled={busy}
        />
      </div>

      {carriesFiles(state.itemType) ? (
        <>
          {capturing ? (
            <CheckboxField label={t("othersAppear")} hint={t("othersAppearHint")} checked={state.showsOtherChildren} onChange={(value) => setState({ ...state, showsOtherChildren: value })} disabled={busy} />
          ) : null}
          {classId ? (
            roster.isError ? (
              <ErrorNotice message={errorMessage(roster.error, t("rosterFailed"))} onRetry={() => void roster.refetch()} />
            ) : (
              <StudentTags
                students={taggable}
                value={state.taggedStudentIds}
                onChange={(ids) => setState(withStudents(state, ids))}
                max={MAX_TAGS}
                label={t("tags")}
                hint={t("tagsHelp")}
                disabled={busy}
              />
            )
          ) : null}
          {confirmShown ? (
            <label className="flex cursor-pointer items-start gap-3 rounded-xl border bg-muted/40 p-3 text-sm" htmlFor="portfolio-confirm">
              <input
                id="portfolio-confirm"
                type="checkbox"
                className="mt-0.5 size-5 shrink-0 accent-primary"
                checked={state.confirmNoUnconsented}
                disabled={busy}
                onChange={(event) => setState({ ...state, confirmNoUnconsented: event.target.checked })}
                data-testid="portfolio-confirm"
              />
              <span>
                <span className="block font-medium">{t("confirmLabel")}</span>
                <span className="text-muted-foreground">{t("confirmHelp")}</span>
              </span>
            </label>
          ) : null}
        </>
      ) : null}

      {gate ? (
        <GateNotice
          error={gate}
          audience="author"
          disabled={busy}
          onUntag={(ids) => {
            setState(withoutStudents(state, ids));
            setGate(null);
          }}
          confirmed={state.confirmNoUnconsented}
          onConfirmChange={(value) => setState({ ...state, confirmNoUnconsented: value })}
        />
      ) : null}

      {otherProblems.length > 0 ? (
        <ul role="alert" className="rounded-xl bg-destructive/10 p-3 text-sm font-medium text-destructive" data-testid="portfolio-problems">
          {otherProblems.map((problem) => (
            <li key={problem}>{tp(problem)}</li>
          ))}
        </ul>
      ) : null}
      {error ? (
        <p role="alert" className="rounded-xl bg-destructive/10 p-3 text-sm font-medium text-destructive" data-testid="portfolio-form-error">
          {error}
        </p>
      ) : null}

      <DialogFooter className="flex-wrap">
        <Button type="button" variant="outline" onClick={onClose} disabled={busy}>
          {t("cancel")}
        </Button>
        {capturing ? (
          <Button type="submit" disabled={busy} data-testid="portfolio-capture">
            {busy ? <Loader2Icon className="size-4 animate-spin" aria-hidden /> : null}
            {t("capture")}
          </Button>
        ) : (
          <>
            <Button type="button" variant="outline" disabled={busy} onClick={() => send("draft")} data-testid="portfolio-save">
              {busy && save.variables === "draft" ? <Loader2Icon className="size-4 animate-spin" aria-hidden /> : null}
              {editing ? t("save") : t("saveDraft")}
            </Button>
            <Button type="button" disabled={busy} onClick={() => send("submit")} data-testid="portfolio-submit">
              {busy && save.variables === "submit" ? <Loader2Icon className="size-4 animate-spin" aria-hidden /> : null}
              {editing && mode.item.status === "CHANGES_REQUESTED" ? t("sendAgain") : t("submit")}
            </Button>
          </>
        )}
      </DialogFooter>
    </form>
  );
}
