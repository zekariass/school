import {
  AwardIcon,
  BackpackIcon,
  BookOpenIcon,
  CalendarCheckIcon,
  CompassIcon,
  HandHelpingIcon,
  HandIcon,
  HeartIcon,
  HouseIcon,
  LightbulbIcon,
  MessageSquareXIcon,
  MountainIcon,
  PencilIcon,
  SmileIcon,
  SproutIcon,
  StarIcon,
  TargetIcon,
  TrophyIcon,
  UsersIcon,
  Volume2Icon,
  type LucideIcon,
} from "lucide-react";
import { resolveIcon, type IconName } from "@/lib/points/icons";
import { cn } from "@/lib/utils";

const ICONS: Record<IconName, LucideIcon> = {
  star: StarIcon,
  "hand-helping": HandHelpingIcon,
  "hand-raised": HandIcon,
  users: UsersIcon,
  mountain: MountainIcon,
  target: TargetIcon,
  heart: HeartIcon,
  "book-open": BookOpenIcon,
  lightbulb: LightbulbIcon,
  smile: SmileIcon,
  trophy: TrophyIcon,
  sprout: SproutIcon,
  award: AwardIcon,
  pencil: PencilIcon,
  "calendar-check": CalendarCheckIcon,
  house: HouseIcon,
  backpack: BackpackIcon,
  compass: CompassIcon,
  "message-x": MessageSquareXIcon,
  "volume-high": Volume2Icon,
};

/** A skill's or badge's icon: our drawing for a known name, an emoji as it is, else the fallback. Decorative. */
export function CategoryIcon({
  icon,
  fallback = "star",
  className,
}: {
  icon: string | null | undefined;
  fallback?: IconName;
  className?: string;
}) {
  const resolved = resolveIcon(icon, fallback);
  if (resolved.kind === "emoji") {
    return (
      <span aria-hidden className={cn("leading-none", className)}>
        {resolved.text}
      </span>
    );
  }
  const Icon = ICONS[resolved.name];
  return <Icon aria-hidden className={cn("size-6", className)} />;
}
