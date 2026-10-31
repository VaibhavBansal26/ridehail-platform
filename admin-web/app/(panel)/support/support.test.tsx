import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Suspense } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import SupportInboxPage from "@/app/(panel)/support/page";
import SupportThreadPage from "@/app/(panel)/support/[id]/page";

const ID = "33333333-3333-4333-8333-333333333333";
const reply = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
const item = (over: Record<string, unknown> = {}) => ({
  id: ID, role: "CUSTOMER", userId: "u1", name: "Ravi", phone: "+15550001", status: "OPEN", awaitingReply: true,
  awaitingSince: new Date(Date.now() - 2 * 3600_000).toISOString(), lastPreview: "Where is my refund?", lastMessageAt: new Date().toISOString(), ...over,
});
const thread = (over: Record<string, unknown> = {}) => ({
  conversation: item(),
  messages: [
    { id: "m1", from: "USER", body: "<b>Where</b> is my refund?", tripId: null, createdAt: new Date().toISOString() },
    { id: "m2", from: "STAFF", body: "Checking.", tripId: null, createdAt: new Date().toISOString() },
  ],
  ...over,
});

let fetchMock: ReturnType<typeof vi.fn>;
beforeEach(() => {
  fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
    if (init?.method === "POST") return reply(200, {});
    if (String(url).includes("support?")) return reply(200, { items: [item()], total: 1, page: 0, size: 20 });
    return reply(200, thread());
  });
  vi.stubGlobal("fetch", fetchMock);
});
afterEach(() => vi.unstubAllGlobals());

const wrap = (ui: React.ReactNode) =>
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><Suspense fallback="loading">{ui}</Suspense></QueryClientProvider>);
const renderThread = () => act(async () => { wrap(<SupportThreadPage params={Promise.resolve({ id: ID })} />); });
const posts = () => fetchMock.mock.calls.filter(([, i]) => i?.method === "POST");

describe("the support inbox", () => {
  it("asks for the people waiting first and shows who, what and for how long", async () => {
    wrap(<SupportInboxPage />);
    const table = await screen.findByRole("table", { name: "Support conversations" });
    expect(table).toHaveTextContent("Ravi");
    expect(table).toHaveTextContent("Where is my refund?");
    expect(table).toHaveTextContent("Needs reply");
    expect(table).toHaveTextContent("2h ago");
    expect(String(fetchMock.mock.calls[0]![0])).toContain("filter=awaiting");
    expect(screen.getByRole("link", { name: "Open" })).toHaveAttribute("href", `/support/${ID}`);
  });
});

describe("a conversation", () => {
  it("shows messages as plain text, never as markup", async () => {
    await renderThread();
    expect(await screen.findByText("<b>Where</b> is my refund?")).toBeInTheDocument();
    expect(document.querySelector("li b")).toBeNull();
  });

  it("sends a trimmed reply and clears the box", async () => {
    const user = userEvent.setup();
    await renderThread();
    const box = await screen.findByRole("textbox", { name: /Your reply/ });
    expect(screen.getByRole("button", { name: "Send reply" })).toBeDisabled();
    await user.type(box, "  It was refunded today.  ");
    await user.click(screen.getByRole("button", { name: "Send reply" }));
    await vi.waitFor(() => expect(posts()).toHaveLength(1));
    expect(String(posts()[0]![0])).toBe(`/api/admin/support/${ID}/reply`);
    expect(JSON.parse(String(posts()[0]![1].body))).toEqual({ body: "It was refunded today." });
    await vi.waitFor(() => expect(box).toHaveValue(""));
  });

  it("can be marked resolved, but not twice", async () => {
    const user = userEvent.setup();
    await renderThread();
    await user.click(await screen.findByRole("button", { name: "Mark resolved" }));
    await vi.waitFor(() => expect(String(posts()[0]![0])).toBe(`/api/admin/support/${ID}/resolve`));
  });

  it("offers no resolve button once resolved", async () => {
    fetchMock.mockImplementation(async () => reply(200, thread({ conversation: item({ status: "RESOLVED", awaitingReply: false }) })));
    await renderThread();
    await screen.findByText("Conversation");
    expect(screen.queryByRole("button", { name: "Mark resolved" })).not.toBeInTheDocument();
  });
});
