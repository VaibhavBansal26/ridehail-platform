"use client";

import { useSearchParams } from "next/navigation";
import { type FormEvent, useState } from "react";
import { Button, ErrorBox } from "@/components/ui";
import { CSRF_HEADER, CSRF_VALUE, safeNextPath } from "@/lib/session";

export function LoginForm() {
  const params = useSearchParams();
  const [phone, setPhone] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const res = await fetch("/api/session/login", {
        method: "POST",
        credentials: "same-origin",
        headers: { "Content-Type": "application/json", [CSRF_HEADER]: CSRF_VALUE },
        body: JSON.stringify({ phone, password }),
      });
      if (!res.ok) {
        const body = (await res.json().catch(() => ({}))) as { detail?: string };
        setError(body.detail ?? "Could not sign in");
        setBusy(false);
        return;
      }
      // a full navigation, so the next page is rendered with the new cookies
      window.location.assign(safeNextPath(params.get("next")));
    } catch {
      setError("Cannot reach the admin server");
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} className="mt-6 space-y-4" noValidate>
      <label className="block text-sm font-medium">
        Phone or username
        <input
          name="phone" autoComplete="username" required value={phone} onChange={(e) => setPhone(e.target.value)}
          className="mt-1 block w-full rounded-md border border-line bg-surface px-3 py-2 text-sm"
        />
      </label>
      <label className="block text-sm font-medium">
        Password
        <input
          name="password" type="password" autoComplete="current-password" required value={password} onChange={(e) => setPassword(e.target.value)}
          className="mt-1 block w-full rounded-md border border-line bg-surface px-3 py-2 text-sm"
        />
      </label>
      {error ? <ErrorBox error={new Error(error)} /> : null}
      <Button type="submit" variant="primary" busy={busy} className="w-full" disabled={!phone || !password}>Sign in</Button>
    </form>
  );
}
