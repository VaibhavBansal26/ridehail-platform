"use client";

import { type FormEvent, useState } from "react";
import { Button, Field, inputClass } from "@/components/ui";
import type { Fix } from "@/lib/position";

/** Set the position by hand. Shown when the browser cannot supply one, and always available behind a disclosure. */
export function ManualPosition({ value, onChange, open = false }: { value: Fix | null; onChange: (fix: Fix | null) => void; open?: boolean }) {
  const [error, setError] = useState<string | null>(null);

  function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    const lat = Number(f.get("lat")), lon = Number(f.get("lon"));
    if (!Number.isFinite(lat) || !Number.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) {
      setError("Enter a latitude between -90 and 90 and a longitude between -180 and 180.");
      return;
    }
    setError(null);
    onChange({ lat, lon });
  }

  return (
    <details open={open || value !== null} className="rounded-lg border border-line px-3 py-2 text-sm">
      <summary className="min-h-9 cursor-pointer py-1.5 font-medium">{value ? "Using a position you set" : "Set position by hand"}</summary>
      <form onSubmit={submit} className="mt-2 space-y-3 pb-1">
        <div className="grid grid-cols-2 gap-2">
          <Field label="Latitude">
            <input name="lat" inputMode="decimal" defaultValue={value?.lat ?? 12.9716} className={inputClass} />
          </Field>
          <Field label="Longitude">
            <input name="lon" inputMode="decimal" defaultValue={value?.lon ?? 77.5946} className={inputClass} />
          </Field>
        </div>
        {error ? <p role="alert" className="text-xs text-[var(--bad-fg)]">{error}</p> : null}
        <div className="flex gap-2">
          <Button type="submit" variant="secondary" className="flex-1">Use this position</Button>
          {value ? (
            <Button type="button" variant="secondary" onClick={() => onChange(null)}>Use GPS</Button>
          ) : null}
        </div>
      </form>
    </details>
  );
}
