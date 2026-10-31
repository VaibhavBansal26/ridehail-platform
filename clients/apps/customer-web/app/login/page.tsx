import type { Metadata } from "next";
import { LoginForm } from "./login-form";

export const metadata: Metadata = { title: "Sign in" };

export default function LoginPage() {
  return (
    <main className="mx-auto flex min-h-dvh max-w-sm flex-col justify-center gap-5 px-4 py-10">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Ridehail</h1>
        <p className="mt-1 text-sm text-muted">Get a ride in minutes.</p>
      </div>
      <LoginForm />
    </main>
  );
}
