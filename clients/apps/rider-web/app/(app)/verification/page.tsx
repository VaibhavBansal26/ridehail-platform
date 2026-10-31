import type { Metadata } from "next";
import { VerificationView } from "@/components/verification-view";

export const metadata: Metadata = { title: "Verification" };

export default function Page() {
  return <VerificationView />;
}
