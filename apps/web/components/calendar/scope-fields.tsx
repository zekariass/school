"use client";

import { useQuery } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { ErrorNotice, LoadingRows } from "@/components/console/states";
import { SelectField } from "@/components/school/fields";
import { errorMessage } from "@/lib/console/errors";
import { classChoice, typesForScope, type EventCapabilities, type EventScope } from "@/lib/calendar/access";
import type { EventFormState } from "@/lib/calendar/form";
import { CALENDAR_GC_MS } from "@/lib/calendar/queries";
import { fetchCampuses, fetchClasses, fetchGrades, fetchRoster, sk } from "@/lib/school/queries";

type Problem = (field: "scope" | "campus" | "grade" | "class" | "student") => string | null;

/** The campus, grade or class a school-wide or class event is for, or the child a parent meeting is about. */
export function ScopeTargets({
  schoolId,
  caps,
  state,
  onChange,
  problem,
}: {
  schoolId: string;
  caps: EventCapabilities;
  state: EventFormState;
  onChange: (patch: Partial<EventFormState>) => void;
  problem: Problem;
}) {
  if (state.scope === "CAMPUS") return <CampusPick schoolId={schoolId} value={state.campusId} onChange={(campusId) => onChange({ campusId })} error={problem("campus")} />;
  if (state.scope === "GRADE_LEVEL") return <GradePick schoolId={schoolId} value={state.gradeLevelId} onChange={(gradeLevelId) => onChange({ gradeLevelId })} error={problem("grade")} />;
  if (state.scope === "CLASS") return <ClassPick schoolId={schoolId} caps={caps} value={state.classId} onChange={(classId) => onChange({ classId })} error={problem("class")} />;
  if (state.scope === "PARENT_MEETING") {
    return <MeetingPick schoolId={schoolId} studentId={state.studentId} onChange={(studentId) => onChange({ studentId })} error={problem("student")} />;
  }
  return null;
}

function Loadable({ query, children }: { query: { isPending: boolean; isError: boolean; error: unknown; refetch: () => unknown }; children: React.ReactNode }) {
  const t = useTranslations("school.calendar.fields");
  if (query.isPending) return <LoadingRows rows={1} />;
  if (query.isError) return <ErrorNotice message={errorMessage(query.error, t("loadFailed"))} onRetry={() => void query.refetch()} />;
  return <>{children}</>;
}

function CampusPick({ schoolId, value, onChange, error }: { schoolId: string; value: string; onChange: (id: string) => void; error: string | null }) {
  const t = useTranslations("school.calendar.fields");
  const campuses = useQuery({ queryKey: sk.campuses(schoolId), queryFn: () => fetchCampuses(schoolId), staleTime: 60_000, gcTime: CALENDAR_GC_MS, retry: false });
  return (
    <Loadable query={campuses}>
      <SelectField
        label={t("campus")}
        value={value}
        onChange={onChange}
        placeholder={t("choose")}
        options={(campuses.data?.items ?? []).filter((c) => c.status === "ACTIVE").map((c) => ({ value: c.id, label: c.name }))}
        error={error}
      />
    </Loadable>
  );
}

function GradePick({ schoolId, value, onChange, error }: { schoolId: string; value: string; onChange: (id: string) => void; error: string | null }) {
  const t = useTranslations("school.calendar.fields");
  const grades = useQuery({ queryKey: sk.grades(schoolId), queryFn: () => fetchGrades(schoolId), staleTime: 60_000, gcTime: CALENDAR_GC_MS, retry: false });
  return (
    <Loadable query={grades}>
      <SelectField
        label={t("grade")}
        value={value}
        onChange={onChange}
        placeholder={t("choose")}
        options={(grades.data ?? []).filter((g) => g.active).map((g) => ({ value: g.id, label: g.name }))}
        error={error}
      />
    </Loadable>
  );
}

