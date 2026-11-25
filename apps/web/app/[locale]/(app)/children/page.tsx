import { redirect } from "next/navigation";
import { getLocale } from "next-intl/server";
import { Suspense } from "react";

/** The notification path `/children` (a guardian's "your child got points"): their children's page. */
export default function ChildrenPage() {
  return (
    <Suspense fallback={null}>
      <ToFamily />
    </Suspense>
  );
}

async function ToFamily(): Promise<null> {
  redirect(`/${await getLocale()}/family`);
}

