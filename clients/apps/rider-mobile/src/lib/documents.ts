import * as ImagePicker from "expo-image-picker";

export type Picked = { uri: string; mimeType: string };
export type PickResult = { ok: true; picked: Picked } | { ok: false; reason: "cancelled" | "denied" | "unsupported"; message?: string };

const ACCEPTED = ["image/jpeg", "image/png"];

/**
 * Take a photo or choose one, for a verification document. iPhone photos can be HEIC, which the server does not accept, so a
 * compressed copy is asked for (that makes a JPEG); if what comes back is still not a JPEG or PNG the rider is told to take a new photo
 * rather than being let down by an error from the server later.
 */
export async function pickDocument(source: "camera" | "library", opts: { selfie?: boolean } = {}): Promise<PickResult> {
  try {
    if (source === "camera") {
      const perm = await ImagePicker.requestCameraPermissionsAsync();
      if (!perm.granted) return { ok: false, reason: "denied", message: "Camera access is off. Turn it on in Settings, or choose a photo from your library." };
    }
    const options: ImagePicker.ImagePickerOptions = {
      mediaTypes: ["images"],
      quality: 0.8,
      allowsEditing: false,
      ...(opts.selfie ? { cameraType: ImagePicker.CameraType.front } : {}),
    };
    const result = source === "camera" ? await ImagePicker.launchCameraAsync(options) : await ImagePicker.launchImageLibraryAsync(options);
    if (result.canceled || !result.assets?.[0]) return { ok: false, reason: "cancelled" };
    const asset = result.assets[0];
    const mimeType = (asset.mimeType ?? guessFromUri(asset.uri)).toLowerCase();
    if (!ACCEPTED.includes(mimeType)) {
      return { ok: false, reason: "unsupported", message: "That picture is in a format we can't use. Take a new photo with the camera instead." };
    }
    return { ok: true, picked: { uri: asset.uri, mimeType } };
  } catch (e) {
    return { ok: false, reason: "unsupported", message: (e as Error).message || "Could not open the picture." };
  }
}

function guessFromUri(uri: string): string {
  const ext = uri.split("?")[0]!.split(".").pop()?.toLowerCase();
  return ext === "png" ? "image/png" : ext === "jpg" || ext === "jpeg" ? "image/jpeg" : "";
}

/** The picture's bytes, for upload. Refuses early what the server would refuse. */
export async function readPicked(picked: Picked, maxBytes: number): Promise<{ blob: Blob } | { error: string }> {
  const blob = await (await fetch(picked.uri)).blob();
  if (blob.size === 0) return { error: "That picture is empty." };
  if (blob.size > maxBytes) return { error: `That picture is larger than ${Math.round(maxBytes / (1024 * 1024))} MB. Take a new photo.` };
  return { blob };
}
