"use client";

import { Badge, Card, DataTable, ErrorBox, Loading, PageHeader, Td } from "@/components/ui";
import { money, titleCase } from "@/lib/format";
import { useTrialBalance } from "@/lib/queries";

export default function LedgerPage() {
  const tb = useTrialBalance();
  if (tb.isPending) return <Loading />;
  if (tb.isError) return <ErrorBox error={tb.error} onRetry={() => tb.refetch()} />;
  const d = tb.data;
  return (
    <>
      <PageHeader title="Ledger" subtitle="Every movement of money is recorded twice, as a debit and an equal credit. The books are healthy when the totals match." />
      <Card className="mb-4">
        <div className="flex flex-wrap items-center gap-x-8 gap-y-2 text-sm">
          <Badge tone={d.balanced ? "ok" : "bad"}>{d.balanced ? "Balanced" : "OUT OF BALANCE: stop and investigate"}</Badge>
          <span><span className="text-muted">Total debits </span><span className="tabular-nums">{money(d.totalDebits)}</span></span>
          <span><span className="text-muted">Total credits </span><span className="tabular-nums">{money(d.totalCredits)}</span></span>
        </div>
      </Card>
      <DataTable caption="Trial balance by account type" head={["Account", "Debits", "Credits", "Net"]}>
        {Object.entries(d.byType).map(([type, t]) => (
          <tr key={type}>
            <Td>{titleCase(type)}</Td>
            <Td className="tabular-nums">{money(t.debits)}</Td>
            <Td className="tabular-nums">{money(t.credits)}</Td>
            <Td className="tabular-nums">{money(t.debits - t.credits)}</Td>
          </tr>
        ))}
      </DataTable>
    </>
  );
}
