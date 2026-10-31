import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Linking } from "react-native";
import { Button, Card, ErrorNote, Muted } from "@/components/ui";
import { auth } from "@/lib/api";
import { API_URL } from "@/lib/config";
import { useCardCheckout, useCustomer } from "@/lib/queries";

const DEV_CARD = process.env.EXPO_PUBLIC_DEV_CARD === "true";

export function AccountView() {
  const customer = useCustomer();
  const checkout = useCardCheckout();
  const qc = useQueryClient();
  // development only: the simulated payment provider accepts a card token from the client; a real one refuses it
  const testCard = useMutation({
    mutationFn: async () => {
      const res = await fetch(`${API_URL}/api/customers/me/payment-method`, {
        method: "PUT",
        headers: { "Content-Type": "application/json", Authorization: `Bearer ${await auth.accessToken()}` },
        body: JSON.stringify({ token: "tok_ok" }),
      });
      if (!res.ok) throw new Error("The test card shortcut is not enabled on this server.");
    },
    onSuccess: () => qc.invalidateQueries(),
  });

  if (customer.isPending) return <Muted style={{ textAlign: "center", paddingVertical: 40 }}>Loading…</Muted>;
  if (customer.isError) return <ErrorNote>{customer.error.message}</ErrorNote>;
  const has = customer.data.hasPaymentMethod;

  return (
    <Card title="Payment">
      <Muted>{has ? "You have a card on file. We charge it when a trip ends." : "Add a card to book rides. We only charge it when a trip ends."}</Muted>
      <Muted>You enter your card on our payment provider&apos;s page, not in this app.</Muted>
      {checkout.error ? <ErrorNote>{checkout.error.message}</ErrorNote> : null}
      {testCard.error ? <ErrorNote>{testCard.error.message}</ErrorNote> : null}
      <Button
        label={has ? "Replace card" : "Add a card"}
        busy={checkout.isPending}
        // the provider's hosted page opens in the system browser; the card reaches us by webhook and this screen refetches when you return
        onPress={() => checkout.mutate(undefined, { onSuccess: (r) => void Linking.openURL(r.url) })}
      />
      {DEV_CARD && !has ? <Button label="Use a test card (development)" variant="secondary" busy={testCard.isPending} onPress={() => testCard.mutate()} /> : null}
    </Card>
  );
}
