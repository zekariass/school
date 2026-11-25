"use client";

import { useTranslations } from "next-intl";
import { useSchool } from "@/components/school/school-context";
import { EmptyNotice } from "@/components/console/states";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { classPointsAbilities } from "@/lib/points/access";
import type { ClassView } from "@/lib/school/queries";
import { GroupsTool } from "./groups-tool";
import { PickerTool } from "./picker-tool";
import { RoutinesTool } from "./routines-tool";
import { TimerTool } from "./timer-tool";

/** The teacher's toolkit for the lesson: timer and stopwatch, random student, group maker, routines. */
export function ClassToolkitTab({ cls }: { cls: ClassView }) {
  const t = useTranslations("school.toolkit");
  const { permissions } = useSchool();
  if (!classPointsAbilities(cls, permissions).canUse) return <EmptyNotice>{t("notForYou")}</EmptyNotice>;
  return (
    <Tabs defaultValue="timer">
      <TabsList className="w-full justify-start overflow-x-auto group-data-[orientation=horizontal]/tabs:h-11">
        <TabsTrigger value="timer" className="min-h-10 flex-none px-4" data-testid="tool-timer">
          {t("tabs.timer")}
        </TabsTrigger>
        <TabsTrigger value="picker" className="min-h-10 flex-none px-4" data-testid="tool-picker">
          {t("tabs.picker")}
        </TabsTrigger>
        <TabsTrigger value="groups" className="min-h-10 flex-none px-4" data-testid="tool-groups">
          {t("tabs.groups")}
        </TabsTrigger>
        <TabsTrigger value="routines" className="min-h-10 flex-none px-4" data-testid="tool-routines">
          {t("tabs.routines")}
        </TabsTrigger>
      </TabsList>
      <TabsContent value="timer" className="pt-4">
        <TimerTool />
      </TabsContent>
      <TabsContent value="picker" className="pt-4">
        <PickerTool cls={cls} />
      </TabsContent>
      <TabsContent value="groups" className="pt-4">
        <GroupsTool cls={cls} />
      </TabsContent>
      <TabsContent value="routines" className="pt-4">
        <RoutinesTool cls={cls} />
      </TabsContent>
    </Tabs>
  );
}
