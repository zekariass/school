"use client";

import { createContext, useContext, useMemo } from "react";

export interface ConsoleSession {
  /** Permission codes held at any scope (the API still checks each call). */
  permissions: ReadonlySet<string>;
}

const ConsoleContext = createContext<ConsoleSession | null>(null);

export function ConsoleProvider({
  permissions,
  children,
}: {
  permissions: string[];
  children: React.ReactNode;
}) {
  const value = useMemo(() => ({ permissions: new Set(permissions) }), [permissions]);
  return <ConsoleContext.Provider value={value}>{children}</ConsoleContext.Provider>;
}

export function useConsole(): ConsoleSession {
  const value = useContext(ConsoleContext);
  if (!value) throw new Error("useConsole needs a ConsoleProvider");
  return value;
}

/** True when the user holds `permission` at some scope. */
export function useCan(permission: string): boolean {
  return useConsole().permissions.has(permission);
}
