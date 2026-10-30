"use client";

import { type FormEvent, useState } from "react";
import { Badge, Button, Card, ConfirmDialog, DataTable, ErrorBox, Loading, PageHeader, Td } from "@/components/ui";
import { dateTime } from "@/lib/format";
import { useAdmins, useCreateAdmin, useSetAdminEnabled } from "@/lib/queries";
import type { AdminAccount } from "@/lib/schemas";

const MIN_PASSWORD = 12;

export default function AdminsPage() {
  const admins = useAdmins();
  const create = useCreateAdmin();
  const toggle = useSetAdminEnabled();
  const [phone, setPhone] = useState("");
  const [password, setPassword] = useState("");
  const [target, setTarget] = useState<AdminAccount | null>(null);

  function submit(e: FormEvent) {
    e.preventDefault();
    create.mutate({ phone: phone.trim(), password }, { onSuccess: () => { setPhone(""); setPassword(""); } });
  }

  return (
    <>
      <PageHeader title="Admins" subtitle="Give each person their own account so the audit log says who did what. Share nothing." />
      {admins.isPending ? <Loading /> : admins.isError ? <ErrorBox error={admins.error} onRetry={() => admins.refetch()} /> : (
        <DataTable caption="Admin accounts" head={["Login name", "Status", "Created", ""]}>
          {admins.data.map((a) => (
            <tr key={a.id}>
              <Td className="font-medium">{a.phone}{a.you ? <span className="ml-2 text-xs text-muted">(you)</span> : null}</Td>
              <Td><Badge tone={a.enabled ? "ok" : "neutral"}>{a.enabled ? "Active" : "Disabled"}</Badge></Td>
              <Td>{dateTime(a.createdAt)}</Td>
              <Td className="text-right">{a.you ? null : <Button onClick={() => setTarget(a)}>{a.enabled ? "Disable" : "Enable"}</Button>}</Td>
            </tr>
          ))}
        </DataTable>
      )}

      <Card title="Add an admin" className="mt-6 max-w-lg">
        <form onSubmit={submit} className="space-y-3" noValidate>
          <label className="block text-sm font-medium">Login name
            <input value={phone} onChange={(e) => setPhone(e.target.value)} autoComplete="off" className="mt-1 block w-full rounded-md border border-line bg-surface px-3 py-2 text-sm" />
          </label>
          <label className="block text-sm font-medium">Password
            <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="new-password" className="mt-1 block w-full rounded-md border border-line bg-surface px-3 py-2 text-sm" />
            <span className="text-xs font-normal text-muted">At least {MIN_PASSWORD} characters. Tell them to keep it private; it cannot be shown again.</span>
          </label>
          {create.isError ? <ErrorBox error={create.error} /> : null}
          <Button type="submit" variant="primary" busy={create.isPending} disabled={!phone.trim() || password.length < MIN_PASSWORD}>Create admin</Button>
        </form>
      </Card>

      <ConfirmDialog
        open={target !== null} title={target?.enabled ? "Disable this admin?" : "Enable this admin?"} confirmLabel={target?.enabled ? "Disable" : "Enable"}
        danger={target?.enabled} busy={toggle.isPending} error={toggle.error} onCancel={() => { setTarget(null); toggle.reset(); }}
        onConfirm={() => target && toggle.mutate({ id: target.id, enabled: !target.enabled }, { onSuccess: () => setTarget(null) })}
      >
        {target?.enabled
          ? `${target.phone} will not be able to sign in again. A session already open can keep working for up to 15 minutes.`
          : `${target?.phone} will be able to sign in again.`}
      </ConfirmDialog>
    </>
  );
}
