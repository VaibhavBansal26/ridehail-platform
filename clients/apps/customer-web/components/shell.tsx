"use client";

import { Car, CreditCard, LifeBuoy, LogOut } from "lucide-react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import type { ReactNode } from "react";
import { useConnection } from "@/components/realtime";
import { useNotices } from "@/components/notices";
import { Badge } from "@/components/ui";
import { sessionCall } from "@/lib/api";
import { useCustomer, useSupport } from "@/lib/queries";

const NAV = [
  { href: "/", label: "Ride", icon: Car },
  { href: "/account", label: "Account", icon: CreditCard },
  { href: "/support", label: "Support", icon: LifeBuoy },
] as const;

export function Shell({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const customer = useCustomer();
  const supportUnread = (useSupport().data?.unread ?? 0) > 0;
  const connection = useConnection();
  const { notice, clear } = useNotices();

  async function signOut() {
    await sessionCall("/api/session/logout");
    // eslint-disable-next-line @next/next/no-location-assign-relative-destination
    window.location.assign("/login");
  }

  return (
    <div className="mx-auto flex min-h-dvh max-w-md flex-col">
      <header className="flex items-center justify-between gap-2 px-4 pt-4 pb-2">
        <div className="min-w-0">
          <div className="truncate text-base font-semibold">{customer.data?.name ?? "Ridehail"}</div>
          <div className="mt-0.5 flex items-center gap-1.5">
            {connection === "reconnecting" ? <Badge tone="warn">Reconnecting…</Badge> : null}
          </div>
        </div>
        <button onClick={signOut} className="inline-flex min-h-11 items-center gap-1.5 rounded-lg px-3 text-sm text-muted hover:bg-surface-2">
          <LogOut className="size-4" aria-hidden /> Sign out
        </button>
      </header>

      {notice ? (
        <div role="status" className="mx-4 mt-2 flex items-start justify-between gap-3 rounded-lg bg-[var(--info-bg)] px-3 py-2 text-sm text-[var(--info-fg)] data-[tone=bad]:bg-[var(--bad-bg)] data-[tone=bad]:text-[var(--bad-fg)] data-[tone=ok]:bg-[var(--ok-bg)] data-[tone=ok]:text-[var(--ok-fg)]" data-tone={notice.tone}>
          <span>{notice.text}</span>
          <button onClick={clear} aria-label="Dismiss" className="font-medium">✕</button>
        </div>
      ) : null}

      <main className="flex-1 space-y-4 px-4 py-4">{children}</main>

      <nav className="sticky bottom-0 grid grid-cols-3 border-t border-line bg-surface">
        {NAV.map(({ href, label, icon: Icon }) => {
          const active = href === "/" ? pathname === "/" : pathname.startsWith(href);
          return (
            <Link key={href} href={href} aria-current={active ? "page" : undefined} className={`flex min-h-14 flex-col items-center justify-center gap-0.5 text-xs font-medium ${active ? "text-accent" : "text-muted"}`}>
              <span className="relative">
                <Icon className="size-5" aria-hidden />
                {href === "/support" && supportUnread ? <span className="absolute -top-1 -right-1.5 size-2 rounded-full bg-[var(--bad-fg)]" aria-label="new reply" /> : null}
              </span>
              {label}
            </Link>
          );
        })}
      </nav>
    </div>
  );
}
