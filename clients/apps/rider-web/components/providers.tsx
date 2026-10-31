"use client";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { type ReactNode, useState } from "react";
import { NoticeProvider } from "@/components/notices";

export function Providers({ children }: { children: ReactNode }) {
  const [client] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            staleTime: 2_000,
            // a 401 means the session ended and the page is already on its way to /login; do not hammer the API meanwhile
            retry: (count, error) => count < 2 && !(error as { status?: number }).status,
            refetchOnWindowFocus: true,
          },
        },
      }),
  );
  return (
    <QueryClientProvider client={client}>
      <NoticeProvider>{children}</NoticeProvider>
    </QueryClientProvider>
  );
}
