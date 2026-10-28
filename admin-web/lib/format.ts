export function money(amount: number | null | undefined, currency = "USD"): string {
  if (amount === null || amount === undefined) return "—";
  try {
    return new Intl.NumberFormat("en-US", { style: "currency", currency }).format(amount);
  } catch {
    return `${amount.toFixed(2)} ${currency}`;
  }
}

export function dateTime(iso: string | null | undefined): string {
  if (!iso) return "—";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "—";
  return new Intl.DateTimeFormat("en-GB", { day: "2-digit", month: "short", hour: "2-digit", minute: "2-digit" }).format(d);
}

const UNITS: [number, string][] = [
  [86_400, "d"],
  [3_600, "h"],
  [60, "m"],
];

/** "5m ago" / "in 2d". `now` is injectable so tests (and a ticking clock) stay deterministic. */
export function relative(iso: string | null | undefined, now: number = Date.now()): string {
  if (!iso) return "—";
  const t = new Date(iso).getTime();
  if (Number.isNaN(t)) return "—";
  const diff = Math.round((t - now) / 1000);
  const abs = Math.abs(diff);
  if (abs < 45) return diff >= 0 ? "in a moment" : "just now";
  for (const [size, unit] of UNITS) {
    if (abs >= size) {
      const n = Math.round(abs / size);
      return diff >= 0 ? `in ${n}${unit}` : `${n}${unit} ago`;
    }
  }
  return "just now";
}

/** How long until a deadline, for evidence due dates. Past deadlines say so loudly. */
export function deadline(iso: string | null | undefined, now: number = Date.now()): { label: string; overdue: boolean; soon: boolean } {
  if (!iso) return { label: "No deadline", overdue: false, soon: false };
  const ms = new Date(iso).getTime() - now;
  if (Number.isNaN(ms)) return { label: "No deadline", overdue: false, soon: false };
  if (ms < 0) return { label: `Overdue by ${relative(iso, now).replace(" ago", "")}`, overdue: true, soon: false };
  return { label: `Due ${relative(iso, now)}`, overdue: false, soon: ms < 2 * 86_400_000 };
}

export function shortId(id: string | null | undefined): string {
  return id ? id.slice(0, 8) : "—";
}

export function titleCase(s: string): string {
  return s.toLowerCase().split("_").map((w) => w.charAt(0).toUpperCase() + w.slice(1)).join(" ");
}

/** Only https links to the payment provider's dashboard are offered as links; anything else from the API is shown as text. */
export function safeProviderUrl(url: string | null | undefined): string | null {
  if (!url) return null;
  try {
    const u = new URL(url);
    return u.protocol === "https:" && (u.hostname === "dashboard.stripe.com" || u.hostname.endsWith(".stripe.com")) ? u.toString() : null;
  } catch {
    return null;
  }
}
