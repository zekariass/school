"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import type { CodeSheet } from "@/lib/school/codes";

interface IssuedCodes {
  /** The last sheet issued in this tab, or null. In memory only. */
  sheet: CodeSheet | null;
  put: (sheet: CodeSheet) => void;
  clear: () => void;
}

const Context = createContext<IssuedCodes | null>(null);

/**
 * Holds the plaintext login codes a teacher just generated, for the print page.
 *
 * The codes are shown once, so they live only in this component's React state: never
 * in a URL, localStorage/sessionStorage, the TanStack Query cache or a cookie. The
 * provider sits in the school workspace layout, so a client-side navigation to the
 * print page keeps them, while a reload, a new tab or leaving the workspace drops them.
 * They are also dropped when the tab is hidden for good (`pagehide`).
 */
export function IssuedCodesProvider({ children }: { children: React.ReactNode }) {
  const [sheet, setSheet] = useState<CodeSheet | null>(null);
  const put = useCallback((next: CodeSheet) => setSheet(next), []);
  const clear = useCallback(() => setSheet(null), []);

  useEffect(() => {
    const drop = () => setSheet(null);
    window.addEventListener("pagehide", drop);
    return () => window.removeEventListener("pagehide", drop);
  }, []);

  const value = useMemo(() => ({ sheet, put, clear }), [sheet, put, clear]);
  return <Context.Provider value={value}>{children}</Context.Provider>;
}

export function useIssuedCodes(): IssuedCodes {
  const value = useContext(Context);
  if (!value) throw new Error("useIssuedCodes needs an IssuedCodesProvider");
  return value;
}
