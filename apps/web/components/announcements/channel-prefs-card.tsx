"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { toast } from "sonner";
import { ErrorNotice, LoadingRows } from "@/components/console/states";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Switch } from "@/components/ui/switch";
import { ank, fetchChannels, updateChannels, type ChannelPreferences } from "@/lib/announcements/queries";
import { errorMessage } from "@/lib/console/errors";

/**
 * "Email and text messages": the opt-outs of announcements by email and by text message. Push and in-app are the
 * notification preferences above. These two switches also apply to urgent announcements.
 */
export function ChannelPrefsCard() {
  const t = useTranslations("school.announcements.channelPrefs");
  const queryClient = useQueryClient();
  const channels = useQuery({ queryKey: ank.channels, queryFn: fetchChannels, retry: false });
  const change = useMutation({
    mutationFn: (body: { email?: boolean; sms?: boolean }) => updateChannels(body),
    onMutate: async (body) => {
      await queryClient.cancelQueries({ queryKey: ank.channels });
      const previous = queryClient.getQueryData<ChannelPreferences>(ank.channels);
      if (previous) queryClient.setQueryData<ChannelPreferences>(ank.channels, { ...previous, ...body });
      return { previous };
    },
    onSuccess: (next) => {
      queryClient.setQueryData(ank.channels, next);
      toast.success(t("saved"));
    },
    onError: (error, _body, context) => {
      if (context?.previous) queryClient.setQueryData(ank.channels, context.previous);
      toast.error(errorMessage(error, t("saveFailed")));
    },
  });

  return (
    <Card data-testid="channel-prefs">
      <CardHeader>
        <CardTitle>{t("title")}</CardTitle>
        <CardDescription>{t("description")}</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {channels.isPending ? (
          <LoadingRows rows={2} />
        ) : channels.isError ? (
          <ErrorNotice message={errorMessage(channels.error, t("loadFailed"))} onRetry={() => void channels.refetch()} />
        ) : (
          <>
            <label className="flex min-h-11 items-center justify-between gap-3 text-sm font-medium">
              <span>{t("email")}</span>
              <Switch checked={channels.data.email} onCheckedChange={(email) => change.mutate({ email })} data-testid="pref-email" />
            </label>
            <label className="flex min-h-11 items-center justify-between gap-3 text-sm font-medium">
              <span>{t("sms")}</span>
              <Switch
                checked={channels.data.sms && !channels.data.smsBlockedByProvider}
                disabled={channels.data.smsBlockedByProvider}
                onCheckedChange={(sms) => change.mutate({ sms })}
                data-testid="pref-sms"
              />
            </label>
            {channels.data.smsBlockedByProvider ? <p className="text-sm text-muted-foreground">{t("blocked")}</p> : null}
            <p className="text-sm text-muted-foreground">{t("urgentNote")}</p>
          </>
        )}
      </CardContent>
    </Card>
  );
}
