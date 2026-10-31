import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { LoginForm } from "@/app/login/login-form";
import { sessionCall } from "@/lib/api";

const params = new URLSearchParams();
vi.mock("next/navigation", () => ({ useSearchParams: () => params }));
vi.mock("@/lib/api", () => ({ sessionCall: vi.fn() }));

const assign = vi.fn();
beforeEach(() => {
  vi.clearAllMocks();
  params.delete("next");
  vi.stubGlobal("location", { ...window.location, assign });
});

describe("LoginForm", () => {
  it("signs in and goes where the rider was headed", async () => {
    params.set("next", "/earnings");
    vi.mocked(sessionCall).mockResolvedValue({ ok: true, status: 200 });
    const user = userEvent.setup();
    render(<LoginForm />);
    await user.type(screen.getByLabelText("Phone number"), "+1555");
    await user.type(screen.getByLabelText("Password"), "pw-pw-pw-1");
    await user.click(screen.getByRole("button", { name: "Sign in" }));
    expect(sessionCall).toHaveBeenCalledWith("/api/session/login", { phone: "+1555", password: "pw-pw-pw-1" });
    expect(assign).toHaveBeenCalledWith("/earnings");
  });

  it("will not be sent to another site by a crafted link", async () => {
    params.set("next", "//evil.test/phish");
    vi.mocked(sessionCall).mockResolvedValue({ ok: true, status: 200 });
    const user = userEvent.setup();
    render(<LoginForm />);
    await user.type(screen.getByLabelText("Phone number"), "+1555");
    await user.type(screen.getByLabelText("Password"), "pw-pw-pw-1");
    await user.click(screen.getByRole("button", { name: "Sign in" }));
    expect(assign).toHaveBeenCalledWith("/");
  });

  it("shows the server's reason, including a lockout", async () => {
    vi.mocked(sessionCall).mockResolvedValue({ ok: false, status: 429, detail: "Too many sign-in attempts. Try again in 30 seconds." });
    const user = userEvent.setup();
    render(<LoginForm />);
    await user.type(screen.getByLabelText("Phone number"), "+1555");
    await user.type(screen.getByLabelText("Password"), "x");
    await user.click(screen.getByRole("button", { name: "Sign in" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Try again in 30 seconds");
    expect(assign).not.toHaveBeenCalled();
  });

  it("registers a customer", async () => {
    vi.mocked(sessionCall).mockResolvedValue({ ok: true, status: 201 });
    const user = userEvent.setup();
    render(<LoginForm />);
    await user.click(screen.getByRole("tab", { name: "Create account" }));
    await user.type(screen.getByLabelText("Your name"), "Asha");
    await user.type(screen.getByLabelText("Phone number"), "+1555");
    await user.type(screen.getByLabelText(/Password/), "password123");
    await user.click(screen.getByRole("button", { name: "Create account" }));
    expect(sessionCall).toHaveBeenCalledWith("/api/session/register", { name: "Asha", phone: "+1555", password: "password123" });
  });
});
