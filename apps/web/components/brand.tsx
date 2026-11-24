import { GraduationCapIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { Link } from "@/i18n/navigation";

export function Brand() {
  const t = useTranslations();
  return (
    <Link
      href="/"
      className="flex min-h-11 items-center gap-2 rounded-xl px-1 text-lg font-extrabold tracking-tight"
    >
      <span className="grid size-9 place-items-center rounded-xl bg-primary text-primary-foreground">
        <GraduationCapIcon className="size-5" aria-hidden />
      </span>
      {t("app.name")}
    </Link>
  );
}
