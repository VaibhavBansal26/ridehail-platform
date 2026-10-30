import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import AdminsPage from "@/app/(panel)/admins/page";

const admin = (over: Record<string, unknown> = {}) => ({
  id: "11111111-1111-4111-8111-111111111111", phone: "admin", enabled: true, you: true, createdAt: new Date().toISOString(), ...over,
});
const other = admin({ id: "22222222-2222-4222-8222-222222222222", phone: "sam", you: false });
const reply = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

let fetchMock: ReturnType<typeof vi.fn>;
beforeEach(() => {
  fetchMock = vi.fn(async (_url: string, init?: RequestInit) => (init?.method === "POST" ? reply(200, {}) : reply(200, [admin(), other])));
  vi.stubGlobal("fetch", fetchMock);
});
afterEach(() => vi.unstubAllGlobals());

const renderPage = () =>
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><AdminsPage /></QueryClientProvider>);

describe("Admins page", () => {
  it("lists admins, marks you, and offers no way to disable yourself", async () => {
    renderPage();
    const table = await screen.findByRole("table", { name: "Admin accounts" });
    expect(within(table).getByText("(you)")).toBeInTheDocument();
    expect(within(table).getAllByRole("button")).toHaveLength(1);   // only for "sam"
  });

  it("will not submit a short password", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole("table");
    await user.type(screen.getByLabelText(/Login name/), "newperson");
    await user.type(screen.getByLabelText(/Password/), "short");
    expect(screen.getByRole("button", { name: "Create admin" })).toBeDisabled();
    await user.type(screen.getByLabelText(/Password/), "-long-enough");
    expect(screen.getByRole("button", { name: "Create admin" })).toBeEnabled();
  });

  it("creates an admin and clears the form", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole("table");
    await user.type(screen.getByLabelText(/Login name/), "newperson");
    await user.type(screen.getByLabelText(/Password/), "a-long-password-1");
    await user.click(screen.getByRole("button", { name: "Create admin" }));

    const post = await vi.waitFor(() => fetchMock.mock.calls.find(([, i]) => i?.method === "POST")!);
    expect(post[0]).toBe("/api/admin/admins");
    expect(JSON.parse(post[1].body)).toEqual({ phone: "newperson", password: "a-long-password-1" });
    await vi.waitFor(() => expect(screen.getByLabelText(/Login name/)).toHaveValue(""));
  });

  it("shows the API's reason when it refuses", async () => {
    fetchMock.mockImplementation(async (_u: string, init?: RequestInit) =>
      init?.method === "POST" ? reply(409, { detail: "That login name is already taken" }) : reply(200, [admin()]));
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole("table");
    await user.type(screen.getByLabelText(/Login name/), "admin");
    await user.type(screen.getByLabelText(/Password/), "a-long-password-1");
    await user.click(screen.getByRole("button", { name: "Create admin" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("That login name is already taken");
  });
});
