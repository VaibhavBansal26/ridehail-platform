import type { Metadata } from "next";
import { Home } from "@/components/home";

export const metadata: Metadata = { title: "Ride" };

export default function Page() {
  return <Home />;
}
