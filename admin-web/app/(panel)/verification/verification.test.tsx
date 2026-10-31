import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Suspense } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import VerificationReviewPage from "@/app/(panel)/verification/[riderId]/page";
import VerificationPage from "@/app/(panel)/verification/page";

const RIDER = "11111111-1111-4111-8111-111111111111";
const reply = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
const day = (n: number) => new Date(Date.now() + n * 86_400_000).toISOString().slice(0, 10);

const detail = (status: string, over: Record<string, unknown> = {}) => ({
  riderId: RIDER, name: "Asha", phone: "+15550001", vehicleType: "SEDAN",
  verification: {
    required: true, status, note: null, submittedAt: new Date().toISOString(), reviewedAt: null, canUpload: false, canSubmit: false, missing: [],
    requirements: [],
    documents: [
      { type: "DRIVING_LICENCE", label: "Driving licence", contentType: "image/jpeg", sizeBytes: 1000, uploadedAt: new Date().toISOString(), expiresOn: day(300) },
      { type: "INSURANCE", label: "Insurance certificate", contentType: "application/pdf", sizeBytes: 2000, uploadedAt: new Date().toISOString(), expiresOn: day(10) },
      { type: "SELFIE", label: "Selfie", contentType: "image/png", sizeBytes: 3000, uploadedAt: new Date().toISOString(), expiresOn: null },
    ],
    ...over,
  },
});

let fetchMock: ReturnType<typeof vi.fn>;
let current = detail("PENDING_REVIEW");
beforeEach(() => {
  current = detail("PENDING_REVIEW");
  fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
    if (init?.method === "POST") return reply(200, {});
    if (url.startsWith("/api/admin/verifications?")) {
      return reply(200, { items: [{ riderId: RIDER, name: "Asha", phone: "+15550001", vehicleType: "SEDAN", status: "PENDING_REVIEW", submittedAt: new Date(Date.now() - 3 * 3600_000).toISOString(), documents: 4 }], total: 1, page: 0, size: 20 });
    }
    return reply(200, current);
  });
  vi.stubGlobal("fetch", fetchMock);
});
afterEach(() => vi.unstubAllGlobals());

const wrap = (ui: React.ReactNode) =>
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><Suspense fallback="loading">{ui}</Suspense></QueryClientProvider>);
// the page reads its route params with use(): the promise has to settle inside act for the page to render
const renderReview = () => act(async () => { wrap(<VerificationReviewPage params={Promise.resolve({ riderId: RIDER })} />); });
const posts = () => fetchMock.mock.calls.filter(([, i]) => i?.method === "POST");

describe("the review queue", () => {
  it("lists who is waiting, how long, and links to the review", async () => {
    wrap(<VerificationPage />);
    const table = await screen.findByRole("table", { name: "Riders by verification status" });
    expect(table).toHaveTextContent("Asha");
    expect(table).toHaveTextContent("4 of 4");
    expect(table).toHaveTextContent("3h ago");
    expect(screen.getByRole("link", { name: "Review" })).toHaveAttribute("href", `/verification/${RIDER}`);
    expect(String(fetchMock.mock.calls[0]![0])).toContain("status=PENDING_REVIEW");
  });
});

