import Constants from "expo-constants";
import * as Notifications from "expo-notifications";
import * as SecureStore from "expo-secure-store";
import { Platform } from "react-native";
import { api } from "@/lib/api";
import { registerForPush, routeForNotification, unregisterPush } from "@/lib/push";

jest.mock("@/lib/api", () => ({ api: { devices: { register: jest.fn(), unregister: jest.fn() } } }));

const N = Notifications as jest.Mocked<typeof Notifications>;
const store = SecureStore as jest.Mocked<typeof SecureStore>;
const devices = api.devices as jest.Mocked<typeof api.devices>;
const TOKEN = "ExponentPushToken[abcdefgh12345678]";
const project = (id: string | undefined) => { (Constants as unknown as { expoConfig: unknown }).expoConfig = { extra: { eas: { projectId: id } } }; };

beforeEach(() => {
  jest.clearAllMocks();
  project("test-project");
  N.getPermissionsAsync.mockResolvedValue({ granted: true } as never);
  N.getExpoPushTokenAsync.mockResolvedValue({ type: "expo", data: TOKEN } as never);
  devices.register.mockResolvedValue(undefined);
  devices.unregister.mockResolvedValue(undefined);
});

describe("registerForPush", () => {
  it("registers the phone's token with the server and remembers it for sign-out", async () => {
    expect(await registerForPush({ prompt: false })).toEqual({ status: "registered", token: TOKEN });
    expect(N.getExpoPushTokenAsync).toHaveBeenCalledWith({ projectId: "test-project" });
    expect(devices.register).toHaveBeenCalledWith(TOKEN, Platform.OS === "ios" ? "ios" : "android");
    expect(store.setItemAsync).toHaveBeenCalledWith(expect.any(String), TOKEN);
  });

  it("does not ask for permission on a quiet launch, so a first-time user is not prompted out of the blue", async () => {
    N.getPermissionsAsync.mockResolvedValue({ granted: false } as never);
    expect(await registerForPush({ prompt: false })).toEqual({ status: "not-asked" });
    expect(N.requestPermissionsAsync).not.toHaveBeenCalled();
    expect(devices.register).not.toHaveBeenCalled();
  });

  it("asks when allowed to, and registers if they say yes", async () => {
    N.getPermissionsAsync.mockResolvedValue({ granted: false } as never);
    N.requestPermissionsAsync.mockResolvedValue({ granted: true } as never);
    expect((await registerForPush({ prompt: true })).status).toBe("registered");
    expect(N.requestPermissionsAsync).toHaveBeenCalledTimes(1);
  });

  it("accepts iOS provisional permission", async () => {
    N.getPermissionsAsync.mockResolvedValue({ granted: false, ios: { status: 3 } } as never);
    expect((await registerForPush({ prompt: false })).status).toBe("registered");
  });

  it("registers nothing when they say no", async () => {
    N.getPermissionsAsync.mockResolvedValue({ granted: false } as never);
    N.requestPermissionsAsync.mockResolvedValue({ granted: false } as never);
    expect(await registerForPush({ prompt: true })).toEqual({ status: "denied" });
    expect(devices.register).not.toHaveBeenCalled();
  });

  it("says so, and does nothing, when the build has no Expo project id", async () => {
    project(undefined);
    const r = await registerForPush({ prompt: false });
    expect(r.status).toBe("unavailable");
    expect(N.getExpoPushTokenAsync).not.toHaveBeenCalled();
  });

  it("reports a failure instead of throwing when offline or the server refuses", async () => {
    devices.register.mockRejectedValue(new Error("Cannot reach the server"));
    expect(await registerForPush({ prompt: false })).toEqual({ status: "failed", reason: "Cannot reach the server" });
    N.getExpoPushTokenAsync.mockRejectedValue(new Error("no network"));
    expect((await registerForPush({ prompt: false })).status).toBe("failed");
  });
});

describe("unregisterPush", () => {
  it("tells the server and forgets the token", async () => {
    store.getItemAsync.mockResolvedValue(TOKEN);
    await unregisterPush();
    expect(devices.unregister).toHaveBeenCalledWith(TOKEN);
    expect(store.deleteItemAsync).toHaveBeenCalled();
  });

  it("does nothing when no phone was registered", async () => {
    store.getItemAsync.mockResolvedValue(null);
    await unregisterPush();
    expect(devices.unregister).not.toHaveBeenCalled();
  });

  it("never keeps someone signed in: a failing server or keychain is swallowed", async () => {
    store.getItemAsync.mockResolvedValue(TOKEN);
    devices.unregister.mockRejectedValue(new Error("offline"));
    await expect(unregisterPush()).resolves.toBeUndefined();
    store.getItemAsync.mockRejectedValue(new Error("keychain locked"));
    await expect(unregisterPush()).resolves.toBeUndefined();
  });
});

describe("routeForNotification", () => {
  it("opens the right screen", () => {
    expect(routeForNotification({ type: "BANK_PAYOUT_FAILED" })).toBe("/earnings");
    expect(routeForNotification({ type: "SUPPORT_REPLY" })).toBe("/support");
    for (const type of ["VERIFICATION_APPROVED", "VERIFICATION_REJECTED", "VERIFICATION_SUSPENDED", "VERIFICATION_EXPIRING", "VERIFICATION_EXPIRED"]) {
      expect(routeForNotification({ type })).toBe("/verification");
    }
    for (const data of [{ type: "OFFER_RECEIVED" }, { type: "TRIP_ACCEPTED" }, {}, undefined]) expect(routeForNotification(data)).toBe("/");
  });
});
