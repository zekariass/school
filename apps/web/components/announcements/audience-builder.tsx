"use client";

import { useQuery } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { ErrorNotice, LoadingRows } from "@/components/console/states";
import {
  allowedKinds,
  allowedRoles,
  toggleId,
  toggleRole,
  withKind,
  type AudienceKind,
  type CapsLike,
  type ComposerState,
  type RecipientRole,
} from "@/lib/announcements/composer";
import { errorMessage } from "@/lib/console/errors";
import { fetchCampuses, fetchClasses, fetchGrades, sk } from "@/lib/school/queries";
import { cn } from "@/lib/utils";
import { PeoplePicker } from "./people-picker";

interface Choice {
  id: string;
  label: string;
}

/**
 * Who receives the announcement: a kind of audience (the ones the API's capabilities allow), the targets of that kind,
 * and the roles to reach. The text under it explains the API's rule in words; the form never works out who the people
 * are (the exact count comes from the API before publishing).
 */
export function AudienceBuilder({
  schoolId,
  caps,
  state,
  onChange,
  disabled,
}: {
  schoolId: string;
  caps: CapsLike;
  state: ComposerState;
  onChange: (next: ComposerState) => void;
  disabled?: boolean;
}) {
  const t = useTranslations("school.announcements.composer.audience");
  const kinds = allowedKinds(caps);
  const roles = allowedRoles(caps);

  if (state.audienceLocked) {
    return (
      <p className="rounded-xl bg-muted p-3 text-sm" data-testid="audience-locked">
        {t("locked")}
      </p>
    );
  }

  return (
    <div className="flex flex-col gap-4" data-testid="audience-builder">
      <div role="radiogroup" aria-label={t("kind")} className="flex flex-wrap gap-2">
        {kinds.map((kind) => (
          <button
            key={kind}
            type="button"
            role="radio"
            aria-checked={state.kind === kind}
            disabled={disabled}
            onClick={() => onChange(withKind(state, kind))}
            data-testid={`kind-${kind}`}
            className={cn("min-h-11 rounded-full border px-4 text-sm font-semibold", state.kind === kind ? "border-primary bg-primary text-primary-foreground" : "hover:bg-accent")}
          >
            {t(`kinds.${kind}`)}
          </button>
        ))}
      </div>

      {state.kind === "CAMPUS" ? <CampusChoices schoolId={schoolId} state={state} onChange={onChange} /> : null}
      {state.kind === "GRADE_LEVEL" ? <GradeChoices schoolId={schoolId} state={state} onChange={onChange} /> : null}
      {state.kind === "CLASS" ? <ClassChoices schoolId={schoolId} caps={caps} state={state} onChange={onChange} /> : null}
      {state.kind === "USER" ? (
        <PeoplePicker
          schoolId={schoolId}
          manager={caps.schoolWide}
          classes={caps.classes}
          studentsEnabled={caps.studentsEnabled}
          people={state.people}
          onChange={(people) => onChange({ ...state, people })}
        />
      ) : (
        <fieldset className="flex flex-col gap-2" disabled={disabled}>
          <legend className="mb-1 text-sm font-medium">{t("roles")}</legend>
          <div className="flex flex-wrap gap-2">
            {roles.map((role) => (
              <RoleChip key={role} role={role} checked={state.roles.includes(role)} onToggle={() => onChange({ ...state, roles: toggleRole(state.roles, role) })} />
            ))}
          </div>
        </fieldset>
      )}

      <Explanation state={state} />
    </div>
  );
}

function RoleChip({ role, checked, onToggle }: { role: RecipientRole; checked: boolean; onToggle: () => void }) {
  const t = useTranslations("school.announcements.composer.audience.roleLabels");
  return (
    <label className={cn("flex min-h-11 cursor-pointer items-center gap-2 rounded-full border px-4 text-sm font-semibold", checked ? "border-primary bg-primary/10" : "hover:bg-accent")}>
      <input type="checkbox" className="size-4 accent-primary" checked={checked} onChange={onToggle} data-testid={`role-${role}`} />
      {t(role)}
    </label>
  );
}

function ChoiceList({ label, choices, selected, onToggle, empty }: { label: string; choices: readonly Choice[]; selected: readonly string[]; onToggle: (id: string) => void; empty: string }) {
  return (
    <fieldset className="flex flex-col gap-1">
      <legend className="mb-1 text-sm font-medium">{label}</legend>
      {choices.length === 0 ? (
        <p className="text-sm text-muted-foreground">{empty}</p>
      ) : (
        <div className="grid gap-1 sm:grid-cols-2">
          {choices.map((c) => (
            <label key={c.id} className={cn("flex min-h-11 cursor-pointer items-center gap-3 rounded-xl border px-3 text-sm font-medium", selected.includes(c.id) ? "border-primary bg-primary/10" : "hover:bg-accent")}>
              <input type="checkbox" className="size-5 shrink-0 accent-primary" checked={selected.includes(c.id)} onChange={() => onToggle(c.id)} data-testid={`choice-${c.id}`} />
              <span className="min-w-0 truncate">{c.label}</span>
            </label>
          ))}
        </div>
      )}
    </fieldset>
  );
}

