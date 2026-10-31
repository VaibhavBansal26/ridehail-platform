"use client";

import { Button, Card, ErrorNote } from "@/components/ui";
import { useCardCheckout, useCustomer } from "@/lib/queries";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { CSRF_HEADER, CSRF_VALUE } from "@/lib/session";

const DEV_CARD = process.env.NEXT_PUBLIC_DEV_CARD === "true";

export function AccountView() {
  const customer = useCustomer();
  const checkout = useCardCheckout();
  const qc = useQueryClient();
  // development only: the simulated payment provider accepts a card token from the client
  const testCard = useMutation({
    mutationFn: async () => {
      const res = await fetch("/api/backend/api/customers/me/payment-method", {
        method: "PUT",
        credentials: "same-origin",
        headers: { "Content-Type": "application/json", [CSRF_HEADER]: CSRF_VALUE },
        body: JSON.stringify({ token: "tok_ok" }),
      });
      if (!res.ok) throw new Error("The test card shortcut is not enabled on this server.");
    },
    onSuccess: () => qc.invalidateQueries(),
  });

  if (customer.isPending) return <p className="py-10 text-center text-sm text-muted">Loading…</p>;
  if (customer.isError) return <ErrorNote>{customer.error.message}</ErrorNote>;
  const has = customer.data.hasPaymentMethod;

  return (
    <Card title="Payment">
      <p className="text-sm">{has ? "You have a card on file. We charge it when a trip ends." : "Add a card to book rides. We only charge it when a trip ends."}</p>
      <p className="mt-1 text-xs text-muted">You enter your card on our payment provider&apos;s page, not in this app.</p>
      {checkout.error ? <div className="mt-3"><ErrorNote>{checkout.error.message}</ErrorNote></div> : null}
      {testCard.error ? <div className="mt-3"><ErrorNote>{testCard.error.message}</ErrorNote></div> : null}
      <Button
        className="mt-4 w-full"
        busy={checkout.isPending}
        // a normal navigation to the provider's hosted page; the card then reaches us by webhook and this page refetches
        onClick={() => checkout.mutate(undefined, { onSuccess: (r) => window.location.assign(r.url) })}
      >
        {has ? "Replace card" : "Add a card"}
      </Button>
      {DEV_CARD && !has ? (
        <Button variant="secondary" className="mt-2 w-full" busy={testCard.isPending} onClick={() => testCard.mutate()}>
          Use a test card (development)
        </Button>
      ) : null}
    </Card>
  );
}
