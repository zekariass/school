"use client";

import { useQuery } from "@tanstack/react-query";
import { SearchIcon, XIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { SelectField } from "@/components/school/fields";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { MAX_PEOPLE, addPeople, type ChosenPerson } from "@/lib/announcements/composer";
import { errorMessage } from "@/lib/console/errors";
import { fetchRoster, fetchStaff, fetchStudentGuardians, fetchStudents, sk, type StudentView } from "@/lib/school/queries";
import { useDebounced } from "@/lib/school/use-debounced";
import { cn } from "@/lib/utils";

interface Child {
  id: string;
  name: string;
}

/**
 * Chooses individual people, only from the lists the API already lets this person see: managers search the school's
 * students and staff, teachers pick a child from the roster of one of their own classes. Guardians are reached
 * through a child (their confirmed, notifiable links). Nothing is enumerated across schools, and the names stay in
 * this component's state (memory) next to the ids that are sent.
 */
export function PeoplePicker({
  schoolId,
  manager,
  classes,
  studentsEnabled,
  people,
  onChange,
}: {
  schoolId: string;
  manager: boolean;
  classes: readonly { id: string; name: string }[];
  studentsEnabled: boolean;
  people: readonly ChosenPerson[];
  onChange: (people: ChosenPerson[]) => void;
}) {
  const t = useTranslations("school.announcements.composer.people");
  const [mode, setMode] = useState<"child" | "staff">("child");
  const [search, setSearch] = useState("");
  const [classId, setClassId] = useState("");
  const [child, setChild] = useState<Child | null>(null);
  const q = useDebounced(search.trim());
  const full = people.length >= MAX_PEOPLE;

  const students = useQuery({
    queryKey: sk.studentList(schoolId, { announcementPicker: true, q }),
    queryFn: () => fetchStudents(schoolId, { status: "ACTIVE", q }, undefined, 20),
    enabled: manager && mode === "child",
    staleTime: 30_000,
    retry: false,
  });
  const roster = useQuery({
    queryKey: sk.classRoster(schoolId, classId || "none"),
    queryFn: () => fetchRoster(schoolId, classId),
    enabled: !manager && mode === "child" && !!classId,
    staleTime: 30_000,
    retry: false,
  });
  const staff = useQuery({
    queryKey: sk.staffList(schoolId, { announcementPicker: true, q }),
    queryFn: () => fetchStaff(schoolId, { status: "ACTIVE", q }, undefined, 20),
    enabled: manager && mode === "staff",
    staleTime: 30_000,
    retry: false,
  });

  const children: Child[] = manager
    ? (students.data?.items ?? []).map((s: StudentView) => ({ id: s.id, name: s.displayName }))
    : (roster.data ?? [])
        .filter((s) => !q || s.displayName.toLowerCase().includes(q.toLowerCase()))
        .map((s) => ({ id: s.studentId, name: s.displayName }));
  const active = mode === "child" ? (manager ? students : roster) : staff;

  return (
    <div className="flex flex-col gap-3" data-testid="people-picker">
      <div className="flex flex-col gap-1.5">
        <span className="text-sm font-medium">{t("title")}</span>
        {people.length === 0 ? (
          <p className="text-sm text-muted-foreground">{t("none")}</p>
        ) : (
          <>
            <ul className="flex flex-wrap gap-1.5" aria-label={t("count", { count: people.length })} data-testid="chosen-people">
              {people.map((p) => (
                <li key={p.id} className="flex items-center gap-1 rounded-full bg-primary/10 py-0.5 ps-3 pe-1 text-sm font-medium">
                  {p.name || t("unnamed")}
                  <button
                    type="button"
                    aria-label={p.name ? t("remove", { name: p.name }) : t("removeUnnamed")}
                    onClick={() => onChange(people.filter((x) => x.id !== p.id))}
                    className="grid size-8 place-items-center rounded-full hover:bg-primary/20"
                  >
                    <XIcon className="size-3.5" aria-hidden />
                  </button>
                </li>
              ))}
            </ul>
            <div className="flex flex-wrap items-center gap-3 text-sm text-muted-foreground">
              <span>{t("count", { count: people.length })}</span>
              <button type="button" className="font-semibold text-primary hover:underline" onClick={() => onChange([])}>
                {t("clear")}
              </button>
            </div>
          </>
        )}
        {full ? <p className="text-sm text-muted-foreground">{t("limit", { max: MAX_PEOPLE })}</p> : null}
      </div>

      {manager ? (
        <div role="group" aria-label={t("title")} className="flex gap-2">
          {(["child", "staff"] as const).map((value) => (
            <button
              key={value}
              type="button"
              aria-pressed={mode === value}
              onClick={() => {
                setMode(value);
                setChild(null);
                setSearch("");
              }}
              className={cn("min-h-10 rounded-full border px-4 text-sm font-medium", mode === value ? "border-primary bg-primary text-primary-foreground" : "hover:bg-accent")}
            >
              {value === "child" ? t("modeChild") : t("modeStaff")}
            </button>
          ))}
        </div>
      ) : (
        <SelectField
          label={t("pickClass")}
          value={classId}
          onChange={(value) => {
            setClassId(value);
            setChild(null);
          }}
          noneLabel={t("pickClassNone")}
          options={classes.map((c) => ({ value: c.id, label: c.name }))}
        />
      )}

      {manager || classId ? (
        <div className="relative">
          <SearchIcon className="pointer-events-none absolute start-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" aria-hidden />
          <Input
            type="search"
            className="ps-9"
            placeholder={t("search")}
            aria-label={mode === "staff" ? t("searchStaff") : t("searchChild")}
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            data-testid="people-search"
          />
        </div>
      ) : null}

      {manager || classId ? (
        <div className="max-h-56 overflow-y-auto rounded-xl border">
          {active.isPending ? (
            <p className="p-3 text-sm text-muted-foreground">{t("loading")}</p>
          ) : active.isError ? (
            <p role="alert" className="p-3 text-sm text-destructive">
              {errorMessage(active.error, t("failed"))}
            </p>
          ) : mode === "staff" ? (
            (staff.data?.items ?? []).length === 0 ? (
              <p className="p-3 text-sm text-muted-foreground">{t("noResults")}</p>
            ) : (
              <ul>
                {(staff.data?.items ?? []).map((s) => {
                  const added = people.some((p) => p.id === s.userId);
                  return (
                    <li key={s.userId} className="flex min-h-12 items-center justify-between gap-2 px-3 py-1.5">
                      <span className="min-w-0 truncate font-medium">{s.displayName}</span>
                      <Button type="button" size="sm" variant="outline" disabled={added || full} onClick={() => onChange(addPeople(people, [{ id: s.userId, name: s.displayName }]))}>
                        {t("add")}
                      </Button>
                    </li>
                  );
                })}
              </ul>
            )
          ) : children.length === 0 ? (
            <p className="p-3 text-sm text-muted-foreground">{t("noResults")}</p>
          ) : (
            <ul>
              {children.map((c) => (
                <li key={c.id}>
                  <button
                    type="button"
                    onClick={() => setChild(c)}
                    aria-pressed={child?.id === c.id}
                    className={cn("flex min-h-12 w-full items-center px-3 py-1.5 text-start font-medium hover:bg-accent", child?.id === c.id && "bg-primary/10")}
                  >
                    <span className="truncate">{c.name}</span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      ) : null}

      {child && mode === "child" ? <ChildPeople schoolId={schoolId} child={child} studentsEnabled={studentsEnabled} people={people} full={full} onChange={onChange} /> : null}
    </div>
  );
}

/** The confirmed guardians of one child (and the child, where the school addresses students), to tick and add. */
function ChildPeople({
  schoolId,
  child,
  studentsEnabled,
  people,
  full,
  onChange,
}: {
  schoolId: string;
  child: Child;
  studentsEnabled: boolean;
  people: readonly ChosenPerson[];
  full: boolean;
  onChange: (people: ChosenPerson[]) => void;
}) {
  const t = useTranslations("school.announcements.composer.people");
  const [picked, setPicked] = useState<string[]>([]);
  const guardians = useQuery({
    queryKey: sk.studentGuardians(schoolId, child.id),
    queryFn: () => fetchStudentGuardians(schoolId, child.id),
    staleTime: 30_000,
    retry: false,
  });
  const options: ChosenPerson[] = [
    ...(guardians.data ?? []).filter((g) => g.status === "ACTIVE" && !g.custodyRestricted).map((g) => ({ id: g.guardianId, name: g.guardianName })),
    ...(studentsEnabled ? [{ id: child.id, name: t("theStudent", { name: child.name }) }] : []),
  ];
  const free = options.filter((o) => !people.some((p) => p.id === o.id));

  return (
    <fieldset className="flex flex-col gap-2 rounded-xl border p-3" data-testid="child-people">
      <legend className="px-1 text-sm font-semibold">{t("guardiansOf", { name: child.name })}</legend>
      {guardians.isPending ? (
        <p className="text-sm text-muted-foreground">{t("loading")}</p>
      ) : guardians.isError ? (
        <p role="alert" className="text-sm text-destructive">
          {errorMessage(guardians.error, t("failed"))}
        </p>
      ) : options.length === 0 ? (
        <p className="text-sm text-muted-foreground">{t("noGuardians")}</p>
      ) : (
        <>
          {options.map((o) => {
            const already = people.some((p) => p.id === o.id);
            return (
              <label key={o.id} className="flex min-h-11 cursor-pointer items-center gap-3 text-sm font-medium">
                <input
                  type="checkbox"
                  className="size-5 accent-primary"
                  disabled={already}
                  checked={already || picked.includes(o.id)}
                  onChange={(event) => setPicked(event.target.checked ? [...picked, o.id] : picked.filter((id) => id !== o.id))}
                />
                {o.name}
              </label>
            );
          })}
          <div>
            <Button
              type="button"
              size="sm"
              disabled={full || free.every((o) => !picked.includes(o.id))}
              onClick={() => {
                onChange(addPeople(people, free.filter((o) => picked.includes(o.id))));
                setPicked([]);
              }}
              data-testid="people-add"
            >
              {t("add")}
            </Button>
          </div>
        </>
      )}
    </fieldset>
  );
}
