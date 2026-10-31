import { AccountView } from "@/components/account-view";
import { Header } from "@/components/header";
import { Screen } from "@/components/screen";

export default function AccountScreen() {
  return (
    <Screen>
      <Header />
      <AccountView />
    </Screen>
  );
}
