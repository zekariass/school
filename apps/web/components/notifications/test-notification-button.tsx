"use client";

import { BellRingIcon, Loader2Icon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { getBrowserApi } from "@/lib/api-client";
import { unwrap } from "@/lib/api-error";
import { errorMessage } from "@/lib/console/errors";

/** Platform console: sends the signed-in administrator a notification (inbox, live, push). */
export function TestNotificationButton() {
  const t = useTranslations("notifications");
  const [pending, setPending] = useState(false);

  async function send() {
    setPending(true);
    try {
      unwrap(await getBrowserApi().POST("/api/v1/notifications/test"));
      // the notification itself arrives through the live connection
      toast.success(t("test.sent"));
    } catch (error) {
      toast.error(errorMessage(error, t("test.failed")));
    } finally {
      setPending(false);
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("test.button")}</CardTitle>
        <CardDescription>{t("test.hint")}</CardDescription>
      </CardHeader>
      <CardContent>
        <Button onClick={() => void send()} disabled={pending} className="gap-2">
          {pending ? <Loader2Icon className="size-4 animate-spin" aria-hidden /> : <BellRingIcon className="size-4" aria-hidden />}
          {t("test.button")}
        </Button>
      </CardContent>
    </Card>
  );
}