function Loadable({ query, children }: { query: { isPending: boolean; isError: boolean; error: unknown; refetch: () => unknown }; children: React.ReactNode }) {
  const t = useTranslations("school.announcements.composer.audience");
  if (query.isPending) return <LoadingRows rows={2} />;
  if (query.isError) return <ErrorNotice message={errorMessage(query.error, t("loadFailed"))} onRetry={() => void query.refetch()} />;
  return <>{children}</>;
}

function CampusChoices({ schoolId, state, onChange }: { schoolId: string; state: ComposerState; onChange: (s: ComposerState) => void }) {
  const t = useTranslations("school.announcements.composer.audience");
  const campuses = useQuery({ queryKey: sk.campuses(schoolId), queryFn: () => fetchCampuses(schoolId), staleTime: 60_000, retry: false });
  const choices = (campuses.data?.items ?? []).filter((c) => c.status === "ACTIVE").map((c) => ({ id: c.id, label: c.name }));
  return (
    <Loadable query={campuses}>
      <ChoiceList label={t("campuses")} choices={choices} selected={state.campusIds} onToggle={(id) => onChange({ ...state, campusIds: toggleId(state.campusIds, id) })} empty={t("none")} />
    </Loadable>
  );
}

function GradeChoices({ schoolId, state, onChange }: { schoolId: string; state: ComposerState; onChange: (s: ComposerState) => void }) {
  const t = useTranslations("school.announcements.composer.audience");
  const grades = useQuery({ queryKey: sk.grades(schoolId), queryFn: () => fetchGrades(schoolId), staleTime: 60_000, retry: false });
  const choices = (grades.data ?? []).filter((g) => g.active).map((g) => ({ id: g.id, label: g.name }));
  return (
    <Loadable query={grades}>
      <ChoiceList label={t("grades")} choices={choices} selected={state.gradeLevelIds} onToggle={(id) => onChange({ ...state, gradeLevelIds: toggleId(state.gradeLevelIds, id) })} empty={t("none")} />
    </Loadable>
  );
}

function ClassChoices({ schoolId, caps, state, onChange }: { schoolId: string; caps: CapsLike; state: ComposerState; onChange: (s: ComposerState) => void }) {
  const t = useTranslations("school.announcements.composer.audience");
  // School managers pick from every active class; everybody else only from the classes the API says they may write to.
  const all = useQuery({
    queryKey: sk.classList(schoolId, { announcementPicker: true }),
    queryFn: () => fetchClasses(schoolId, { status: "ACTIVE" }, undefined, 100),
    enabled: caps.schoolWide,
    staleTime: 60_000,
    retry: false,
  });
  const choices: Choice[] = caps.schoolWide ? (all.data?.items ?? []).map((c) => ({ id: c.id, label: c.name })) : caps.classes.map((c) => ({ id: c.id, label: c.name }));
  const list = <ChoiceList label={t("classes")} choices={choices} selected={state.classIds} onToggle={(id) => onChange({ ...state, classIds: toggleId(state.classIds, id) })} empty={t("none")} />;
  return (
    <>
      {!caps.schoolWide ? <p className="text-sm text-muted-foreground">{t("ownClasses")}</p> : null}
      {caps.schoolWide ? <Loadable query={all}>{list}</Loadable> : list}
    </>
  );
}

/** The API's audience rule in words: who is reached for what is chosen, without resolving anybody. */
function Explanation({ state }: { state: ComposerState }) {
  const t = useTranslations("school.announcements.composer.audience");
  const nobody = state.kind === "USER" ? state.people.length === 0 : state.roles.length === 0;
  const where = state.kind === "USER" ? null : t(`explainWhere.${state.kind as Exclude<AudienceKind, "USER">}`);
  return (
    <section aria-label={t("explainTitle")} className="flex flex-col gap-2 rounded-xl bg-muted/60 p-3 text-sm" data-testid="audience-explain">
      <h3 className="font-semibold">{t("explainTitle")}</h3>
      {nobody ? (
        <p>{t("explainNobody")}</p>
      ) : state.kind === "USER" ? (
        <p>{t("explainUsers")}</p>
      ) : (
        <ul className="flex list-disc flex-col gap-1 ps-5">
          {state.roles.map((role) => (
            <li key={role}>{t(`explainRole.${role}`, { where: where ?? "" })}</li>
          ))}
        </ul>
      )}
      <p className="text-muted-foreground">{t("explainNote")}</p>
    </section>
  );
}
