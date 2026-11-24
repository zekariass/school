"use client";

import { isServer, QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { CalendarPreferenceProvider } from "@/components/calendar-preference";
import { ThemeProvider } from "@/components/theme-provider";
import { Toaster } from "@/components/ui/sonner";
import { shouldRetry } from "@/lib/api-error";

function makeQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 60_000,
        retry: shouldRetry,
      },
    },
  });
}

let browserQueryClient: QueryClient | undefined;

/** New client per server request, one shared client in the browser. */
function getQueryClient() {
  if (isServer) return makeQueryClient();
  return (browserQueryClient ??= makeQueryClient());
}

export function Providers({ children }: { children: React.ReactNode }) {
  const queryClient = getQueryClient();
  return (
    <ThemeProvider>
      <CalendarPreferenceProvider>
        <QueryClientProvider client={queryClient}>
          {children}
          <Toaster />
        </QueryClientProvider>
      </CalendarPreferenceProvider>
    </ThemeProvider>
  );
}
