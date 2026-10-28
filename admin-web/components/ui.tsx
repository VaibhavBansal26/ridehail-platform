"use client";

import Link from "next/link";
import { type ButtonHTMLAttributes, type ReactNode, useEffect, useId, useRef, useState } from "react";
import { AlertTriangle, Loader2 } from "lucide-react";
import { titleCase } from "@/lib/format";

export type Tone = "ok" | "warn" | "bad" | "info" | "neutral";

const TONE: Record<Tone, string> = {
  ok: "bg-[var(--ok-bg)] text-[var(--ok-fg)]",
  warn: "bg-[var(--warn-bg)] text-[var(--warn-fg)]",
  bad: "bg-[var(--bad-bg)] text-[var(--bad-fg)]",
  info: "bg-[var(--info-bg)] text-[var(--info-fg)]",
  neutral: "bg-[var(--neutral-bg)] text-[var(--neutral-fg)]",
};

export function Badge({ tone = "neutral", children }: { tone?: Tone; children: ReactNode }) {
  return <span className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium whitespace-nowrap ${TONE[tone]}`}>{children}</span>;
}

const STATUS_TONE: Record<string, Tone> = {
  // trips
  REQUESTED: "info", ACCEPTED: "info", ARRIVED: "info", IN_PROGRESS: "info", COMPLETED: "ok", CANCELLED: "neutral", NO_RIDERS_AVAILABLE: "warn",
  // riders
  OFFLINE: "neutral", AVAILABLE: "ok", ON_TRIP: "info",
  // payments / payouts
  PENDING: "warn", CAPTURED: "ok", DISPUTED: "bad", CHARGED_BACK: "bad", REQUIRES_ATTENTION: "bad", REFUNDED: "neutral",
  AWAITING_FUNDS: "warn", PAID: "ok", FAILED: "bad",
  // disputes / incidents / offers
  INQUIRY: "warn", OPEN: "bad", WON: "ok", LOST: "bad", CLOSED: "neutral", RESOLVED: "ok", DECLINED: "neutral", EXPIRED: "neutral",
};

export function StatusBadge({ status }: { status: string }) {
  return <Badge tone={STATUS_TONE[status] ?? "neutral"}>{titleCase(status)}</Badge>;
}

export function PageHeader({ title, subtitle, actions }: { title: string; subtitle?: ReactNode; actions?: ReactNode }) {
  return (
    <div className="mb-5 flex flex-wrap items-end justify-between gap-3">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">{title}</h1>
        {subtitle ? <p className="mt-0.5 text-sm text-muted">{subtitle}</p> : null}
      </div>
      {actions ? <div className="flex items-center gap-2">{actions}</div> : null}
    </div>
  );
}

export function Card({ title, children, className = "", action }: { title?: string; children: ReactNode; className?: string; action?: ReactNode }) {
  return (
    <section className={`rounded-lg border border-line bg-surface ${className}`}>
      {title ? (
        <header className="flex items-center justify-between border-b border-line px-4 py-2.5">
          <h2 className="text-sm font-medium">{title}</h2>
          {action}
        </header>
      ) : null}
      <div className="p-4">{children}</div>
    </section>
  );
}

type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & { variant?: "primary" | "secondary" | "danger"; busy?: boolean };

export function Button({ variant = "secondary", busy, children, className = "", disabled, ...rest }: ButtonProps) {
  const styles = {
    primary: "bg-accent text-accent-ink hover:opacity-90",
    secondary: "border border-line bg-surface hover:bg-surface-2",
    danger: "bg-[var(--bad-fg)] text-[var(--bad-bg)] hover:opacity-90",
  }[variant];
  return (
    <button
      {...rest}
      disabled={disabled || busy}
      className={`inline-flex items-center justify-center gap-1.5 rounded-md px-3 py-1.5 text-sm font-medium transition disabled:cursor-not-allowed disabled:opacity-50 ${styles} ${className}`}
    >
      {busy ? <Loader2 className="size-4 animate-spin" aria-hidden /> : null}
      {children}
    </button>
  );
}

export function Select({ label, value, onChange, options }: { label: string; value: string; onChange: (v: string) => void; options: { value: string; label: string }[] }) {
  const id = useId();
  return (
    <label htmlFor={id} className="flex items-center gap-2 text-sm text-muted">
      {label}
      <select id={id} value={value} onChange={(e) => onChange(e.target.value)} className="rounded-md border border-line bg-surface px-2 py-1.5 text-sm text-ink">
        {options.map((o) => (
          <option key={o.value} value={o.value}>{o.label}</option>
        ))}
      </select>
    </label>
  );
}

export function ErrorBox({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const message = error instanceof Error ? error.message : "Something went wrong";
  return (
    <div role="alert" className="flex items-start gap-3 rounded-lg border border-line bg-[var(--bad-bg)] p-4 text-sm text-[var(--bad-fg)]">
      <AlertTriangle className="mt-0.5 size-4 shrink-0" aria-hidden />
      <div className="flex-1">{message}</div>
      {onRetry ? <button onClick={onRetry} className="font-medium underline">Retry</button> : null}
    </div>
  );
}

export function Empty({ children }: { children: ReactNode }) {
  return <div className="rounded-lg border border-dashed border-line px-4 py-10 text-center text-sm text-muted">{children}</div>;
}

export function Loading({ label = "Loading" }: { label?: string }) {
  return (
    <div role="status" className="flex items-center gap-2 py-10 text-sm text-muted">
      <Loader2 className="size-4 animate-spin" aria-hidden /> {label}…
    </div>
  );
}

export function Pager({ page, size, total, onPage }: { page: number; size: number; total: number; onPage: (p: number) => void }) {
  const pages = Math.max(1, Math.ceil(total / size));
  const from = total === 0 ? 0 : page * size + 1;
  const to = Math.min(total, (page + 1) * size);
  return (
    <div className="flex items-center justify-between pt-3 text-sm text-muted">
      <span>{from}–{to} of {total}</span>
      <div className="flex items-center gap-2">
        <Button disabled={page <= 0} onClick={() => onPage(page - 1)} aria-label="Previous page">Previous</Button>
        <span>Page {page + 1} of {pages}</span>
        <Button disabled={page + 1 >= pages} onClick={() => onPage(page + 1)} aria-label="Next page">Next</Button>
      </div>
    </div>
  );
}

export function DataTable({ head, children, caption }: { head: string[]; children: ReactNode; caption: string }) {
  return (
    <div className="overflow-x-auto rounded-lg border border-line bg-surface">
      <table className="w-full min-w-[640px] text-left text-sm">
        <caption className="sr-only">{caption}</caption>
        <thead className="border-b border-line bg-surface-2 text-xs uppercase tracking-wide text-muted">
          <tr>
            {head.map((h) => (
              <th key={h} scope="col" className="px-3 py-2 font-medium">{h}</th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-line">{children}</tbody>
      </table>
    </div>
  );
}

export function Td({ children, className = "", title }: { children: ReactNode; className?: string; title?: string }) {
  return <td title={title} className={`px-3 py-2.5 align-middle ${className}`}>{children}</td>;
}

export function IdLink({ href, id }: { href: string; id: string }) {
  return <Link href={href} className="font-mono text-xs text-accent hover:underline">{id.slice(0, 8)}</Link>;
}

/** Re-renders every `ms` so "5m ago" labels do not go stale on a page left open. */
export function useNow(ms = 30_000): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), ms);
    return () => clearInterval(t);
  }, [ms]);
  return now;
}

/** A small modal that asks before something with consequences happens. Escape or Cancel closes it; focus is trapped inside. */
export function ConfirmDialog({
  open, title, children, confirmLabel, danger, busy, error, onConfirm, onCancel, confirmDisabled,
}: {
  open: boolean; title: string; children: ReactNode; confirmLabel: string; danger?: boolean; busy?: boolean; error?: unknown;
  onConfirm: () => void; onCancel: () => void; confirmDisabled?: boolean;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const d = ref.current;
    if (!d) return;
    if (open && !d.open) d.showModal();
    if (!open && d.open) d.close();
  }, [open]);
  return (
    <dialog
      ref={ref}
      onCancel={(e) => { e.preventDefault(); if (!busy) onCancel(); }}
      className="m-auto w-[min(92vw,28rem)] rounded-lg border border-line bg-surface p-0 text-ink backdrop:bg-black/50"
    >
      <div className="p-5">
        <h2 className="text-base font-semibold">{title}</h2>
        <div className="mt-2 text-sm text-muted">{children}</div>
        {error ? <div className="mt-3"><ErrorBox error={error} /></div> : null}
        <div className="mt-5 flex justify-end gap-2">
          <Button onClick={onCancel} disabled={busy}>Cancel</Button>
          <Button variant={danger ? "danger" : "primary"} onClick={onConfirm} busy={busy} disabled={confirmDisabled}>{confirmLabel}</Button>
        </div>
      </div>
    </dialog>
  );
}
