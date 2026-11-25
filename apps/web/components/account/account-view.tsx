"use client";

import { useQuery, useQueryClient } from "@tanstack/react-query";
import { UserRoundIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { toast } from "sonner";
import type { components } from "@school/api-client";
import { useSubmit } from "@/components/console/use-submit";
import { MediaImage } from "@/components/media/media-image";
import { ImageUploader } from "@/components/media/image-uploader";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Link } from "@/i18n/navigation";
import { getBrowserApi } from "@/lib/api-client";
import { unwrap } from "@/lib/api-error";

type Me = components["schemas"]["MeResponse"];

const meKey = ["me"] as const;

/** Display name and profile photo of the signed-in user. */
export function AccountView({ initial }: { initial: Me }) {
  const t = useTranslations("account");
  const tm = useTranslations("media");
  const tn = useTranslations("notifications");
  const queryClient = useQueryClient();
  const me = useQuery({
    queryKey: meKey,
    queryFn: async () => unwrap(await getBrowserApi().GET("/api/v1/me")),
    initialData: initial,
    staleTime: 60_000,
  }).data;
  const [name, setName] = useState(me.displayName);

  const setMe = (next: Me) => queryClient.setQueryData(meKey, next);

  const saveName = useSubmit({
    mutationFn: async (displayName: string) =>
      unwrap(await getBrowserApi().PATCH("/api/v1/me", { body: { displayName } })),
    successMessage: t("nameSaved"),
    errorFallback: t("saveFailed"),
    onSuccess: (next) => setMe(next),
  });

  async function attach(mediaId: string) {
    const next = unwrap(await getBrowserApi().PUT("/api/v1/me/avatar", { body: { mediaId } }));
    setMe(next);
    toast.success(tm("avatar.updated"));
  }

  async function clear() {
    const next = unwrap(await getBrowserApi().DELETE("/api/v1/me/avatar"));
    setMe(next);
    toast.success(tm("avatar.removed"));
  }

  const dirty = name.trim() !== me.displayName && name.trim().length > 0;

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-2xl font-bold">{t("title")}</h1>
        <p className="text-muted-foreground">{t("subtitle")}</p>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>{tm("avatar.title")}</CardTitle>
          <CardDescription>{tm("avatar.hint")}</CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-4 sm:flex-row sm:items-start">
          <MediaImage
            mediaId={me.avatar?.id}
            variant="SMALL"
            width={128}
            height={128}
            alt={tm("avatar.alt", { name: me.displayName })}
            className="size-32 shrink-0 rounded-full border object-cover"
            fallback={
              <span className="grid size-32 shrink-0 place-items-center rounded-full bg-muted text-muted-foreground">
                <UserRoundIcon className="size-14" aria-hidden />
              </span>
            }
          />
          <ImageUploader
            purpose="AVATAR"
            hasImage={!!me.avatar}
            onUploaded={attach}
            onRemove={clear}
            labels={{ choose: tm("avatar.choose"), change: tm("avatar.change"), remove: tm("avatar.remove") }}
          />
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>{tn("title")}</CardTitle>
          <CardDescription>{t("notificationsHint")}</CardDescription>
        </CardHeader>
        <CardContent>
          <Button asChild variant="outline">
            <Link href="/account/notifications">{t("notificationsLink")}</Link>
          </Button>
        </CardContent>
      </Card>

      <Card>
        <CardContent className="flex flex-col gap-4">
          <form
            className="flex flex-col gap-2"
            onSubmit={(event) => {
              event.preventDefault();
              if (dirty) saveName.mutate(name.trim());
            }}
          >
            <Label htmlFor="displayName">{t("name")}</Label>
            <div className="flex flex-wrap gap-2">
              <Input
                id="displayName"
                value={name}
                maxLength={100}
                onChange={(event) => setName(event.target.value)}
                className="max-w-sm"
              />
              <Button type="submit" disabled={!dirty || saveName.isPending}>
                {t("save")}
              </Button>
            </div>
          </form>
          <dl className="grid gap-x-6 gap-y-3 sm:grid-cols-2">
            <div className="flex flex-col">
              <dt className="text-sm text-muted-foreground">{t("phone")}</dt>
              <dd className="font-semibold">{me.phone ?? t("notSet")}</dd>
            </div>
            <div className="flex flex-col">
              <dt className="text-sm text-muted-foreground">{t("email")}</dt>
              <dd className="font-semibold">{me.email ?? t("notSet")}</dd>
            </div>
          </dl>
        </CardContent>
      </Card>
    </div>
  );
}
