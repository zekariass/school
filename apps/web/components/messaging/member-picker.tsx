"use client";

import { useQuery } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { EmptyNotice, ErrorNotice, LoadingRows } from "@/components/console/states";
import { Input } from "@/components/ui/input";
import { errorMessage } from "@/lib/console/errors";
import { MESSAGING_GC_MS } from "@/lib/messaging/keys";
import { fetchRoster, fetchStaff } from "@/lib/school/queries";
import { useDebounced } from "@/lib/school/use-debounced";
import type { GroupKind } from "@/lib/messaging/types";
import { cn } from "@/lib/utils";

export interface PickedPerson {
  id: string;
  name: string;
}

/**
 * Picks the people of a group, from lists the caller may already see: the class roster for a student group (the class's
 * own students, which a teacher of the class sees), the school's staff for staff groups. There is no search across the
 * platform and nothing for students: the API refuses ineligible members anyway (`messaging.member_not_eligible`).
 */
export function MemberPicker({
  schoolId,
  kind,
  classId,
  value,
  onChange,
  exclude,
  label,
}: {
  schoolId: string;
  kind: GroupKind;
  classId: string | null;
  value: readonly string[];
  onChange: (ids: string[], people: Record<string, PickedPerson>) => void;
  exclude?: ReadonlySet<string>;
  label: string;
}) {
  const t = useTranslations("school.messaging.picker");
  const [search, setSearch] = useState("");
  const q = useDebounced(search.trim());
  const students = kind === "STUDENT_GROUP";
  const roster = useQuery({
    queryKey: ["messaging", "picker", "roster", schoolId, classId],
    queryFn: () => fetchRoster(schoolId, classId!),
    enabled: students && !!classId,
    gcTime: MESSAGING_GC_MS,
    retry: false,
  });
  const staff = useQuery({
    queryKey: ["messaging", "picker", "staff", schoolId, q],
    queryFn: () => fetchStaff(schoolId, { status: "ACTIVE", q }, undefined, 30),
    enabled: !students,
    gcTime: MESSAGING_GC_MS,
    retry: false,
  });
  const people: PickedPerson[] = students
    ? (roster.data ?? []).filter((s) => !search || s.displayName.toLowerCase().includes(search.toLowerCase())).map((s) => ({ id: s.studentId, name: s.displayName }))
    : (staff.data?.items ?? []).map((s) => ({ id: s.id, name: s.displayName }));
  const visible = people.filter((p) => !exclude?.has(p.id));
  const query = students ? roster : staff;
  const selected = new Set(value);

  function toggle(person: PickedPerson) {
    const next = selected.has(person.id) ? value.filter((id) => id !== person.id) : [...value, person.id];
    onChange(next, Object.fromEntries(people.map((p) => [p.id, p])));
  }

  return (
    <fieldset className="flex flex-col gap-2">
      <legend className="text-sm font-medium">{label}</legend>
      <Input value={search} onChange={(e) => setSearch(e.target.value)} placeholder={t("search")} aria-label={t("search")} />
      {query.isPending && (students ? !!classId : true) ? <LoadingRows rows={3} /> : null}
      {query.isError ? <ErrorNotice message={errorMessage(query.error, t("loadFailed"))} /> : null}
      {students && !classId ? <EmptyNotice>{t("chooseClass")}</EmptyNotice> : null}
      {query.isSuccess && visible.length === 0 ? <EmptyNotice>{t("none")}</EmptyNotice> : null}
      <ul className="flex max-h-56 flex-col gap-1 overflow-y-auto">
        {visible.map((p) => (
          <li key={p.id}>
            <label className={cn("flex min-h-11 cursor-pointer items-center gap-3 rounded-xl border px-3", selected.has(p.id) && "border-primary bg-primary/5")}>
              <input type="checkbox" checked={selected.has(p.id)} onChange={() => toggle(p)} className="size-4 accent-primary" />
              <span className="truncate">{p.name}</span>
            </label>
          </li>
        ))}
      </ul>
      <p className="text-sm text-muted-foreground">{t("selected", { count: value.length })}</p>
    </fieldset>
  );
}
