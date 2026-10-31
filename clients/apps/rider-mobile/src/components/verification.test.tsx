import type { DocumentType } from "@ridehail/api";
import { fireEvent, screen, waitFor } from "@testing-library/react-native";
import { Drive } from "@/components/drive";
import { isValidDate, VerificationView } from "@/components/verification-view";
import { api } from "@/lib/api";
import { pickDocument } from "@/lib/documents";
import { renderApp } from "@/test-utils";

jest.mock("@/lib/api", () => ({
  api: {
    rider: { verification: jest.fn(), uploadDocument: jest.fn(), submitVerification: jest.fn(), me: jest.fn(), offers: jest.fn(), updateLocation: jest.fn(), goOnline: jest.fn(), goOffline: jest.fn() },
    trips: { active: jest.fn() },
  },
}));
jest.mock("@/lib/documents", () => ({ ...jest.requireActual("@/lib/documents"), pickDocument: jest.fn() }));
jest.mock("@/lib/background", () => ({ startBackgroundTracking: jest.fn().mockResolvedValue(false), stopBackgroundTracking: jest.fn() }));
jest.mock("@/lib/push", () => ({ registerForPush: jest.fn() }));
jest.mock("expo-router", () => ({ Link: ({ children }: { children: React.ReactNode }) => { const { Text } = require("react-native"); return <Text accessibilityRole="link">{children}</Text>; } }));

const rider = api.rider as unknown as Record<string, jest.Mock>;
const REQUIREMENTS: { type: DocumentType; label: string; needsExpiry: boolean; imageOnly: boolean }[] = [
  { type: "DRIVING_LICENCE", label: "Driving licence", needsExpiry: true, imageOnly: false },
  { type: "VEHICLE_REGISTRATION", label: "Vehicle registration", needsExpiry: true, imageOnly: false },
  { type: "INSURANCE", label: "Insurance certificate", needsExpiry: true, imageOnly: false },
  { type: "SELFIE", label: "Selfie", needsExpiry: false, imageOnly: true },
];
const sentDoc = (type: string) => ({ type, label: type, contentType: "image/jpeg", sizeBytes: 100, uploadedAt: "2026-10-30T20:00:00Z", expiresOn: null });
const verification = (over: Record<string, unknown> = {}) => ({
  required: true, status: "NOT_SUBMITTED", note: null, submittedAt: null, reviewedAt: null, requirements: REQUIREMENTS,
  documents: [], missing: REQUIREMENTS.map((r) => r.type), canUpload: true, canSubmit: false, ...over,
});

beforeEach(() => {
  jest.clearAllMocks();
  rider.verification.mockResolvedValue(verification());
  rider.uploadDocument.mockResolvedValue(verification());
  globalThis.fetch = jest.fn().mockResolvedValue({ blob: async () => ({ size: 1234 }) }) as never;
});

describe("isValidDate", () => {
  it("accepts real dates only", () => {
    expect(isValidDate("2030-05-31")).toBe(true);
    for (const bad of ["", "2030-5-31", "2030-02-31", "31/05/2030", "2030-13-01", "tomorrow"]) expect(isValidDate(bad)).toBe(false);
  });
});

