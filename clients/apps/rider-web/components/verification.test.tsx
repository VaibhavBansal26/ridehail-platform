import type { DocumentType } from "@ridehail/api";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { Drive } from "@/components/drive";
import { checkFile, VerificationView } from "@/components/verification-view";
import { api } from "@/lib/api";
import { renderApp } from "@/test/render";

vi.mock("@/lib/api", () => ({
  api: {
    rider: { verification: vi.fn(), uploadDocument: vi.fn(), submitVerification: vi.fn(), me: vi.fn(), offers: vi.fn(), updateLocation: vi.fn(), goOnline: vi.fn(), goOffline: vi.fn() },
    trips: { active: vi.fn() },
  },
}));
const rider = api.rider as unknown as Record<string, ReturnType<typeof vi.fn>>;

const REQUIREMENTS: { type: DocumentType; label: string; needsExpiry: boolean; imageOnly: boolean }[] = [
  { type: "DRIVING_LICENCE", label: "Driving licence", needsExpiry: true, imageOnly: false },
  { type: "VEHICLE_REGISTRATION", label: "Vehicle registration", needsExpiry: true, imageOnly: false },
  { type: "INSURANCE", label: "Insurance certificate", needsExpiry: true, imageOnly: false },
  { type: "SELFIE", label: "Selfie", needsExpiry: false, imageOnly: true },
];
const sentDoc = (type: string, expiresOn: string | null) => ({ type, label: type, contentType: "image/jpeg", sizeBytes: 100, uploadedAt: "2026-10-30T20:00:00Z", expiresOn });
const verification = (over: Record<string, unknown> = {}) => ({
  required: true, status: "NOT_SUBMITTED", note: null, submittedAt: null, reviewedAt: null, requirements: REQUIREMENTS,
  documents: [], missing: REQUIREMENTS.map((r) => r.type), canUpload: true, canSubmit: false, ...over,
});
const file = (name: string, type: string, bytes = 100) => new File([new Uint8Array(bytes)], name, { type });

beforeEach(() => {
  vi.clearAllMocks();
  rider.verification.mockResolvedValue(verification());
  rider.uploadDocument.mockResolvedValue(verification());
});

describe("checkFile (what can be refused before a slow upload)", () => {
  const licence = REQUIREMENTS[0]!, selfie = REQUIREMENTS[3]!;
  it("accepts pictures, and PDFs where allowed", () => {
    expect(checkFile(file("a.jpg", "image/jpeg"), licence)).toBeNull();
    expect(checkFile(file("a.png", "image/png"), selfie)).toBeNull();
    expect(checkFile(file("a.pdf", "application/pdf"), licence)).toBeNull();
  });
  it("refuses a PDF as a selfie, other types, empty and oversized files", () => {
    expect(checkFile(file("a.pdf", "application/pdf"), selfie)).toMatch(/picture/);
    expect(checkFile(file("a.exe", "application/x-msdownload"), licence)).toMatch(/JPEG or PNG/);
    expect(checkFile(file("a.jpg", "image/jpeg", 0), licence)).toMatch(/empty/);
    expect(checkFile(file("a.jpg", "image/jpeg", 6 * 1024 * 1024), licence)).toMatch(/5 MB/);
  });
});

