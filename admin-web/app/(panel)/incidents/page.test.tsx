import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import IncidentsPage from "@/app/(panel)/incidents/page";
import { CSRF_HEADER } from "@/lib/session";

const incident = (over: Record<string, unknown> = {}) => ({
  id: "fb2a0a92-9295-492a-8534-314d1485621a", type: "REFUND_FAILED", providerRef: "re_1", status: "OPEN", riderId: null, paymentId: null,
  payoutId: null, amount: 11.31, currency: "USD", detail: "Refund failed: the card has been closed", createdAt: new Date().toISOString(),
  resolvedAt: null, resolutionNote: null, ...over,
});
const page = (items: unknown[]) => ({ items, total: items.length, page: 0, size: 20 });
const reply = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}><IncidentsPage /></QueryClientProvider>);
}

let fetchMock: ReturnType<typeof vi.fn>;
beforeEach(() => {
  fetchMock = vi.fn();
  vi.stubGlobal("fetch", fetchMock);
});
afterEach(() => vi.unstubAllGlobals());

describe("Incidents page", () => {
  it("lists open incidents in plain words", async () => {
    fetchMock.mockResolvedValue(reply(200, page([incident()])));
    renderPage();
    const table = await screen.findByRole("table", { name: "Incidents" });
    expect(within(table).getByText("Refund Failed")).toBeInTheDocument();   // not the dropdown option of the same name
    expect(within(table).getByText("Refund failed: the card has been closed")).toBeInTheDocument();
    expect(screen.getByText("$11.31")).toBeInTheDocument();
    expect(fetchMock.mock.calls[0][0]).toContain("status=OPEN");
  });

  it("says when nothing is waiting", async () => {
    fetchMock.mockResolvedValue(reply(200, page([])));
    renderPage();
    expect(await screen.findByText(/No open incidents/)).toBeInTheDocument();
  });

  it("shows an error with a way to retry when the API fails", async () => {
    fetchMock.mockResolvedValue(reply(500, { detail: "boom" }));
    renderPage();
    expect(await screen.findByRole("alert")).toHaveTextContent("boom");
  });

  it("resolves with the note, then refreshes the list", async () => {
    fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
      if (init?.method === "POST") return reply(200, incident({ status: "RESOLVED" }));
      return reply(200, page(fetchMock.mock.calls.some(([, i]) => i?.method === "POST") ? [] : [incident()]));
    });
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole("button", { name: "Resolve" }));
    const dialog = screen.getByRole("dialog", { hidden: true });
    await user.type(within(dialog).getByRole("textbox", { hidden: true }), "Called the provider");
    await user.click(within(dialog).getByRole("button", { name: "Mark resolved", hidden: true }));

    await waitFor(() => expect(screen.getByText(/No open incidents/)).toBeInTheDocument());
    const post = fetchMock.mock.calls.find(([, i]) => i?.method === "POST")!;
    expect(post[0]).toBe("/api/admin/incidents/fb2a0a92-9295-492a-8534-314d1485621a/resolve");
    expect(JSON.parse(post[1].body)).toEqual({ note: "Called the provider" });
    expect(post[1].headers[CSRF_HEADER]).toBe("1");
  });

  it("keeps the dialog open and explains when the resolve fails", async () => {
    fetchMock.mockImplementation(async (_url: string, init?: RequestInit) =>
      init?.method === "POST" ? reply(409, { detail: "Incident is already resolved" }) : reply(200, page([incident()])));
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole("button", { name: "Resolve" }));
    await user.click(within(screen.getByRole("dialog", { hidden: true })).getByRole("button", { name: "Mark resolved", hidden: true }));

    expect(await within(screen.getByRole("dialog", { hidden: true })).findByText("Incident is already resolved", {}, { timeout: 3000 })).toBeInTheDocument();
  });
});