describe("reviewing a rider", () => {
  it("keeps every document hidden until someone asks to see it (and each look is recorded by the server)", async () => {
    const user = userEvent.setup();
    await renderReview();
    await screen.findByRole("heading", { name: "Asha" });
    expect(screen.queryByRole("img")).not.toBeInTheDocument();
    expect(fetchMock.mock.calls.filter(([u]) => String(u).includes("/documents/"))).toHaveLength(0);

    await user.click(screen.getAllByRole("button", { name: "Show document" })[0]!);
    const img = await screen.findByRole("img", { name: "Driving licence" });
    expect(img).toHaveAttribute("src", `/api/admin/verifications/${RIDER}/documents/DRIVING_LICENCE`);
  });

  it("opens a PDF in a new tab instead of embedding it", async () => {
    const user = userEvent.setup();
    await renderReview();
    await screen.findByRole("heading", { name: "Asha" });
    await user.click(screen.getAllByRole("button", { name: "Show document" })[1]!);
    const link = await screen.findByRole("link", { name: /Open the PDF/ });
    expect(link).toHaveAttribute("href", `/api/admin/verifications/${RIDER}/documents/INSURANCE`);
    expect(link).toHaveAttribute("target", "_blank");
    expect(link).toHaveAttribute("rel", expect.stringContaining("noreferrer"));
  });

  it("warns when a document is about to run out", async () => {
    await renderReview();
    await screen.findByRole("heading", { name: "Asha" });
    expect(screen.getByText(/expires .* \(\d+ days\)/)).toBeInTheDocument();   // the insurance, 10 days away
  });

  it("approves after a confirmation", async () => {
    const user = userEvent.setup();
    await renderReview();
    await user.click(await screen.findByRole("button", { name: "Approve" }));
    expect(posts()).toHaveLength(0);                       // nothing happens until it is confirmed
    await user.click(screen.getAllByRole("button", { name: "Approve" }).at(-1)!);
    await vi.waitFor(() => expect(posts()).toHaveLength(1));
    expect(posts()[0]![0]).toBe(`/api/admin/verifications/${RIDER}/approve`);
  });

  it("will not send a rider back without saying why", async () => {
    const user = userEvent.setup();
    await renderReview();
    await user.click(await screen.findByRole("button", { name: "Send back…" }));
    const confirm = screen.getByRole("button", { name: "Send back" });
    expect(confirm).toBeDisabled();
    await user.type(screen.getByLabelText(/Reason/), "The licence photo is blurry");
    expect(confirm).toBeEnabled();
    await user.click(confirm);
    await vi.waitFor(() => expect(posts()).toHaveLength(1));
    expect(posts()[0]![0]).toBe(`/api/admin/verifications/${RIDER}/reject`);
    expect(JSON.parse(posts()[0]![1].body)).toEqual({ reason: "The licence photo is blurry" });
  });

  it("offers suspension for an approved rider, with a required reason", async () => {
    current = detail("APPROVED");
    const user = userEvent.setup();
    await renderReview();
    expect(screen.queryByRole("button", { name: "Approve" })).not.toBeInTheDocument();
    await user.click(await screen.findByRole("button", { name: "Suspend…" }));
    expect(screen.getByRole("button", { name: "Suspend" })).toBeDisabled();
    await user.type(screen.getByLabelText(/Reason/), "Safety complaint");
    await user.click(screen.getByRole("button", { name: "Suspend" }));
    await vi.waitFor(() => expect(posts()).toHaveLength(1));
    expect(JSON.parse(posts()[0]![1].body)).toEqual({ reason: "Safety complaint" });
  });

  it("offers to lift a suspension, and nothing else", async () => {
    current = detail("SUSPENDED", { note: "Complaint under investigation" });
    const user = userEvent.setup();
    await renderReview();
    expect(await screen.findByText(/Complaint under investigation/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Approve" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Lift suspension" }));
    await user.click(screen.getAllByRole("button", { name: "Lift" }).at(-1)!);
    await vi.waitFor(() => expect(posts()[0]![0]).toBe(`/api/admin/verifications/${RIDER}/reinstate`));
  });

  it("shows what is missing when a rider has not sent everything", async () => {
    current = detail("NOT_SUBMITTED", { missing: ["VEHICLE_REGISTRATION"] });
    await renderReview();
    expect(await screen.findByText(/Not sent yet: vehicle registration/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Approve" })).not.toBeInTheDocument();
  });

  it("shows the API's reason when a decision is refused", async () => {
    fetchMock.mockImplementation(async (_u: string, init?: RequestInit) =>
      init?.method === "POST" ? reply(409, { detail: "This rider is APPROVED, so it cannot be approved" }) : reply(200, current));
    const user = userEvent.setup();
    await renderReview();
    await user.click(await screen.findByRole("button", { name: "Approve" }));
    await user.click(screen.getAllByRole("button", { name: "Approve" }).at(-1)!);
    expect(await screen.findByText(/cannot be approved/)).toBeInTheDocument();
  });
});