describe("VerificationView", () => {
  it("lists every document as needed and cannot be sent yet", async () => {
    await renderApp(<VerificationView />);
    expect(await screen.findByText("Send your documents")).toBeTruthy();
    expect(screen.getAllByText("Needed")).toHaveLength(4);
    expect(screen.getByRole("button", { name: "Send for review" }).props.accessibilityState.disabled).toBe(true);
  });

  it("will not open the camera for a licence without a valid expiry date", async () => {
    await renderApp(<VerificationView />);
    const take = (await screen.findAllByRole("button", { name: "Take photo" }))[0]!;
    await fireEvent.press(take);
    expect(await screen.findByText(/Enter the date this driving licence expires/)).toBeTruthy();
    expect(pickDocument).not.toHaveBeenCalled();
  });

  it("takes a photo and uploads it with the expiry date", async () => {
    (pickDocument as jest.Mock).mockResolvedValue({ ok: true, picked: { uri: "file:///l.jpg", mimeType: "image/jpeg" } });
    await renderApp(<VerificationView />);
    await fireEvent.changeText((await screen.findAllByLabelText("Expiry date (YYYY-MM-DD)"))[0]!, "2030-05-31");
    await fireEvent.press(screen.getAllByRole("button", { name: "Take photo" })[0]!);
    await waitFor(() => expect(rider.uploadDocument).toHaveBeenCalled());
    const [type, blob, expiresOn] = rider.uploadDocument.mock.calls[0]!;
    expect(type).toBe("DRIVING_LICENCE");
    expect(blob.size).toBe(1234);
    expect(expiresOn).toBe("2030-05-31");
    expect(pickDocument).toHaveBeenCalledWith("camera", { selfie: false });
  });

  it("offers only the camera for a selfie, with no expiry", async () => {
    (pickDocument as jest.Mock).mockResolvedValue({ ok: true, picked: { uri: "file:///me.jpg", mimeType: "image/jpeg" } });
    await renderApp(<VerificationView />);
    expect(await screen.findAllByRole("button", { name: "Choose photo" })).toHaveLength(3);   // not for the selfie
    await fireEvent.press((await screen.findAllByRole("button", { name: "Take photo" }))[3]!);
    await waitFor(() => expect(rider.uploadDocument).toHaveBeenCalled());
    expect(rider.uploadDocument.mock.calls[0]![0]).toBe("SELFIE");
    expect(rider.uploadDocument.mock.calls[0]![2]).toBeUndefined();
    expect(pickDocument).toHaveBeenCalledWith("camera", { selfie: true });
  });

  it("shows why a picture was refused, and does not upload it", async () => {
    (pickDocument as jest.Mock).mockResolvedValue({ ok: false, reason: "unsupported", message: "That picture is in a format we can't use." });
    await renderApp(<VerificationView />);
    await fireEvent.press((await screen.findAllByRole("button", { name: "Take photo" }))[3]!);
    expect(await screen.findByText("That picture is in a format we can't use.")).toBeTruthy();
    expect(rider.uploadDocument).not.toHaveBeenCalled();
  });

  it("says nothing when the rider simply backs out of the camera", async () => {
    (pickDocument as jest.Mock).mockResolvedValue({ ok: false, reason: "cancelled" });
    await renderApp(<VerificationView />);
    await fireEvent.press((await screen.findAllByRole("button", { name: "Take photo" }))[3]!);
    await waitFor(() => expect(pickDocument).toHaveBeenCalled());
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("shows the server's reason when it refuses an upload", async () => {
    (pickDocument as jest.Mock).mockResolvedValue({ ok: true, picked: { uri: "file:///me.jpg", mimeType: "image/jpeg" } });
    rider.uploadDocument.mockRejectedValue(new Error("The file is larger than 5 MB"));
    await renderApp(<VerificationView />);
    await fireEvent.press((await screen.findAllByRole("button", { name: "Take photo" }))[3]!);
    expect(await screen.findByText("The file is larger than 5 MB")).toBeTruthy();
  });

  it("sends for review once everything is there", async () => {
    rider.verification.mockResolvedValue(verification({ documents: REQUIREMENTS.map((r) => sentDoc(r.type)), missing: [], canSubmit: true }));
    rider.submitVerification.mockResolvedValue(verification({ status: "PENDING_REVIEW", canUpload: false }));
    await renderApp(<VerificationView />);
    await fireEvent.press(await screen.findByRole("button", { name: "Send for review" }));
    await waitFor(() => expect(rider.submitVerification).toHaveBeenCalled());
  });

  it("locks everything while it is being reviewed", async () => {
    rider.verification.mockResolvedValue(verification({ status: "PENDING_REVIEW", canUpload: false, documents: REQUIREMENTS.map((r) => sentDoc(r.type)), missing: [] }));
    await renderApp(<VerificationView />);
    expect(await screen.findByText("We're reviewing your documents")).toBeTruthy();
    expect(screen.queryByRole("button", { name: /photo|Send for review/ })).toBeNull();
  });

  it("shows the reviewer's reason when sent back", async () => {
    rider.verification.mockResolvedValue(verification({ status: "REJECTED", note: "The licence photo is blurry", documents: REQUIREMENTS.map((r) => sentDoc(r.type)), missing: [] }));
    await renderApp(<VerificationView />);
    expect(await screen.findByText("Your documents need attention")).toBeTruthy();
    expect(screen.getByText(/The licence photo is blurry/)).toBeTruthy();
    expect(screen.getAllByRole("button", { name: "Retake photo" })).toHaveLength(4);
  });

  it("says nothing is needed where verification is switched off", async () => {
    rider.verification.mockResolvedValue(verification({ required: false }));
    await renderApp(<VerificationView />);
    expect(await screen.findByText(/isn't required yet/)).toBeTruthy();
  });
});

describe("Drive, before a rider is verified", () => {
  beforeEach(() => {
    rider.me.mockResolvedValue({ id: "r1", name: "Asha", vehicleType: "SEDAN", status: "OFFLINE", lat: null, lon: null });
    rider.offers.mockResolvedValue([]);
    (api.trips.active as unknown as jest.Mock).mockResolvedValue(null);
  });

  it("will not let them go online, and points them to Verification", async () => {
    await renderApp(<Drive />);
    expect(await screen.findByText(/until you're verified/)).toBeTruthy();
    expect(screen.getByRole("button", { name: "Go online" }).props.accessibilityState.disabled).toBe(true);
  });

  it("lets an approved rider, or anyone where it is off, go online", async () => {
    rider.verification.mockResolvedValue(verification({ status: "APPROVED", canUpload: false }));
    const first = await renderApp(<Drive />);
    expect((await screen.findByRole("button", { name: "Go online" })).props.accessibilityState.disabled).toBe(false);
    expect(screen.queryByText(/until you're verified/)).toBeNull();
    first.unmount();
    rider.verification.mockResolvedValue(verification({ required: false, status: "APPROVED" }));
    await renderApp(<Drive />);
    expect((await screen.findByRole("button", { name: "Go online" })).props.accessibilityState.disabled).toBe(false);
  });
});
