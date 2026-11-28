"use client";

import { useQuery } from "@tanstack/react-query";
import { BellRingIcon, CheckCircle2Icon, ClockIcon, SchoolIcon, SparklesIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useFormatInstant } from "@/components/calendar-preference";
import { EmptyNotice, ErrorNotice, LoadingRows } from "@/components/console/states";
import { StudentAvatar } from "@/components/school/student-avatar";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Link } from "@/i18n/navigation";
import { errorMessage } from "@/lib/console/errors";
import { fetchMyChildren, fetchMyConsents, familyKeys, type MyChild, type PendingLink } from "@/lib/family/queries";
import { openDecisionCount, teacherNames } from "@school/validation/family";
import { FamilyFeed } from "@/components/feed/family-feed";
import { ConnectCodeCard } from "./connect-code-card";

/** "My children": the adult's children with their classes and teachers, waiting requests and open consent decisions. */
export function FamilyHome() {
  const t = useTranslations("family");
  const children = useQuery({ queryKey: familyKeys.children, queryFn: fetchMyChildren });
  // Consent is a separate, optional layer: the page works without it.
  const consents = useQuery({ queryKey: familyKeys.consents, queryFn: fetchMyConsents, retry: false });
  const open = consents.data ? openDecisionCount(consents.data.children) : 0;

  return (
    <div className="flex w-full flex-col gap-6">
      <header>
        <h1 className="text-3xl font-bold">{t("title")}</h1>
        <p className="text-muted-foreground">{t("subtitle")}</p>
      </header>

      {open > 0 ? (
        <Link
          href="/family/consent"
          className="flex min-h-14 items-center gap-3 rounded-2xl bg-warning/15 p-4 font-semibold hover:bg-warning/25"
        >
          <BellRingIcon className="size-5 shrink-0" aria-hidden />
          <span className="flex-1">{t("consentNeeded")}</span>
          <span className="text-primary">{t("consentReview")}</span>
        </Link>
      ) : null}

      {children.data ? <FamilyFeed kids={children.data.children} /> : null}

      <div className="mx-auto flex w-full max-w-3xl flex-col gap-6">
        {children.isPending ? (
          <LoadingRows rows={3} />
        ) : children.isError ? (
          <ErrorNotice message={errorMessage(children.error, t("loadFailed"))} onRetry={() => void children.refetch()} />
        ) : (
          <>
            {children.data.children.length === 0 && children.data.pending.length === 0 ? (
              <EmptyNotice>
                <span className="block text-lg font-bold text-foreground">{t("emptyTitle")}</span>
                {t("emptyBody")}
              </EmptyNotice>
            ) : null}
            <ul className="flex flex-col gap-4">
              {children.data.children.map((child) => (
                <ChildCard key={child.studentId} child={child} needsDecision={childNeeds(consents.data, child.studentId)} />
              ))}
              {children.data.pending.map((link) => (
                <PendingCard key={link.linkId} link={link} />
              ))}
            </ul>
          </>
        )}

        <ConnectCodeCard />
      </div>
    </div>
  );
}

function childNeeds(
  consents: Awaited<ReturnType<typeof fetchMyConsents>> | undefined,
  studentId: string,
): "needed" | "done" | null {
  if (!consents) return null;
  const mine = consents.children.filter((c) => c.studentId === studentId);
  if (mine.length === 0) return null;
  return openDecisionCount(mine) > 0 ? "needed" : "done";
}

function ChildCard({ child, needsDecision }: { child: MyChild; needsDecision: "needed" | "done" | null }) {
  const t = useTranslations("family");
  const tRel = useTranslations("family.relationship");
  return (
    <li>
      <Card data-testid="child-card">
        <CardContent className="flex flex-col gap-4">
          <div className="flex items-center gap-4">
            <StudentAvatar
              id={child.studentId}
              name={child.displayName}
              mediaId={child.avatar?.id}
              config={child.avatarConfig}
              size={64}
            />
            <div className="min-w-0 flex-1">
              <h2 className="truncate text-xl font-bold">{child.displayName}</h2>
              <Badge variant="secondary">{tRel(child.relationshipType)}</Badge>
            </div>
          </div>

          {child.schools.map((school) => {
            const teachers = teacherNames(school);
            return (
              <section key={school.schoolId} className="flex flex-col gap-2 rounded-xl bg-muted/50 p-3">
                <p className="flex items-center gap-2 text-sm font-semibold">
                  <SchoolIcon className="size-4 shrink-0" aria-hidden />
                  {school.schoolName}
                </p>
                {!child.permissions.canViewAcademics ? (
                  <p className="text-sm text-muted-foreground">{t("noAcademics")}</p>
                ) : school.classes.length === 0 ? (
                  <p className="text-sm text-muted-foreground">{t("noClasses")}</p>
                ) : (
                  <ul className="flex flex-wrap gap-2" aria-label={t("classes")}>
                    {school.classes.map((cls) => (
                      <li key={cls.classId}>
                        <Badge variant="outline" className="text-sm">
                          {cls.icon ? <span aria-hidden>{cls.icon}</span> : null}
                          {cls.name}
                        </Badge>
                      </li>
                    ))}
                  </ul>
                )}
                {teachers.length > 0 ? (
                  <p className="text-sm text-muted-foreground">{t("teachers", { names: teachers.join(", ") })}</p>
                ) : null}
              </section>
            );
          })}

          {child.permissions.canViewAcademics ? (
            <Button asChild variant="outline" className="self-start">
              <Link href={`/family/children/${child.studentId}`} data-testid="child-points-link">
                <SparklesIcon className="size-4" aria-hidden />
                {t("pointsLink", { name: child.givenName })}
              </Link>
            </Button>
          ) : null}

          {needsDecision === "needed" ? (
            <Button asChild variant="outline" className="self-start">
              <Link href="/family/consent">
                <BellRingIcon className="size-4" aria-hidden />
                {t("consentNeeded")}
              </Link>
            </Button>
          ) : needsDecision === "done" ? (
            <p className="flex items-center gap-2 text-sm text-muted-foreground">
              <CheckCircle2Icon className="size-4 text-success" aria-hidden />
              {t("consentUpToDate")}
            </p>
          ) : null}
        </CardContent>
      </Card>
    </li>
  );
}

function PendingCard({ link }: { link: PendingLink }) {
  const t = useTranslations("family");
  const formatInstant = useFormatInstant();
  return (
    <li>
      <Card className="border-dashed shadow-none" data-testid="pending-card">
        <CardContent className="flex items-start gap-4">
          <span className="grid size-12 shrink-0 place-items-center rounded-full bg-muted text-muted-foreground">
            <ClockIcon className="size-6" aria-hidden />
          </span>
          <div className="flex min-w-0 flex-col gap-1">
            <h2 className="text-lg font-bold">{link.childFirstName}</h2>
            <p className="font-medium">{t("pendingTitle")}</p>
            <p className="text-sm text-muted-foreground">
              {link.schoolName
                ? t("pendingBody", { school: link.schoolName, child: link.childFirstName })
                : t("pendingBodyNoSchool", { child: link.childFirstName })}
            </p>
            <p className="text-sm text-muted-foreground">{t("requestedOn", { date: formatInstant(link.requestedAt) })}</p>
          </div>
        </CardContent>
      </Card>
    </li>
  );
}
