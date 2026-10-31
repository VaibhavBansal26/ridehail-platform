import { EarningsView } from "@/components/earnings-view";
import { Header } from "@/components/header";
import { Screen } from "@/components/screen";

export default function EarningsScreen() {
  return (
    <Screen>
      <Header />
      <EarningsView />
    </Screen>
  );
}
