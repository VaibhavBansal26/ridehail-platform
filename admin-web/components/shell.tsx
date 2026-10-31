"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { type ReactNode, useState } from "react";
import { BadgeCheck, BookOpen, Car, History, UserCog, CreditCard, Gavel, LayoutDashboard, LogOut, Map, Menu, ShieldAlert, Users, X } from "lucide-react";
import { Badge } from "@/components/ui";
import { useAttention, useVerificationQueue } from "@/lib/queries";
import { CSRF_HEADER, CSRF_VALUE } from "@/lib/session";

type Item = { href: string; label: string; icon: ReactNode; badge?: "disputes" | "incidents" | "payments" | "verification" };

const NAV: Item[] = [
  { href: "/", label: "Overview", icon: <LayoutDashboard className="size-4" aria-hidden /> },
  { href: "/map", label: "Live map", icon: <Map className="size-4" aria-hidden /> },
  { href: "/trips", label: "Trips", icon: <Car className="size-4" aria-hidden /> },
  { href: "/riders", label: "Riders", icon: <Users className="size-4" aria-hidden /> },
  { href: "/verification", label: "Verification", icon: <BadgeCheck className="size-4" aria-hidden />, badge: "verification" },
  { href: "/payments", label: "Payments", icon: <CreditCard className="size-4" aria-hidden />, badge: "payments" },
  { href: "/disputes", label: "Disputes", icon: <Gavel className="size-4" aria-hidden />, badge: "disputes" },
  { href: "/incidents", label: "Incidents", icon: <ShieldAlert className="size-4" aria-hidden />, badge: "incidents" },
  { href: "/ledger", label: "Ledger", icon: <BookOpen className="size-4" aria-hidden /> },
  { href: "/audit", label: "Audit log", icon: <History className="size-4" aria-hidden /> },
  { href: "/admins", label: "Admins", icon: <UserCog className="size-4" aria-hidden /> },
];

export function Shell({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const [open, setOpen] = useState(false);
  const attention = useAttention();
  const a = attention.data;
  // riders waiting to be reviewed: someone who cannot start earning until a person looks
  const pending = useVerificationQueue("PENDING_REVIEW", 0).data?.total ?? 0;

  const count = (b?: Item["badge"]) => {
    if (b === "verification") return pending;
    if (!a || !b) return 0;
    if (b === "disputes") return a.disputes.open + a.disputes.inquiries;
    if (b === "incidents") return a.incidents.open;
    return a.paymentsRequiringAttention + a.payoutsRequiringAttention;
  };

  async function signOut() {
    await fetch("/api/session/logout", { method: "POST", credentials: "same-origin", headers: { [CSRF_HEADER]: CSRF_VALUE } });
    // a full page load on purpose: drops every cached query along with the session
    // eslint-disable-next-line @next/next/no-location-assign-relative-destination
    window.location.assign("/login");
  }

  const nav = (
    <nav aria-label="Main" className="flex flex-col gap-0.5 p-3">
      {NAV.map((item) => {
        const active = item.href === "/" ? pathname === "/" : pathname.startsWith(item.href);
        const n = count(item.badge);
        return (
          <Link
            key={item.href} href={item.href} onClick={() => setOpen(false)} aria-current={active ? "page" : undefined}
            className={`flex items-center gap-2.5 rounded-md px-3 py-2 text-sm ${active ? "bg-surface-2 font-medium" : "text-muted hover:bg-surface-2 hover:text-ink"}`}
          >
            {item.icon}
            <span className="flex-1">{item.label}</span>
            {n > 0 ? <Badge tone="bad"><span aria-label={`${n} need attention`}>{n}</span></Badge> : null}
          </Link>
        );
      })}
    </nav>
  );

  return (
    <div className="min-h-dvh md:grid md:grid-cols-[14rem_1fr]">
      <aside className="hidden border-r border-line bg-surface md:block">
        <div className="px-5 py-4 text-sm font-semibold tracking-tight">Ridehail Ops</div>
        {nav}
        <div className="p-3">
          <button onClick={signOut} className="flex w-full items-center gap-2.5 rounded-md px-3 py-2 text-sm text-muted hover:bg-surface-2 hover:text-ink">
            <LogOut className="size-4" aria-hidden /> Sign out
          </button>
        </div>
      </aside>

      <div className="flex min-w-0 flex-col">
        <header className="flex items-center justify-between border-b border-line bg-surface px-4 py-3 md:hidden">
          <span className="text-sm font-semibold">Ridehail Ops</span>
          <button onClick={() => setOpen(!open)} aria-expanded={open} aria-label={open ? "Close menu" : "Open menu"} className="rounded-md p-1.5 hover:bg-surface-2">
            {open ? <X className="size-5" /> : <Menu className="size-5" />}
          </button>
        </header>
        {open ? (
          <div className="border-b border-line bg-surface md:hidden">
            {nav}
            <div className="p-3 pt-0">
              <button onClick={signOut} className="flex w-full items-center gap-2.5 rounded-md px-3 py-2 text-sm text-muted">
                <LogOut className="size-4" aria-hidden /> Sign out
              </button>
            </div>
          </div>
        ) : null}
        {a?.needsAction ? (
          <Link href="/" className="flex items-center gap-2 border-b border-line bg-[var(--bad-bg)] px-4 py-2 text-sm text-[var(--bad-fg)]">
            <ShieldAlert className="size-4" aria-hidden />
            Something needs your attention
            {a.disputes.overdue > 0 ? ` — ${a.disputes.overdue} dispute${a.disputes.overdue > 1 ? "s" : ""} past deadline` : ""}
          </Link>
        ) : null}
        <main className="min-w-0 flex-1 p-4 md:p-6">{children}</main>
      </div>
    </div>
  );
}