describe("VerificationView", () => {
  it("lists every document as needed and cannot be sent for review yet", async () => {
    await renderApp(<VerificationView />);
    expect(await screen.findByText("Send your documents")).toBeInTheDocument();
    expect(screen.getAllByText("Needed")).toHaveLength(4);
    expect(screen.getByRole("button", { name: "Send for review" })).toBeDisabled();
    expect(screen.getByText(/Still needed: driving licence/)).toBeInTheDocument();
  });

  it("uploads a document with its expiry date", async () => {
    const user = userEvent.setup();
    await renderApp(<VerificationView />);
    const licence = (await screen.findByText("Driving licence")).closest("li")!;
    const input = licence.querySelector('input[type="file"]') as HTMLInputElement;
    const date = licence.querySelector('input[type="date"]') as HTMLInputElement;
    const photo = file("licence.jpg", "image/jpeg");
    await user.upload(input, photo);
    await user.type(date, "2030-05-01");
    await user.click(licence.querySelector("button")!);
    await waitFor(() => expect(rider.uploadDocument).toHaveBeenCalled());
    expect(rider.uploadDocument.mock.calls[0]!.slice(0, 3)).toEqual(["DRIVING_LICENCE", photo, "2030-05-01"]);
  });

  it("sends a selfie with no expiry", async () => {
    const user = userEvent.setup();
    await renderApp(<VerificationView />);
    const row = (await screen.findByText("Selfie")).closest("li")!;
    expect(row.querySelector('input[type="date"]')).toBeNull();
    await user.upload(row.querySelector('input[type="file"]') as HTMLInputElement, file("me.jpg", "image/jpeg"));
    await user.click(row.querySelector("button")!);
    await waitFor(() => expect(rider.uploadDocument).toHaveBeenCalled());
    expect(rider.uploadDocument.mock.calls[0]![0]).toBe("SELFIE");
    expect(rider.uploadDocument.mock.calls[0]![2]).toBeUndefined();
  });

  it("says what is wrong instead of sending a bad upload", async () => {
    const user = userEvent.setup({ applyAccept: false });
    await renderApp(<VerificationView />);
    const licence = (await screen.findByText("Driving licence")).closest("li")!;
    await user.click(licence.querySelector("button")!);
    expect(await screen.findByText("Choose a file first.")).toBeInTheDocument();

    await user.upload(licence.querySelector('input[type="file"]') as HTMLInputElement, file("licence.jpg", "image/jpeg"));
    await user.click(licence.querySelector("button")!);
    expect(await screen.findByText(/Enter the date this driving licence expires/)).toBeInTheDocument();
    expect(rider.uploadDocument).not.toHaveBeenCalled();
  });

  it("shows the server's reason when it refuses a file", async () => {
    rider.uploadDocument.mockRejectedValue(new Error("That driving licence has already expired"));
    const user = userEvent.setup();
    await renderApp(<VerificationView />);
    const licence = (await screen.findByText("Driving licence")).closest("li")!;
    await user.upload(licence.querySelector('input[type="file"]') as HTMLInputElement, file("l.jpg", "image/jpeg"));
    await user.type(licence.querySelector('input[type="date"]') as HTMLInputElement, "2020-01-01");
    await user.click(licence.querySelector("button")!);
    expect(await screen.findByText("That driving licence has already expired")).toBeInTheDocument();
  });

  it("enables sending for review once everything is there", async () => {
    rider.verification.mockResolvedValue(verification({ documents: REQUIREMENTS.map((r) => sentDoc(r.type, r.needsExpiry ? "2030-01-01" : null)), missing: [], canSubmit: true }));
    rider.submitVerification.mockResolvedValue(verification({ status: "PENDING_REVIEW", canUpload: false, canSubmit: false }));
    const user = userEvent.setup();
    await renderApp(<VerificationView />);
    await user.click(await screen.findByRole("button", { name: "Send for review" }));
    await waitFor(() => expect(rider.submitVerification).toHaveBeenCalled());
  });

  it("locks everything while it is being reviewed", async () => {
    rider.verification.mockResolvedValue(verification({ status: "PENDING_REVIEW", canUpload: false, canSubmit: false, documents: REQUIREMENTS.map((r) => sentDoc(r.type, null)), missing: [] }));
    await renderApp(<VerificationView />);
    expect(await screen.findByText("We're reviewing your documents")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Upload|Replace|Send for review/ })).not.toBeInTheDocument();
  });

  it("shows the reviewer's reason and lets a rejected rider fix things", async () => {
    rider.verification.mockResolvedValue(verification({ status: "REJECTED", note: "The licence photo is blurry", documents: REQUIREMENTS.map((r) => sentDoc(r.type, null)), missing: [] }));
    await renderApp(<VerificationView />);
    expect(await screen.findByText("Your documents need attention")).toBeInTheDocument();
    expect(screen.getByRole("note")).toHaveTextContent("The licence photo is blurry");
    expect(screen.getAllByRole("button", { name: "Replace" })).toHaveLength(4);
  });

  it("says a suspended rider cannot change anything", async () => {
    rider.verification.mockResolvedValue(verification({ status: "SUSPENDED", note: "Safety complaint", canUpload: false }));
    await renderApp(<VerificationView />);
    expect(await screen.findByText("Your account is suspended")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Upload/ })).not.toBeInTheDocument();
  });

  it("congratulates an approved rider, and says nothing is needed where verification is off", async () => {
    rider.verification.mockResolvedValue(verification({ status: "APPROVED", canUpload: false }));
    const first = await renderApp(<VerificationView />);
    expect(await screen.findByText("You're approved")).toBeInTheDocument();
    first.unmount();
    rider.verification.mockResolvedValue(verification({ required: false }));
    await renderApp(<VerificationView />);
    expect(await screen.findByText(/isn't required yet/)).toBeInTheDocument();
  });
});

describe("Drive, before a rider is verified", () => {
  beforeEach(() => {
    rider.me.mockResolvedValue({ id: "r1", name: "Asha", vehicleType: "SEDAN", status: "OFFLINE", lat: null, lon: null });
    rider.offers.mockResolvedValue([]);
    (api.trips.active as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(null);
  });

  it("will not let them go online, and points them to Verification", async () => {
    rider.verification.mockResolvedValue(verification());
    await renderApp(<Drive />);
    expect(await screen.findByRole("button", { name: "Go online" })).toBeDisabled();
    expect(screen.getByRole("link", { name: "Open Verification" })).toHaveAttribute("href", "/verification");
  });

  it("lets an approved rider go online", async () => {
    rider.verification.mockResolvedValue(verification({ status: "APPROVED", canUpload: false }));
    await renderApp(<Drive />);
    expect(await screen.findByRole("button", { name: "Go online" })).toBeEnabled();
    expect(screen.queryByRole("link", { name: "Open Verification" })).not.toBeInTheDocument();
  });

  it("does not interfere where verification is switched off", async () => {
    rider.verification.mockResolvedValue(verification({ required: false, status: "APPROVED" }));
    await renderApp(<Drive />);
    expect(await screen.findByRole("button", { name: "Go online" })).toBeEnabled();
  });
});
