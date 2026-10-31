import type { Metadata } from "next";
import { Drive } from "@/components/drive";

export const metadata: Metadata = { title: "Drive" };

export default function Page() {
  return <Drive />;
}
