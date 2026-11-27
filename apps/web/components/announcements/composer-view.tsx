"use client";

import { useMutation, useQuery } from "@tanstack/react-query";
import { ArrowLeftIcon, Loader2Icon, SirenIcon } from "lucide-react";
import { useLocale, useTranslations } from "next-intl";
import { useEffect, useRef, useState } from "react";
import { toast } from "sonner";
import { languageLabel } from "@school/i18n";
import { useFormatInstantIn } from "@/components/attendance/status";
import { EmptyNotice, ErrorNotice, LoadingRows } from "@/components/console/states";
import { PageHeader } from "@/components/school/page-header";
import { useSchool } from "@/components/school/school-context";
import { SelectField } from "@/components/school/fields";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Switch } from "@/components/ui/switch";
import { Textarea } from "@/components/ui/textarea";
import { Link, useRouter } from "@/i18n/navigation";
import {
  MAX_BODY,
  MAX_TITLE,
  allowedTypes,
  buildCreateBody,
  buildDraftUpdateBody,
  buildPublishedEditBody,
  composerFromAnnouncement,
  composerLanguages,
  composerProblems,
  draftProblems,
  hasContent,
  initialComposer,
  normalizeForCaps,
  scheduleInstant,
  smsOfferedFor,
  submissionFingerprint,
  textChanged,
  translationLanguages,
  withLanguage,
  withType,
  type CapsLike,
  type ComposerProblem,
  type ComposerState,
  type Timing,
} from "@/lib/announcements/composer";
import { ank, createAnnouncement, fetchAnnouncement, updateAnnouncement, type Announcement } from "@/lib/announcements/queries";
import { scheduleBounds, checkSchedule } from "@/lib/announcements/schedule";
import { NOTE_MAX, isUrgentType, mayRequireAcknowledgement, type AnnouncementType } from "@/lib/announcements/view";
import { newIdempotencyKey } from "@/lib/console/idempotency";
import { errorMessage } from "@/lib/console/errors";
import { cn } from "@/lib/utils";
import { AudienceBuilder } from "./audience-builder";
import { PublishDialog, type PublishMode } from "./publish-dialog";
import { useAnnouncementCapabilities, useRefreshAnnouncements } from "./use-announcements";

/**
 * `/s/{slug}/announcements/new` and `.../{id}/edit`: loads what the caller may write (`capabilities`) and, when editing,
 * the announcement, then shows the composer.
 */
export function ComposerView({ announcementId }: { announcementId?: string }) {
  const t = useTranslations("school.announcements.composer");
  const { school } = useSchool();
  const caps = useAnnouncementCapabilities(school.id);
  const existing = useQuery({
    queryKey: ank.one(school.id, announcementId ?? "new"),
    queryFn: () => fetchAnnouncement(school.id, announcementId!),
    enabled: !!announcementId,
    retry: false,
    staleTime: 0,
  });
  const base = `/s/${encodeURIComponent(school.slug)}/announcements`;
  const title = announcementId ? (existing.data?.status === "PUBLISHED" ? t("editPublishedTitle") : t("editTitle")) : t("newTitle");

  let body: React.ReactNode;
  if (caps.isPending || (announcementId && existing.isPending)) {
    body = <LoadingRows rows={4} />;
  } else if (caps.isError) {
    body = <ErrorNotice message={errorMessage(caps.error, t("loadFailed"))} onRetry={() => void caps.refetch()} />;
  } else if (existing.isError) {
    body = <EmptyNotice>{errorMessage(existing.error, t("loadFailed"))}</EmptyNotice>;
  } else if ((!caps.data.canCompose && !announcementId) || (existing.data && !existing.data.can.edit)) {
    body = <EmptyNotice>{t("noAccess")}</EmptyNotice>;
  } else {
    body = (
      <ComposerForm
        key={existing.data?.id ?? "new"}
        schoolId={school.id}
        base={base}
        caps={caps.data}
        existing={existing.data}
        languages={school.country.languageCodes}
        defaultLanguage={school.country.defaultLanguageCode}
      />
    );
  }

  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-6">
      <Link href={base} className="inline-flex min-h-11 items-center gap-2 self-start text-sm font-semibold text-primary hover:underline">
        <ArrowLeftIcon className="size-4 rtl:rotate-180" aria-hidden />
        {t("cancel")}
      </Link>
      <PageHeader title={title} description={t("subtitle")} />
      {body}
    </div>
  );
}

