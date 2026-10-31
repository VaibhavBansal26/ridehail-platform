import Constants from "expo-constants";
import * as Notifications from "expo-notifications";
import * as SecureStore from "expo-secure-store";
import { Platform } from "react-native";
import { api } from "@/lib/api";

/**
 * Phone notifications: the lock-screen kind, for when the app is closed. Inside the app everything already arrives live over the
 * WebSocket, so while the app is open a notification would only repeat it and is not shown.
 *
 * Needs a development or production build with push credentials (Expo Go cannot receive remote push on Android) and an EAS project
 * id in app.json (`extra.eas.projectId`). Without them registration simply reports "unavailable" and the app works as before.
 */
const KEY = "ridehail_push_token";

if (Platform.OS !== "web") {
  Notifications.setNotificationHandler({
    handleNotification: async () => ({ shouldShowBanner: false, shouldShowList: false, shouldPlaySound: false, shouldSetBadge: false }),
  });
}

export type PushResult =
  | { status: "registered"; token: string }
  | { status: "denied" | "unavailable" | "not-asked" | "failed"; reason?: string };

type Permission = { granted?: boolean; ios?: { status?: number } };
const allowed = (p: Permission) => !!p.granted || p.ios?.status === Notifications.IosAuthorizationStatus.PROVISIONAL;

/**
 * Registers this phone with the server so it can be notified. `prompt: false` only registers when permission was already given
 * (safe to call on every launch); `prompt: true` may show the system permission dialog, so call it at a moment the person can see
 * why: when the customer requests a ride, the moment they want to hear about it.
 */
export async function registerForPush(opts: { prompt: boolean }): Promise<PushResult> {
  if (Platform.OS === "web") return { status: "unavailable", reason: "Notifications are not available on the web build." };
  try {
    // Android 13+ needs the channel to exist before the permission prompt can appear
    if (Platform.OS === "android") {
      await Notifications.setNotificationChannelAsync("default", { name: "Ride updates", importance: Notifications.AndroidImportance.HIGH });
    }
    let permission = (await Notifications.getPermissionsAsync()) as Permission;
    if (!allowed(permission)) {
      if (!opts.prompt) return { status: "not-asked" };
      permission = (await Notifications.requestPermissionsAsync()) as Permission;
      if (!allowed(permission)) return { status: "denied" };
    }
    const projectId = Constants.expoConfig?.extra?.eas?.projectId ?? Constants.easConfig?.projectId;
    if (!projectId) return { status: "unavailable", reason: "This build has no Expo project id, so it cannot receive push notifications." };
    const { data } = await Notifications.getExpoPushTokenAsync({ projectId });
    await api.devices.register(data, Platform.OS === "ios" ? "ios" : "android");
    await SecureStore.setItemAsync(KEY, data);
    return { status: "registered", token: data };
  } catch (e) {
    // offline, or the provider is unreachable: the next launch tries again, and the app works without it
    return { status: "failed", reason: (e as Error).message };
  }
}

/** Sign-out: stop notifying this phone about this account. Best effort; a failure must never keep someone signed in. */
export async function unregisterPush(): Promise<void> {
  if (Platform.OS === "web") return;
  try {
    const token = await SecureStore.getItemAsync(KEY);
    if (!token) return;
    await api.devices.unregister(token).catch(() => undefined);
    await SecureStore.deleteItemAsync(KEY);
  } catch {
    /* nothing to undo */
  }
}

/** Where tapping a notification should go. */
export function routeForNotification(data: Record<string, unknown> | undefined): "/" | "/account" | "/support" {
  if (data?.type === "SUPPORT_REPLY") return "/support";
  return data?.type === "PAYMENT_FAILED" ? "/account" : "/";
}
