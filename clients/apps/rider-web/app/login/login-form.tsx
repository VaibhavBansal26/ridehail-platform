"use client";

import { useSearchParams } from "next/navigation";
import { type FormEvent, useState } from "react";
import { Button, ErrorNote, Field, inputClass } from "@/components/ui";
import { sessionCall } from "@/lib/api";
import { safeNextPath } from "@/lib/session";

const VEHICLES = [
  ["BIKE", "Bike"],
  ["AUTO", "Auto"],
  ["SEDAN", "Sedan"],
  ["SUV", "SUV"],
] as const;

export function LoginForm() {
  const next = safeNextPath(useSearchParams().get("next"));
  const [mode, setMode] = useState<"signin" | "register">("signin");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    setBusy(true);
    setError(null);
    const result =
      mode === "signin"
        ? await sessionCall("/api/session/login", { phone: f.get("phone"), password: f.get("password") })
        : await sessionCall("/api/session/register", {
            name: f.get("name"),
            phone: f.get("phone"),
            password: f.get("password"),
            vehicleType: f.get("vehicleType"),
          });
    if (result.ok) {
      // a full page load: the cookies are new, and every cached query should start clean
      window.location.assign(next);
      return;
    }
    setError(result.detail ?? "Something went wrong");
    setBusy(false);
  }

  return (
    <form onSubmit={submit} className="space-y-4 rounded-xl border border-line bg-surface p-4">
      <div role="tablist" className="grid grid-cols-2 gap-1 rounded-lg bg-surface-2 p-1 text-sm">
        {(["signin", "register"] as const).map((m) => (
          <button
            key={m}
            type="button"
            role="tab"
            aria-selected={mode === m}
            onClick={() => {
              setMode(m);
              setError(null);
            }}
            className={`min-h-9 rounded-md font-medium ${mode === m ? "bg-surface shadow-sm" : "text-muted"}`}
          >
            {m === "signin" ? "Sign in" : "Create account"}
          </button>
        ))}
      </div>

      {mode === "register" ? (
        <Field label="Your name">
          <input name="name" autoComplete="name" required className={inputClass} />
        </Field>
      ) : null}
      <Field label="Phone number">
        <input name="phone" type="tel" autoComplete="tel" required className={inputClass} />
      </Field>
      <Field label="Password" hint={mode === "register" ? "8 to 72 characters" : undefined}>
        <input name="password" type="password" autoComplete={mode === "signin" ? "current-password" : "new-password"} required minLength={mode === "register" ? 8 : undefined} className={inputClass} />
      </Field>
      {mode === "register" ? (
        <Field label="Vehicle">
          <select name="vehicleType" defaultValue="SEDAN" className={inputClass}>
            {VEHICLES.map(([v, label]) => (
              <option key={v} value={v}>
                {label}
              </option>
            ))}
          </select>
        </Field>
      ) : null}

      {error ? <ErrorNote>{error}</ErrorNote> : null}
      <Button type="submit" busy={busy} className="w-full">
        {mode === "signin" ? "Sign in" : "Create account"}
      </Button>
    </form>
  );
}
