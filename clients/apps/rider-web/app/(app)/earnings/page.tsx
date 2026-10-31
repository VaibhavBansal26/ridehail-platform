import type { Metadata } from "next";
import { EarningsView } from "@/components/earnings-view";

export const metadata: Metadata = { title: "Earnings" };

export default function Page() {
  return <EarningsView />;
}
