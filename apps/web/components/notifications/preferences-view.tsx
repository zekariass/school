"use client";

import { useQuery, useQueryClient } from "@tanstack/react-query";
import { LockIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useId, useState } from "react";
import { toast } from "sonner";
import { ErrorNotice, LoadingRows } from "@/components/console/states";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { getBrowserApi } from "@/lib/api-client";
import { unwrap } from "@/lib/api-error";
import { errorMessage } from "@/lib/console/errors";
import { notificationKeys } from "@/lib/notifications/keys";
import type { NotificationSettings, PreferencesView } from "@/lib/notifications/types";

/** Which notifications push to the phone, and quiet hours. In-app delivery is always on. */
export function PreferencesView() {
  const t = useTranslations("notifications");
  const queryClient = useQueryClient();
  const prefs = useQuery({
    queryKey: notificationKeys.preferences,
    queryFn: async (): Promise<PreferencesView> => unwrap(await getBrowserApi().GET("/api/v1/me/notification-preferences")),
  });

  async function setPush(typeCode: string, enabled: boolean) {
    const previous = queryClient.getQueryData<PreferencesView>(notificationKeys.preferences);
    // optimistic: flip the switch now, roll back if the API says no
    if (previous) {
      queryClient.setQueryData<PreferencesView>(notificationKeys.preferences, {
        categories: previous.categories.map((c) => ({
          ...c,
          types: c.types.map((type) =>
            type.code === typeCode
              ? { ...type, channels: type.channels.map((ch) => (ch.channel === "PUSH" ? { ...ch, enabled } : ch)) }
              : type,
          ),
        })),
      });
    }
    try {
      const next = unwrap(
        await getBrowserApi().PUT("/api/v1/me/notification-preferences", {
          body: { changes: [{ typeCode, channel: "PUSH", enabled }] },
        }),
      );
      queryClient.setQueryData(notificationKeys.preferences, next);
    } catch (error) {
      queryClient.setQueryData(notificationKeys.preferences, previous);
      toast.error(errorMessage(error, t("preferences.saveFailed")));
    }
  }

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-2xl font-bold">{t("preferences.title")}</h1>
        <p className="text-muted-foreground">{t("preferences.subtitle")}</p>
      </div>

      {prefs.isPending ? (
        <LoadingRows rows={5} />
      ) : prefs.isError ? (
        <ErrorNotice message={t("preferences.loadFailed")} onRetry={() => void prefs.refetch()} />
      ) : (
        prefs.data.categories.map((category) => (
          <Card key={category.code}>
            <CardHeader>
              <CardTitle>{category.label}</CardTitle>
            </CardHeader>
            <CardContent>
              <ul className="flex flex-col divide-y">
                {category.types.map((type) => {
                  const push = type.channels.find((c) => c.channel === "PUSH");
                  return (
                    <li key={type.code} className="flex flex-wrap items-center justify-between gap-3 py-3">
                      <div className="min-w-0">
                        <p className="font-medium">{type.label}</p>
                        <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                          <Badge variant="secondary">
                            {t("preferences.inApp")}: {t("preferences.alwaysOn")}
                          </Badge>
                          {type.canBeUrgent ? <span>{t("preferences.urgent")}</span> : null}
                          {push?.locked ? (
                            <span className="inline-flex items-center gap-1">
                              <LockIcon className="size-3" aria-hidden />
                              {t("preferences.locked")}
                            </span>
                          ) : null}
                        </div>
                      </div>
                      {push ? (
                        <label className="flex items-center gap-2 text-sm">
                          <span>{t("preferences.push")}</span>
                          <Switch
                            checked={push.enabled}
                            disabled={push.locked}
                            onCheckedChange={(checked) => void setPush(type.code, checked)}
                          />
                        </label>
                      ) : null}
                    </li>
                  );
                })}
              </ul>
            </CardContent>
          </Card>
        ))
      )}

      <QuietHoursCard />
    </div>
  );
}

function QuietHoursCard() {
  const t = useTranslations("notifications");
  const queryClient = useQueryClient();
  const startId = useId();
  const endId = useId();
  const settings = useQuery({
    queryKey: notificationKeys.settings,
    queryFn: async (): Promise<NotificationSettings> =>
      unwrap(await getBrowserApi().GET("/api/v1/me/notification-settings")),
  });
  const saved = settings.data;
  const [draft, setDraft] = useState<{ start: string; end: string } | null>(null);
  const [saving, setSaving] = useState(false);
  const start = draft?.start ?? saved?.quietHoursStart ?? "";
  const end = draft?.end ?? saved?.quietHoursEnd ?? "";

  async function save(next: { start: string; end: string }) {
    if (!!next.start !== !!next.end) {
      toast.error(t("preferences.quietHours.incomplete"));
      return;
    }
    setSaving(true);
    try {
      const result = unwrap(
        await getBrowserApi().PUT("/api/v1/me/notification-settings", {
          body: next.start ? { quietHoursStart: next.start, quietHoursEnd: next.end } : {},
        }),
      );
      queryClient.setQueryData(notificationKeys.settings, result);
      setDraft(null);
      toast.success(next.start ? t("preferences.quietHours.saved") : t("preferences.quietHours.cleared"));
    } catch (error) {
      toast.error(errorMessage(error, t("preferences.saveFailed")));
    } finally {
      setSaving(false);
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("preferences.quietHours.title")}</CardTitle>
        <CardDescription>
          {settings.data ? t("preferences.quietHours.hint", { timezone: settings.data.timezone }) : null}
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {settings.isPending ? (
          <LoadingRows rows={1} />
        ) : settings.isError ? (
          <ErrorNotice message={t("preferences.loadFailed")} onRetry={() => void settings.refetch()} />
        ) : (
          <>
            <div className="flex flex-wrap items-end gap-4">
              <div className="flex flex-col gap-1.5">
                <Label htmlFor={startId}>{t("preferences.quietHours.start")}</Label>
                <Input
                  id={startId}
                  type="time"
                  className="w-36"
                  value={start}
                  onChange={(e) => setDraft({ start: e.target.value, end })}
                />
              </div>
              <div className="flex flex-col gap-1.5">
                <Label htmlFor={endId}>{t("preferences.quietHours.end")}</Label>
                <Input
                  id={endId}
                  type="time"
                  className="w-36"
                  value={end}
                  onChange={(e) => setDraft({ start, end: e.target.value })}
                />
              </div>
              <Button disabled={saving || draft === null} onClick={() => void save({ start, end })}>
                {t("preferences.quietHours.save")}
              </Button>
              <Button
                variant="outline"
                disabled={saving || (!saved?.quietHoursStart && !draft)}
                onClick={() => void save({ start: "", end: "" })}
              >
                {t("preferences.quietHours.clear")}
              </Button>
            </div>
            {!saved?.quietHoursStart ? (
              <p className="text-sm text-muted-foreground">{t("preferences.quietHours.off")}</p>
            ) : null}
          </>
        )}
      </CardContent>
    </Card>
  );
}
