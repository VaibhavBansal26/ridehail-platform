import * as ImagePicker from "expo-image-picker";
import { pickDocument, readPicked } from "@/lib/documents";

const picker = ImagePicker as jest.Mocked<typeof ImagePicker>;
const asset = (over: object = {}) => ({ canceled: false, assets: [{ uri: "file:///photo.jpg", mimeType: "image/jpeg", ...over }] });

beforeEach(() => {
  jest.clearAllMocks();
  picker.requestCameraPermissionsAsync.mockResolvedValue({ granted: true } as never);
  picker.launchCameraAsync.mockResolvedValue(asset() as never);
  picker.launchImageLibraryAsync.mockResolvedValue(asset() as never);
});

describe("pickDocument", () => {
  it("takes a photo with the camera, asking for a compressed copy", async () => {
    expect(await pickDocument("camera")).toEqual({ ok: true, picked: { uri: "file:///photo.jpg", mimeType: "image/jpeg" } });
    expect(picker.launchCameraAsync).toHaveBeenCalledWith(expect.objectContaining({ mediaTypes: ["images"], quality: 0.8 }));
  });

  it("uses the front camera for a selfie", async () => {
    await pickDocument("camera", { selfie: true });
    expect(picker.launchCameraAsync).toHaveBeenCalledWith(expect.objectContaining({ cameraType: "front" }));
    await pickDocument("camera");
    expect(picker.launchCameraAsync).toHaveBeenLastCalledWith(expect.not.objectContaining({ cameraType: "front" }));
  });

  it("does not open the camera, and says why, when camera access is off", async () => {
    picker.requestCameraPermissionsAsync.mockResolvedValue({ granted: false } as never);
    const r = await pickDocument("camera");
    expect(r).toMatchObject({ ok: false, reason: "denied" });
    expect(picker.launchCameraAsync).not.toHaveBeenCalled();
  });

  it("chooses from the library without asking for camera access", async () => {
    expect((await pickDocument("library")).ok).toBe(true);
    expect(picker.requestCameraPermissionsAsync).not.toHaveBeenCalled();
  });

  it("treats backing out as nothing happening, not as an error", async () => {
    picker.launchCameraAsync.mockResolvedValue({ canceled: true, assets: null } as never);
    expect(await pickDocument("camera")).toEqual({ ok: false, reason: "cancelled" });
  });

  it("refuses a format the server would refuse (HEIC) and tells the rider what to do", async () => {
    picker.launchImageLibraryAsync.mockResolvedValue(asset({ uri: "file:///IMG_1.HEIC", mimeType: "image/heic" }) as never);
    const r = await pickDocument("library");
    expect(r).toMatchObject({ ok: false, reason: "unsupported" });
    expect((r as { message: string }).message).toMatch(/new photo/);
  });

  it("works out the type from the file name when the picker does not say", async () => {
    picker.launchImageLibraryAsync.mockResolvedValue(asset({ uri: "file:///a/b.PNG?x=1", mimeType: null }) as never);
    expect(await pickDocument("library")).toMatchObject({ ok: true, picked: { mimeType: "image/png" } });
    picker.launchImageLibraryAsync.mockResolvedValue(asset({ uri: "file:///a/b.gif", mimeType: undefined }) as never);
    expect((await pickDocument("library")).ok).toBe(false);
  });

  it("reports a picker failure instead of throwing", async () => {
    picker.launchCameraAsync.mockRejectedValue(new Error("camera is busy"));
    expect(await pickDocument("camera")).toEqual({ ok: false, reason: "unsupported", message: "camera is busy" });
  });
});

describe("readPicked", () => {
  const fetchBlob = (size: number) => (globalThis.fetch = jest.fn().mockResolvedValue({ blob: async () => ({ size }) }) as never);

  it("returns the picture's bytes", async () => {
    fetchBlob(1000);
    const r = await readPicked({ uri: "file:///p.jpg", mimeType: "image/jpeg" }, 5_000_000);
    expect("blob" in r && r.blob.size).toBe(1000);
  });

  it("refuses an empty or oversized picture before a slow upload", async () => {
    fetchBlob(0);
    expect(await readPicked({ uri: "u", mimeType: "image/jpeg" }, 5_000_000)).toEqual({ error: "That picture is empty." });
    fetchBlob(6_000_000);
    expect(await readPicked({ uri: "u", mimeType: "image/jpeg" }, 5 * 1024 * 1024)).toEqual({ error: expect.stringMatching(/5 MB/) });
  });
});
