"use client";

import { isServer, QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { PlatformPlaceProvider } from "@/components/display-place";
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
      <QueryClientProvider client={queryClient}>
        {/* Public pages show the platform default country's zone and calendars; signed-in layouts replace them. */}
        <PlatformPlaceProvider>
          {children}
          <Toaster />
        </PlatformPlaceProvider>
      </QueryClientProvider>
    </ThemeProvider>
  );
}
