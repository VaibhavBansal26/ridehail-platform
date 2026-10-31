import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Suspense } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import DisputeEvidencePage from "@/app/(panel)/disputes/[id]/page";

const ID = "22222222-2222-4222-8222-222222222222";
const reply = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

const view = (over: Record<string, unknown> = {}) => ({
  disputeId: ID, explanation: null, submitted: false, submittedAt: null, lastError: null, canSubmit: false,
  cannotSubmitBecause: "Write the explanation first", evidenceDueBy: new Date(Date.now() + 5 * 86_400_000).toISOString(),
  facts: { customerName: "Ravi", productDescription: "Ride-hailing trip (SEDAN)", serviceDate: "2026-10-30", accessActivityLog: "Trip started at 10:00.\nTrip completed at 10:20." },
  ...over,
});

let fetchMock: ReturnType<typeof vi.fn>;
let current = view();
beforeEach(() => {
  current = view();
  fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
    if (init?.method === "POST") {
      if (String(url).endsWith("/submit")) current = view({ ...current, submitted: true, submittedAt: new Date().toISOString(), canSubmit: false });
      else current = view({ explanation: JSON.parse(String(init.body)).explanation, canSubmit: true, cannotSubmitBecause: null });
      return reply(200, {});
    }
    return reply(200, current);
  });
  vi.stubGlobal("fetch", fetchMock);
});
afterEach(() => vi.unstubAllGlobals());

const renderPage = () => act(async () => {
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><Suspense fallback="loading"><DisputeEvidencePage params={Promise.resolve({ id: ID })} /></Suspense></QueryClientProvider>);
});
const posts = () => fetchMock.mock.calls.filter(([, i]) => i?.method === "POST");

describe("answering a dispute", () => {
  it("shows the facts we will send, and why submitting is not yet possible", async () => {
    await renderPage();
    expect(await screen.findByText("Ravi")).toBeInTheDocument();
    expect(screen.getByText(/Trip completed at 10:20/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Submit to the provider/ })).toBeDisabled();
    expect(screen.getByText("Write the explanation first")).toBeInTheDocument();
  });

  it("saves a draft first, and only then offers to submit, after a confirmation that says it is final", async () => {
    const user = userEvent.setup();
    await renderPage();
    await screen.findByText("Ravi");

    await user.type(screen.getByRole("textbox", { name: /Why this charge is valid/ }), "Customer took the ride.");
    expect(screen.getByRole("button", { name: /Submit to the provider/ })).toBeDisabled();   // unsaved changes
    await user.click(screen.getByRole("button", { name: "Save draft" }));
    expect(JSON.parse(String(posts()[0]![1].body))).toEqual({ explanation: "Customer took the ride." });

    const submit = await screen.findByRole("button", { name: /Submit to the provider/ });
    await vi.waitFor(() => expect(submit).toBeEnabled());
    await user.click(submit);
    expect(await screen.findByText(/one response per dispute/)).toBeInTheDocument();
    expect(posts()).toHaveLength(1);                                                           // nothing sent by merely asking

    await user.click(screen.getByRole("button", { name: "Submit" }));
    await vi.waitFor(() => expect(posts().map(([u]) => String(u))).toContain(`/api/admin/disputes/${ID}/evidence/submit`));
    expect(await screen.findByText(/Submitted/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Save draft" })).not.toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: /Why this charge is valid/ })).toHaveAttribute("readonly");
  });

  it("shows why the provider refused, and a dispute with no trip says the explanation must carry the case", async () => {
    current = view({ explanation: "x", canSubmit: true, lastError: "Dispute can no longer be answered", facts: { customerName: null, productDescription: null, serviceDate: null, accessActivityLog: null } });
    await renderPage();
    expect(await screen.findByRole("alert")).toHaveTextContent("Dispute can no longer be answered");
    expect(screen.getByText(/not tied to a trip/)).toBeInTheDocument();
  });

  it("does not let the text run past the field's limit", async () => {
    const user = userEvent.setup();
    await renderPage();
    await screen.findByText("Ravi");
    const box = screen.getByRole("textbox", { name: /Why this charge is valid/ });
    await user.click(box);
    await user.paste("x".repeat(5200));
    expect((box as HTMLTextAreaElement).value).toHaveLength(5000);
  });
});
