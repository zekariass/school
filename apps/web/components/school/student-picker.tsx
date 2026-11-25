"use client";

import { useQuery } from "@tanstack/react-query";
import { SearchIcon, XIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { Input } from "@/components/ui/input";
import { errorMessage } from "@/lib/console/errors";
import { fetchStudents, sk, type StudentView } from "@/lib/school/queries";
import { useDebounced } from "@/lib/school/use-debounced";
import { cn } from "@/lib/utils";
import { useSchool } from "./school-context";
import { StudentAvatar } from "./student-avatar";

/**
 * A searchable multi-select over the school's students. The selection is a list of ids;
 * names of chosen students are remembered here so the chips survive new searches.
 * `exclude` hides students who are already in the target (e.g. the class roster).
 */
export function StudentPicker({
  value,
  onChange,
  exclude,
  label,
}: {
  value: string[];
  onChange: (ids: string[]) => void;
  exclude?: ReadonlySet<string>;
  label: string;
}) {
  const t = useTranslations("school.picker");
  const { school } = useSchool();
  const [search, setSearch] = useState("");
  const [known, setKnown] = useState<Record<string, StudentView>>({});
  const q = useDebounced(search.trim());
  const results = useQuery({
    queryKey: sk.studentList(school.id, { picker: true, q }),
    queryFn: () => fetchStudents(school.id, { status: "ACTIVE", q }, undefined, 30),
    staleTime: 30_000,
  });
  const items = (results.data?.items ?? []).filter((s) => !exclude?.has(s.id));
  const selected = new Set(value);

  function toggle(student: StudentView) {
    if (selected.has(student.id)) {
      onChange(value.filter((id) => id !== student.id));
    } else {
      setKnown((prev) => ({ ...prev, [student.id]: student }));
      onChange([...value, student.id]);
    }
  }

  return (
    <div className="flex flex-col gap-2">
      <span className="text-sm leading-none font-medium">{label}</span>
      {value.length > 0 ? (
        <ul className="flex flex-wrap gap-1.5" aria-label={t("selected")}>
          {value.map((id) => (
            <li key={id} className="flex items-center gap-1 rounded-full bg-primary/10 py-0.5 ps-3 pe-1 text-sm font-medium">
              {known[id]?.displayName ?? id}
              <button
                type="button"
                aria-label={t("remove", { name: known[id]?.displayName ?? id })}
                onClick={() => onChange(value.filter((v) => v !== id))}
                className="grid size-7 place-items-center rounded-full hover:bg-primary/20"
              >
                <XIcon className="size-3.5" aria-hidden />
              </button>
            </li>
          ))}
        </ul>
      ) : null}
      <div className="relative">
        <SearchIcon className="pointer-events-none absolute start-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" aria-hidden />
        <Input
          type="search"
          className="ps-9"
          placeholder={t("search")}
          aria-label={t("search")}
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
      </div>
      <div className="max-h-56 overflow-y-auto rounded-xl border">
        {results.isPending ? (
          <p className="p-3 text-sm text-muted-foreground">{t("loading")}</p>
        ) : results.isError ? (
          <p role="alert" className="p-3 text-sm text-destructive">
            {errorMessage(results.error, t("failed"))}
          </p>
        ) : items.length === 0 ? (
          <p className="p-3 text-sm text-muted-foreground">{t("none")}</p>
        ) : (
          <ul>
            {items.map((student) => {
              const checked = selected.has(student.id);
              return (
                <li key={student.id}>
                  <label
                    className={cn(
                      "flex min-h-12 cursor-pointer items-center gap-3 px-3 py-1.5 hover:bg-accent",
                      checked && "bg-primary/5",
                    )}
                  >
                    <input
                      type="checkbox"
                      className="size-5 accent-primary"
                      checked={checked}
                      onChange={() => toggle(student)}
                    />
                    <StudentAvatar
                      id={student.id}
                      name={student.displayName}
                      mediaId={student.avatar?.id}
                      config={student.avatarConfig}
                      size={32}
                    />
                    <span className="min-w-0 flex-1 truncate font-medium">{student.displayName}</span>
                    {(student.classes ?? []).length > 0 ? (
                      <span className="truncate text-xs text-muted-foreground">
                        {(student.classes ?? []).map((c) => c.name).join(", ")}
                      </span>
                    ) : null}
                  </label>
                </li>
              );
            })}
          </ul>
        )}
      </div>
    </div>
  );
}