function ClassPick({ schoolId, caps, value, onChange, error }: { schoolId: string; caps: EventCapabilities; value: string; onChange: (id: string) => void; error: string | null }) {
  const t = useTranslations("school.calendar.fields");
  const choice = classChoice(caps);
  // Managers pick from every active class; teachers from the classes the API says they may add events to.
  const all = useQuery({
    queryKey: sk.classList(schoolId, { calendarPicker: true }),
    queryFn: () => fetchClasses(schoolId, { status: "ACTIVE" }, undefined, 100),
    enabled: choice.all,
    staleTime: 60_000,
    gcTime: CALENDAR_GC_MS,
    retry: false,
  });
  const options = choice.all ? (all.data?.items ?? []).map((c) => ({ value: c.id, label: c.name })) : choice.classes.map((c) => ({ value: c.id, label: c.name }));
  const field = <SelectField label={t("class")} value={value} onChange={onChange} placeholder={t("choose")} options={options} error={error} />;
  return choice.all ? <Loadable query={all}>{field}</Loadable> : field;
}

/**
 * A parent meeting is about one child: the teacher picks one of their classes and a student of it. The guardians who are
 * invited are resolved by the API (they are not listed here); the form says in words who and what the notification shows.
 */
function MeetingPick({ schoolId, studentId, onChange, error }: { schoolId: string; studentId: string; onChange: (id: string) => void; error: string | null }) {
  const t = useTranslations("school.calendar.fields");
  const [classId, setClassId] = useState("");
  const classes = useQuery({
    queryKey: sk.classList(schoolId, { calendarMeetingPicker: true }),
    queryFn: () => fetchClasses(schoolId, { status: "ACTIVE", mine: true }, undefined, 100),
    staleTime: 60_000,
    gcTime: CALENDAR_GC_MS,
    retry: false,
  });
  // Children's names stay in memory for a short while only.
  const roster = useQuery({
    queryKey: sk.classRoster(schoolId, classId),
    queryFn: () => fetchRoster(schoolId, classId),
    enabled: !!classId,
    staleTime: 30_000,
    gcTime: CALENDAR_GC_MS,
    retry: false,
  });
  return (
    <div className="flex flex-col gap-3" data-testid="meeting-pick">
      <Loadable query={classes}>
        <SelectField
          label={t("meetingClass")}
          value={classId}
          onChange={(id) => {
            setClassId(id);
            onChange("");
          }}
          placeholder={t("choose")}
          options={(classes.data?.items ?? []).map((c) => ({ value: c.id, label: c.name }))}
        />
      </Loadable>
      {classId ? (
        <Loadable query={roster}>
          <SelectField
            label={t("meetingStudent")}
            value={studentId}
            onChange={onChange}
            placeholder={t("choose")}
            options={(roster.data ?? []).map((s) => ({ value: s.studentId, label: s.displayName }))}
            error={error}
          />
        </Loadable>
      ) : error ? (
        <p role="alert" className="text-sm font-medium text-destructive">
          {error}
        </p>
      ) : null}
    </div>
  );
}

/** The kind of calendar entry (scope) the caller may add, as a select. */
export function ScopeSelect({ scopes, value, onChange }: { scopes: readonly EventScope[]; value: EventScope; onChange: (scope: EventScope) => void }) {
  const t = useTranslations("school.calendar");
  return (
    <SelectField
      label={t("fields.scope")}
      value={value}
      onChange={(v) => onChange(v as EventScope)}
      options={scopes.map((scope) => ({ value: scope, label: t(`scopes.${scope}`) }))}
    />
  );
}

/** The event type, when the scope allows more than one. */
export function TypeSelect({ scope, value, onChange }: { scope: EventScope; value: string; onChange: (type: string) => void }) {
  const t = useTranslations("school.calendar");
  const types = typesForScope(scope);
  if (types.length < 2) return null;
  return <SelectField label={t("fields.type")} value={value} onChange={onChange} options={types.map((type) => ({ value: type, label: t(`types.${type}`) }))} />;
}
