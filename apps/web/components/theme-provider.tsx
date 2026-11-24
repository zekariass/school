"use client";

import { createContext, useContext, useEffect, useMemo, useSyncExternalStore } from "react";
import { useLocalStorageValue } from "@/lib/use-local-storage";
import {
  isThemePreference,
  THEME_STORAGE_KEY,
  type ThemePreference,
} from "@/lib/theme";

interface ThemeContextValue {
  theme: ThemePreference;
  /** What is actually applied; "light" until mounted when preference is system. */
  resolvedTheme: "light" | "dark";
  setTheme: (theme: ThemePreference) => void;
}

const ThemeContext = createContext<ThemeContextValue | null>(null);

const DARK_QUERY = "(prefers-color-scheme: dark)";

function subscribeSystemDark(onChange: () => void) {
  const query = window.matchMedia(DARK_QUERY);
  query.addEventListener("change", onChange);
  return () => query.removeEventListener("change", onChange);
}

export function ThemeProvider({ children }: { children: React.ReactNode }) {
  const [stored, store] = useLocalStorageValue(THEME_STORAGE_KEY);
  const theme: ThemePreference = isThemePreference(stored) ? stored : "system";
  const systemDark = useSyncExternalStore(
    subscribeSystemDark,
    () => window.matchMedia(DARK_QUERY).matches,
    () => false,
  );
  const resolvedTheme = theme === "dark" || (theme === "system" && systemDark) ? "dark" : "light";

  // The inline script handles first paint; this keeps the class in sync after
  // changes (and re-applies it after React's dev-mode remount resets <html>).
  useEffect(() => {
    document.documentElement.classList.toggle("dark", resolvedTheme === "dark");
  }, [resolvedTheme]);

  const value = useMemo<ThemeContextValue>(
    () => ({ theme, resolvedTheme, setTheme: store }),
    [theme, resolvedTheme, store],
  );
  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}

export function useTheme(): ThemeContextValue {
  const ctx = useContext(ThemeContext);
  if (!ctx) throw new Error("useTheme must be used within ThemeProvider");
  return ctx;
}