function ComposerForm({
  schoolId,
  base,
  caps,
  existing,
  languages,
  defaultLanguage,
}: {
  schoolId: string;
  base: string;
  caps: CapsLike;
  existing?: Announcement;
  /** The languages of the school's country (D42): what an announcement can be written and translated in. */
  languages: readonly string[];
  defaultLanguage: string;
}) {
  const locale = useLocale();
  const t = useTranslations("school.announcements.composer");
  const tp = useTranslations("school.announcements.composer.problems");
  const tt = useTranslations("school.announcements");
  const router = useRouter();
  const refresh = useRefreshAnnouncements();
  const formatIn = useFormatInstantIn();
  const zone = caps.timeZone ?? "UTC";
  const mode: "create" | "editDraft" | "editPublished" = !existing ? "create" : existing.status === "PUBLISHED" ? "editPublished" : "editDraft";
  const [state, setState] = useState<ComposerState>(() => {
    const now = new Date();
    return normalizeForCaps(existing ? composerFromAnnouncement(existing, caps, zone, now, defaultLanguage) : initialComposer(caps, zone, now, defaultLanguage), caps);
  });
  const [material, setMaterial] = useState<boolean | null>(null);
  const [changeNote, setChangeNote] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [showProblems, setShowProblems] = useState(false);
  const [publishing, setPublishing] = useState<{ id: string; mode: PublishMode } | null>(null);
  const [finished, setFinished] = useState(false);
  // The draft made for this form: a second press on "publish" updates it instead of making another one.
  const savedId = useRef<string | null>(existing?.id ?? null);
  const key = useRef<{ fingerprint: string; key: string } | null>(null);
  const bounds = scheduleBounds(zone, new Date());

  const types = allowedTypes(caps);
  const set = (patch: Partial<ComposerState>) => setState((s) => ({ ...s, ...patch }));

  const editingPublished = mode === "editPublished";
  const blocking: ComposerProblem[] = editingPublished || mode === "editDraft" || state.timing === "draft" ? draftProblems(state) : composerProblems(state, zone, new Date());
  const materialMissing = editingPublished && material === null;
  const unchanged = editingPublished && existing ? !textChanged(state, existing) : false;

  // Text that was not saved is not lost by a stray reload or a closed tab without a warning.
  useEffect(() => {
    if (finished || !hasContent(state)) return;
    const warn = (event: BeforeUnloadEvent) => {
      event.preventDefault();
    };
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [state, finished]);

  const detailHref = (id: string) => `${base}/${id}`;
  const finish = (id: string) => {
    setFinished(true);
    router.push(detailHref(id));
  };

  const save = useMutation({
    mutationFn: async (intent: { kind: "draft" } | { kind: "publish" } | { kind: "schedule"; instant: string } | { kind: "edit" }) => {
      if (intent.kind === "edit" && existing) {
        const body = editingPublished ? buildPublishedEditBody(state, material, changeNote) : buildDraftUpdateBody(state);
        if (!body) throw new Error("material");
        return { announcement: await updateAnnouncement(schoolId, existing.id, body), intent };
      }
      // Saved first as a draft: the text is safe whatever happens when publishing, and the API counts the recipients.
      if (savedId.current) {
        return { announcement: await updateAnnouncement(schoolId, savedId.current, buildDraftUpdateBody(state)), intent };
      }
      const body = buildCreateBody(state);
      const fingerprint = submissionFingerprint(body);
      if (key.current?.fingerprint !== fingerprint) key.current = { fingerprint, key: newIdempotencyKey() };
      const created = await createAnnouncement(schoolId, body, key.current.key);
      savedId.current = created.id;
      return { announcement: created, intent };
    },
    onSuccess: async ({ announcement, intent }) => {
      await refresh();
      if (intent.kind === "publish") setPublishing({ id: announcement.id, mode: { kind: "publish" } });
      else if (intent.kind === "schedule") setPublishing({ id: announcement.id, mode: { kind: "schedule", instant: intent.instant, whenLabel: formatIn(intent.instant, zone) } });
      else {
        toast.success(intent.kind === "draft" ? t("draftSaved") : t("saved"));
        finish(announcement.id);
      }
    },
    onError: (caught) => setError(caught instanceof Error && caught.message === "material" ? null : errorMessage(caught, t("failed"))),
  });

  function submit(event?: React.FormEvent) {
    event?.preventDefault();
    setError(null);
    setShowProblems(true);
    if (blocking.length > 0 || materialMissing) return;
    if (mode !== "create") {
      if (editingPublished && unchanged) return;
      save.mutate({ kind: "edit" });
      return;
    }
    if (state.timing === "draft") save.mutate({ kind: "draft" });
    else if (state.timing === "now") save.mutate({ kind: "publish" });
    else {
      const instant = scheduleInstant(state, zone, new Date());
      if (instant) save.mutate({ kind: "schedule", instant });
    }
  }

  const schedulePick = state.timing === "schedule" ? checkSchedule(state.scheduledLocal, zone, new Date()) : null;
  const smsOffered = smsOfferedFor(caps, state.type);
  const pending = save.isPending;

  return (
    <form noValidate onSubmit={submit} className="flex flex-col gap-6" data-testid="composer-form" data-mode={mode}>
      {mode !== "editPublished" ? (
        <fieldset className="flex flex-col gap-2" disabled={pending}>
          <legend className="mb-1 text-sm font-semibold">{t("type")}</legend>
          <div role="radiogroup" aria-label={t("type")} className="flex flex-wrap gap-2">
            {types.map((type) => (
              <TypeChoice key={type} type={type} selected={state.type === type} onSelect={() => setState((s) => withType(s, type, caps))} />
            ))}
          </div>
          {isUrgentType(state.type) ? (
            <p role="note" className="flex items-start gap-2 rounded-xl border-2 border-destructive bg-destructive/10 p-3 text-sm font-semibold" data-testid="urgent-warning">
              <SirenIcon className="mt-0.5 size-5 shrink-0 text-destructive" aria-hidden />
              {t("typeWarning")}
            </p>
          ) : null}
        </fieldset>
      ) : null}

      <section className="flex flex-col gap-4" aria-label={t("title")}>
        <div className="flex flex-col gap-1.5">
          <label htmlFor="ann-title" className="text-sm font-semibold">
            {t("title")}
          </label>
          <Input
            id="ann-title"
            value={state.title}
            maxLength={MAX_TITLE}
            placeholder={t("titlePlaceholder")}
            onChange={(event) => set({ title: event.target.value })}
            disabled={pending}
            dir="auto"
            data-testid="ann-title"
          />
          <p className="text-xs text-muted-foreground">{t("counter", { count: state.title.length, max: MAX_TITLE })}</p>
        </div>
        <div className="flex flex-col gap-1.5">
          <label htmlFor="ann-body" className="text-sm font-semibold">
            {t("body")}
          </label>
          <Textarea
            id="ann-body"
            rows={8}
            value={state.body}
            maxLength={MAX_BODY}
            placeholder={t("bodyPlaceholder")}
            onChange={(event) => set({ body: event.target.value })}
            disabled={pending}
            dir="auto"
            data-testid="ann-body"
          />
          <p className="flex flex-wrap justify-between gap-2 text-xs text-muted-foreground">
            <span>{t("plainText")}</span>
            <span>{t("counter", { count: state.body.length, max: MAX_BODY })}</span>
          </p>
        </div>

        <SelectField
          label={t("language")}
          value={state.language}
          onChange={(value) => setState((s) => withLanguage(s, value))}
          options={composerLanguages(languages, state.language).map((l) => ({ value: l, label: languageLabel(l, locale) }))}
          className="w-56"
          disabled={pending}
        />

        <Translations state={state} setState={setState} languages={languages} disabled={pending} />
      </section>

      {mode === "editPublished" ? (
        <>
          <p className="rounded-xl bg-muted p-3 text-sm">{tt("edit.locked")}</p>
          <MaterialChoice material={material} onChange={setMaterial} changeNote={changeNote} onNote={setChangeNote} showError={showProblems && materialMissing} />
        </>
      ) : (
        <>
          <section className="flex flex-col gap-3" aria-label={t("audience.title")}>
            <h2 className="text-lg font-bold">{t("audience.title")}</h2>
            <AudienceBuilder schoolId={schoolId} caps={caps} state={state} onChange={setState} disabled={pending} />
          </section>

          <section className="flex flex-col gap-3" aria-label={t("channels.title")}>
            <h2 className="text-lg font-bold">{t("channels.title")}</h2>
            <label className="flex min-h-11 items-center gap-3 text-sm font-medium">
              <input type="checkbox" className="size-5 accent-primary" checked disabled aria-describedby="in-app-note" />
              <span id="in-app-note">{t("channels.inApp")}</span>
            </label>
            <label className="flex min-h-11 items-center gap-3 text-sm font-medium">
              <input type="checkbox" className="size-5 accent-primary" checked={state.push} onChange={(e) => set({ push: e.target.checked })} disabled={pending} data-testid="channel-push" />
              {t("channels.push")}
            </label>
            {caps.emailAvailable ? (
              <label className="flex min-h-11 items-start gap-3 text-sm font-medium">
                <input type="checkbox" className="mt-1 size-5 accent-primary" checked={state.email} onChange={(e) => set({ email: e.target.checked })} disabled={pending} data-testid="channel-email" />
                <span>
                  {t("channels.email")}
                  <span className="block text-xs font-normal text-muted-foreground">{t("channels.emailHelp")}</span>
                </span>
              </label>
            ) : null}
            {caps.smsAvailable ? (
              smsOffered ? (
                <label className="flex min-h-11 items-start gap-3 text-sm font-medium">
                  <input type="checkbox" className="mt-1 size-5 accent-primary" checked={state.sms} onChange={(e) => set({ sms: e.target.checked })} disabled={pending} data-testid="channel-sms" />
                  <span>
                    {t("channels.sms")}
                    <span className="block text-xs font-normal text-muted-foreground" data-testid="sms-help">
                      {t("channels.smsHelp", {
                        types: caps.smsTypes.map((x) => tt(`types.${x}`)).join(", "),
                        perAnnouncement: caps.smsMaxPerAnnouncement ?? "—",
                        perDay: caps.smsMaxPerDay ?? "—",
                      })}
                    </span>
                  </span>
                </label>
              ) : (
                <p className="text-xs text-muted-foreground">{t("channels.smsNotForType")}</p>
              )
            ) : null}
            {caps.emailAvailable || caps.smsAvailable ? <p className="text-xs text-muted-foreground">{t("channels.optOut")}</p> : null}

            <div className="flex items-center justify-between gap-3 rounded-xl border p-3">
              <label htmlFor="ann-ack" className="min-w-0 text-sm font-medium">
                {t("acknowledge.label")}
                <span className="block text-xs font-normal text-muted-foreground">{mayRequireAcknowledgement(state.type) ? t("acknowledge.help") : t("acknowledge.notNormal")}</span>
              </label>
              <Switch
                id="ann-ack"
                checked={state.requiresAcknowledgement}
                disabled={pending || !mayRequireAcknowledgement(state.type)}
                onCheckedChange={(value) => set({ requiresAcknowledgement: value })}
                data-testid="ann-ack"
              />
            </div>
          </section>

          {mode === "create" ? (
            <fieldset className="flex flex-col gap-2" disabled={pending}>
              <legend className="mb-1 text-lg font-bold">{t("when.title")}</legend>
              <div className="flex flex-wrap gap-2">
                {(["now", "schedule", "draft"] as Timing[]).map((timing) => (
                  <label
                    key={timing}
                    className={cn("flex min-h-11 cursor-pointer items-center gap-2 rounded-full border px-4 text-sm font-semibold", state.timing === timing ? "border-primary bg-primary/10" : "hover:bg-accent")}
                  >
                    <input type="radio" name="ann-timing" className="accent-primary" checked={state.timing === timing} onChange={() => set({ timing })} data-testid={`timing-${timing}`} />
                    {t(`when.${timing}`)}
                  </label>
                ))}
              </div>
              {state.timing === "schedule" ? (
                <div className="flex flex-col gap-2 rounded-xl bg-muted/50 p-3">
                  <label htmlFor="ann-schedule" className="text-sm font-medium">
                    {t("when.scheduleAt", { zone })}
                  </label>
                  <input
                    id="ann-schedule"
                    type="datetime-local"
                    className="h-11 w-full max-w-xs rounded-md border border-input bg-background px-3"
                    value={state.scheduledLocal}
                    min={bounds?.min}
                    max={bounds?.max}
                    onChange={(event) => set({ scheduledLocal: event.target.value })}
                    data-testid="ann-schedule"
                  />
                  {schedulePick?.ok ? <p className="text-sm" data-testid="schedule-preview">{t("when.willSend", { when: formatIn(schedulePick.instant, zone) })}</p> : null}
                </div>
              ) : null}
              {state.timing === "draft" ? <p className="text-sm text-muted-foreground">{t("when.draftHelp")}</p> : null}
            </fieldset>
          ) : null}
        </>
      )}

      {showProblems && blocking.length > 0 ? (
        <ul role="alert" className="rounded-xl bg-destructive/10 p-3 text-sm font-medium text-destructive" data-testid="composer-problems">
          {blocking.map((problem) => (
            <li key={problem}>{tp(problem)}</li>
          ))}
        </ul>
      ) : null}
      {error ? (
        <p role="alert" className="rounded-xl bg-destructive/10 p-3 text-sm font-medium text-destructive" data-testid="composer-error">
          {error}
        </p>
      ) : null}

      <div className="flex flex-wrap items-center gap-3">
        <Button type="submit" disabled={pending || (editingPublished && unchanged)} data-testid="composer-submit">
          {pending ? <Loader2Icon className="size-4 animate-spin" aria-hidden /> : null}
          {mode !== "create" ? t("submit.save") : t(`submit.${state.timing === "now" ? "now" : state.timing}`)}
        </Button>
        {editingPublished && unchanged ? <span className="text-sm text-muted-foreground">{tt("edit.nothingChanged")}</span> : null}
      </div>

      {publishing ? (
        <PublishDialog
          schoolId={schoolId}
          announcementId={publishing.id}
          type={state.type}
          mode={publishing.mode}
          onClose={() => setPublishing(null)}
          onDone={async () => {
            await refresh();
            finish(publishing.id);
          }}
        />
      ) : null}
    </form>
  );
}

function TypeChoice({ type, selected, onSelect }: { type: AnnouncementType; selected: boolean; onSelect: () => void }) {
  const t = useTranslations("school.announcements");
  return (
    <button
      type="button"
      role="radio"
      aria-checked={selected}
      onClick={onSelect}
      data-testid={`type-choice-${type}`}
      className={cn(
        "flex min-h-14 min-w-36 flex-col items-start rounded-xl border px-4 py-2 text-start",
        selected ? (isUrgentType(type) ? "border-destructive bg-destructive/10" : "border-primary bg-primary/10") : "hover:bg-accent",
      )}
    >
      <span className="text-sm font-bold">{t(`types.${type}`)}</span>
      <span className="text-xs text-muted-foreground">{t(`typeHelp.${type}`)}</span>
    </button>
  );
}

function Translations({ state, setState, languages, disabled }: { state: ComposerState; setState: React.Dispatch<React.SetStateAction<ComposerState>>; languages: readonly string[]; disabled: boolean }) {
  const t = useTranslations("school.announcements.composer");
  const locale = useLocale();
  const available = translationLanguages(state, languages);
  const entries = Object.entries(state.translations);
  if (entries.length === 0 && available.length === 0) return null;
  return (
    <section className="flex flex-col gap-3" aria-label={t("translations")}>
      <div>
        <h2 className="text-sm font-semibold">{t("translations")}</h2>
        <p className="text-xs text-muted-foreground">{t("translationsHelp")}</p>
      </div>
      {entries.map(([code, value]) => {
        const name = languageLabel(code, locale);
        return (
          <div key={code} className="flex flex-col gap-2 rounded-xl border p-3" data-testid={`translation-${code}`}>
            <Input
              aria-label={t("translationTitle", { language: name })}
              placeholder={t("translationTitle", { language: name })}
              value={value.title}
              maxLength={MAX_TITLE}
              dir="auto"
              disabled={disabled}
              onChange={(event) => setState((s) => ({ ...s, translations: { ...s.translations, [code]: { ...value, title: event.target.value } } }))}
              data-testid={`translation-title-${code}`}
            />
            <Textarea
              aria-label={t("translationBody", { language: name })}
              placeholder={t("translationBody", { language: name })}
              rows={4}
              value={value.body}
              maxLength={MAX_BODY}
              dir="auto"
              disabled={disabled}
              onChange={(event) => setState((s) => ({ ...s, translations: { ...s.translations, [code]: { ...value, body: event.target.value } } }))}
              data-testid={`translation-body-${code}`}
            />
            <div>
              <Button
                type="button"
                variant="ghost"
                size="sm"
                disabled={disabled}
                onClick={() =>
                  setState((s) => {
                    const next = { ...s.translations };
                    delete next[code];
                    return { ...s, translations: next };
                  })
                }
              >
                {t("removeTranslation", { language: name })}
              </Button>
            </div>
          </div>
        );
      })}
      <div className="flex flex-wrap gap-2">
        {available.map((code) => (
          <Button
            key={code}
            type="button"
            variant="outline"
            size="sm"
            disabled={disabled}
            onClick={() => setState((s) => ({ ...s, translations: { ...s.translations, [code]: { title: "", body: "" } } }))}
            data-testid={`add-translation-${code}`}
          >
            {t("addTranslation", { language: languageLabel(code, locale) })}
          </Button>
        ))}
      </div>
    </section>
  );
}

/** After publishing the author says whether people hear about the change: nothing is assumed. */
function MaterialChoice({
  material,
  onChange,
  changeNote,
  onNote,
  showError,
}: {
  material: boolean | null;
  onChange: (value: boolean) => void;
  changeNote: string;
  onNote: (value: string) => void;
  showError: boolean;
}) {
  const t = useTranslations("school.announcements.edit");
  return (
    <fieldset className="flex flex-col gap-3 rounded-2xl border p-4" data-testid="material-choice">
      <legend className="px-1 text-base font-bold">{t("materialTitle")}</legend>
      {([true, false] as const).map((value) => (
        <label
          key={String(value)}
          className={cn("flex min-h-14 cursor-pointer items-start gap-3 rounded-xl border p-3", material === value ? "border-primary bg-primary/10" : "hover:bg-accent")}
        >
          <input type="radio" name="material" className="mt-1 size-5 accent-primary" checked={material === value} onChange={() => onChange(value)} data-testid={value ? "material-yes" : "material-no"} />
          <span className="text-sm">
            <span className="block font-bold">{value ? t("materialYes") : t("materialNo")}</span>
            <span className="text-muted-foreground">{value ? t("materialYesHelp") : t("materialNoHelp")}</span>
          </span>
        </label>
      ))}
      <div className="flex flex-col gap-1.5">
        <label htmlFor="ann-note" className="text-sm font-medium">
          {t("changeNote")}
        </label>
        <Input id="ann-note" value={changeNote} maxLength={NOTE_MAX} onChange={(event) => onNote(event.target.value)} dir="auto" data-testid="change-note" />
        <p className="text-xs text-muted-foreground">{t("changeNoteHelp")}</p>
      </div>
      {showError ? (
        <p role="alert" className="text-sm font-medium text-destructive" data-testid="material-error">
          {t("materialRequired")}
        </p>
      ) : null}
    </fieldset>
  );
}
