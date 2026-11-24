"use client";

import { useQueryClient } from "@tanstack/react-query";
import { SchoolIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { toast } from "sonner";
import { ImageUploader } from "@/components/media/image-uploader";
import { MediaImage } from "@/components/media/media-image";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { getBrowserApi } from "@/lib/api-client";
import { unwrap } from "@/lib/api-error";
import { P } from "@/lib/console/permissions";
import { queryKeys, type School } from "@/lib/console/queries";
import { useCan } from "./console-context";

type Image = "logo" | "cover";

/** The school's logo and cover image: shown to everyone who sees the school, changed by its managers. */
export function SchoolBranding({ school }: { school: School }) {
  const t = useTranslations("console.branding");
  const tm = useTranslations("media");
  const canManage = useCan(P.schoolManage);
  const queryClient = useQueryClient();

  const refresh = async (updated: School) => {
    queryClient.setQueryData(queryKeys.school(school.id), updated);
    await queryClient.invalidateQueries({ queryKey: queryKeys.schoolsAll });
  };

  async function attach(image: Image, mediaId: string) {
    const api = getBrowserApi();
    const path = { params: { path: { schoolId: school.id } }, body: { mediaId } };
    const updated = unwrap(
      image === "logo"
        ? await api.PUT("/api/v1/platform/schools/{schoolId}/logo", path)
        : await api.PUT("/api/v1/platform/schools/{schoolId}/cover", path),
    );
    await refresh(updated);
    toast.success(tm("image.updated"));
  }

  async function clear(image: Image) {
    const api = getBrowserApi();
    const path = { params: { path: { schoolId: school.id } } };
    const updated = unwrap(
      image === "logo"
        ? await api.DELETE("/api/v1/platform/schools/{schoolId}/logo", path)
        : await api.DELETE("/api/v1/platform/schools/{schoolId}/cover", path),
    );
    await refresh(updated);
    toast.success(tm("image.removed"));
  }

  const labels = { choose: tm("image.choose"), change: tm("image.change"), remove: tm("image.remove") };

  return (
    <Card className="lg:col-span-2">
      <CardHeader>
        <CardTitle>{t("title")}</CardTitle>
      </CardHeader>
      <CardContent className="grid gap-6 md:grid-cols-2">
        <section className="flex flex-col gap-3" aria-labelledby="logo-heading">
          <div>
            <h3 id="logo-heading" className="font-semibold">
              {t("logo")}
            </h3>
            <CardDescription>{t("logoHint")}</CardDescription>
          </div>
          <MediaImage
            mediaId={school.logo?.id}
            variant="SMALL"
            width={112}
            height={112}
            alt={`${t("logo")}: ${school.name}`}
            className="size-28 rounded-2xl border bg-card object-contain"
            fallback={
              <span className="grid size-28 place-items-center rounded-2xl bg-muted text-muted-foreground">
                <SchoolIcon className="size-10" aria-hidden />
              </span>
            }
          />
          {canManage ? (
            <ImageUploader
              purpose="BRANDING"
              context={{ type: "SCHOOL", id: school.id }}
              hasImage={!!school.logo}
              onUploaded={(mediaId) => attach("logo", mediaId)}
              onRemove={() => clear("logo")}
              labels={labels}
            />
          ) : null}
        </section>

        <section className="flex flex-col gap-3" aria-labelledby="cover-heading">
          <div>
            <h3 id="cover-heading" className="font-semibold">
              {t("cover")}
            </h3>
            <CardDescription>{t("coverHint")}</CardDescription>
          </div>
          <MediaImage
            mediaId={school.cover?.id}
            variant="MEDIUM"
            width={448}
            height={160}
            alt={`${t("cover")}: ${school.name}`}
            className="h-40 w-full max-w-md rounded-2xl border object-cover"
            fallback={
              <span className="grid h-40 w-full max-w-md place-items-center rounded-2xl bg-muted text-sm text-muted-foreground">
                {tm("image.none")}
              </span>
            }
          />
          {canManage ? (
            <ImageUploader
              purpose="BRANDING"
              context={{ type: "SCHOOL", id: school.id }}
              hasImage={!!school.cover}
              onUploaded={(mediaId) => attach("cover", mediaId)}
              onRemove={() => clear("cover")}
              labels={labels}
            />
          ) : null}
        </section>
      </CardContent>
    </Card>
  );
}
