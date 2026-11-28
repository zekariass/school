"use client";

import { CalendarDaysIcon, HomeIcon, ImageIcon, TrophyIcon, type LucideIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { FEED_TABS, isFeedTab, type FeedTab } from "@/lib/feed/tabs";

const ICONS: Record<FeedTab, LucideIcon> = {
  feed: HomeIcon,
  photos: ImageIcon,
  activities: CalendarDaysIcon,
  achievements: TrophyIcon,
};

/**
 * The strip Feed | Photos | Activities | Achievements: a real tablist (arrow keys, Home/End, `aria-selected`) with an
 * underline on the active tab. The panel is the one list of the active tab: the API filters, so each tab is its own
 * query. Touch targets are at least 44 px.
 */
export function FeedTabs({ value, onValueChange, children }: { value: FeedTab; onValueChange: (tab: FeedTab) => void; children: React.ReactNode }) {
  const t = useTranslations("school.feed.redesign.tabs");
  return (
    <Tabs value={value} onValueChange={(next) => isFeedTab(next) && onValueChange(next)} className="gap-4" data-testid="feed-tabs">
      <TabsList
        variant="line"
        aria-label={t("label")}
        className="w-full justify-between gap-0 overflow-x-auto rounded-none border-b p-0 group-data-[orientation=horizontal]/tabs:h-12"
      >
        {FEED_TABS.map((tab) => {
          const Icon = ICONS[tab];
          return (
            <TabsTrigger
              key={tab}
              value={tab}
              data-testid={`feed-tab-${tab}`}
              className="h-12 min-h-11 flex-1 gap-2 rounded-none px-3 text-sm font-semibold data-[state=active]:text-primary sm:text-base group-data-[variant=line]/tabs-list:after:bottom-0 group-data-[variant=line]/tabs-list:after:h-[3px] group-data-[variant=line]/tabs-list:after:rounded-full group-data-[variant=line]/tabs-list:after:bg-primary"
            >
              <Icon aria-hidden />
              {t(tab)}
            </TabsTrigger>
          );
        })}
      </TabsList>
      <TabsContent value={value} className="flex flex-col gap-4">
        {children}
      </TabsContent>
    </Tabs>
  );
}
