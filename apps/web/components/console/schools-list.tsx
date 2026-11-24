"use client";

import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { PlusIcon } from "lucide-react";
import { useLocale, useTranslations } from "next-intl";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Link } from "@/i18n/navigation";
import { P } from "@/lib/console/permissions";
import {
  fetchCountries,
  fetchSchools,
  queryKeys,
  type SchoolFilters,
  type SchoolStatus,
} from "@/lib/console/queries";
import { useFormatInstant } from "@/components/calendar-preference";
import { errorMessage } from "@/lib/console/errors";
import { useCan } from "./console-context";
import { EmptyNotice, ErrorNotice, LoadingRows, LoadMore } from "./states";
import { StatusBadge } from "./status-badge";

const ALL = "ALL";
const STATUSES: SchoolStatus[] = ["ONBOARDING", "ACTIVE", "SUSPENDED", "ARCHIVED"];

export function SchoolsList() {
  const t = useTranslations("console");
  const locale = useLocale();
  const formatInstant = useFormatInstant();
  const canCreate = useCan(P.schoolsManage);
  const [status, setStatus] = useState<string>(ALL);
  const [country, setCountry] = useState<string>(ALL);

  const filters: SchoolFilters = {
    ...(status !== ALL ? { status: status as SchoolStatus } : {}),
    ...(country !== ALL ? { countryCode: country } : {}),
  };

  const countries = useQuery({ queryKey: queryKeys.countries, queryFn: fetchCountries, staleTime: 600_000 });
  const schools = useInfiniteQuery({
    queryKey: queryKeys.schools(filters),
    queryFn: ({ pageParam }) => fetchSchools(filters, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor,
  });
  const rows = schools.data?.pages.flatMap((p) => p.items) ?? [];

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold">{t("schools.title")}</h1>
          <p className="text-muted-foreground">{t("schools.subtitle")}</p>
        </div>
        {canCreate ? (
          <Button asChild size="lg">
            <Link href="/platform/schools/new">
              <PlusIcon className="size-4" aria-hidden />
              {t("schools.create")}
            </Link>
          </Button>
        ) : null}
      </div>

      <div className="flex flex-wrap gap-4">
        <div className="flex min-w-44 flex-col gap-1.5">
          <Label htmlFor="school-status-filter">{t("filters.status")}</Label>
          <Select value={status} onValueChange={setStatus}>
            <SelectTrigger id="school-status-filter" className="w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={ALL}>{t("filters.all")}</SelectItem>
              {STATUSES.map((s) => (
                <SelectItem key={s} value={s}>
                  {t(`status.${s}`)}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        <div className="flex min-w-44 flex-col gap-1.5">
          <Label htmlFor="school-country-filter">{t("filters.country")}</Label>
          <Select value={country} onValueChange={setCountry}>
            <SelectTrigger id="school-country-filter" className="w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={ALL}>{t("filters.all")}</SelectItem>
              {(countries.data ?? []).map((c) => (
                <SelectItem key={c.code} value={c.code}>
                  {c.nameI18n[locale] ?? c.nameI18n.en ?? c.code}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      </div>

      {schools.isPending ? (
        <LoadingRows />
      ) : schools.isError ? (
        <ErrorNotice message={errorMessage(schools.error, t("loadFailed"))} onRetry={() => void schools.refetch()} />
      ) : rows.length === 0 ? (
        <EmptyNotice>{t("schools.empty")}</EmptyNotice>
      ) : (
        <>
          <div className="rounded-2xl border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t("fields.name")}</TableHead>
                  <TableHead>{t("fields.slug")}</TableHead>
                  <TableHead>{t("fields.country")}</TableHead>
                  <TableHead>{t("fields.status")}</TableHead>
                  <TableHead>{t("fields.created")}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((school) => (
                  <TableRow key={school.id}>
                    <TableCell className="font-semibold">
                      <Link
                        href={`/platform/schools/${school.id}`}
                        className="underline-offset-4 hover:underline"
                      >
                        {school.name}
                      </Link>
                    </TableCell>
                    <TableCell className="font-mono text-sm">{school.slug}</TableCell>
                    <TableCell>{school.countryCode}</TableCell>
                    <TableCell>
                      <StatusBadge status={school.status} />
                    </TableCell>
                    <TableCell>{formatInstant(school.createdAt)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <LoadMore
            hasMore={!!schools.hasNextPage}
            loading={schools.isFetchingNextPage}
            onClick={() => void schools.fetchNextPage()}
          />
        </>
      )}
    </div>
  );
}
