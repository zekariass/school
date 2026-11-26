"use client";

import { useQuery } from "@tanstack/react-query";
import { ArrowLeftIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { EmptyNotice, ErrorNotice, LoadingRows } from "@/components/console/states";
import { AttendanceHistory } from "@/components/attendance/attendance-history";
import { StudentAvatar } from "@/components/school/student-avatar";
import { StudentPointsView } from "@/components/points/student-points-view";
import { Link } from "@/i18n/navigation";
import { errorMessage } from "@/lib/console/errors";
import { fetchMyChildren, familyKeys } from "@/lib/family/queries";
import { canSeeChildAttendance } from "@/lib/attendance/access";
import { canAwardHome } from "@/lib/points/sections";
import { AbsenceReports } from "./absence-reports";

/** One child: their School points and Home points (two sections), and for guardians who may, awarding home points. */
export function ChildDetail({ studentId }: { studentId: string }) {
  const t = useTranslations("family");
  const children = useQuery({ queryKey: familyKeys.children, queryFn: fetchMyChildren });

  const back = (
    <Link href="/family" className="inline-flex min-h-11 items-center gap-2 self-start text-sm font-semibold text-primary hover:underline">
      <ArrowLeftIcon className="size-4 rtl:rotate-180" aria-hidden />
      {t("backToChildren")}
    </Link>
  );

  if (children.isPending) return <LoadingRows rows={3} />;
  if (children.isError) {
    return (
      <div className="mx-auto flex w-full max-w-3xl flex-col gap-4">
        {back}
        <ErrorNotice message={errorMessage(children.error, t("loadFailed"))} onRetry={() => void children.refetch()} />
      </div>
    );
  }
  const child = children.data.children.find((c) => c.studentId === studentId);
  if (!child) {
    return (
      <div className="mx-auto flex w-full max-w-3xl flex-col gap-4">
        {back}
        <EmptyNotice>{t("childNotFound")}</EmptyNotice>
      </div>
    );
  }

  return (
    <div className="mx-auto flex w-full max-w-3xl flex-col gap-6">
      {back}
      <header className="flex items-center gap-4">
        <StudentAvatar id={child.studentId} name={child.displayName} mediaId={child.avatar?.id} config={child.avatarConfig} size={64} />
        <h1 className="min-w-0 truncate text-3xl font-bold">{child.displayName}</h1>
      </header>
      {!child.permissions.canViewAcademics ? (
        <EmptyNotice>{t("noAcademics")}</EmptyNotice>
      ) : child.schools.length === 0 ? (
        <EmptyNotice>{t("noClasses")}</EmptyNotice>
      ) : (
        child.schools.map((school) => (
          <StudentPointsView
            key={school.schoolId}
            schoolId={school.schoolId}
            studentId={child.studentId}
            audience="guardian"
            canAwardHome={canAwardHome(child.permissions)}
            heading={child.schools.length > 1 ? school.schoolName : school.schoolName}
          />
        ))
      )}
      {child.schools.map((school, index) => (
        // The attendance notification opens #attendance: the history and the absence reports of the child's school.
        <div key={school.schoolId} id={index === 0 ? "attendance" : undefined} className="flex scroll-mt-20 flex-col gap-6" data-testid="child-attendance">
          {canSeeChildAttendance(child.permissions) ? (
            <AttendanceHistory
              schoolId={school.schoolId}
              studentId={child.studentId}
              audience="guardian"
              heading={child.schools.length > 1 ? school.schoolName : undefined}
            />
          ) : null}
          <AbsenceReports schoolId={school.schoolId} studentId={child.studentId} childName={child.displayName} />
        </div>
      ))}
    </div>
  );
}
